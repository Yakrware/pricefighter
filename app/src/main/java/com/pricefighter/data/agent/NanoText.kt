package com.pricefighter.data.agent

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.pricefighter.data.nano.NanoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Thin text-only wrapper around the on-device Gemini Nano prompt API (ML Kit GenAI). The image
 * path lives in [com.pricefighter.data.vision.ProductIdentifier]; this is the text sibling the
 * price-check agent uses to understand a spoken/typed request and to judge listing matches.
 *
 * Nano is only usable in the foreground and on supported devices; every call fails soft, returning
 * null so the agent can fall back to offline heuristics. It also self-heals a not-yet-downloaded
 * model by kicking off the one-time background download.
 */
class NanoText(context: Context) {

    private val appContext = context.applicationContext

    /** True when Nano can answer right now (model present and device supported). */
    suspend fun isAvailable(): Boolean = runCatching {
        NanoClient.model().checkStatus() == FeatureStatus.AVAILABLE
    }.getOrDefault(false)

    /** Warms the one-time model download (e.g. when the landing page opens) so the first ask is ready. */
    fun prepare() {
        downloadScope.launch {
            runCatching {
                val model = NanoClient.model()
                if (model.checkStatus() == FeatureStatus.DOWNLOADABLE) ensureDownload(model)
            }.onFailure { Log.w(TAG, "NanoText prepare failed", it) }
        }
    }

    /**
     * Runs a single text prompt and returns Nano's trimmed answer, or null if Nano isn't ready
     * (device unsupported, model still downloading, backgrounded, or any error).
     *
     * @param maxOutputTokens caps Nano's reply — keep small; the model is tiny and slows on long output.
     */
    suspend fun complete(prompt: String, maxOutputTokens: Int = 64): String? {
        return try {
            val model = NanoClient.model()
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    val request = generateContentRequest(TextPart(prompt)) {
                        this.maxOutputTokens = maxOutputTokens
                        temperature = 0f // deterministic: we want extraction/judgement, not creativity
                    }
                    model.generateContent(request).candidates.firstOrNull()?.text?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    ensureDownload(model)
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "NanoText complete failed", e)
            null
        }
    }

    private fun ensureDownload(model: GenerativeModel) {
        if (downloadStarted) return
        downloadStarted = true
        Log.i(TAG, "NanoText: model not ready — starting one-time download")
        downloadScope.launch {
            runCatching {
                model.download().collect { Log.i(TAG, "NanoText download: $it") }
                Log.i(TAG, "NanoText download finished")
            }.onFailure {
                downloadStarted = false
                Log.w(TAG, "NanoText download failed", it)
            }
        }
    }

    companion object {
        private const val TAG = "PriceFighter"

        @Volatile
        private var downloadStarted = false
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
