package com.pricefighter.data.agent

import android.content.Context
import com.pricefighter.data.nano.GenAi

/**
 * Text-only view of the generative backend, used by [PriceCheckAgent] to understand a spoken/typed
 * request and to judge which listings genuinely match.
 *
 * In production that backend is on-device Gemini Nano; on an emulator a debug build can stand in a
 * hosted Gemma (see `RemoteGemmaBackend`). Either way every call fails soft to null, so the agent
 * degrades to its offline heuristics rather than breaking.
 */
class NanoText(context: Context) {

    private val appContext = context.applicationContext

    /** True when the backend can answer right now. */
    suspend fun isAvailable(): Boolean = GenAi.info().available

    /** Warms the one-time model download (e.g. when the landing page opens). */
    fun prepare() = GenAi.prepare()

    /**
     * Runs a single text prompt and returns the trimmed answer, or null if the backend isn't ready
     * (unsupported device, model still downloading, backgrounded) or the call failed.
     *
     * @param maxOutputTokens caps the reply — keep it small; the on-device model is tiny.
     */
    suspend fun complete(prompt: String, maxOutputTokens: Int = 64): String? =
        GenAi.generate(prompt, image = null, maxOutputTokens = maxOutputTokens)
}
