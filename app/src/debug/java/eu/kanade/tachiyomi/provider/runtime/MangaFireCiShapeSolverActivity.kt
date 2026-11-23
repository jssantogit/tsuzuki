package eu.kanade.tachiyomi.provider.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.net.http.SslError
import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in, debug/CI-only investigation of the same-site MangaFire shape challenge.
 *
 * Uses a bounded (at most 4 challenge trials), Apache-2.0-attributed Keiyoushi
 * solver asset. Never exports clearance cookies, captcha images, request URLs,
 * response bodies, WebView storage or JS bridge data other than closed verdict
 * and fixed enumerated, non-sensitive progress stages.
 * Production Browser Host and signed MangaALL code10 remain unchanged.
 *
 * The caller MUST still validate real signed provider API JSON and chapters.
 */
internal object MangaFireCiShapeSolverGate {
    const val ACK = "I_ACCEPT_MANGAFIRE_SHAPE_SOLVER_CI_LIVE"
    const val OFFLINE_ACK = "I_ACCEPT_MANGAFIRE_SHAPE_SOLVER_CI_OFFLINE"
    const val ASSET_OFFLINE_ACK = "I_ACCEPT_MANGAFIRE_SHAPE_SOLVER_CI_ASSET_OFFLINE"

    private val latch = AtomicReference(CountDownLatch(1))
    private val status = AtomicReference("TIMEOUT")
    private val milestone = AtomicReference("NO_EVENT")
    private val nativeStage = AtomicReference("NO_EVENT")
    private val documentReady = AtomicBoolean(false)
    private val permittedNativeStages = setOf(
        "ACTIVITY_CREATED",
        "ASSET_READY",
        "PROFILE_READY",
        "LISTENER_REGISTERED",
        "LOAD_REQUESTED",
        "PAGE_COMMITTED",
        "PAGE_FINISHED",
    )
    private val permittedStages = setOf(
        "DOCUMENT_READY",
        "OPENCV_IMPORTED",
        "OPENCV_IMPORT_FAILED",
        "SOLVER_RUNNING",
        "GENERATE_STARTED",
        "GENERATE_OK",
        "GENERATE_HTTP_ERROR",
        "VERIFY_STARTED",
        "VERIFY_OK",
        "VERIFY_HTTP_ERROR",
    )

    fun reset() {
        status.set("TIMEOUT")
        milestone.set("NO_EVENT")
        nativeStage.set("NO_EVENT")
        documentReady.set(false)
        latch.set(CountDownLatch(1))
    }

    fun complete(outcome: String) {
        require(
            outcome in setOf(
                "SOLVED",
                "UNSOLVED",
                "UNSUPPORTED",
                "HTTP_REJECTED",
                "NAVIGATION_FAILED",
                "TLS_REJECTED",
                "RENDERER_EXITED",
                "ASSET_INVALID",
                "BRIDGE_READY",
                "ASSET_BRIDGE_READY",
            ),
        )
        if (status.compareAndSet("TIMEOUT", outcome)) {
            latch.get().countDown()
        }
    }

    fun markStage(value: String) {
        if (value in permittedStages) {
            if (value == "DOCUMENT_READY") documentReady.set(true)
            milestone.set(value)
        }
    }

    fun markNativeStage(value: String) {
        if (value in permittedNativeStages) nativeStage.set(value)
    }

    fun lastNativeStage(): String = nativeStage.get()

    fun hasDocumentReady(): Boolean = documentReady.get()

    fun lastStage(): String = milestone.get()

    fun await(seconds: Long): String {
        require(seconds in 1..120)
        latch.get().await(seconds, TimeUnit.SECONDS)
        return status.get()
    }
}

class MangaFireCiShapeSolverActivity : Activity() {
    private var page: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val optIn = intent?.getStringExtra("ciOptIn")
        val bridgeOnly = optIn == MangaFireCiShapeSolverGate.OFFLINE_ACK
        val exactAssetOffline = optIn == MangaFireCiShapeSolverGate.ASSET_OFFLINE_ACK
        if (!bridgeOnly && !exactAssetOffline && optIn != MangaFireCiShapeSolverGate.ACK) {
            finish()
            return
        }
        MangaFireCiShapeSolverGate.markNativeStage("ACTIVITY_CREATED")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            MangaFireCiShapeSolverGate.complete("UNSUPPORTED")
            finish()
            return
        }
        val html = if (bridgeOnly) {
            // No external script, WAF endpoint, image, or HTTP request. The
            // same-origin JS bridge can be tested in CI without a challenge.
            "<!doctype html><html><head><script>" +
                "bridge.postMessage('stage:DOCUMENT_READY');" +
                "setTimeout(() => bridge.postMessage('probe'), 0);" +
                "</script></head><body></body></html>"
        } else {
            runCatching {
                assets.open("mangafire-ci-solver.html").bufferedReader().use { it.readText() }
            }.getOrNull()
        }
        if (html.isNullOrBlank() || !html.contains("bridge.postMessage")) {
            MangaFireCiShapeSolverGate.complete("ASSET_INVALID")
            finish()
            return
        }
        MangaFireCiShapeSolverGate.markNativeStage("ASSET_READY")
        val view = WebView(this)
        page = view
        WebViewCompat.setProfile(view, ProviderBrowserProfiles.nameFor("app.tsuzuki.mangaall"))
        MangaFireCiShapeSolverGate.markNativeStage("PROFILE_READY")
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            // Only the offline control/exact-asset diagnostics disable ALL
            // networking at the Chromium engine level; they also return
            // blocked responses from shouldInterceptRequest.
            blockNetworkLoads = bridgeOnly || exactAssetOffline
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        WebViewCompat.addWebMessageListener(
            view,
            "bridge",
            setOf("https://mangafire.to"),
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame &&
                sourceOrigin.scheme == "https" &&
                sourceOrigin.host == "mangafire.to"
            ) {
                if (bridgeOnly) {
                    when (message.data) {
                        "stage:DOCUMENT_READY" -> MangaFireCiShapeSolverGate.markStage("DOCUMENT_READY")
                        "probe" -> MangaFireCiShapeSolverGate.complete("BRIDGE_READY")
                    }
                } else if (exactAssetOffline) {
                    // Execute the EXACT debug asset in Chromium with ALL
                    // requests intercepted. The first inline script must
                    // attest that the origin-scoped bridge actually works.
                    // No CAPTCHA data, HTTP or JS payload is surfaced.
                    val stageRecord = message.data
                    if (stageRecord?.startsWith("stage:") == true) {
                        val stage = stageRecord.removePrefix("stage:")
                        MangaFireCiShapeSolverGate.markStage(stage)
                        if (stage == "DOCUMENT_READY") {
                            MangaFireCiShapeSolverGate.complete("ASSET_BRIDGE_READY")
                        }
                    }
                } else {
                    when (message.data) {
                        "true" -> MangaFireCiShapeSolverGate.complete("SOLVED")
                        "false" -> MangaFireCiShapeSolverGate.complete("UNSOLVED")
                        else -> {
                            val stageRecord = message.data
                            if (stageRecord?.startsWith("stage:") == true) {
                                MangaFireCiShapeSolverGate.markStage(stageRecord.removePrefix("stage:"))
                            }
                        }
                    }
                }
            }
        }
        MangaFireCiShapeSolverGate.markNativeStage("LISTENER_REGISTERED")
        view.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                MangaFireCiShapeSolverGate.markNativeStage("PAGE_COMMITTED")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                MangaFireCiShapeSolverGate.markNativeStage("PAGE_FINISHED")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val target = request.url
                // Never navigate to unapproved login, solver or redirect origins.
                if (target.scheme != "https" || target.host != "mangafire.to" ||
                    target.port != -1 || target.encodedPath != "/@waf/solver"
                ) {
                    MangaFireCiShapeSolverGate.complete("NAVIGATION_FAILED")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                if (request == null) return null
                if (bridgeOnly || exactAssetOffline) return blockedResponse()
                val uri = request.url
                val sameSiteSolver = uri.host == "mangafire.to" &&
                    uri.encodedPath in setOf("/@waf/generate", "/@waf/verify")
                val trustedOpenCv = uri.host == "docs.opencv.org" &&
                    uri.encodedPath == "/5.0/opencv.js"
                val allowed = uri.scheme == "https" && uri.port == -1 &&
                    (sameSiteSolver || trustedOpenCv)
                return if (allowed) null else blockedResponse()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?,
            ) {
                if (!exactAssetOffline && !bridgeOnly &&
                    response != null && response.statusCode >= 400 &&
                    request?.url?.host == "mangafire.to" &&
                    request?.url?.encodedPath?.startsWith("/@waf/") == true
                ) {
                    MangaFireCiShapeSolverGate.complete("HTTP_REJECTED")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (!bridgeOnly && !exactAssetOffline &&
                    request != null && request.url.scheme == "https" &&
                    request.url.host in setOf("mangafire.to", "docs.opencv.org")
                ) {
                    MangaFireCiShapeSolverGate.complete("NAVIGATION_FAILED")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                MangaFireCiShapeSolverGate.complete("TLS_REJECTED")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                MangaFireCiShapeSolverGate.complete("RENDERER_EXITED")
                return true
            }
        }

        setContentView(view)
        MangaFireCiShapeSolverGate.markNativeStage("LOAD_REQUESTED")
        // Base origin is exactly the user-facing site. Only @waf endpoints
        // and a single fixed OpenCV.js asset may be requested by this pilot.
        view.loadDataWithBaseURL(
            "https://mangafire.to/@waf/solver",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Disallowed solver resource",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    override fun onDestroy() {
        page?.let { webView ->
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
        page = null
        super.onDestroy()
    }
}
