package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaAllBrowserHttpContinuationTest {

    @Test
    fun `disabled continuation preserves immediate failure for other providers`() {
        val continuation = MangaAllBrowserHttpContinuation(enabled = false)
        continuation.mainFrameStarted()
        continuation.deferFirstRejection(ProviderBrowserFailure.HTTP_REJECTED) shouldBe false
        continuation.mayReadCompletedPage() shouldBe true
        continuation.firstDeferredFailure() shouldBe null
    }

    @Test
    fun `first 403 is never returned as successful API page`() {
        val continuation = MangaAllBrowserHttpContinuation(enabled = true)
        continuation.mainFrameStarted()
        continuation.mayReadCompletedPage() shouldBe true

        continuation.deferFirstRejection(ProviderBrowserFailure.HTTP_REJECTED) shouldBe true
        continuation.mayReadCompletedPage() shouldBe false
        continuation.firstDeferredFailure() shouldBe ProviderBrowserFailure.HTTP_REJECTED

        // A real subsequent main-frame navigation is required.
        continuation.mainFrameStarted()
        continuation.mayReadCompletedPage() shouldBe true
    }

    @Test
    fun `a second 403 cannot be deferred indefinitely`() {
        val continuation = MangaAllBrowserHttpContinuation(enabled = true)
        continuation.mainFrameStarted()
        continuation.deferFirstRejection(ProviderBrowserFailure.UPSTREAM_CHALLENGE) shouldBe true
        continuation.mainFrameStarted()
        continuation.deferFirstRejection(ProviderBrowserFailure.HTTP_REJECTED) shouldBe false
        continuation.firstDeferredFailure() shouldBe ProviderBrowserFailure.UPSTREAM_CHALLENGE
    }

    @Test
    fun `only HTTP rejection families are eligible for passive continuation`() {
        val continuation = MangaAllBrowserHttpContinuation(enabled = true)
        continuation.mainFrameStarted()
        continuation.deferFirstRejection(ProviderBrowserFailure.NAVIGATION) shouldBe false
        continuation.deferFirstRejection(ProviderBrowserFailure.TLS) shouldBe false
        continuation.deferFirstRejection(ProviderBrowserFailure.RENDERER_GONE) shouldBe false
        continuation.firstDeferredFailure() shouldBe null
    }

    @Test
    fun `unchanged first rejected main frame fails closed at deadline`() {
        val continuation = MangaAllBrowserHttpContinuation(enabled = true)
        continuation.mainFrameStarted()
        continuation.deferFirstRejection(ProviderBrowserFailure.HTTP_REJECTED) shouldBe true
        continuation.mayReadCompletedPage() shouldBe false
        continuation.mayReadCompletedPage() shouldBe false
        continuation.firstDeferredFailure() shouldBe ProviderBrowserFailure.HTTP_REJECTED
    }
}
