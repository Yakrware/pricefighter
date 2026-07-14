package com.pricefighter.data.nano

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig

/** What this device actually resolved for on-device generation — surfaced on the About screen. */
data class NanoInfo(
    val statusLabel: String,
    val available: Boolean,
    /** The base model AICore resolved to, as the device reports it. Null until it's downloaded. */
    val baseModelName: String?,
    val tokenLimit: Int?,
)

/**
 * The single place we obtain the on-device Gemini Nano client.
 *
 * **We never name a model** — the ML Kit prompt API has no such parameter. AICore hands us whatever
 * Nano variant the device carries. The one lever it does expose is [ModelPreference]: we ask for
 * **FULL** (the more capable variant) rather than FAST, because identification/filtering quality
 * matters more here than shaving a few hundred milliseconds. [info] asks the device what it
 * actually resolved to, so we're not guessing.
 */
object NanoClient {

    private const val TAG = "PriceFighter"

    private val fullModel = modelConfig {
        preference = ModelPreference.FULL
        releaseStage = ModelReleaseStage.STABLE
    }

    fun model(): GenerativeModel =
        Generation.getClient(generationConfig { modelConfig = fullModel })

    /** Introspects the on-device model. Fails soft — the About screen must never crash. */
    suspend fun info(): NanoInfo = try {
        val model = model()
        val status = model.checkStatus()
        val ready = status == FeatureStatus.AVAILABLE
        NanoInfo(
            statusLabel = label(status),
            available = ready,
            // These only answer once the model is actually on the device.
            baseModelName = if (ready) runCatching { model.getBaseModelName() }.getOrNull() else null,
            tokenLimit = if (ready) runCatching { model.getTokenLimit() }.getOrNull() else null,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Nano info failed", e)
        NanoInfo("Not supported on this device", available = false, baseModelName = null, tokenLimit = null)
    }

    private fun label(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "Available"
        FeatureStatus.DOWNLOADABLE -> "Not downloaded yet"
        FeatureStatus.DOWNLOADING -> "Downloading…"
        else -> "Not supported on this device"
    }
}
