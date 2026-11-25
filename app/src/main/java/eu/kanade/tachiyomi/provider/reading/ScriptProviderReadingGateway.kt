package eu.kanade.tachiyomi.provider.reading

import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationPolicy
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.core.provider.supplychain.sha256Hex
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderMalformedResultStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderPageFailureStage
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterObservation
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.reading.ProviderError
import tachiyomi.domain.tsuzuki.provider.reading.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.reading.ProviderLookupFailureCode
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import tachiyomi.domain.tsuzuki.provider.reading.ProviderPage
import tachiyomi.domain.tsuzuki.provider.reading.ProviderPageRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkDetailOutcome
import java.util.UUID

data class ActiveProviderScriptPackage(
    val repositoryId: String,
    val versionCode: Long,
    val bytes: ByteArray,
    val parsed: ParsedProviderPackage,
    /** Populated only by StoredProviderScriptPackageSource from the verified installation ledger. */
    val installedArtifactSha256: String? = null,
    val repositoryTrustAnchorSha256: String? = null,
)

fun interface ActiveProviderScriptPackageSource {
    suspend fun get(providerId: ProviderId): ActiveProviderScriptPackage?
}

class StoredProviderScriptPackageSource(
    private val artifactStore: ProviderArtifactStore,
    private val parser: ProviderPackageParser = ProviderPackageParser(),
) : ActiveProviderScriptPackageSource {

    override suspend fun get(providerId: ProviderId): ActiveProviderScriptPackage? {
        val stored = artifactStore.current(providerId.value) ?: return null
        if (stored.revoked || stored.providerId != providerId.value) return null

        // The store's ledger was created by index-signature + artifact-digest
        // verification at installation. Recheck the *actual* bytes, then
        // reject a concurrent update, rollback or revocation during this read.
        val bytes = artifactStore.readCurrentArtifact(providerId.value)
        val again = artifactStore.current(providerId.value) ?: return null
        if (stored != again || sha256Hex(bytes) != stored.sha256) return null
        val parsed = parser.parse(bytes)
        if (
            parsed.manifest.id != providerId.value ||
            parsed.manifest.version.code != stored.versionCode
        ) {
            return null
        }

        return ActiveProviderScriptPackage(
            repositoryId = stored.repositoryId,
            versionCode = stored.versionCode,
            bytes = bytes,
            parsed = parsed,
            installedArtifactSha256 = stored.sha256,
            repositoryTrustAnchorSha256 = stored.repositoryTrustAnchorSha256,
        )
    }
}

fun interface ProviderPackageCapabilityInvoker {
    suspend fun invoke(
        request: ProviderRuntimeInvocationRequest,
        packageBytes: ByteArray,
        inputJson: String,
        hostPolicy: ProviderHostInvocationPolicy,
    ): ProviderRuntimeInvocationResponse
}

class ScriptProviderReadingGateway internal constructor(
    private val registry: ProviderRegistry,
    private val packageSource: ActiveProviderScriptPackageSource,
    private val invokePackage: ProviderPackageCapabilityInvoker,
    private val limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
    private val invocationIdFactory: () -> String = { "reading:${UUID.randomUUID()}" },
    private val managedResources: ProviderManagedResourceResolver = ProviderManagedResourceResolver.DenyAll,
    private val enableExperimentalMangaAllV2: Boolean = false,
    /** Test-only; the public production constructor NEVER enables unsigned archives. */
    private val allowUnsignedMangaAllV2Fixtures: Boolean = false,
) : ProviderReadingGateway {

    constructor(
        registry: ProviderRegistry,
        packageSource: ActiveProviderScriptPackageSource,
        runtimeClient: ProviderRuntimeClient,
        limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
        managedResources: ProviderManagedResourceResolver = ProviderManagedResourceResolver.DenyAll,
        enableExperimentalMangaAllV2: Boolean = false,
    ) : this(
        registry = registry,
        packageSource = packageSource,
        invokePackage = ProviderPackageCapabilityInvoker(runtimeClient::invokePackage),
        limits = limits,
        managedResources = managedResources,
        enableExperimentalMangaAllV2 = enableExperimentalMangaAllV2,
    )

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    override suspend fun lookup(
        providerId: ProviderId,
        request: ProviderReadingLookupRequest,
    ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> =
        call(
            providerId = providerId,
            capability = tachiyomi.domain.tsuzuki.provider.ProviderCapabilities.ReadingLookupV1,
            inputJson = json.encodeToString(
                LookupRequestDto(
                    titles = request.titles,
                    cursor = request.cursor?.value,
                    coverageEvidenceVersion = request.coverageEvidenceVersion,
                    requestedLanguage = request.requestedLanguage,
                    catalogEvidenceVersion = request.catalogEvidenceVersion,
                ),
            ),
        ) { value, active ->
            val decoded = json.decodeFromString<LookupPageDto>(value)
            if (decoded.lookupFailureCode != null) {
                require(decoded.items.isEmpty() && decoded.nextCursor == null) {
                    "Source failure cannot carry successful candidates"
                }
                require(decoded.lookupHttpStatus == null || decoded.lookupHttpStatus in 100..599)
                throw SourceLookupFailure(
                    ProviderLookupFailureCode.valueOf(decoded.lookupFailureCode),
                    decoded.lookupHttpStatus,
                )
            }
            require(decoded.lookupHttpStatus == null) { "HTTP status requires a source lookup failure" }
            ProviderPage(
                items = decoded.items.map { item ->
                    item.url?.let { validateResultUrl(active, it, browserAllowed = true) }
                    require(
                        item.mangaUpdatesReferenceIds.isEmpty() ||
                            (
                                request.catalogEvidenceVersion == 1 &&
                                    providerId.value == "app.tsuzuki.mangaall" &&
                                    active.versionCode == 11L &&
                                    active.repositoryId == "mangaall-frozen30-unsigned-research" &&
                                    active.installedArtifactSha256 == null &&
                                    item.externalWorkId.startsWith("mangadot:")
                                ),
                    ) { "Unsolicited or out-of-scope catalog evidence" }
                    ProviderWorkCandidate(
                        externalWorkId = item.externalWorkId,
                        title = item.title,
                        aliases = item.aliases,
                        url = item.url,
                        language = item.language,
                        chapterCount = item.chapterCount,
                        authors = item.authors,
                        coverageOutcome = item.coverageOutcome?.let(
                            tachiyomi.domain.tsuzuki.provider.reading.ProviderCoverageOutcome::valueOf,
                        ),
                        coverageObservedCount = item.coverageObservedCount,
                        coverageMinMilli = item.coverageMinMilli,
                        coverageMaxMilli = item.coverageMaxMilli,
                        coverageHasMore = item.coverageHasMore,
                        detailOutcome = item.detailOutcome?.let(ProviderWorkDetailOutcome::valueOf),
                        detailHttpStatus = item.detailHttpStatus,
                        mangaUpdatesReferenceIds = item.mangaUpdatesReferenceIds,
                    )
                },
                nextCursor = decoded.nextCursor?.let(::ProviderCursor),
            )
        }

    /**
     * EXPERIMENTAL / not wired into the production ProviderReadingGateway.
     * Never invoke V2 against code8 or an unverified repository identity.
     * A V2 response is read-only diagnostic evidence, not ProviderPage items,
     * chapter binding or an authorized Reader source.
     */
    internal suspend fun lookupMangaAllV2ReadOnly(
        providerId: ProviderId,
        request: ProviderReadingLookupRequest,
    ): ProviderCallResult<MangaAllLookupV2ReadOnlyDiagnostics.ReadOnlyAudit> {
        if (
            !enableExperimentalMangaAllV2 ||
            providerId.value != MANGAALL_PROVIDER_ID ||
            request.requestedLanguage != "en"
        ) {
            return unavailable()
        }
        return call(
            providerId = providerId,
            capability = tachiyomi.domain.tsuzuki.provider.ProviderCapabilities.ReadingLookupV1,
            inputJson = json.encodeToString(
                LookupRequestDto(
                    titles = request.titles,
                    cursor = request.cursor?.value,
                    diagnosticsVersion = 2,
                    requestedLanguage = request.requestedLanguage,
                ),
            ),
            packageGate = { active -> mangaAllV2HostProvenance(active) != null },
            recheckPackageAfterInvocation = true,
        ) { value, active ->
            val provenance = mangaAllV2HostProvenance(active)
                ?: error("MangaAll signed-version provenance lost before decoding")
            val declaredContract = when (provenance.classification) {
                MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance.Classification.SIGNED_INSTALLATION ->
                    "V2_READONLY_OBSERVATION"
                MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance.Classification.UNSIGNED_TEST_FIXTURE ->
                    "UNSIGNED_CANDIDATE_NOT_LIVE"
            }
            MangaAllLookupV2ReadOnlyDiagnostics.decodeReadOnly(value, declaredContract)
                ?.copy(hostProvenance = provenance)
                ?: error("Malformed or unauthorized MangaAll V2 diagnostic envelope")
        }
    }

    /**
     * Only the installed artifact ledger can establish signed-package origin.
     * An untrusted JS response declaring "signed" is never sufficient.
     * Even SIGNED_INSTALLATION means package identity, NOT editorial authority,
     * complete source availability or permission to read CDN images.
     */
    private fun mangaAllV2HostProvenance(
        active: ActiveProviderScriptPackage,
    ): MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance? {
        // Only independently reviewed release generations are eligible.
        // Development code10 and any later code11+ remain ineligible.
        // An eligible version alone is NEVER sufficient to establish trust:
        // all signed-installation checks below remain mandatory.
        val releaseName = when (active.versionCode) {
            9L -> "0.5.4"
            10L -> "0.5.5"
            else -> return null
        }
        if (
            active.repositoryId != MANGAALL_TRUSTED_REPOSITORY ||
            active.parsed.manifest.id != MANGAALL_PROVIDER_ID ||
            active.parsed.manifest.version.code != active.versionCode
        ) {
            return null
        }
        val digest = sha256Hex(active.bytes)
        val signed = packageSource is StoredProviderScriptPackageSource &&
            active.installedArtifactSha256 == digest &&
            active.repositoryTrustAnchorSha256 == MANGAALL_TRUST_ANCHOR_SHA256 &&
            active.parsed.manifest.version.name == releaseName
        // Historical unsigned code9 fixture only. Code10 can NEVER qualify
        // from an unsigned test package, even with experimental opt-in.
        val fixture = active.versionCode == 9L &&
            allowUnsignedMangaAllV2Fixtures &&
            packageSource !is StoredProviderScriptPackageSource &&
            active.installedArtifactSha256 == null &&
            active.repositoryTrustAnchorSha256 == null &&
            active.parsed.manifest.version.name == "0.5.4-dev-unsigned"
        if (!signed && !fixture) return null
        return MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance(
            classification = if (signed) {
                MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance.Classification.SIGNED_INSTALLATION
            } else {
                MangaAllLookupV2ReadOnlyDiagnostics.HostProvenance.Classification.UNSIGNED_TEST_FIXTURE
            },
            repositoryId = active.repositoryId,
            versionCode = active.versionCode,
            artifactSha256 = digest,
            trustAnchorSha256 = if (signed) active.repositoryTrustAnchorSha256 else null,
        )
    }

    /**
     * EXPERIMENTAL: collect one title's V2 read-only evidence through the
     * same installed-package/runtime checks as each individual lookup.
     * Cross-page results remain diagnostic, never Reader authorization.
     */
    internal suspend fun auditMangaAllV2ReadOnly(
        providerId: ProviderId,
        request: ProviderReadingLookupRequest,
    ): ProviderCallResult<MangaAllLookupV2ReadOnlyCursorAccumulator.Snapshot> {
        if (request.titles.size != 1 || request.cursor != null || request.requestedLanguage != "en") {
            return malformed()
        }
        val accumulator = MangaAllLookupV2ReadOnlyCursorAccumulator()
        var cursor: String? = null
        repeat(8) {
            val result = lookupMangaAllV2ReadOnly(
                providerId,
                request.copy(cursor = cursor?.let(::ProviderCursor)),
            )
            when (result) {
                is ProviderCallResult.Failure -> return result
                is ProviderCallResult.Success -> {
                    val page = accumulator.acceptValidated(result.value, cursor)
                        ?: return malformed()
                    if (page.nextCursor == null) return ProviderCallResult.Success(page)
                    cursor = page.nextCursor
                }
            }
        }
        return malformed()
    }

    override suspend fun chapters(
        providerId: ProviderId,
        request: ProviderReadingChaptersRequest,
    ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
        if (request.binding.providerId != providerId) {
            return malformed()
        }

        return call(
            providerId = providerId,
            capability = tachiyomi.domain.tsuzuki.provider.ProviderCapabilities.ReadingChaptersV1,
            inputJson = json.encodeToString(
                ChaptersRequestDto(
                    binding = request.binding.toDto(),
                    cursor = request.cursor?.value,
                ),
            ),
        ) { value, _ ->
            val decoded = json.decodeFromString<ChaptersPageDto>(value)
            ProviderPage(
                items = decoded.items.map { item ->
                    ProviderChapterObservation(
                        providerChapterId = item.providerChapterId,
                        rawLabel = item.rawLabel,
                        rawNumber = item.rawNumber,
                        volume = item.volume,
                        title = item.title,
                        language = item.language,
                        scanlationGroup = item.scanlationGroup,
                        releaseDateMillis = item.releaseDateMillis,
                    )
                },
                nextCursor = decoded.nextCursor?.let(::ProviderCursor),
            )
        }
    }

    override suspend fun pages(
        providerId: ProviderId,
        request: ProviderReadingPagesRequest,
    ): ProviderCallResult<ProviderReadingDelivery> {
        if (request.binding.providerId != providerId) {
            return malformed()
        }

        return call(
            providerId = providerId,
            capability = tachiyomi.domain.tsuzuki.provider.ProviderCapabilities.ReadingPagesV1,
            inputJson = json.encodeToString(
                PagesRequestDto(
                    binding = request.binding.toDto(),
                    providerChapterId = request.providerChapterId,
                    diagnosticsVersion = 2,
                ),
            ),
        ) { value, active ->
            val decoded = json.decodeFromString<PagesResultDto>(value)
            when (decoded.type) {
                PAGE_FAILURE_TYPE -> {
                    if (decoded.pages.isNotEmpty() || decoded.resource != null || decoded.format != null) {
                        error("Provider page failure contains unexpected delivery")
                    }
                    val stage = DiagnosticProviderPageFailureStage.valueOf(
                        decoded.pageFailureStage ?: error("Provider page failure has no stage"),
                    )
                    throw SourcePageFailure(stage)
                }
                PAGE_LIST_TYPE -> {
                    if (decoded.pageFailureStage != null) {
                        error("Provider page list contains unrelated failure stage")
                    }
                    if (decoded.pages.isEmpty()) {
                        error("Provider page list is empty")
                    }
                    val network = active.parsed.manifest.permissions.network
                        ?: error("Provider returned page URLs without network permission authority")
                    if (network.origins.isEmpty()) {
                        error("Provider returned page URLs without allowed network origins")
                    }
                    ProviderReadingDelivery.PageList(
                        pages = decoded.pages.map { page ->
                            // A CDN outside the Provider's signed permission
                            // allowlist is a policy failure, not malformed JSON.
                            try {
                                validateResultUrl(active, page.url, browserAllowed = false)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                throw PageOriginPolicyFailure()
                            }
                            ProviderPageRequest(
                                url = page.url,
                                headers = page.headers,
                                allowedOrigins = network.origins,
                                allowLocalNetwork = network.localNetwork,
                            )
                        },
                    )
                }
                MANAGED_FILE_TYPE -> {
                    if (decoded.pageFailureStage != null) {
                        error("Provider managed file contains unrelated failure stage")
                    }
                    val resource = ProviderManagedResourceRef(
                        decoded.resource ?: error("Managed Provider file is missing resource token"),
                    )
                    val format = ProviderManagedFileFormat.valueOf(
                        decoded.format?.uppercase()
                            ?: error("Managed Provider file is missing format"),
                    )
                    if (managedResources.resolve(providerId, resource, format) == null) {
                        error("Managed Provider file is not owned by this Provider")
                    }
                    ProviderReadingDelivery.ManagedFile(
                        resource = resource,
                        format = format,
                    )
                }
                else -> error("Unknown Provider reading delivery type")
            }
        }
    }

    private suspend fun <T> call(
        providerId: ProviderId,
        capability: ProviderCapabilityRef,
        inputJson: String,
        packageGate: (ActiveProviderScriptPackage) -> Boolean = { true },
        recheckPackageAfterInvocation: Boolean = false,
        decode: (String, ActiveProviderScriptPackage) -> T,
    ): ProviderCallResult<T> {
        registry.awaitReady()
        val registration = registry.registration(providerId)
            ?: return unavailable()
        if (
            registration.lifecycleStatus != ProviderLifecycleStatus.ENABLED ||
            registration.descriptor.runtime != ProviderRuntimeKind.SCRIPT ||
            capability !in registration.enabledCapabilities ||
            capability !in registration.descriptor.capabilities
        ) {
            return unavailable()
        }

        val active = try {
            packageSource.get(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return unavailable(retryable = true)
        } ?: return unavailable()

        if (!matchesRegistration(registration, active, capability) || !packageGate(active)) {
            return unavailable()
        }

        val invocationId = invocationIdFactory()
        val hostPolicy = try {
            ProviderHostInvocationPolicy.fromManifest(
                manifest = active.parsed.manifest,
                invocationId = invocationId,
            ).forCapability(capability.id)
        } catch (_: Throwable) {
            return malformed()
        }

        val runtimeRequest = try {
            ProviderRuntimeInvocationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = invocationId,
                providerId = providerId.value,
                artifactVersionCode = active.versionCode,
                capabilityId = capability.id,
                capabilityVersion = capability.version,
                configurationFingerprint = registration.configurationFingerprint,
                fileName = active.parsed.manifest.entrypoint,
                hostModules = hostPolicy.allowedHostModules(),
                limits = limits,
            )
        } catch (_: Throwable) {
            return malformed()
        }

        val response = try {
            invokePackage.invoke(
                request = runtimeRequest,
                packageBytes = active.bytes,
                inputJson = inputJson,
                hostPolicy = hostPolicy,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return unavailable(retryable = true)
        }

        // Invocations cross an isolated process boundary and can finish after
        // a capability is revoked or its package is replaced. Never publish
        // a result issued under a stale registration/permission snapshot.
        if (registry.registration(providerId) != registration) {
            return unavailable()
        }
        if (recheckPackageAfterInvocation) {
            // Same package SHA and origin must still be active AFTER QuickJS:
            // revocation/replacement must not yield a successful audit.
            val stillActive = try {
                packageSource.get(providerId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (
                stillActive == null ||
                !matchesRegistration(registration, stillActive, capability) ||
                mangaAllV2HostProvenance(stillActive) != mangaAllV2HostProvenance(active)
            ) {
                return unavailable()
            }
        }

        response.failure?.let { failure ->
            return failure.toProviderFailure()
        }

        val value = response.value ?: return malformed(DiagnosticProviderMalformedResultStage.EMPTY_VALUE)
        return try {
            ProviderCallResult.Success(decode(value, active))
        } catch (error: CancellationException) {
            throw error
        } catch (_: PageOriginPolicyFailure) {
            failure(ProviderErrorCode.NETWORK_POLICY, retryable = false)
        } catch (error: SourcePageFailure) {
            ProviderCallResult.Failure(
                ProviderError(
                    code = ProviderErrorCode.SOURCE_READ_ERROR,
                    retryable = false,
                    pageFailureStage = error.stage,
                ),
            )
        } catch (error: SourceLookupFailure) {
            ProviderCallResult.Failure(
                ProviderError(
                    code = ProviderErrorCode.SOURCE_READ_ERROR,
                    retryable = error.kind == ProviderLookupFailureCode.CHALLENGE_BLOCKED ||
                        (
                            error.kind == ProviderLookupFailureCode.HTTP_ERROR &&
                                error.httpStatus?.let { it in 500..599 } == true
                            ),
                    sourceFailureCode = error.kind,
                    httpStatus = error.httpStatus,
                ),
            )
        } catch (_: SerializationException) {
            malformed(DiagnosticProviderMalformedResultStage.SCHEMA_DECODE)
        } catch (_: Throwable) {
            malformed(DiagnosticProviderMalformedResultStage.CONTRACT_VALIDATION)
        }
    }

    private class PageOriginPolicyFailure : RuntimeException()

    private class SourceLookupFailure(
        val kind: ProviderLookupFailureCode,
        val httpStatus: Int?,
    ) : RuntimeException()

    private class SourcePageFailure(
        val stage: DiagnosticProviderPageFailureStage,
    ) : RuntimeException()

    private fun matchesRegistration(
        registration: ProviderRegistration,
        active: ActiveProviderScriptPackage,
        capability: ProviderCapabilityRef,
    ): Boolean {
        val descriptor = registration.descriptor
        val manifest = active.parsed.manifest
        val repository = descriptor.origin as? ProviderOrigin.Repository ?: return false

        return repository.repositoryId == active.repositoryId &&
            descriptor.id.value == manifest.id &&
            descriptor.version.code == active.versionCode &&
            descriptor.version.code == manifest.version.code &&
            descriptor.version.name == manifest.version.name &&
            manifest.capabilities.any {
                it.id == capability.id && it.version == capability.version
            }
    }

    private fun validateResultUrl(
        active: ActiveProviderScriptPackage,
        url: String,
        browserAllowed: Boolean,
    ) {
        val permissions = active.parsed.manifest.permissions
        val origins = buildSet {
            addAll(permissions.network?.origins.orEmpty())
            if (browserAllowed) {
                addAll(permissions.browser?.origins.orEmpty())
            }
        }
        if (origins.isEmpty()) {
            error("Provider returned a URL without matching permission authority")
        }
        ProviderNetworkPolicy(
            allowedOrigins = origins,
            allowLocalNetwork = permissions.network?.localNetwork == true,
        ).validate(url, resolveAddress = false)
    }

    private fun ProviderBindingRef.toDto() = BindingDto(
        providerId = providerId.value,
        facetId = facetId,
        externalWorkId = externalWorkId,
    )

    private fun ProviderRuntimeFailureCode.toProviderFailure(): ProviderCallResult.Failure = when (this) {
        ProviderRuntimeFailureCode.TIMEOUT ->
            failure(ProviderErrorCode.TIMEOUT, retryable = true)
        ProviderRuntimeFailureCode.RUNTIME_DIED ->
            failure(ProviderErrorCode.RUNTIME_DIED, retryable = true)
        ProviderRuntimeFailureCode.RESOURCE_LIMIT ->
            failure(ProviderErrorCode.RESOURCE_LIMIT, retryable = true)
        ProviderRuntimeFailureCode.INVOCATION_CONFLICT ->
            failure(ProviderErrorCode.INVOCATION_CONFLICT, retryable = true)
        ProviderRuntimeFailureCode.MALFORMED_RESULT ->
            malformed(DiagnosticProviderMalformedResultStage.RUNTIME_PAYLOAD)
        ProviderRuntimeFailureCode.CANCELLED ->
            throw CancellationException("Provider reading invocation cancelled")
        ProviderRuntimeFailureCode.HOST_ERROR ->
            failure(ProviderErrorCode.HOST_ERROR, retryable = true)
        ProviderRuntimeFailureCode.SCRIPT_ERROR ->
            failure(ProviderErrorCode.SCRIPT_ERROR, retryable = false)
        ProviderRuntimeFailureCode.PACKAGE_INVALID ->
            failure(ProviderErrorCode.PACKAGE_INVALID, retryable = false)
        ProviderRuntimeFailureCode.MALFORMED_REQUEST ->
            failure(ProviderErrorCode.MALFORMED_REQUEST, retryable = false)
        ProviderRuntimeFailureCode.SOURCE_TOO_LARGE ->
            failure(ProviderErrorCode.SOURCE_TOO_LARGE, retryable = false)
        ProviderRuntimeFailureCode.SOURCE_READ_ERROR ->
            failure(ProviderErrorCode.SOURCE_READ_ERROR, retryable = false)
    }

    private fun unavailable(retryable: Boolean = false) =
        failure(ProviderErrorCode.UNAVAILABLE, retryable)

    private fun malformed(
        stage: DiagnosticProviderMalformedResultStage = DiagnosticProviderMalformedResultStage.CONTRACT_VALIDATION,
    ) = ProviderCallResult.Failure(
        ProviderError(
            code = ProviderErrorCode.MALFORMED_RESULT,
            retryable = false,
            malformedStage = stage,
        ),
    )

    private fun failure(
        code: ProviderErrorCode,
        retryable: Boolean,
    ) = ProviderCallResult.Failure(
        ProviderError(
            code = code,
            retryable = retryable,
        ),
    )

    @Serializable
    internal data class BindingDto(
        val providerId: String,
        val facetId: String? = null,
        val externalWorkId: String,
    )

    @Serializable
    internal data class LookupRequestDto(
        val titles: List<String>,
        val cursor: String? = null,
        /** V1 Provider ignores this; upgraded Provider emits extra diagnostic keys only when requested. */
        val diagnosticsVersion: Int = 1,
        /** Optional editorial evidence; ignored by Providers lacking this extension. */
        val editorialEvidenceVersion: Int = 1,
        val coverageEvidenceVersion: Int? = null,
        val requestedLanguage: String? = null,
        val catalogEvidenceVersion: Int? = null,
    )

    @Serializable
    internal data class ChaptersRequestDto(
        val binding: BindingDto,
        val cursor: String? = null,
    )

    @Serializable
    internal data class PagesRequestDto(
        val binding: BindingDto,
        val providerChapterId: String,
        /** New Providers opt into structured failures only on this Host. */
        val diagnosticsVersion: Int,
    )

    @Serializable
    internal data class LookupPageDto(
        val items: List<WorkCandidateDto>,
        val nextCursor: String? = null,
        val lookupFailureCode: String? = null,
        val lookupHttpStatus: Int? = null,
    )

    @Serializable
    internal data class WorkCandidateDto(
        val externalWorkId: String,
        val title: String,
        val aliases: List<String> = emptyList(),
        val url: String? = null,
        val language: String? = null,
        val chapterCount: Int? = null,
        val authors: List<String> = emptyList(),
        val coverageOutcome: String? = null,
        val coverageObservedCount: Int? = null,
        val coverageMinMilli: Int? = null,
        val coverageMaxMilli: Int? = null,
        val coverageHasMore: Boolean? = null,
        val detailOutcome: String? = null,
        val detailHttpStatus: Int? = null,
        val mangaUpdatesReferenceIds: List<String> = emptyList(),
    )

    @Serializable
    internal data class ChaptersPageDto(
        val items: List<ChapterObservationDto>,
        val nextCursor: String? = null,
    )

    @Serializable
    internal data class ChapterObservationDto(
        val providerChapterId: String,
        val rawLabel: String,
        val rawNumber: Double? = null,
        val volume: Int? = null,
        val title: String? = null,
        val language: String? = null,
        val scanlationGroup: String? = null,
        val releaseDateMillis: Long? = null,
    )

    @Serializable
    internal data class PagesResultDto(
        val type: String,
        val pages: List<PageRequestDto> = emptyList(),
        val resource: String? = null,
        val format: String? = null,
        val pageFailureStage: String? = null,
    )

    @Serializable
    internal data class PageRequestDto(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val MANGAALL_PROVIDER_ID = "app.tsuzuki.mangaall"
        const val MANGAALL_TRUSTED_REPOSITORY = "app.tsuzuki.providers"

        // Same P-256 repository root must attest either signed code9 or future
        // signed code10. Key rotation and code11+ require an explicit new gate.
        const val MANGAALL_TRUST_ANCHOR_SHA256 =
            "20875348d95649ff0009e329f0d43de1b84ccf8af7966c215d5e0f449e697159"
        const val PAGE_LIST_TYPE = "page_list"
        const val PAGE_FAILURE_TYPE = "page_failure"
        const val MANAGED_FILE_TYPE = "managed_file"
    }
}
