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
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
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
    fun `resolves only Provider evidence belonging to an available exact binding`(): Unit = runBlocking {
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
    fun `explicitly confirmed edition uniquely outranks unresolved alternative Reader sources`(): Unit = runBlocking {
        val confirmed = binding.copy(verification = ProviderBindingVerification.USER_CONFIRMED)
        val alternateRef = ProviderBindingRef(providerId, "en", "work-other")
        val alternate = binding.copy(id = "binding-other", ref = alternateRef)
        val sourceAssociation = ProviderWorkSourceAssociation(
            canonicalTitleId = chapter.canonicalTitleId,
            ref = alternateRef,
            workVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
            editionQualification = ProviderSourceEditionQualification.UNRESOLVED,
            observedAt = 2,
        )
        val reader = resolver(
            bindings = listOf(confirmed),
            evidence = listOf(
                evidence(confirmed, "confirmed-chapter-1", mappedChapterId = chapter.id),
                evidence(alternate, "provisional-chapter-1", mappedChapterId = chapter.id),
            ),
            sourceAssociations = listOf(sourceAssociation),
        )
        val allOptions = reader.options(chapter.id)
        allOptions.size shouldBe 2
        reader.uniqueUserConfirmedOption(chapter.id, allOptions) shouldBe
            allOptions.single { it.bindingId == confirmed.id }

        val automatic = resolver(
            bindings = listOf(binding),
            evidence = listOf(
                evidence(binding, "confirmed-chapter-1", mappedChapterId = chapter.id),
                evidence(alternate, "provisional-chapter-1", mappedChapterId = chapter.id),
            ),
            sourceAssociations = listOf(sourceAssociation),
        )
        val unresolved = automatic.options(chapter.id)
        unresolved.size shouldBe 2
        automatic.uniqueUserConfirmedOption(chapter.id, unresolved) shouldBe null
    }

    @Test
    fun `two chapter variants under a single confirmed edition remain ambiguous`(): Unit = runBlocking {
        val confirmed = binding.copy(verification = ProviderBindingVerification.USER_CONFIRMED)
        val reader = resolver(
            bindings = listOf(confirmed),
            evidence = listOf(
                evidence(confirmed, "chapter-variant-one", mappedChapterId = chapter.id),
                evidence(confirmed, "chapter-variant-two", mappedChapterId = chapter.id),
            ),
        )
        val options = reader.options(chapter.id)
        options.size shouldBe 2
        reader.uniqueUserConfirmedOption(chapter.id, options) shouldBe null
        reader.uniqueUserConfirmedOption(
            chapter.id,
            options.map { it.copy(canonicalChapterId = "different-chapter") },
        ) shouldBe null
    }

    @Test
    fun `Reader accepts MangaFire subtitle volume on matching early numbered chapters`(): Unit = runBlocking {
        (1..6).forEach { number ->
            val canonical = chapter.copy(
                id = "canonical-$number",
                baseNumber = number,
                displayNumber = "$number",
                volume = null,
            )
            val observed = evidence(binding, "source-$number", mappedChapterId = canonical.id).copy(
                evidence = providerEvidence(
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = "source-$number",
                ).copy(
                    rawLabel = "Ch. $number - Chapter $number - Volume 1 (1r0n)",
                    rawNumber = number.toDouble(),
                    volume = null,
                    title = "Chapter $number - Volume 1 (1r0n)",
                ),
            )
            val reader = resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = canonical,
            )
            reader.options(canonical.id).map { it.providerChapterId } shouldBe listOf("source-$number")
        }
    }

    @Test
    fun `Reader rejects inconsistent chapter subtitle even with matching leading volume`(): Unit = runBlocking {
        val observed = evidence(binding, "contradictory-subtitle", mappedChapterId = chapter.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "contradictory-subtitle",
            ).copy(
                rawLabel = "Vol. 1 Ch. 1 - Chapter 2 - Volume 1",
                rawNumber = 1.0,
                volume = 1,
                title = "Chapter 2 - Volume 1",
            ),
        )
        val reader = resolver(bindings = listOf(binding), evidence = listOf(observed))
        reader.options(chapter.id) shouldBe emptyList()
        reader.auditNoMatch(chapter.id).identityRejectionReason shouldBe
            ProviderReadingIdentityRejectionReason.CONFLICTING_CHAPTER_MARKERS

        val withoutVolume = observed.copy(
            evidence = observed.evidence.copy(
                rawLabel = "Ch. 1 - Chapter 2",
                volume = null,
            ),
        )
        resolver(bindings = listOf(binding), evidence = listOf(withoutVolume))
            .options(chapter.id) shouldBe emptyList()
    }

    @Test
    fun `Reader rejects a trailing foreign volume without a verified edition`(): Unit = runBlocking {
        val observed = evidence(binding, "foreign-subtitle", mappedChapterId = chapter.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "foreign-subtitle",
            ).copy(
                rawLabel = "Ch. 1 - Chapter 1 - Volume 112",
                rawNumber = 1.0,
                volume = null,
            ),
        )
        val reader = resolver(bindings = listOf(binding), evidence = listOf(observed))
        reader.options(chapter.id) shouldBe emptyList()
    }

    @Test
    fun `Reader accepts first chapter of volume one when the canonical volume is not yet known`(): Unit = runBlocking {
        val observed = evidence(binding, "one-volume-one", mappedChapterId = chapter.id).copy(
            evidence = providerEvidence(binding.ref.evidenceProducerId(), "one-volume-one").copy(
                rawLabel = "Vol. 1 Ch. 1 - Chapter 1 - Volume 1 (1r0n)",
                rawNumber = 1.0,
                volume = 1,
                title = "Chapter 1 - Volume 1 (1r0n)",
            ),
        )
        val reader = resolver(bindings = listOf(binding), evidence = listOf(observed))
        reader.options(chapter.id).map { it.providerChapterId } shouldBe listOf("one-volume-one")
        val wrong = observed.copy(
            evidence = observed.evidence.copy(
                rawLabel = "Vol. 112 Ch. 1 - Chapter 1 - Volume 112",
                volume = 112,
            ),
        )
        resolver(bindings = listOf(binding), evidence = listOf(wrong))
            .options(chapter.id) shouldBe emptyList()
    }

    @Test
    fun `Reader accepts numerically consistent volume evidence when catalog has no volume`(): Unit = runBlocking {
        listOf(2 to 1, 25 to 4, 100 to 12).forEach { (number, sourceVolume) ->
            val canonical = chapter.copy(
                id = "canonical-$number",
                baseNumber = number,
                displayNumber = "$number",
                volume = null,
            )
            val observed = evidence(binding, "source-$number", mappedChapterId = canonical.id).copy(
                evidence = providerEvidence(
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = "source-$number",
                ).copy(
                    rawLabel = "Vol. $sourceVolume Ch. $number - Chapter $number",
                    rawNumber = number.toDouble(),
                    volume = sourceVolume,
                ),
            )
            resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = canonical,
            ).options(canonical.id).map { it.providerChapterId } shouldBe listOf("source-$number")
        }
    }

    @Test
    fun `Reader still blocks volume later than canonical global chapter number`(): Unit = runBlocking {
        val second = chapter.copy(id = "canonical-2", baseNumber = 2, displayNumber = "2", volume = null)
        val foreign = evidence(binding, "source-late-volume", mappedChapterId = second.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "source-late-volume",
            ).copy(rawLabel = "Vol. 112 Ch. 2", rawNumber = 2.0, volume = 112),
        )
        val reader = resolver(
            bindings = listOf(binding),
            evidence = listOf(foreign),
            canonicalChapter = second,
        )
        reader.options(second.id) shouldBe emptyList()
        reader.auditNoMatch(second.id).identityRejectionReason shouldBe
            ProviderReadingIdentityRejectionReason.CANONICAL_VOLUME_CONFLICT
    }

    @Test
    fun `Reader quarantines unqualified early-numbered extras but retains genuine prologues`(): Unit = runBlocking {
        val chapterZero = chapter.copy(id = "chapter-zero", displayNumber = "0", baseNumber = 0)
        val sourceZero = evidence(binding, "source-zero", mappedChapterId = chapterZero.id)
        val ambiguous = sourceZero.copy(
            evidence = sourceZero.evidence.copy(
                rawLabel = "Ch. 0 - Darkshine vs Garou",
                rawNumber = 0.0,
                title = "Darkshine vs Garou",
            ),
        )
        val quarantine = resolver(
            bindings = listOf(binding),
            evidence = listOf(ambiguous),
            canonicalChapter = chapterZero,
        )
        quarantine.options(chapterZero.id) shouldBe emptyList()
        quarantine.auditNoMatch(chapterZero.id).reason shouldBe
            ProviderReadingNoMatchReason.UNVERIFIED_EARLY_TITLE
        // Prologue is a distinct canonical type, not an alias for regular Ch. 0.
        val prologueChapter = chapter.copy(
            id = "chapter-prologue",
            type = CanonicalChapterType.PROLOGUE,
            baseNumber = null,
            displayNumber = "Prologue",
        )
        val prologue = ambiguous.copy(
            mappedCanonicalChapterId = prologueChapter.id,
            evidence = ambiguous.evidence.copy(
                rawLabel = "Ch. 0 - Prologue",
                title = "Prologue",
            ),
        )
        resolver(
            bindings = listOf(binding),
            evidence = listOf(prologue),
            canonicalChapter = prologueChapter,
        ).options(prologueChapter.id).size shouldBe 1

        val fraction = chapter.copy(
            id = "chapter-zero-point-two",
            displayNumber = "0.2",
            baseNumber = 0,
            part = 2,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction.parse("2"),
        )
        val scene = ambiguous.copy(
            mappedCanonicalChapterId = fraction.id,
            evidence = ambiguous.evidence.copy(
                rawLabel = "Ch. 0.2 - Saitama vs Garou (not full)",
                rawNumber = 0.2,
                title = "Saitama vs Garou (not full)",
            ),
        )
        resolver(
            bindings = listOf(binding),
            evidence = listOf(scene),
            canonicalChapter = fraction,
        ).options(fraction.id) shouldBe emptyList()
        val explicitlyNumbered = scene.copy(
            evidence = scene.evidence.copy(
                rawLabel = "Ch. 0.2 - Chapter 0.2 Bonus",
                title = "Chapter 0.2 Bonus",
            ),
        )
        resolver(
            bindings = listOf(binding),
            evidence = listOf(explicitlyNumbered),
            canonicalChapter = fraction,
        ).options(fraction.id).size shouldBe 1
    }

    @Test
    fun `Reader rejects stale Provider chapter key mapped to another chapter`(): Unit = runBlocking {
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
    fun `Reader diagnoses a missing source chapter without calling it a provider outage`(): Unit = runBlocking {
        val zero = chapter.copy(id = "chapter-zero", displayNumber = "0", baseNumber = 0)
        val source = (1..3).map { number ->
            evidence(binding, "source-$number", mappedChapterId = null).copy(
                evidence = providerEvidence(binding.ref.evidenceProducerId(), "source-$number").copy(
                    rawLabel = "Ch. $number",
                    rawNumber = number.toDouble(),
                ),
            )
        }
        resolver(
            bindings = listOf(binding),
            evidence = source,
            canonicalChapter = zero,
        ).auditNoMatch(zero.id) shouldBe ProviderReadingNoMatchAudit(
            eligibleSourceCount = 1,
            providerEvidenceCount = 3,
            matchingNumberEvidenceCount = 0,
            mappedEvidenceCount = 0,
            mappedEligibleSourceEvidenceCount = 0,
            reason = ProviderReadingNoMatchReason.NO_MATCHING_SOURCE_CHAPTER,
        )
    }

    @Test
    fun `Reader distinguishes matching source chapters missing a canonical mapping`(): Unit = runBlocking {
        val noMap = evidence(binding, "source-1", mappedChapterId = null)
        resolver(
            bindings = listOf(binding),
            evidence = listOf(noMap),
        ).auditNoMatch(chapter.id) shouldBe ProviderReadingNoMatchAudit(
            eligibleSourceCount = 1,
            providerEvidenceCount = 1,
            matchingNumberEvidenceCount = 1,
            mappedEvidenceCount = 0,
            mappedEligibleSourceEvidenceCount = 0,
            reason = ProviderReadingNoMatchReason.NO_CANONICAL_MAPPING,
        )
    }

    @Test
    fun `Reader distinguishes no Provider evidence from ineligible source`(): Unit = runBlocking {
        resolver(
            bindings = listOf(binding),
            evidence = emptyList(),
        ).auditNoMatch(chapter.id) shouldBe ProviderReadingNoMatchAudit(
            eligibleSourceCount = 1,
            providerEvidenceCount = 0,
            matchingNumberEvidenceCount = 0,
            mappedEvidenceCount = 0,
            mappedEligibleSourceEvidenceCount = 0,
            reason = ProviderReadingNoMatchReason.NO_PROVIDER_EVIDENCE,
        )
    }

    @Test
    fun `Reader diagnoses mapped chapter evidence only belonging to a different HID`(): Unit = runBlocking {
        val other = binding.copy(
            id = "binding-other",
            ref = ProviderBindingRef(providerId, "en", "different-source"),
        )
        val observed = evidence(other, "external-ch-1", mappedChapterId = chapter.id)
        resolver(
            bindings = listOf(binding),
            evidence = listOf(observed),
        ).auditNoMatch(chapter.id) shouldBe ProviderReadingNoMatchAudit(
            eligibleSourceCount = 1,
            providerEvidenceCount = 1,
            matchingNumberEvidenceCount = 1,
            mappedEvidenceCount = 1,
            mappedEligibleSourceEvidenceCount = 0,
            reason = ProviderReadingNoMatchReason.NO_ELIGIBLE_MAPPED_SOURCE,
        )
    }

    @Test
    fun `Reader reports identity rejection if a mapped source declares a mismatched volume`(): Unit = runBlocking {
        val wronglyMapped = evidence(binding, "wrong-volume", chapter.id).copy(
            evidence = providerEvidence(binding.ref.evidenceProducerId(), "wrong-volume").copy(
                rawLabel = "Vol. 112 Ch. 1",
                rawNumber = 1.0,
                volume = 112,
            ),
        )
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(wronglyMapped),
        )
        resolver.options(chapter.id) shouldBe emptyList()
        resolver.auditNoMatch(chapter.id) shouldBe ProviderReadingNoMatchAudit(
            eligibleSourceCount = 1,
            providerEvidenceCount = 1,
            matchingNumberEvidenceCount = 1,
            mappedEvidenceCount = 1,
            mappedEligibleSourceEvidenceCount = 1,
            reason = ProviderReadingNoMatchReason.IDENTITY_REJECTED,
            identityRejectionReason = ProviderReadingIdentityRejectionReason.CANONICAL_VOLUME_CONFLICT,
        )
    }

    @Test
    fun `Reader diagnoses unavailable source association without inventing chapter selection`(): Unit = runBlocking {
        resolver(
            bindings = emptyList(),
            evidence = listOf(evidence(binding, "source-1", mappedChapterId = chapter.id)),
        ).auditNoMatch(chapter.id) shouldBe ProviderReadingNoMatchAudit(
            eligibleSourceCount = 0,
            providerEvidenceCount = 1,
            matchingNumberEvidenceCount = 1,
            mappedEvidenceCount = 1,
            mappedEligibleSourceEvidenceCount = 0,
            reason = ProviderReadingNoMatchReason.NO_ELIGIBLE_SOURCE,
        )
    }

    @Test
    fun `source variant blocks automatic Reader but honors confirmed edition`(): Unit = runBlocking {
        val colorAssociation = ProviderWorkSourceAssociation(
            canonicalTitleId = chapter.canonicalTitleId,
            ref = binding.ref,
            workVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
            editionQualification = ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT,
            observedAt = 2L,
        )
        val observed = evidence(binding, "source-1", mappedChapterId = chapter.id)
        val automatic = resolver(
            bindings = listOf(binding),
            evidence = listOf(observed),
            sourceAssociations = listOf(colorAssociation),
        )
        automatic.options(chapter.id) shouldBe emptyList()
        automatic.auditNoMatch(chapter.id).eligibleSourceCount shouldBe 0
        automatic.auditNoMatch(chapter.id).reason shouldBe ProviderReadingNoMatchReason.SOURCE_EDITION_EXCLUDED
        // Negative source-edition metadata is meaningful even without
        // independently verified authors of the canonical work.
        resolver(
            bindings = listOf(binding),
            evidence = listOf(observed),
            sourceAssociations = listOf(
                colorAssociation.copy(workVerification = ProviderWorkVerification.CANONICAL_METADATA_UNVERIFIED),
            ),
        ).options(chapter.id) shouldBe emptyList()

        val explicitlyChosen = resolver(
            bindings = listOf(binding.copy(verification = ProviderBindingVerification.USER_CONFIRMED)),
            evidence = listOf(observed),
            sourceAssociations = listOf(colorAssociation),
        )
        explicitlyChosen.options(chapter.id).map { it.providerChapterId } shouldBe listOf("source-1")
    }

    @Test
    fun `Reader rejects volume-sized page deliveries for unqualified regular chapter zero`(): Unit = runBlocking {
        val zero = chapter.copy(
            id = "chapter-canonical-0",
            displayNumber = "0",
            baseNumber = 0,
        )
        // Physical #176: source IDs served 182, 186 and 191 pages for
        // canonical Chapter 0. A long bundled volume can be advertised as
        // numeric zero while lacking independent volume identity.
        listOf(
            "9324579" to 182,
            "7336925" to 186,
            "7422102" to 191,
            // A54 code11 on 2026-11-02: One Punch-Man chapter 0 was routed
            // to an unrelated Saitama/Garou section, HID 729pj/id 5892072.
            // The 102-page delivery escaped the previous 120-page guard.
            "5892072" to 102,
        ).forEach { (sourceId, pageCount) ->
            val observed = evidence(binding, sourceId, mappedChapterId = zero.id).copy(
                evidence = providerEvidence(
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = sourceId,
                ).copy(rawLabel = "Ch. 0", rawNumber = 0.0, volume = null),
            )
            val resolver = resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = zero,
                gateway = gateway {
                    ProviderCallResult.Success(
                        ProviderReadingDelivery.PageList(
                            (1..pageCount).map { index ->
                                ProviderPageRequest(
                                    url = "https://reader.example/pages/$index.jpg",
                                    allowedOrigins = setOf("https://reader.example"),
                                )
                            },
                        ),
                    )
                },
            )
            val options = resolver.options(zero.id)
            options.map { it.providerChapterId } shouldBe listOf(sourceId)
            resolver.preparedContent(options.single()) shouldBe ProviderCallResult.Failure(
                ProviderError(
                    ProviderErrorCode.CHAPTER_IDENTITY_UNVERIFIED,
                    retryable = false,
                    observedPageCount = pageCount,
                ),
            )
        }
    }

    @Test
    fun `Reader preserves genuine short chapter zero and does not ban prologues or fractions`(): Unit = runBlocking {
        val zero = chapter.copy(id = "regular-zero", displayNumber = "0", baseNumber = 0)
        val observed = evidence(binding, "real-short-zero", mappedChapterId = zero.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "real-short-zero",
            ).copy(rawLabel = "Ch. 0", rawNumber = 0.0, volume = null),
        )
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(observed),
            canonicalChapter = zero,
            gateway = gateway {
                ProviderCallResult.Success(
                    ProviderReadingDelivery.PageList(
                        (1..24).map { index ->
                            ProviderPageRequest(
                                url = "https://reader.example/pages/$index.jpg",
                                allowedOrigins = setOf("https://reader.example"),
                            )
                        },
                    ),
                )
            },
        )
        val option = resolver.options(zero.id).single()
        (resolver.preparedContent(option) is ProviderCallResult.Success) shouldBe true
    }

    @Test
    fun `Reader rejects source titled whole volume even if canonical chapter zero has matching volume`(): Unit = runBlocking {
        val zero = chapter.copy(
            id = "volume-112-zero",
            displayNumber = "0",
            baseNumber = 0,
            volume = 112,
        )
        listOf("Volume 112", "Volume 112 Extra", "Vol. 112: Bonus").forEach { title ->
            val observed = evidence(binding, "volume-bundle", mappedChapterId = zero.id).copy(
                evidence = providerEvidence(binding.ref.evidenceProducerId(), "volume-bundle").copy(
                    rawLabel = "Vol. 112 Ch. 0 - $title",
                    rawNumber = 0.0,
                    volume = 112,
                    title = title,
                ),
            )
            val resolver = resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = zero,
            )
            resolver.options(zero.id) shouldBe emptyList()
            resolver.auditSourceIdentity(
                ProviderChapterReadingOption(
                    canonicalChapterId = zero.id,
                    bindingId = binding.id,
                    providerId = binding.ref.providerId,
                    facetId = binding.ref.facetId,
                    externalWorkId = binding.ref.externalWorkId,
                    providerChapterId = "volume-bundle",
                ),
            )?.sourceTitleIsVolumeContainer shouldBe true
        }
    }

    @Test
    fun `Reader preserves large correctly qualified chapter zero and large regular chapters`(): Unit = runBlocking {
        val numberedZero = chapter.copy(
            id = "volume-one-zero",
            displayNumber = "0",
            baseNumber = 0,
            volume = 1,
        )
        val ordinaryChapter = chapter.copy(id = "regular-one")
        val cases = listOf(
            Triple(numberedZero, "Vol. 1 Ch. 0", 0.0),
            Triple(ordinaryChapter, "Ch. 1", 1.0),
        )
        cases.forEach { (canonical, sourceLabel, number) ->
            val volume = if (canonical.id == numberedZero.id) 1 else null
            val observed = evidence(binding, "long-chapter", mappedChapterId = canonical.id).copy(
                evidence = providerEvidence(binding.ref.evidenceProducerId(), "long-chapter").copy(
                    rawLabel = sourceLabel,
                    rawNumber = number,
                    volume = volume,
                ),
            )
            val reader = resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = canonical,
                gateway = gateway {
                    ProviderCallResult.Success(
                        ProviderReadingDelivery.PageList(
                            (1..102).map { pageIndex ->
                                ProviderPageRequest(
                                    url = "https://reader.example/pages/$pageIndex.jpg",
                                    allowedOrigins = setOf("https://reader.example"),
                                )
                            },
                        ),
                    )
                },
            )
            val option = reader.options(canonical.id).single()
            (reader.preparedContent(option) is ProviderCallResult.Success) shouldBe true
        }
    }

    @Test
    fun `detailed Reader source identity is scoped to the exact selected chapter and HID`(): Unit = runBlocking {
        val canonical = chapter.copy(id = "volume-one-chapter-zero", baseNumber = 0, volume = 1)
        val source = evidence(binding, "source-with-metadata", mappedChapterId = canonical.id).copy(
            evidence = providerEvidence(binding.ref.evidenceProducerId(), "source-with-metadata").copy(
                rawLabel = "Vol. 1 Ch. 0 - Opening",
                rawNumber = 0.0,
                volume = 1,
                title = "Opening",
            ),
        )
        val reader = resolver(
            bindings = listOf(binding),
            evidence = listOf(source),
            canonicalChapter = canonical,
        )
        val selected = reader.options(canonical.id).single()
        reader.auditSourceIdentity(selected) shouldBe ProviderReadingSourceIdentityAudit(
            sourceNumberMilli = 0,
            sourceVolume = 1,
            labelHasExplicitVolumeMarker = true,
            sourceTitleIsVolumeContainer = false,
            sourceTitlePresent = true,
        )
        reader.auditSourceIdentity(selected.copy(providerChapterId = "different-id")) shouldBe null
        reader.auditSourceIdentity(selected.copy(externalWorkId = "different-hid")) shouldBe null
        reader.auditSourceIdentity(selected.copy(canonicalChapterId = "different-canonical-chapter")) shouldBe null
    }

    @Test
    fun `Reader rejects volume explicitly stated in label even when source volume field is missing`(): Unit = runBlocking {
        val zero = chapter.copy(id = "chapter-canonical-0", displayNumber = "0", baseNumber = 0)
        val observed = evidence(binding, "volume-112-as-zero", mappedChapterId = zero.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "volume-112-as-zero",
            ).copy(rawLabel = "Vol. 112 Ch. 0", rawNumber = 0.0, volume = null),
        )
        resolver(
            bindings = listOf(binding),
            evidence = listOf(observed),
            canonicalChapter = zero,
        ).options(zero.id) shouldBe emptyList()
    }

    @Test
    fun `Reader rejects a source volume disguised as chapter zero even when the payload is short`(): Unit = runBlocking {
        val zero = chapter.copy(id = "canonical-zero", displayNumber = "0", baseNumber = 0)
        listOf(
            "Ch. 0 - Volume 112",
            "Ch. 0 - Vol. 112",
            "Chapter 0 - Volume 112",
        ).forEach { rawLabel ->
            val observed = evidence(binding, "volume-bundle", mappedChapterId = zero.id).copy(
                evidence = providerEvidence(
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = "volume-bundle",
                ).copy(rawLabel = rawLabel, rawNumber = 0.0, volume = null),
            )
            resolver(
                bindings = listOf(binding),
                evidence = listOf(observed),
                canonicalChapter = zero,
            ).options(zero.id) shouldBe emptyList()
        }
    }

    @Test
    fun `Reader allows independently volume-qualified regular chapter zero`(): Unit = runBlocking {
        val zero = chapter.copy(
            id = "chapter-canonical-vol-0",
            displayNumber = "0",
            baseNumber = 0,
            volume = 1,
        )
        val source = evidence(binding, "volume-1-chapter-0", mappedChapterId = zero.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "volume-1-chapter-0",
            ).copy(rawLabel = "Vol. 1 Ch. 0", rawNumber = 0.0, volume = 1),
        )
        val resolver = resolver(
            bindings = listOf(binding),
            evidence = listOf(source),
            canonicalChapter = zero,
        )
        resolver.options(zero.id).map { it.providerChapterId } shouldBe
            listOf("volume-1-chapter-0")
    }

    @Test
    fun `Reader rejects an explicitly foreign volume even when the chapter number matches`(): Unit = runBlocking {
        val foreign = evidence(binding, "volume-112-chapter-1", mappedChapterId = chapter.id).copy(
            evidence = providerEvidence(
                producerId = binding.ref.evidenceProducerId(),
                externalChapterKey = "volume-112-chapter-1",
            ).copy(
                rawLabel = "Vol. 112 Ch. 1",
                rawNumber = 1.0,
                volume = 112,
            ),
        )
        val reader = resolver(bindings = listOf(binding), evidence = listOf(foreign))

        // Canonical volume is unknown; volume 112 is a positive, conflicting
        // assertion, not a wildcard for canonical chapter one.
        reader.options(chapter.id) shouldBe emptyList()
    }

    @Test
    fun `Reader retains exact prologue and decimal chapter evidence without merging identities`(): Unit = runBlocking {
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
    fun `Reader accepts source decimal matching the precise canonical v2 numbering`(): Unit = runBlocking {
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
    fun `unavailable binding and stale unmatched Provider evidence are excluded`(): Unit = runBlocking {
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
    fun `metadata-only chapter provider cannot be selected or invoked by Reader`(): Unit = runBlocking {
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
    fun `selected option is revalidated before requesting Provider pages`(): Unit = runBlocking {
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
    fun `maps Provider page delivery into provider-neutral Reader content`(): Unit = runBlocking {
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
    fun `verified managed Provider archive becomes Reader content`(): Unit = runBlocking {
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
    fun `managed Provider files remain fail closed before host-owned promotion`(): Unit = runBlocking {
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
    fun `missing canonical chapter fails closed without touching Provider runtime`(): Unit = runBlocking {
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
    fun `persisted Provider binding is rechecked when Provider becomes disabled`(): Unit = runBlocking {
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
    fun `persisted binding requires active reading capabilities and facet`(): Unit = runBlocking {
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

    @Test
    fun `Reader resolves exact chapter evidence from a verified nonpreferred HID`(): Unit = runBlocking {
        val source = ProviderWorkSourceAssociation(
            canonicalTitleId = chapter.canonicalTitleId,
            ref = ProviderBindingRef(providerId, "en", "other-readable-hid"),
            workVerification = ProviderWorkVerification.AUTHOR_CORROBORATED,
            editionQualification = ProviderSourceEditionQualification.UNRESOLVED,
            observedAt = 100L,
        )
        var requests = 0
        val resolver = resolver(
            bindings = emptyList(),
            sourceAssociations = listOf(source),
            evidence = listOf(
                PersistedChapterEvidence(
                    evidence = providerEvidence(source.ref.evidenceProducerId(), "real-chapter-1"),
                    mappedCanonicalChapterId = chapter.id,
                ),
            ),
            gateway = gateway { request ->
                requests += 1
                request.binding shouldBe source.ref
                request.providerChapterId shouldBe "real-chapter-1"
                ProviderCallResult.Success(
                    ProviderReadingDelivery.PageList(
                        listOf(
                            ProviderPageRequest(
                                url = "https://cdn.example/real-1.jpg",
                                allowedOrigins = setOf("https://cdn.example"),
                            ),
                        ),
                    ),
                )
            },
        )
        val option = resolver.options(chapter.id).single()
        option.externalWorkId shouldBe "other-readable-hid"
        option.providerChapterId shouldBe "real-chapter-1"
        resolver.delivery(option) shouldBe ProviderCallResult.Success(
            ProviderReadingDelivery.PageList(
                listOf(
                    ProviderPageRequest(
                        url = "https://cdn.example/real-1.jpg",
                        allowedOrigins = setOf("https://cdn.example"),
                    ),
                ),
            ),
        )
        requests shouldBe 1
    }

    @Test
    fun `Reader refuses conflicting author or explicit variant HID even with stale mapped evidence`(): Unit = runBlocking {
        val ref = ProviderBindingRef(providerId, "en", "unverified-edition")
        val persisted = PersistedChapterEvidence(
            providerEvidence(ref.evidenceProducerId(), "chapter-1"),
            mappedCanonicalChapterId = chapter.id,
        )
        for ((verification, qualification) in listOf(
            ProviderWorkVerification.AUTHOR_CONFLICT to ProviderSourceEditionQualification.UNRESOLVED,
            ProviderWorkVerification.AUTHOR_CORROBORATED to ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT,
        )) {
            val resolver = resolver(
                bindings = emptyList(),
                sourceAssociations = listOf(
                    ProviderWorkSourceAssociation(
                        canonicalTitleId = chapter.canonicalTitleId,
                        ref = ref,
                        workVerification = verification,
                        editionQualification = qualification,
                        observedAt = 100L,
                    ),
                ),
                evidence = listOf(persisted),
                gateway = gateway { error("Unsafe source cannot be delivered") },
            )
            resolver.options(chapter.id) shouldBe emptyList()
        }
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
        sourceAssociations: List<ProviderWorkSourceAssociation> = emptyList(),
    ) = ResolveProviderChapterReading(
        canonicalChapterRepository = canonicalChapterRepository(canonicalChapter),
        evidenceRepository = evidenceRepository(evidence),
        bindingRepository = bindingRepository(bindings),
        workSourceAssociationRepository = sourceAssociationRepository(sourceAssociations),
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

    private fun sourceAssociationRepository(
        values: List<ProviderWorkSourceAssociation>,
    ) = object : ProviderWorkSourceAssociationRepository {
        override suspend fun observe(association: ProviderWorkSourceAssociation): Boolean = false

        override suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation? =
            values.firstOrNull { it.ref == ref }

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderWorkSourceAssociation> =
            values.filter { it.canonicalTitleId == canonicalTitleId }
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
