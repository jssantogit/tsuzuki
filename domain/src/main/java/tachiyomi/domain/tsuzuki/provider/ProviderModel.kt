package tachiyomi.domain.tsuzuki.provider

@JvmInline
value class ProviderId(val value: String) {
    init {
        require(PROVIDER_ID.matches(value)) { "Provider ID is invalid" }
    }

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

data class ProviderVersion(
    val name: String,
    val code: Long,
) {
    init {
        require(name.isNotBlank()) { "Provider version name must not be blank" }
        require(code > 0L) { "Provider version code must be positive" }
    }
}

sealed interface ProviderOrigin {
    data object Builtin : ProviderOrigin

    data class Repository(val repositoryId: String) : ProviderOrigin {
        init {
            require(repositoryId.isNotBlank()) { "Repository ID must not be blank" }
        }
    }
}

enum class ProviderRuntimeKind {
    BUILTIN,
    SCRIPT,
}

data class ProviderCapabilityRef(
    val id: String,
    val version: Int,
) {
    init {
        require(CAPABILITY_ID.matches(id)) { "Provider capability ID is invalid" }
        require(version > 0) { "Provider capability version must be positive" }
    }

    private companion object {
        val CAPABILITY_ID = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    }
}

object ProviderCapabilities {
    val CatalogSearchV1 = ProviderCapabilityRef("catalog.search", 1)
    val CatalogDiscoverV1 = ProviderCapabilityRef("catalog.discover", 1)
    val MetadataBasicV1 = ProviderCapabilityRef("metadata.basic", 1)
    val MetadataArtworkV1 = ProviderCapabilityRef("metadata.artwork", 1)
    val MetadataEditorialV1 = ProviderCapabilityRef("metadata.editorial", 1)
    val MetadataStaffV1 = ProviderCapabilityRef("metadata.staff", 1)
    val MetadataCrosswalkV1 = ProviderCapabilityRef("metadata.crosswalk", 1)
    val RatingsReadV1 = ProviderCapabilityRef("ratings.read", 1)
    val RelationsReadV1 = ProviderCapabilityRef("relations.read", 1)
    val AccountTrackingV1 = ProviderCapabilityRef("account.tracking", 1)
    val AccountListsV1 = ProviderCapabilityRef("account.lists", 1)
    val LibraryRemoteV1 = ProviderCapabilityRef("library.remote", 1)
    val ReadingLookupV1 = ProviderCapabilityRef("reading.lookup", 1)
    val ReadingChaptersV1 = ProviderCapabilityRef("reading.chapters", 1)
    val ReadingPagesV1 = ProviderCapabilityRef("reading.pages", 1)
    val TorrentSearchV1 = ProviderCapabilityRef("torrent.search", 1)
    val DebridResolveV1 = ProviderCapabilityRef("debrid.resolve", 1)
    val AcquisitionP2pV1 = ProviderCapabilityRef("acquisition.p2p", 1)
    val DownloadsManageV1 = ProviderCapabilityRef("downloads.manage", 1)
}

data class ProviderNetworkPermission(
    val origins: Set<String> = emptySet(),
    val localNetwork: Boolean = false,
) {
    init {
        require(origins.all { it.isNotBlank() }) { "Network origins must not be blank" }
    }
}

data class ProviderBrowserPermission(
    val origins: Set<String> = emptySet(),
) {
    init {
        require(origins.all { it.isNotBlank() }) { "Browser origins must not be blank" }
    }
}

data class ProviderStoragePermission(
    val enabled: Boolean = false,
)

data class ProviderPermissionSet(
    val network: ProviderNetworkPermission? = null,
    val browser: ProviderBrowserPermission? = null,
    val storage: ProviderStoragePermission = ProviderStoragePermission(),
    val secrets: Set<String> = emptySet(),
) {
    init {
        require(secrets.all { it.isNotBlank() }) { "Secret identifiers must not be blank" }
    }
}

enum class ProviderSettingType {
    STRING,
    BOOLEAN,
    SELECT,
    SECRET,
}

data class ProviderSettingDescriptor(
    val key: String,
    val label: String,
    val type: ProviderSettingType,
    val required: Boolean = false,
    val options: List<String> = emptyList(),
) {
    init {
        require(SETTING_KEY.matches(key)) { "Provider setting key is invalid" }
        require(label.isNotBlank()) { "Provider setting label must not be blank" }
        require(options.all { it.isNotBlank() }) { "Provider setting options must not be blank" }
        if (type == ProviderSettingType.SELECT) {
            require(options.isNotEmpty()) { "Select settings require at least one option" }
        }
    }

    private companion object {
        val SETTING_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

data class ProviderDescriptor(
    val id: ProviderId,
    val name: String,
    val version: ProviderVersion,
    val origin: ProviderOrigin,
    val runtime: ProviderRuntimeKind,
    val capabilities: Set<ProviderCapabilityRef>,
    val permissions: ProviderPermissionSet,
    val settings: List<ProviderSettingDescriptor>,
    val contentLanguages: Set<String>,
) {
    init {
        require(name.isNotBlank()) { "Provider name must not be blank" }
        require(contentLanguages.all { it.isNotBlank() }) { "Provider content languages must not be blank" }

        val capabilityIds = capabilities.map { it.id }
        require(capabilityIds.distinct().size == capabilityIds.size) {
            "Provider may declare only one version of a capability ID"
        }

        val settingKeys = settings.map { it.key }
        require(settingKeys.distinct().size == settingKeys.size) {
            "Provider setting keys must be unique"
        }
    }
}

data class ProviderFacetRef(
    val providerId: ProviderId,
    val facetId: String,
) {
    init {
        require(facetId.isNotBlank()) { "Provider facet ID must not be blank" }
    }
}

enum class ProviderLifecycleStatus {
    ENABLED,
    DISABLED,
    BLOCKED,
    INVALID,
}

data class ProviderRegistration(
    val descriptor: ProviderDescriptor,
    val lifecycleStatus: ProviderLifecycleStatus,
    val facets: List<ProviderFacetRef> = emptyList(),
    val configurationFingerprint: String = "",
    val enabledCapabilities: Set<ProviderCapabilityRef> = descriptor.capabilities,
) {
    init {
        require(facets.all { it.providerId == descriptor.id }) {
            "Provider facets must belong to their registered Provider"
        }
        require(enabledCapabilities.all { it in descriptor.capabilities }) {
            "Enabled Provider capabilities must be declared by the Provider descriptor"
        }
    }
}
