package tachiyomi.core.provider.runtime

import java.util.concurrent.atomic.AtomicBoolean

class ProviderInvocationLimiter(
    private val maxGlobalInvocations: Int = 8,
    private val maxInvocationsPerProvider: Int = 2,
) {

    private var globalInvocations = 0
    private val providerInvocations = mutableMapOf<String, Int>()

    init {
        require(maxGlobalInvocations > 0) { "Provider global concurrency limit must be positive" }
        require(maxInvocationsPerProvider > 0) { "Provider concurrency limit must be positive" }
        require(maxInvocationsPerProvider <= maxGlobalInvocations) {
            "Provider concurrency limit cannot exceed the global limit"
        }
    }

    @Synchronized
    fun tryAcquire(providerId: String): Lease? {
        require(PROVIDER_ID.matches(providerId)) { "Provider ID is invalid" }

        val providerCount = providerInvocations[providerId] ?: 0
        if (
            globalInvocations >= maxGlobalInvocations ||
            providerCount >= maxInvocationsPerProvider
        ) {
            return null
        }

        globalInvocations += 1
        providerInvocations[providerId] = providerCount + 1
        return Lease(this, providerId)
    }

    @Synchronized
    fun activeGlobalInvocations(): Int = globalInvocations

    @Synchronized
    fun activeProviderInvocations(providerId: String): Int =
        providerInvocations[providerId] ?: 0

    @Synchronized
    private fun release(providerId: String) {
        val providerCount = providerInvocations[providerId] ?: return
        check(globalInvocations > 0) { "Provider invocation limiter global count underflow" }
        check(providerCount > 0) { "Provider invocation limiter provider count underflow" }

        globalInvocations -= 1
        if (providerCount == 1) {
            providerInvocations.remove(providerId)
        } else {
            providerInvocations[providerId] = providerCount - 1
        }
    }

    class Lease internal constructor(
        private val limiter: ProviderInvocationLimiter,
        private val providerId: String,
    ) : AutoCloseable {

        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                limiter.release(providerId)
            }
        }
    }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
