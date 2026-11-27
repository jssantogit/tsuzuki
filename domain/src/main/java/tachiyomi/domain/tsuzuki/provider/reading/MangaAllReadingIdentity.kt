package tachiyomi.domain.tsuzuki.provider.reading

/**
 * Trusted, host-owned identity contract for one internal content source.
 * The source's adapter defines the key grammar; the shared gate enforces
 * namespace, supported language and Reader capability for every source.
 * A source-supplied lookup result cannot register or change its own contract.
 */
internal data class MangaAllSourceIdentityContract(
    val sourceId: String,
    val languages: Set<String>,
    val readerReady: Boolean,
    val workKey: Regex = Regex("[\\s\\S]+"),
    val chapterKey: Regex = Regex("[\\s\\S]+"),
) {
    init {
        require(sourceId.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        require(languages.isNotEmpty() && languages.all { it.isNotBlank() && it == it.lowercase() })
    }
}

/** The same fail-closed rules apply to an arbitrary number of registered sources. */
internal class MangaAllSourceIdentityPolicy(contracts: List<MangaAllSourceIdentityContract>) {
    private val byId = contracts.associateBy { it.sourceId }

    init {
        require(byId.size == contracts.size) { "Duplicate MangaAll source identity contract" }
    }

    fun sourceId(ref: ProviderBindingRef): String? {
        if (ref.providerId.value != MANGAALL_PROVIDER_ID) return null
        val source = ref.externalWorkId.substringBefore(':', missingDelimiterValue = "")
        val localId = ref.externalWorkId.substringAfter(':', missingDelimiterValue = "")
        return source.takeIf { it in byId && localId.isNotBlank() }
    }

    fun supportsRequestedLanguage(ref: ProviderBindingRef): Boolean {
        val source = sourceId(ref) ?: return false
        val language = ref.facetId?.lowercase() ?: return false
        return language in byId.getValue(source).languages
    }

    fun accepts(
        ref: ProviderBindingRef,
        providerChapterId: String,
        reportedLanguage: String?,
    ): Boolean {
        if (ref.providerId.value != MANGAALL_PROVIDER_ID) return true
        val source = sourceId(ref) ?: return false
        val contract = byId.getValue(source)
        val language = ref.facetId?.lowercase() ?: return false
        if (!contract.readerReady || language !in contract.languages) return false
        if (reportedLanguage?.lowercase() != language) return false
        val workKey = ref.externalWorkId.substringAfter(':')
        val chapterSource = providerChapterId.substringBefore(':', missingDelimiterValue = "")
        val chapterKey = providerChapterId.substringAfter(':', missingDelimiterValue = "")
        return chapterSource == source &&
            chapterKey.isNotBlank() &&
            contract.workKey.matches(workKey) &&
            contract.chapterKey.matches(chapterKey)
    }
}

private const val MANGAALL_PROVIDER_ID = "app.tsuzuki.mangaall"

/**
 * Production defaults remain explicitly trusted. Adding a source requires
 * a reviewed contract, not a special branch in the shared identity algorithm.
 * The current Reader readiness flags do not authorize publication.
 */
object MangaAllReadingIdentity {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val policy = MangaAllSourceIdentityPolicy(
        listOf(
            MangaAllSourceIdentityContract("mangadex", setOf("en", "pt-br"), true, uuid, uuid),
            MangaAllSourceIdentityContract(
                "mangadot",
                setOf("en", "pt-br"),
                true,
                chapterKey = Regex("^(uploads|chapters):[1-9][0-9]{0,15}$"),
            ),
            MangaAllSourceIdentityContract("mangafire", setOf("en"), true),
        ),
    )

    fun sourceId(ref: ProviderBindingRef): String? = policy.sourceId(ref)

    fun supportsRequestedLanguage(ref: ProviderBindingRef): Boolean = policy.supportsRequestedLanguage(ref)

    fun accepts(
        ref: ProviderBindingRef,
        providerChapterId: String,
        reportedLanguage: String?,
    ): Boolean = policy.accepts(ref, providerChapterId, reportedLanguage)
}
