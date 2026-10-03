package tachiyomi.core.provider.runtime

import java.util.UUID

@JvmInline
value class ProviderResourceHandle(val value: String) {
    init {
        require(value.isNotBlank()) { "Provider resource handle must not be blank" }
    }
}

enum class ProviderResourceKind {
    BINARY,
    HTML,
    ARCHIVE,
    IMAGE,
}

data class ProviderResourceOwner(
    val providerId: String,
    val invocationId: String,
) {
    init {
        require(providerId.isNotBlank()) { "Provider resource owner ID must not be blank" }
        require(invocationId.isNotBlank()) { "Provider invocation ID must not be blank" }
    }
}

data class ProviderResourceLimits(
    val maxResourcesPerInvocation: Int = 64,
    val maxResourceBytes: Int = 16 * 1024 * 1024,
    val maxTotalBytesPerInvocation: Long = 32L * 1024L * 1024L,
) {
    init {
        require(maxResourcesPerInvocation > 0) { "Provider resource handle limit must be positive" }
        require(maxResourceBytes > 0) { "Provider per-resource byte limit must be positive" }
        require(maxTotalBytesPerInvocation >= maxResourceBytes) {
            "Provider total resource byte limit must cover one maximum-sized resource"
        }
    }
}

class ProviderResourceAccessException(message: String) : SecurityException(message)

class ProviderResourceLimitException(message: String) : IllegalStateException(message)

class ProviderResourceStore(
    private val limits: ProviderResourceLimits = ProviderResourceLimits(),
) {

    private val resources = linkedMapOf<ProviderResourceHandle, ResourceEntry>()
    private val usage = mutableMapOf<ProviderResourceOwner, InvocationUsage>()

    @Synchronized
    fun put(
        owner: ProviderResourceOwner,
        kind: ProviderResourceKind,
        bytes: ByteArray,
    ): ProviderResourceHandle {
        if (bytes.size > limits.maxResourceBytes) {
            throw ProviderResourceLimitException("Provider resource exceeds the per-resource byte limit")
        }

        val current = usage[owner] ?: InvocationUsage()
        if (current.resourceCount >= limits.maxResourcesPerInvocation) {
            throw ProviderResourceLimitException("Provider invocation exceeds the resource handle limit")
        }
        if (current.totalBytes + bytes.size > limits.maxTotalBytesPerInvocation) {
            throw ProviderResourceLimitException("Provider invocation exceeds the total resource byte limit")
        }

        val handle = newHandle()
        resources[handle] = ResourceEntry(
            owner = owner,
            kind = kind,
            bytes = bytes.copyOf(),
        )
        usage[owner] = current.copy(
            resourceCount = current.resourceCount + 1,
            totalBytes = current.totalBytes + bytes.size,
        )
        return handle
    }

    @JvmOverloads
    @Synchronized
    fun read(
        owner: ProviderResourceOwner,
        handle: ProviderResourceHandle,
        expectedKind: ProviderResourceKind? = null,
    ): ByteArray {
        val entry = resources[handle]
            ?: throw ProviderResourceAccessException("Provider resource handle is invalid")
        if (entry.owner != owner) {
            throw ProviderResourceAccessException("Provider resource handle belongs to another invocation")
        }
        if (expectedKind != null && entry.kind != expectedKind) {
            throw ProviderResourceAccessException("Provider resource kind does not match the requested operation")
        }
        return entry.bytes.copyOf()
    }

    @Synchronized
    fun release(
        owner: ProviderResourceOwner,
        handle: ProviderResourceHandle,
    ): Boolean {
        val entry = resources[handle] ?: return false
        if (entry.owner != owner) {
            throw ProviderResourceAccessException("Provider resource handle belongs to another invocation")
        }

        resources.remove(handle)
        subtractUsage(entry)
        return true
    }

    @Synchronized
    fun releaseInvocation(owner: ProviderResourceOwner): Int {
        val handles = resources
            .filterValues { it.owner == owner }
            .keys
            .toList()

        handles.forEach { handle ->
            resources.remove(handle)
        }
        usage.remove(owner)
        return handles.size
    }

    private fun subtractUsage(entry: ResourceEntry) {
        val current = usage[entry.owner] ?: return
        val nextCount = current.resourceCount - 1
        val nextBytes = current.totalBytes - entry.bytes.size
        if (nextCount <= 0) {
            usage.remove(entry.owner)
        } else {
            usage[entry.owner] = InvocationUsage(
                resourceCount = nextCount,
                totalBytes = nextBytes,
            )
        }
    }

    private fun newHandle(): ProviderResourceHandle {
        while (true) {
            val handle = ProviderResourceHandle("res:${UUID.randomUUID()}")
            if (handle !in resources) return handle
        }
    }

    private data class ResourceEntry(
        val owner: ProviderResourceOwner,
        val kind: ProviderResourceKind,
        val bytes: ByteArray,
    )

    private data class InvocationUsage(
        val resourceCount: Int = 0,
        val totalBytes: Long = 0L,
    )
}
