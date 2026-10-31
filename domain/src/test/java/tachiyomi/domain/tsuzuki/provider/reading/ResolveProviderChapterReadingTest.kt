package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderFacetRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage

class ResolveProviderChapterReadingTest {

    private val providerId = ProviderId("org.example.reader")
    private val binding = ProviderReadingBinding(
        id = "binding-1",
        canonicalTitleId = "title-1",
        ref = ProviderBindingRef(providerId, "en", "work-1"),
        verification = ProviderBindingVerification.EXACT,
        availability = ProviderBindingAvailability.AVAILABLE,
        createdAt = 1,
        updatedAt = 1,
    )
    private val chapter = CanonicalChapter(
        id = "chapter-canonical-1",
        canonicalTitleId = "title-1",
        displayNumber = "1",
        volume = null,
        title = null,
        type = CanonicalChapterType.REGULAR,
        baseNumber = 1,
        part = null,
        alphaSuffix = null,
        confidence = 0.95,
        createdAt = 1,
        updatedAt = 1,
        confirmation = CanonicalChapterConfirmation.PROVISIONAL,
    )

    @Test
    fun `resolves only Provider evidence belonging to an available exact binding`() = runBlocking {
        val otherBinding = binding.copy(
            id = "binding-2",
            ref = ProviderBindingRef(providerId, "pt-br", "work-2"),
        )
        val resolver = resolver(
            bindings = listOf(binding, otherBinding),
            evidence = listOf(
                evidence(binding, "provider-chapter-1", mappedChapterId = chapter.id),
                evidence(otherBinding, "provider-chapter-2", mappedChapterId = "other-chapter"),
                persistedAddonEvidence(),
            ),
        )

        resolver.options(chapter.id) shouldBe listOf(
            ProviderChapterReadingOption(
                canonicalChapterId = chapter.id,
                bindingId = binding.id,
                providerId = providerId,
                facetId = "en",
                externalWorkId = "work-1",
                providerChapterId = "provider-chapter-1",
            ),
        )
    }

    @Test
    fun `Reader refuses a stale Provider chapter key mapped to a different canonical chapter number`() = runBlocking {
        var pagesCalls = 0
        val stale = evidence(binding, "source-chapter-84", mappedChapterId = chapter.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "source-chapter-84",
            ).copy(
                rawLabel = "Ch. 84",
                rawNumber = 84.0,
            ),
        )
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(stale),
            gateway = gateway {
                pagesCalls++
                error("A mismatched chapter must never reach the Provider runtime")
            },
        )

        resolver.options(chapter.id) shouldBe emptyList()
        resolver.delivery(
            ProviderChapterReadingOption(
                canonicalChapterId = chapter.id,
                bindingId = binding.id,
                providerId = providerId,
                facetId = "en",
                externalWorkId = "work-1",
                providerChapterId = "source-chapter-84",
            ),
        ) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
        )
        pagesCalls shouldBe 0
    }

    @Test
    fun `Reader retains exact prologue and decimal chapter evidence without merging identities`() = runBlocking {
        val prologue = chapter.copy(
            id = "prologue-1",
            displayNumber = "Prologue 1",
            type = CanonicalChapterType.PROLOGUE,
        )
        val decimal = chapter.copy(
            id = "decimal-0-1",
            displayNumber = "0.1",
            baseNumber = 0,
            part = 1,
        )
        val cases = listOf(
            Triple(prologue, "Prologue 1", 1.0),
            Triple(decimal, "Ch. 0.1", 0.1),
        )
        cases.forEach { (canonical, rawLabel, rawNumber) ->
            val observed = evidence(
                binding,
                "source-" + canonical.id,
                mappedChapterId = canonical.id,
            ).copy(
                evidence = providerEvidence(
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = "source-" + canonical.id,
                ).copy(
                    rawLabel = rawLabel,
                    rawNumber = rawNumber,
                ),
            )
            val resolver = resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = canonical,
            )
            resolver.options(canonical.id).map { it.providerChapterId } shouldBe
                listOf("source-" + canonical.id)
        }
    }

    @Test
    fun `Reader accepts source decimal matching the precise canonical v2 numbering`() = runBlocking {
        val exact = chapter.copy(
            id = "exact-decimal",
            displayNumber = "0.1",
            baseNumber = 0,
            part = 1,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction.parse("1"),
        )
        val observed = evidence(
            binding = binding,
            providerChapterId = "source-exact-decimal",
            mappedChapterId = exact.id,
        ).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "source-exact-decimal",
            ).copy(rawLabel = "Ch. 0.1", rawNumber = 0.1),
        )
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(observed),
            canonicalChapter = exact,
        )

        resolver.options(exact.id).map { it.providerChapterId } shouldBe listOf("source-exact-decimal")
    }

    @Test
    fun `unavailable binding and stale unmatched Provider evidence are excluded`() = runBlocking {
        val unavailable = binding.copy(
            availability = ProviderBindingAvailability.UNAVAILABLE,
        )
        val resolver = resolver(
            bindings = listOf(unavailable),
            evidence = listOf(
                evidence(binding, "provider-chapter-1", mappedChapterId = chapter.id),
                PersistedChapterEvidence(
                    evidence = providerEvidence(
                        producerId = "provider-binding:stale",
                        externalChapterKey = "stale-chapter",
                    ),
                    mappedCanonicalChapterId = chapter.id,
                ),
            ),
        )

        resolver.options(chapter.id) shouldBe emptyList()
    }

    @Test
    fun `metadata-only chapter provider cannot be selected or invoked by Reader`() = runBlocking {
        var pagesCalls = 0
        val reader = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "metadata-only", chapter.id)),
            providerRegistry = DefaultProviderRegistry(
                registrations = {
                    listOf(
                        readingRegistration(
                            enabledCapabilities = setOf(
                                ProviderCapabilities.ReadingLookupV1,
                                ProviderCapabilities.ReadingChaptersV1,
                            ),
                        ),
                    )
                },
            ),
            gateway = gateway {
                pagesCalls++
                error("metadata-only provider cannot provide reading pages")
            },
        )
        val selected = ProviderChapterReadingOption(
            canonicalChapterId = chapter.id,
            bindingId = binding.id,
            providerId = providerId,
            facetId = "en",
            externalWorkId = "work-1",
            providerChapterId = "metadata-only",
        )
        reader.options(chapter.id) shouldBe emptyList()
        reader.delivery(selected) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
        )
        pagesCalls shouldBe 0
    }

    @Test
    fun `selected option is revalidated before requesting Provider pages`() = runBlocking {
        var pagesCalls = 0
        val gateway = gateway { request ->
            pagesCalls += 1
            request.binding shouldBe binding.ref
            request.providerChapterId shouldBe "provider-chapter-1"
            ProviderCallResult.Success(
                ProviderReadingDelivery.PageList(
                    listOf(
                        ProviderPageRequest(
                            url = "https://cdn.example/page.jpg",
                            allowedOrigins = setOf("https://cdn.example"),
                        ),
                    ),
                ),
            )
        }
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            gateway = gateway,
        )
        val option = resolver.options(chapter.id).single()

        resolver.delivery(option) shouldBe ProviderCallResult.Success(
            ProviderReadingDelivery.PageList(
                listOf(
                    ProviderPageRequest(
                        url = "https://cdn.example/page.jpg",
                        allowedOrigins = setOf("https://cdn.example"),
                    ),
                ),
            ),
        )
        pagesCalls shouldBe 1

        resolver.delivery(
            option.copy(providerChapterId = "forged"),
        ) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
        )
        pagesCalls shouldBe 1
    }

    @Test
    fun `maps Provider page delivery into provider-neutral Reader content`() = runBlocking {
        val gateway = gateway {
            ProviderCallResult.Success(
                ProviderReadingDelivery.PageList(
                    listOf(
                        ProviderPageRequest(
                            url = "https://cdn.example/page-1.jpg",
                            headers = mapOf("Referer" to "https://reader.example/"),
                            allowedOrigins = setOf("https://cdn.example"),
                        ),
                        ProviderPageRequest(
                            url = "https://cdn.example/page-2.jpg",
                            allowedOrigins = setOf("https://cdn.example"),
                        ),
                    ),
                ),
            )
        }
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            gateway = gateway,
        )
        val option = resolver.options(chapter.id).single()

        resolver.preparedContent(option) shouldBe ProviderCallResult.Success(
            PreparedChapterContent.HttpPages(
                pages = listOf(
                    PreparedHttpPage(
                        url = "https://cdn.example/page-1.jpg",
                        headers = mapOf("Referer" to "https://reader.example/"),
                        allowedOrigins = setOf("https://cdn.example"),
                    ),
                    PreparedHttpPage(
                        url = "https://cdn.example/page-2.jpg",
                        allowedOrigins = setOf("https://cdn.example"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `verified managed Provider archive becomes Reader content`() = runBlocking {
        val gateway = gateway {
            ProviderCallResult.Success(
                ProviderReadingDelivery.ManagedFile(
                    resource = ProviderManagedResourceRef("managed:archive"),
                    format = ProviderManagedFileFormat.CBZ,
                ),
            )
        }
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            gateway = gateway,
            managedResources = ProviderManagedResourceResolver { providerId, resource, format ->
                if (
                    providerId == binding.ref.providerId &&
                    resource.value == "managed:archive" &&
                    format == ProviderManagedFileFormat.CBZ
                ) {
                    "content://provider/archive.cbz"
                } else {
                    null
                }
            },
        )

        resolver.preparedContent(resolver.options(chapter.id).single()) shouldBe
            ProviderCallResult.Success(
                PreparedChapterContent.CanonicalDownload(
                    uri = "content://provider/archive.cbz",
                    format = "CBZ",
                ),
            )
    }

    @Test
    fun `managed Provider files remain fail closed before host-owned promotion`() = runBlocking {
        val gateway = gateway {
            ProviderCallResult.Success(
                ProviderReadingDelivery.ManagedFile(
                    resource = ProviderManagedResourceRef("managed:archive"),
                    format = ProviderManagedFileFormat.CBZ,
                ),
            )
        }
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            gateway = gateway,
        )

        resolver.preparedContent(resolver.options(chapter.id).single()) shouldBe
            ProviderCallResult.Failure(
                ProviderError(ProviderErrorCode.MALFORMED_RESULT, retryable = false),
            )
    }

    @Test
    fun `missing canonical chapter fails closed without touching Provider runtime`() = runBlocking {
        var pagesCalls = 0
        val resolver = ResolveProviderChapterReading(
            canonicalChapterRepository = canonicalChapterRepository(chapter = null),
            evidenceRepository = evidenceRepository(emptyList()),
            bindingRepository = bindingRepository(listOf(binding)),
            providerRegistry = DefaultProviderRegistry(registrations = { listOf(readingRegistration()) }),
            gateway = gateway {
                pagesCalls += 1
                error("must not invoke")
            },
            managedResources = ProviderManagedResourceResolver.DenyAll,
        )
        val option = ProviderChapterReadingOption(
            canonicalChapterId = "missing",
            bindingId = binding.id,
            providerId = providerId,
            facetId = "en",
            externalWorkId = "work-1",
            providerChapterId = "provider-chapter-1",
        )

        resolver.options("missing") shouldBe emptyList()
        resolver.delivery(option) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
        )
        pagesCalls shouldBe 0
    }

    @Test
    fun `persisted Provider binding is rechecked when Provider becomes disabled`() = runBlocking {
        var lifecycle = ProviderLifecycleStatus.ENABLED
        var pagesCalls = 0
        val registry = DefaultProviderRegistry(registrations = {
            listOf(readingRegistration(lifecycle = lifecycle))
        })
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            providerRegistry = registry,
            gateway = gateway {
                pagesCalls++
                error("Disabled Provider must never reach the runtime")
            },
        )

        val staleOption = resolver.options(chapter.id).single()
        lifecycle = ProviderLifecycleStatus.DISABLED

        resolver.options(chapter.id) shouldBe emptyList()
        resolver.delivery(staleOption) shouldBe ProviderCallResult.Failure(
            ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
        )
        pagesCalls shouldBe 0
    }

    @Test
    fun `persisted binding requires active reading capabilities and facet`() = runBlocking {
        val registrations = listOf(
            readingRegistration(
                enabledCapabilities = setOf(
                    ProviderCapabilities.ReadingLookupV1,
                    ProviderCapabilities.ReadingChaptersV1,
                ),
            ),
        )
        val capabilityResolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            providerRegistry = DefaultProviderRegistry(registrations = { registrations }),
        )
        capabilityResolver.options(chapter.id) shouldBe emptyList()

        val wrongFacet = resolver(
            bindings = listOf(binding),
            evidence = listOf(evidence(binding, "provider-chapter-1", chapter.id)),
            providerRegistry = DefaultProviderRegistry(registrations = {
                listOf(readingRegistration(facet = "pt-br"))
            }),
        )
        wrongFacet.options(chapter.id) shouldBe emptyList()
    }

    private fun readingRegistration(
        lifecycle: ProviderLifecycleStatus = ProviderLifecycleStatus.ENABLED,
        facet: String = "en",
        enabledCapabilities: Set<ProviderCapabilityRef> = setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
        ),
    ) = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = providerId,
            name = "Reader",
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("fixture"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.ReadingLookupV1,
                ProviderCapabilities.ReadingChaptersV1,
                ProviderCapabilities.ReadingPagesV1,
            ),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = setOf("en", "pt-br"),
        ),
        lifecycleStatus = lifecycle,
        facets = listOf(ProviderFacetRef(providerId, facet)),
        enabledCapabilities = enabledCapabilities,
    )

    private fun resolver(
        bindings: List<ProviderReadingBinding>,
        evidence: List<PersistedChapterEvidence>,
        gateway: ProviderReadingGateway = gateway {
            error("pages not expected")
        },
        managedResources: ProviderManagedResourceResolver = ProviderManagedResourceResolver.DenyAll,
        providerRegistry: ProviderRegistry = DefaultProviderRegistry(registrations = { listOf(readingRegistration()) }),
        canonicalChapter: CanonicalChapter = chapter,
    ) = ResolveProviderChapterReading(
        canonicalChapterRepository = canonicalChapterRepository(canonicalChapter),
        evidenceRepository = evidenceRepository(evidence),
        bindingRepository = bindingRepository(bindings),
        providerRegistry = providerRegistry,
        gateway = gateway,
        managedResources = managedResources,
    )

    private fun evidence(
        binding: ProviderReadingBinding,
        providerChapterId: String,
        mappedChapterId: String?,
    ) = PersistedChapterEvidence(
        evidence = providerEvidence(
            producerId = binding.ref.evidenceProducerId(),
            externalChapterKey = providerChapterId,
        ),
        mappedCanonicalChapterId = mappedChapterId,
    )

    private fun providerEvidence(
        producerId: String,
        externalChapterKey: String,
    ) = ChapterEvidence(
        id = "evidence-$externalChapterKey",
        canonicalTitleId = "title-1",
        producerKind = ProducerKind.PROVIDER,
        producerId = producerId,
        externalChapterKey = externalChapterKey,
        rawLabel = "Chapter 1",
        rawNumber = 1.0,
        volume = null,
        title = null,
        observedAt = 1,
        confidence = 0.95,
        authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
    )

    private fun persistedAddonEvidence() = PersistedChapterEvidence(
        evidence = ChapterEvidence(
            id = "addon-evidence",
            canonicalTitleId = "title-1",
            producerKind = ProducerKind.ADDON,
            producerId = "addon.example",
            externalChapterKey = "addon-chapter",
            rawLabel = "Chapter 1",
            rawNumber = 1.0,
            volume = null,
            title = null,
            observedAt = 1,
            confidence = 0.95,
            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
        ),
        mappedCanonicalChapterId = chapter.id,
    )

    private fun gateway(
        pages: suspend (ProviderReadingPagesRequest) -> ProviderCallResult<ProviderReadingDelivery>,
    ) = object : ProviderReadingGateway {
        override suspend fun lookup(
            providerId: ProviderId,
            request: ProviderReadingLookupRequest,
        ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("not used")

        override suspend fun chapters(
            providerId: ProviderId,
            request: ProviderReadingChaptersRequest,
        ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("not used")

        override suspend fun pages(
            providerId: ProviderId,
            request: ProviderReadingPagesRequest,
        ): ProviderCallResult<ProviderReadingDelivery> {
            providerId shouldBe this@ResolveProviderChapterReadingTest.providerId
            return pages(request)
        }
    }

    private fun bindingRepository(
        values: List<ProviderReadingBinding>,
    ) = object : ProviderReadingBindingRepository {
        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? = values.firstOrNull {
            it.canonicalTitleId == canonicalTitleId &&
                it.ref.providerId == providerId &&
                it.ref.facetId == facetId
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
            values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ProviderReadingBinding) = Unit

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
    }

    private fun evidenceRepository(
        values: List<PersistedChapterEvidence>,
    ) = object : ChapterEvidenceRepository {
        override suspend fun getByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<PersistedChapterEvidence> =
            values.filter { it.evidence.canonicalTitleId == canonicalTitleId }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = values.firstOrNull {
            it.evidence.producerKind == producerKind &&
                it.evidence.producerId == producerId &&
                it.evidence.externalChapterKey == externalChapterKey
        }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
    }

    private fun canonicalChapterRepository(
        chapter: CanonicalChapter?,
    ) = object : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            listOfNotNull(chapter).filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            flowOf(listOfNotNull(chapter).filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? =
            chapter?.takeIf { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) = Unit

        override suspend fun upsertVariant(variant: ChapterVariant) = Unit

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }
}
