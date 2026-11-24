package eu.kanade.tachiyomi.provider.runtime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy

/**
 * Research only: use the unmodified Android Browser Host from the MangaFire
 * experiment to test normal API navigation. A rejection terminates the probe.
 * Passive homepage initialization before API access; no challenge solver, imported session or retries.
 */
@RunWith(AndroidJUnit4::class)
class MangaBallBrowserHostResearchTest {
    @Test(timeout = 240_000L)
    fun threeTitleNormalBrowserTransport() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(
            "I_ACCEPT_MANGABALL_BROWSER_TRANSPORT",
            InstrumentationRegistry.getArguments().getString("mangaBallBrowserOptIn"),
        )
        val context = instrumentation.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)

        // Validate the old adapter's public lookup envelope, not HTML,
        // chapter metadata, or a successful WebView lifecycle callback.
        assertEquals(1, candidateRows("""{"code":200,"data":[{"id":"fixture","name":"Fixture"}],"pagination":{"page":1,"total_pages":1}}"""))
        assertEquals(-1, candidateRows("""{"code":403,"data":[],"pagination":{"page":1,"total_pages":0}}"""))
        assertEquals(-1, candidateRows("<html>challenge</html>"))

        val urls = instrumentation.context.assets.open("mangaball/lookup-requests.json")
            .bufferedReader().use { JSONArray(it.readText()) }
        assertEquals(3, urls.length())
        val titles = listOf("Oyasumi Punpun", "Akame ga Kill!", "Koe no Katachi")
        for ((index, title) in titles.withIndex()) {
            val uri = Uri.parse(urls.getString(index))
            assertEquals("https", uri.scheme)
            assertEquals("mangaball.com", uri.host)
            assertEquals("/api/v1/title/search-advanced", uri.path)
            assertEquals(title, uri.getQueryParameter("keyword"))
        }
        MangaBallCiBrowserWarmupGate.reset()
        val activity = instrumentation.startActivitySync(
            Intent(context, MangaBallCiBrowserWarmupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val warmup = withContext(Dispatchers.IO) { MangaBallCiBrowserWarmupGate.await(30L) }
        instrumentation.runOnMainSync { activity.finish() }
        publish("MANGABALL_BROWSER_WARMUP|outcome=$warmup")
        // A homepage result is not proof of API access. No retry, solver,
        // or session transfer; only the ordinary lookup endpoint is tested.
        assertEquals("WebView initialization must complete", true, warmup in setOf("PAGE_FINISHED", "HTTP_REJECTED"))
        var accepted = 0
        for (index in titles.indices) {
            val url = urls.getString(index)
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    AndroidProviderBrowserHostService(
                        context = context,
                        policy = ProviderNetworkPolicy(setOf("https://mangaball.com")),
                        providerProfileName = ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall.mangaball"),
                        timeoutSeconds = 25L,
                    ).use { it.readText(url, "body") }
                }
            }
            val rows = response.getOrNull()?.let(::candidateRows) ?: -1
            val outcome = if (response.isFailure) {
                when (response.exceptionOrNull()?.message) {
                    "Provider browser upstream challenge" -> "UPSTREAM_CHALLENGE"
                    "Provider browser HTTP response rejected" -> "HTTP_REJECTED"
                    "Provider browser operation timed out" -> "TIMEOUT"
                    else -> "BROWSER_FAILED"
                }
            } else if (rows >= 0) {
                "VALID_JSON"
            } else {
                "INVALID_JSON"
            }
            publish("MANGABALL_BROWSER|index=${index + 1}|outcome=$outcome|rows=$rows")
            if (outcome != "VALID_JSON") break
            accepted++
        }
        publish("MANGABALL_BROWSER_SUMMARY|accepted=$accepted|expected=3|chapters=NOT_TESTED|reader=NOT_TESTED")
        assertEquals("All three normal browser API requests must return the lookup envelope", 3, accepted)
    }

    private fun candidateRows(body: String): Int = runCatching {
        val json = JSONObject(body)
        require(!json.has("error") && json.optInt("code", 200) == 200)
        val data = requireNotNull(json.optJSONArray("data"))
        val paging = requireNotNull(json.optJSONObject("pagination"))
        val current = paging.optInt("current_page", paging.optInt("page", -1))
        val last = paging.optInt("last_page", paging.optInt("total_pages", -1))
        require(current == 1 && last in 0..32 && data.length() <= 24)
        require(last <= 1 || data.length() > 0)
        data.length()
    }.getOrDefault(-1)

    private fun publish(record: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", record) },
        )
    }
}
