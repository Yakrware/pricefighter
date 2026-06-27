package com.pricefighter.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a photo was identified as, and which on-device tier produced it. */
data class Identification(val searchTerm: String, val via: String)

/**
 * Identifies a product from a photo, fully on-device, trying cheap/universal methods first:
 *
 *  1. **Barcode** (ML Kit) — a UPC/EAN is a precise product key eBay can search directly.
 *  2. **Label OCR** (ML Kit) — read a model-number-looking token off the item.
 *  3. **Gemini Nano** (ML Kit GenAI Prompt API) — multimodal identification for messy/unlabeled
 *     items, but only on supported devices (`checkStatus() == AVAILABLE`); skipped otherwise.
 *
 * Returns null when nothing on-device could identify it — the caller then falls back to handing
 * the photo to the full Gemini app (tier 4).
 */
class ProductIdentifier(context: Context) {

    private val appContext = context.applicationContext
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun identify(file: File): Identification? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)

        barcode(image)?.let { return Identification(it, "barcode") }
        labelModelNumber(image)?.let { return Identification(it, "label text") }
        nano(bitmap)?.let { return Identification(it, "Gemini Nano") }
        return null
    }

    private suspend fun barcode(image: InputImage): String? = runCatching {
        barcodeScanner.process(image).await()
            .firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf { it.isNotBlank() } }
    }.getOrNull()

    private suspend fun labelModelNumber(image: InputImage): String? = runCatching {
        extractModelNumber(textRecognizer.process(image).await().text)
    }.getOrNull()

    private suspend fun nano(bitmap: Bitmap): String? = runCatching {
        val model = Generation.getClient()
        if (model.checkStatus() != FeatureStatus.AVAILABLE) return@runCatching null
        val request = generateContentRequest(ImagePart(bitmap), TextPart(NANO_PROMPT)) {}
        model.generateContent(request).candidates.firstOrNull()?.text?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    }.getOrNull()

    companion object {
        private const val NANO_PROMPT =
            "Identify the product brand and model in this photo. Reply with only the brand and " +
                "model number, for example \"Sony WH-1000XM5\". If unsure, reply \"unknown\"."

        /**
         * Picks the most model-number-like token from OCR text: mixes letters and digits, 4–20
         * chars (e.g. "WH-1000XM5", "HEG-001"). Returns the longest such token, or null.
         */
        fun extractModelNumber(text: String): String? =
            Regex("[A-Za-z0-9][A-Za-z0-9-]{3,19}")
                .findAll(text)
                .map { it.value }
                .filter { token -> token.any(Char::isDigit) && token.any(Char::isLetter) }
                .maxByOrNull { it.length }
    }
}

/** Bridges a Play-services [Task] to a coroutine without pulling in coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
