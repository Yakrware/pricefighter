package com.pricefighter.data.nano

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * On-device Gemini Nano, via the ML Kit GenAI Prompt API.
 *
 * **We never name a model** — the API has no such parameter, so AICore hands us whatever Nano
 * variant the device carries. The one lever it exposes is [ModelPreference]: we ask for **FULL**
 * (the more capable variant) over FAST, because identification and match-filtering accuracy matter
 * more here than shaving latency. [info] asks the device what it actually resolved to.
 *
 * Nano runs only while the app is foreground (`BACKGROUND_USE_BLOCKED` otherwise) and is absent on
 * emulators and non-flagship devices — so every call fails soft to null.
 */
class NanoBackend : GenAiBackend {

    private val fullModel = modelConfig {
        preference = ModelPreference.FULL
        releaseStage = ModelReleaseStage.STABLE
    }

    private fun model(): GenerativeModel =
        Generation.getClient(generationConfig { modelConfig = fullModel })

    override suspend fun info(): GenAiInfo = try {
        val model = model()
        val status = model.checkStatus()
        val ready = status == FeatureStatus.AVAILABLE
        GenAiInfo(
            backend = BACKEND,
            statusLabel = label(status),
            available = ready,
            // These only answer once the model is actually on the device.
            activeModel = if (ready) runCatching { model.getBaseModelName() }.getOrNull() else null,
            tokenLimit = if (ready) runCatching { model.getTokenLimit() }.getOrNull() else null,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Nano info failed", e)
        GenAiInfo(BACKEND, "Not supported on this device", available = false, activeModel = null, tokenLimit = null)
    }

    override suspend fun generate(prompt: String, image: Bitmap?, maxOutputTokens: Int): String? {
        return try {
            val model = model()
            val status = model.checkStatus()
            Log.i(TAG, "Nano checkStatus=$status (0=UNAVAILABLE 1=DOWNLOADABLE 2=DOWNLOADING 3=AVAILABLE)")
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    val request = if (image != null) {
                        generateContentRequest(ImagePart(image), TextPart(prompt)) {
                            this.maxOutputTokens = maxOutputTokens
                            temperature = 0f
                        }
                    } else {
                        generateContentRequest(TextPart(prompt)) {
                            this.maxOutputTokens = maxOutputTokens
                            temperature = 0f
                        }
                    }
                    model.generateContent(request).candidates.firstOrNull()?.text?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                // Not on the device yet — start the one-time download so a later call can use it.
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    ensureDownload(model)
                    null
                }
                else -> null // UNAVAILABLE: device/SDK doesn't support on-device Nano.
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nano generate failed", e)
            null
        }
    }

    override fun prepare() {
        downloadScope.launch {
            runCatching {
                val model = model()
                if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) ensureDownload(model)
            }.onFailure { Log.w(TAG, "Nano prepare failed", it) }
        }
    }

    private fun ensureDownload(model: GenerativeModel) {
        if (downloadStarted) return
        downloadStarted = true
        Log.i(TAG, "Nano model not ready — starting one-time download")
        downloadScope.launch {
            runCatching {
                model.download().collect { Log.i(TAG, "Nano download: $it") }
                Log.i(TAG, "Nano download finished")
            }.onFailure {
                downloadStarted = false
                Log.w(TAG, "Nano download failed", it)
            }
        }
    }

    private fun label(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "Available"
        FeatureStatus.DOWNLOADABLE -> "Not downloaded yet"
        FeatureStatus.DOWNLOADING -> "Downloading…"
        else -> "Not supported on this device"
    }

    companion object {
        private const val TAG = "PriceFighter"
        private const val BACKEND = "Gemini Nano (on-device)"

        // App-lifetime scope for the one-time model download.
        @Volatile
        private var downloadStarted = false
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
