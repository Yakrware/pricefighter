package com.pricefighter.data.nano

import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.pricefighter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * **Development stand-in for Gemini Nano — emulator only.**
 *
 * Gemini Nano is built on Gemma, and an emulator has no AICore, so every Nano-dependent path
 * (photo → ranked brand/model candidates, and the agent's match filtering) is dead there and silently
 * falls back to keyword heuristics. That makes those paths effectively untestable without a flagship
 * device. This backend points the *same prompts* at a hosted Gemma via OpenRouter so they can be
 * exercised end-to-end on an emulator.
 *
 * It is hard-gated by [isEnabled] to **debug build + emulator + a key supplied in local.properties**
 * (gitignored, and release builds compile the key out entirely). It can never run on a real device
 * or in a release build.
 *
 * Note this sends the prompt — and for identification, the photo — to OpenRouter, an external
 * service. That is exactly why it is restricted to emulators and never ships.
 */
class RemoteGemmaBackend : GenAiBackend {

    private val http = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun info(): GenAiInfo = GenAiInfo(
        backend = BACKEND,
        statusLabel = "Available (dev stand-in for Nano)",
        available = true,
        activeModel = BuildConfig.OPENROUTER_MODEL,
        tokenLimit = null,
    )

    override fun prepare() = Unit // nothing to download

    override suspend fun generate(prompt: String, image: Bitmap?, maxOutputTokens: Int): String? =
        withContext(Dispatchers.IO) {
            try {
                val body = requestBody(prompt, image, maxOutputTokens).toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("Authorization", "Bearer ${BuildConfig.OPENROUTER_API_KEY}")
                    .header("X-Title", "PriceFighter (dev)")
                    .post(body)
                    .build()

                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "OpenRouter HTTP ${response.code}: ${text.take(300)}")
                        return@withContext null
                    }
                    parseContent(text).also { Log.i(TAG, "Gemma answer=\"$it\"") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OpenRouter call failed", e)
                null
            }
        }

    /** OpenAI-compatible chat-completions payload; the image (if any) rides as an inline data URL. */
    private fun requestBody(prompt: String, image: Bitmap?, maxOutputTokens: Int): JSONObject {
        val content = JSONArray().put(
            JSONObject().put("type", "text").put("text", prompt),
        )
        image?.let {
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${it.toBase64Jpeg()}")),
            )
        }
        return JSONObject()
            .put("model", BuildConfig.OPENROUTER_MODEL)
            .put("max_tokens", maxOutputTokens)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", content)),
            )
    }

    private fun parseContent(json: String): String? =
        runCatching {
            JSONObject(json)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .takeIf { it.isNotBlank() }
        }.getOrNull()

    /** Downscale before sending — a full-res camera frame is needlessly large for identification. */
    private fun Bitmap.toBase64Jpeg(): String {
        val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(width, height).toFloat()
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
        } else {
            this
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "PriceFighter"
        private const val BACKEND = "Gemma via OpenRouter (emulator dev stand-in)"
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private const val MAX_IMAGE_EDGE = 1024

        /** Debug build + emulator + a key in local.properties. All three, or we use on-device Nano. */
        val isEnabled: Boolean
            get() = BuildConfig.DEBUG &&
                BuildConfig.OPENROUTER_API_KEY.isNotBlank() &&
                isEmulator()

        private fun isEmulator(): Boolean =
            Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.lowercase().contains("emulator") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.BRAND.startsWith("generic") ||
                Build.PRODUCT == "google_sdk" ||
                Build.PRODUCT.startsWith("sdk_gphone")
    }
}
