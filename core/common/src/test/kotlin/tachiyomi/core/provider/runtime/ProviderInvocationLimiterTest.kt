package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderInvocationLimiterTest {

    @Test
    fun `enforces global and per provider concurrency without queueing unbounded work`() {
        val limiter = ProviderInvocationLimiter(
            maxGlobalInvocations = 3,
            maxInvocationsPerProvider = 2,
        )

        val a1 = limiter.tryAcquire("org.example.a")
        val a2 = limiter.tryAcquire("org.example.a")

        (a1 != null) shouldBe true
        (a2 != null) shouldBe true
        limiter.tryAcquire("org.example.a") shouldBe null

        val b1 = limiter.tryAcquire("org.example.b")
        (b1 != null) shouldBe true
        limiter.tryAcquire("org.example.b") shouldBe null

        requireNotNull(a1).close()
        val b2 = limiter.tryAcquire("org.example.b")
        (b2 != null) shouldBe true

        requireNotNull(a2).close()
        requireNotNull(b1).close()
        requireNotNull(b2).close()

        limiter.activeGlobalInvocations() shouldBe 0
        limiter.activeProviderInvocations("org.example.a") shouldBe 0
        limiter.activeProviderInvocations("org.example.b") shouldBe 0
    }

    @Test
    fun `lease release is idempotent`() {
        val limiter = ProviderInvocationLimiter(
            maxGlobalInvocations = 1,
            maxInvocationsPerProvider = 1,
        )
        val lease = requireNotNull(limiter.tryAcquire("org.example.a"))

        lease.close()
        lease.close()

        limiter.activeGlobalInvocations() shouldBe 0
        (limiter.tryAcquire("org.example.a") != null) shouldBe true
    }
}
