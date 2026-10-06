package tachiyomi.domain.tsuzuki.provider

@JvmInline
value class ProviderManagedResourceRef(val value: String) {
    init {
        require(MANAGED_RESOURCE.matches(value)) {
            "Provider managed resource reference is invalid"
        }
    }

    private companion object {
        val MANAGED_RESOURCE = Regex("managed:[A-Za-z0-9_-]{1,256}")
    }
}

enum class ProviderManagedFileFormat {
    CBZ,
    ZIP,
}

fun interface ProviderManagedResourceResolver {
    fun resolve(
        providerId: ProviderId,
        resource: ProviderManagedResourceRef,
        format: ProviderManagedFileFormat,
    ): String?

    companion object {
        val DenyAll = ProviderManagedResourceResolver { _, _, _ -> null }
    }
}
