package com.pricefighter

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.genai.prompt.Generation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic (run manually against a real device): reports on-device Gemini Nano availability
 * for the ML Kit GenAI Prompt API. Runs headlessly via `am instrument`.
 *
 * checkStatus: 0=UNAVAILABLE, 1=DOWNLOADABLE, 2=DOWNLOADING, 3=AVAILABLE. Verified on a
 * Galaxy Z Fold 7 (SM-F966): starts DOWNLOADABLE, a ~12 MB download flips it to AVAILABLE.
 */
@RunWith(AndroidJUnit4::class)
class NanoStatusTest {

    @Test
    fun reportNanoStatus() {
        runBlocking {
            val result = runCatching { Generation.getClient().checkStatus() }
            Log.i("PriceFighter", "NANO_STATUS=${result.getOrNull()} error=${result.exceptionOrNull()}")
        }
    }
}
