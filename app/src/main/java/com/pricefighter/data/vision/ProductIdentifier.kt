package com.pricefighter.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a photo was identified as, and which on-device tier produced it. */
data class Identification(val searchTerm: String, val via: String)

/**
 * Identifies a product from a photo, fully on-device. The goal is a **brand + model** an eBay
 * search can use, so it tries the most reliable identifiers first:
 *
 *  1. **Barcode** (ML Kit) — a UPC/EAN is a precise product key eBay can search directly.
 *  2. **Gemini Nano** (ML Kit GenAI Prompt API) — multimodal brand-and-model identification, our
 *     primary identifier on supported devices (`checkStatus() == AVAILABLE`); skipped otherwise.
 *  3. **Labeled model number** (ML Kit OCR) — only pulled when the item explicitly labels it
 *     ("Model", "M/N", …). We deliberately do NOT grab arbitrary alphanumeric tokens: a random
 *     code or serial off the packaging makes a useless search, so we only use one we're sure of.
 *
 * Returns null when nothing on-device could identify it — the caller then falls back to handing
 * the photo to the full Gemini app (tier 4).
 */
class ProductIdentifier(context: Context) {

    private val appContext = context.applicationContext
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * Kicks off the one-time Gemini Nano model download early (e.g. when the camera opens) so
     * tier 3 is ready by the first snap instead of falling through to Gemini while it downloads.
     */
    fun prepareNano() {
        downloadScope.launch {
            runCatching {
                val model = Generation.getClient()
                if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) ensureNanoDownload(model)
            }.onFailure { Log.w(TAG, "prepareNano failed", it) }
        }
    }

    suspend fun identify(file: File): Identification? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)

        barcode(image)?.let { return Identification(it, "barcode") }
        nano(bitmap)?.let { return Identification(it, "Gemini Nano") }
        labelModelNumber(image)?.let { return Identification(it, "label text") }
        return null
    }

    private suspend fun barcode(image: InputImage): String? = runCatching {
        barcodeScanner.process(image).await()
            .firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf { it.isNotBlank() } }
    }.getOrNull()

    private suspend fun labelModelNumber(image: InputImage): String? = runCatching {
        extractModelNumber(textRecognizer.process(image).await().text)
    }.getOrNull()

    private suspend fun nano(bitmap: Bitmap): String? {
        return try {
            val model = Generation.getClient()
            val status = model.checkStatus()
            Log.i(TAG, "Gemini Nano checkStatus=$status (0=UNAVAILABLE 1=DOWNLOADABLE 2=DOWNLOADING 3=AVAILABLE)")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    val request = generateContentRequest(ImagePart(bitmap), TextPart(NANO_PROMPT)) {}
                    val answer = model.generateContent(request).candidates.firstOrNull()?.text?.trim()
                    Log.i(TAG, "Gemini Nano answer=\"$answer\"")
                    answer?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
                }
                // The model isn't on the device yet — kick off the (one-time) download in the
                // background so a later snap can use it, and fall through for this one.
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    ensureNanoDownload(model)
                    null
                }
                else -> null // UNAVAILABLE: device/SDK doesn't support on-device Nano here.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano failed", e)
            null
        }
    }

    private fun ensureNanoDownload(model: GenerativeModel) {
        if (nanoDownloadStarted) return
        nanoDownloadStarted = true
        Log.i(TAG, "Gemini Nano model not ready — starting one-time download")
        downloadScope.launch {
            runCatching {
                model.download().collect { Log.i(TAG, "Gemini Nano download: $it") }
                Log.i(TAG, "Gemini Nano download finished")
            }.onFailure {
                nanoDownloadStarted = false
                Log.w(TAG, "Gemini Nano download failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "PriceFighter"
        private const val NANO_PROMPT =
            "Identify the product brand and model in this photo. Reply with only the brand and " +
                "model number, for example \"Sony WH-1000XM5\". If unsure, reply \"unknown\"."

        // App-lifetime scope for the one-time Gemini Nano model download.
        @Volatile
        private var nanoDownloadStarted = false
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // A model number is only trustworthy when the item explicitly labels it. Matches
        // "Model", "Model No/Number/#", "M/N", "Type" (any case) followed by the token, so a
        // random code or serial elsewhere on the packaging is never picked up.
        private val LABELED_MODEL = Regex(
            """(?i)\b(?:model(?:\s*(?:no\.?|number|name|#))?|m/?n|type)\s*[:#.\-]?\s*([A-Za-z0-9][A-Za-z0-9\-/]{3,19})""",
        )

        /**
         * Reads a model number from OCR text **only when it's explicitly labeled** (e.g.
         * "Model WH-1000XM5", "M/N: A2338"). The token must mix letters and digits so a labeled
         * plain word or price is ignored. Returns null when nothing is confidently labeled — we'd
         * rather fall through to Gemini than search a made-up token.
         */
        fun extractModelNumber(text: String): String? =
            LABELED_MODEL.findAll(text)
                .map { it.groupValues[1].trim('-', '/', '.', ' ') }
                .firstOrNull { token -> token.any(Char::isDigit) && token.any(Char::isLetter) }
    }
}

/** Bridges a Play-services [Task] to a coroutine without pulling in coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
