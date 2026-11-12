package eu.kanade.presentation.reader

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.DefaultSkinValidationActivity
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterReadingOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Android semantics/click boundary for MangaAll's actual Reader source row.
 * The separate scripted canonical journey verifies pages and bytes. This UI
 * fixture deliberately does not claim to invoke ReaderViewModel or open a real
 * MangaAll package, and performs no upstream calls.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllReaderSourceCardInstrumentedTest {
    @Test(timeout = 90_000L)
    fun tappingMangaAllSourcePassesExactVerifiedChapterOptionWithoutCrossSourceSelection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, DefaultSkinValidationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as DefaultSkinValidationActivity
        val selected = AtomicReference<ProviderChapterReadingOption?>()
        val received = CountDownLatch(1)
        val fire = ProviderChapterReadingOption(
            canonicalChapterId = "canonical-punpun-chapter-1",
            bindingId = "verified-punpun",
            providerId = ProviderId("app.tsuzuki.mangaall"),
            facetId = "en",
            externalWorkId = "mangafire:zk12",
            providerChapterId = "mangafire:chapter-one",
        )
        val dex = fire.copy(
            bindingId = "verified-dex",
            externalWorkId = "mangadex:verified-work",
            providerChapterId = "mangadex:verified-chapter",
        )
        try {
            instrumentation.runOnMainSync {
                activity.setValidationContent(sceneId = "mangaall-source-row") {
                    Column {
                        ReaderSourceCard(
                            state = ContentSelectorScreenState.Loading,
                            providerOptions = listOf(dex, fire),
                            activeProviderSourceKey = null,
                            activeOptionKey = null,
                            activeContentLabel = null,
                            failure = null,
                            onSelect = {},
                            onSelectProvider = {
                                selected.set(it)
                                received.countDown()
                            },
                            onRetry = {},
                            onFindSource = {},
                            onChooseEdition = {},
                            onCancelDiscovery = {},
                        )
                    }
                }
            }
            // UiAutomator only targets the current foreground window. On a
            // cold emulator Pixel Launcher may show an ANR/dialog above the
            // activity after startActivitySync returned. Never dismiss app
            // failures or change Reader behavior to make a fixture pass.
            var windowFocused = false
            val focusDeadline = SystemClock.elapsedRealtime() + 20_000L
            while (SystemClock.elapsedRealtime() < focusDeadline) {
                if (device.hasObject(By.text("Pixel Launcher isn't responding"))) {
                    device.findObject(By.text("Close app"))?.click()
                }
                if (device.hasObject(By.text("Viewing full screen"))) {
                    device.findObject(By.text("Got it"))?.click()
                }
                instrumentation.runOnMainSync {
                    windowFocused = activity.window.decorView.hasWindowFocus()
                }
                if (windowFocused) break
                SystemClock.sleep(200L)
            }
            assertTrue(
                "MangaAll Reader Source test activity must own the foreground before touching",
                windowFocused,
            )
            instrumentation.waitForIdleSync()
            assertTrue(
                "MangaFire option must be visible in the focused Compose Reader card",
                device.wait(Until.hasObject(By.text("MangaFire")), 15_000L),
            )
            assertTrue(device.hasObject(By.text("MangaDex")))
            assertFalse("Unlisted sources must not be offered", device.hasObject(By.text("MangaDot")))
            device.findObject(By.text("MangaFire")).click()
            assertTrue("Tap must invoke the exact selected MangaAll option", received.await(10, TimeUnit.SECONDS))
            assertEquals(fire, selected.get())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
