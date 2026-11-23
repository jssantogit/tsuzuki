package eu.kanade.tachiyomi.provider.runtime

/**
 * Optional, one-shot passive continuation after a rejected MangaFire API
 * main-frame load. A real subsequent navigation is required; the original
 * 403 page can never be returned as provider text. No challenge is solved,
 * no requests are injected, and no browser state leaves the WebView.
 *
 * The caller scopes this to the signed MangaALL provider and exact HTTPS
 * MangaFire API origin. Other browser operations fail closed immediately.
 */
internal class MangaAllBrowserHttpContinuation(
    private val enabled: Boolean,
) {
    private var navigationGeneration = 0
    private var rejectedGeneration = -1

    @Volatile
    private var firstRejection: ProviderBrowserFailure? = null

    fun mainFrameStarted() {
        navigationGeneration += 1
    }

    /**
     * Only a verified main-frame challenge (cf-mitigated: challenge) can
     * justify one passive continuation. A plain HTTP 403 has no evidence of
     * recoverable browser-side verification and must fail immediately,
     * otherwise each lookup wastes the signed provider's bounded deadline.
     */
    fun deferFirstRejection(failure: ProviderBrowserFailure): Boolean {
        if (!enabled || firstRejection != null ||
            failure != ProviderBrowserFailure.UPSTREAM_CHALLENGE
        ) {
            return false
        }
        firstRejection = failure
        rejectedGeneration = navigationGeneration
        return true
    }

    /** A 403 response body is never evidence of a successful API read. */
    fun mayReadCompletedPage(): Boolean =
        firstRejection == null || navigationGeneration > rejectedGeneration

    /** Preserve the original HTTP rejection if no subsequent page succeeds. */
    fun firstDeferredFailure(): ProviderBrowserFailure? = firstRejection
}
