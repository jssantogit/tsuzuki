package tachiyomi.core.provider.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderResourceStoreTest {

    private val ownerA = ProviderResourceOwner(
        providerId = "org.example.reader",
        invocationId = "invocation-a",
    )
    private val ownerB = ProviderResourceOwner(
        providerId = "org.example.reader",
        invocationId = "invocation-b",
    )

    @Test
    fun `resource handles are scoped to one provider invocation`() {
        val store = ProviderResourceStore()
        val handle = store.put(
            owner = ownerA,
            kind = ProviderResourceKind.BINARY,
            bytes = "payload".encodeToByteArray(),
        )

        store.read(ownerA, handle, ProviderResourceKind.BINARY).decodeToString() shouldBe "payload"
        shouldThrow<ProviderResourceAccessException> {
            store.read(ownerB, handle, ProviderResourceKind.BINARY)
        }
    }

    @Test
    fun `resource store copies bytes and enforces resource kind`() {
        val store = ProviderResourceStore()
        val original = "payload".encodeToByteArray()
        val handle = store.put(ownerA, ProviderResourceKind.ARCHIVE, original)
        original.fill(0)

        store.read(ownerA, handle, ProviderResourceKind.ARCHIVE).decodeToString() shouldBe "payload"
        shouldThrow<ProviderResourceAccessException> {
            store.read(ownerA, handle, ProviderResourceKind.IMAGE)
        }
    }

    @Test
    fun `per resource total byte and handle quotas fail closed`() {
        val store = ProviderResourceStore(
            limits = ProviderResourceLimits(
                maxResourcesPerInvocation = 2,
                maxResourceBytes = 4,
                maxTotalBytesPerInvocation = 6,
            ),
        )

        store.put(ownerA, ProviderResourceKind.BINARY, byteArrayOf(1, 2, 3, 4))

        shouldThrow<ProviderResourceLimitException> {
            store.put(ownerA, ProviderResourceKind.BINARY, byteArrayOf(1, 2, 3, 4, 5))
        }
        shouldThrow<ProviderResourceLimitException> {
            store.put(ownerA, ProviderResourceKind.BINARY, byteArrayOf(1, 2, 3))
        }

        store.put(ownerA, ProviderResourceKind.BINARY, byteArrayOf(1, 2))
        shouldThrow<ProviderResourceLimitException> {
            store.put(ownerA, ProviderResourceKind.BINARY, byteArrayOf(1))
        }
    }

    @Test
    fun `invocation cleanup releases every owned handle without touching other invocations`() {
        val store = ProviderResourceStore()
        val first = store.put(ownerA, ProviderResourceKind.BINARY, byteArrayOf(1))
        store.put(ownerA, ProviderResourceKind.IMAGE, byteArrayOf(2))
        val other = store.put(ownerB, ProviderResourceKind.BINARY, byteArrayOf(3))

        store.releaseInvocation(ownerA) shouldBe 2

        shouldThrow<ProviderResourceAccessException> { store.read(ownerA, first) }
        store.read(ownerB, other) shouldBe byteArrayOf(3)
    }
}
