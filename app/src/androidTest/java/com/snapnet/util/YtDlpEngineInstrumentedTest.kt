package com.snapnet.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YtDlpEngineInstrumentedTest {
    @Test
    fun latestStableEngineRunsOnAndroidWithTheLibraryJsRuntimeArgument() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.getInstance().init(context)

        val updateResult =
            withContext(Dispatchers.IO) {
                YtDlpUpdater.safeUpdate(context, YoutubeDL.UpdateChannel.STABLE)
            }
        assertTrue(
            "stable yt-dlp update failed: $updateResult",
            updateResult == YtDlpUpdater.Result.UPDATED ||
                updateResult == YtDlpUpdater.Result.ALREADY_UP_TO_DATE,
        )

        val version = YtDlpEngine.probeVersion().getOrThrow()
        assertTrue(
            "yt-dlp engine $version must support --js-runtimes",
            YtDlpVersion.supportsJsRuntimes(version),
        )
    }
}
