package eu.kanade.tachiyomi.provider.runtime

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MangaAllMangaFireUserAgentInstrumentationTest {

    @Test
    fun mangaAllMangaFireDefaultHttpUserAgent_matchesRealChromiumWithoutCookies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expected = WebSettings.getDefaultUserAgent(context)
        val actual = AtomicReference<String>()
        instrumentation.runOnMainSync {
            val webView = WebView(context)
            try {
                actual.set(webView.settings.userAgentString)
            } finally {
                webView.destroy()
            }
        }
        assertTrue(expected.startsWith("Mozilla/5.0"))
        assertEquals(expected, actual.get())
        val original = Request.Builder()
            .url("https://mangafire.to/api/titles?keyword=fixture")
            .header("Accept", "application/json")
            .build()
        val request = MangaAllMangaFireUserAgent.align(original, expected)
        assertEquals(actual.get(), request.header("User-Agent"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        assertNull(original.header("User-Agent"))
    }
}
