package com.pricefighter.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pricefighter.data.nano.NanoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** How sure we are that a candidate is the photographed product. */
enum class Confidence(val rank: Int) {
    HIGH(3),
    MEDIUM(2),
    LOW(1),
    ;

    companion object {
        fun parse(raw: String): Confidence = when {
            raw.contains("high", ignoreCase = true) -> HIGH
            raw.contains("low", ignoreCase = true) -> LOW
            else -> MEDIUM
        }
    }
}

/**
 * One possible identification of a photographed product. [brand] and [model] are kept separate so
 * the UI can show them (and so a wrong guess is legible); [searchTerm] is what we'd actually send
 * to eBay.
 */
data class ProductCandidate(
    val searchTerm: String,
    val brand: String? = null,
    val model: String? = null,
    val confidence: Confidence = Confidence.MEDIUM,
    val via: String,
)

/**
 * Identifies a product from a photo, fully on-device, returning a **confidence-ranked list of
 * candidates** (best first) rather than one guess — auto-detection misses often enough that the UI
 * needs to show what it searched and let the user pick a different match.
 *
 *  1. **Barcode** (ML Kit) — a UPC/EAN is a precise product key; when found it's the sole,
 *     high-confidence candidate.
 *  2. **Gemini Nano** (ML Kit GenAI Prompt API) — multimodal brand-and-model identification, our
 *     primary identifier on supported devices (`checkStatus() == AVAILABLE`). We OCR the item first
 *     and feed that text into Nano's prompt (a small model is far better at *classifying* strings
 *     it's handed than at reading them off pixels), and ask it for up to three ranked
 *     `brand | model | confidence` guesses.
 *  3. **Labeled identifier** — offline fallback / extra candidate from the same OCR text. Pulled
 *     only when the item explicitly labels it: a model/part number ("Model", "M/N", "P/N", …) is
 *     preferred, else a labeled serial ("S/N", "Serial", …). We never grab an arbitrary token.
 *
 * Returns an empty list when nothing on-device could identify it — the caller then falls back to
 * handing the photo to the full Gemini app (tier 4).
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
                val model = NanoClient.model()
                if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) ensureNanoDownload(model)
            }.onFailure { Log.w(TAG, "prepareNano failed", it) }
        }
    }

    /** Best-first list of what this photo might be; empty when nothing on-device could tell. */
    suspend fun identify(file: File): List<ProductCandidate> {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return emptyList()
        val image = InputImage.fromBitmap(bitmap, 0)

        // A barcode is an exact product key — no point offering guesses alongside it.
        barcode(image)?.let {
            return listOf(ProductCandidate(searchTerm = it, confidence = Confidence.HIGH, via = "barcode"))
        }

        // OCR the whole item once. The text both feeds Nano (so it can tell a brand/model from a
        // serial or store label) and, if Nano isn't available, is mined for a labeled model number.
        val ocrText = readAllText(image)

        val candidates = nanoCandidates(bitmap, ocrText).toMutableList()

        // The labeled model/serial is a real identifier — offer it as another option (and as the
        // only one when Nano isn't available).
        extractLabeledId(ocrText)?.let { id ->
            if (candidates.none { it.searchTerm.equals(id, ignoreCase = true) }) {
                candidates += ProductCandidate(
                    searchTerm = id,
                    model = id,
                    confidence = if (candidates.isEmpty()) Confidence.MEDIUM else Confidence.LOW,
                    via = "label text",
                )
            }
        }

        return candidates
            .distinctBy { it.searchTerm.lowercase() }
            .sortedByDescending { it.confidence.rank } // stable: keeps Nano's own ranking within a tier
    }

    private suspend fun barcode(image: InputImage): String? = runCatching {
        barcodeScanner.process(image).await()
            .firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf { it.isNotBlank() } }
    }.getOrNull()

    /** All text ML Kit can read off the item, trimmed; empty string if OCR finds nothing/fails. */
    private suspend fun readAllText(image: InputImage): String = runCatching {
        textRecognizer.process(image).await().text.trim()
    }.getOrDefault("")

    private suspend fun nanoCandidates(bitmap: Bitmap, ocrText: String): List<ProductCandidate> {
        return try {
            val model = NanoClient.model()
            val status = model.checkStatus()
            Log.i(TAG, "Gemini Nano checkStatus=$status (0=UNAVAILABLE 1=DOWNLOADABLE 2=DOWNLOADING 3=AVAILABLE)")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    val request = generateContentRequest(ImagePart(bitmap), TextPart(nanoPrompt(ocrText))) {
                        maxOutputTokens = 120
                    }
                    val answer = model.generateContent(request).candidates.firstOrNull()?.text?.trim()
                    Log.i(TAG, "Gemini Nano answer=\"$answer\"")
                    parseCandidates(answer.orEmpty())
                }
                // The model isn't on the device yet — kick off the (one-time) download in the
                // background so a later snap can use it, and fall through for this one.
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    ensureNanoDownload(model)
                    emptyList()
                }
                else -> emptyList() // UNAVAILABLE: device/SDK doesn't support on-device Nano here.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Nano failed", e)
            emptyList()
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

        // Cap on how much OCR text we hand Nano — a product label is short, and the small model
        // has a limited context window, so trim anything unusually long.
        private const val OCR_CONTEXT_LIMIT = 1200

        /** Max guesses we ask Nano for — enough to offer alternatives without a long, slow reply. */
        private const val MAX_CANDIDATES = 3

        /**
         * Builds Nano's identification prompt, folding in whatever OCR read off the item so the
         * model can decide which strings are the brand/model versus serials, lot codes, or labels.
         * Asks for several ranked guesses, since one guess is wrong often enough that the user
         * needs alternatives to choose from.
         */
        private fun nanoPrompt(ocrText: String): String {
            val ask = "List up to $MAX_CANDIDATES possibilities, best first, ONE PER LINE, in " +
                "exactly this format:\n" +
                "brand | model | confidence\n" +
                "- confidence must be high, medium, or low\n" +
                "- a bare serial number identifies one unit, not the model — never use it as the model\n" +
                "Example: Sony | WH-1000XM5 | high\n" +
                "If you cannot identify it at all, reply exactly: unknown"

            if (ocrText.isBlank()) {
                return "Identify the product in this photo so it can be looked up on eBay.\n$ask"
            }
            val clipped = ocrText.take(OCR_CONTEXT_LIMIT)
            return "Identify the product in this photo so it can be looked up on eBay. " +
                "An OCR reader found this text on the item — it may include serial numbers, lot/" +
                "batch codes, store labels, or unrelated words, so use judgement:\n" +
                "\"\"\"\n$clipped\n\"\"\"\n" +
                "Using the photo together with that text:\n$ask"
        }

        // Nano likes to prefix list items ("1.", "-", "*"); strip that before reading the brand.
        private val LIST_PREFIX = Regex("^\\s*[-*\\u2022]?\\s*\\d*[.)]?\\s*")

        /**
         * Parses Nano's `brand | model | confidence` lines into ranked candidates. Anything that
         * doesn't fit the shape is skipped rather than guessed at.
         */
        fun parseCandidates(raw: String): List<ProductCandidate> {
            if (raw.isBlank() || raw.trim().equals("unknown", ignoreCase = true)) return emptyList()
            return raw.lineSequence()
                .mapNotNull { line ->
                    val parts = line.split("|").map { it.trim() }
                    if (parts.size < 2) return@mapNotNull null
                    val brand = parts[0].replace(LIST_PREFIX, "").trim().nullIfUnknown()
                    val model = parts[1].nullIfUnknown()
                    val term = listOfNotNull(brand, model).joinToString(" ").trim()
                    if (term.isBlank()) return@mapNotNull null
                    ProductCandidate(
                        searchTerm = term,
                        brand = brand,
                        model = model,
                        confidence = parts.getOrNull(2)?.let(Confidence::parse) ?: Confidence.MEDIUM,
                        via = "Gemini Nano",
                    )
                }
                .take(MAX_CANDIDATES)
                .toList()
        }

        private fun String.nullIfUnknown(): String? =
            takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && it != "-" }

        // App-lifetime scope for the one-time Gemini Nano model download.
        @Volatile
        private var nanoDownloadStarted = false
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // An identifier is only trustworthy when the item explicitly labels it, so a random code
        // elsewhere on the packaging is never picked up. A model/part number is the better search
        // term, but a labeled serial is a real identifier too — we just have to be *sure* it's one.
        private val LABELED_MODEL = Regex(
            """(?i)\b(?:model(?:\s*(?:no\.?|number|name|#))?|m/?n|type|part(?:\s*(?:no\.?|number|#))?|p/?n)\s*[:#.\-]?\s*([A-Za-z0-9][A-Za-z0-9\-/]{3,19})""",
        )
        private val LABELED_SERIAL = Regex(
            """(?i)\b(?:serial(?:\s*(?:no\.?|number|#))?|s/?n)\s*[:#.\-]?\s*([A-Za-z0-9][A-Za-z0-9\-/]{4,23})""",
        )

        /**
         * Reads an identifier from OCR text **only when it's explicitly labeled** — a model/part
         * number ("Model WH-1000XM5", "M/N: A2338", "P/N …") is preferred, falling back to a
         * labeled serial ("S/N: …", "Serial …"). The token must contain a digit so a labeled plain
         * word is ignored. Returns null when nothing is confidently labeled — we'd rather fall
         * through to Gemini than search a made-up token.
         */
        fun extractLabeledId(text: String): String? =
            firstLabeledToken(LABELED_MODEL, text) ?: firstLabeledToken(LABELED_SERIAL, text)

        private fun firstLabeledToken(label: Regex, text: String): String? =
            label.findAll(text)
                .map { it.groupValues[1].trim('-', '/', '.', ' ') }
                .firstOrNull { token -> token.length >= 4 && token.any(Char::isDigit) }
    }
}

/** Bridges a Play-services [Task] to a coroutine without pulling in coroutines-play-services. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
