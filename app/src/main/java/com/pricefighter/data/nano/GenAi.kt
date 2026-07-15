package com.pricefighter.data.nano

import android.graphics.Bitmap

/** What the app is actually running for generative AI — surfaced on the About card. */
data class GenAiInfo(
    /** Which backend answered, e.g. "Gemini Nano (on-device)". */
    val backend: String,
    val statusLabel: String,
    val available: Boolean,
    /** The model actually in use, as the backend reports it. Null when nothing is loaded. */
    val activeModel: String?,
    val tokenLimit: Int?,
)

/**
 * A generative backend. In production this is always on-device Gemini Nano; on an emulator a debug
 * build can stand in a hosted Gemma (see [RemoteGemmaBackend]) so the Nano-dependent paths can
 * actually be exercised.
 */
interface GenAiBackend {
    suspend fun info(): GenAiInfo

    /**
     * Runs one prompt, optionally with an image. Returns null when the backend isn't available or
     * the call fails — every caller must degrade to its offline heuristic rather than blow up.
     */
    suspend fun generate(prompt: String, image: Bitmap? = null, maxOutputTokens: Int = 64): String?

    /** Kicks off any one-time model download. No-op for remote backends. */
    fun prepare()
}

/**
 * The backend the app uses. Resolves once: the hosted Gemma stand-in only when a debug build is
 * running on an emulator *and* a key was supplied in local.properties; otherwise on-device Nano.
 */
object GenAi : GenAiBackend {

    private val backend: GenAiBackend by lazy {
        if (RemoteGemmaBackend.isEnabled) RemoteGemmaBackend() else NanoBackend()
    }

    override suspend fun info(): GenAiInfo = backend.info()

    override suspend fun generate(prompt: String, image: Bitmap?, maxOutputTokens: Int): String? =
        backend.generate(prompt, image, maxOutputTokens)

    override fun prepare() = backend.prepare()
}
