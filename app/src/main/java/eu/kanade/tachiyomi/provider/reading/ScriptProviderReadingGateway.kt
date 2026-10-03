package eu.kanade.tachiyomi.provider.reading

import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationPolicy
import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderPage
import tachiyomi.domain.tsuzuki.provider.reading.ProviderPageRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkCandidate
import java.util.UUID

data class ActiveProviderScriptPackage(
    val repositoryId: String,
    val versionCode: Long,
    val bytes: ByteArray,
    val parsed: ParsedProviderPackage,
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
        if (stored.revoked) return null

        val bytes = artifactStore.readCurrentArtifact(providerId.value)
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
) : ProviderReadingGateway {

    constructor(
        registry: ProviderRegistry,
        packageSource: ActiveProviderScriptPackageSource,
        runtimeClient: ProviderRuntimeClient,
        limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
    ) : this(
        registry = registry,
        packageSource = packageSource,
        invokePackage = ProviderPackageCapabilityInvoker(runtimeClient::invokePackage),
        limits = limits,
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
                ),
            ),
        ) { value, active ->
            val decoded = json.decodeFromString<LookupPageDto>(value)
            ProviderPage(
                items = decoded.items.map { item ->
                    item.url?.let { validateResultUrl(active, it, browserAllowed = true) }
                    ProviderWorkCandidate(
                        externalWorkId = item.externalWorkId,
                        title = item.title,
                        aliases = item.aliases,
                        url = item.url,
                        language = item.language,
                    )
                },
                nextCursor = decoded.nextCursor?.let(::ProviderCursor),
            )
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
                ),
            ),
        ) { value, active ->
            val decoded = json.decodeFromString<PagesResultDto>(value)
            when (decoded.type) {
                PAGE_LIST_TYPE -> {
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
                            validateResultUrl(active, page.url, browserAllowed = false)
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
                    error("Managed Provider files require host-owned promotion")
                }
                else -> error("Unknown Provider reading delivery type")
            }
        }
    }

    private suspend fun <T> call(
        providerId: ProviderId,
        capability: ProviderCapabilityRef,
        inputJson: String,
        decode: (String, ActiveProviderScriptPackage) -> T,
    ): ProviderCallResult<T> {
        registry.awaitReady()
        val registration = registry.registration(providerId)
            ?: return unavailable()
        if (
            registration.lifecycleStatus != ProviderLifecycleStatus.ENABLED ||
            registration.descriptor.runtime != ProviderRuntimeKind.SCRIPT ||
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

        if (!matchesRegistration(registration, active, capability)) {
            return unavailable()
        }

        val invocationId = invocationIdFactory()
        val hostPolicy = try {
            ProviderHostInvocationPolicy.fromManifest(
                manifest = active.parsed.manifest,
                invocationId = invocationId,
            )
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

        response.failure?.let { failure ->
            return failure.toProviderFailure()
        }

        val value = response.value ?: return malformed()
        return try {
            ProviderCallResult.Success(decode(value, active))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            malformed()
        }
    }

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
        ProviderRuntimeFailureCode.RESOURCE_LIMIT,
        ProviderRuntimeFailureCode.INVOCATION_CONFLICT,
        ->
            failure(ProviderErrorCode.RESOURCE_LIMIT, retryable = true)
        ProviderRuntimeFailureCode.MALFORMED_RESULT ->
            malformed()
        ProviderRuntimeFailureCode.CANCELLED ->
            throw CancellationException("Provider reading invocation cancelled")
        ProviderRuntimeFailureCode.HOST_ERROR ->
            unavailable(retryable = true)
        ProviderRuntimeFailureCode.SCRIPT_ERROR,
        ProviderRuntimeFailureCode.PACKAGE_INVALID,
        ProviderRuntimeFailureCode.MALFORMED_REQUEST,
        ProviderRuntimeFailureCode.SOURCE_TOO_LARGE,
        ProviderRuntimeFailureCode.SOURCE_READ_ERROR,
        ->
            failure(ProviderErrorCode.SCRIPT_ERROR, retryable = false)
    }

    private fun unavailable(retryable: Boolean = false) =
        failure(ProviderErrorCode.UNAVAILABLE, retryable)

    private fun malformed() =
        failure(ProviderErrorCode.MALFORMED_RESULT, retryable = false)

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
    )

    @Serializable
    internal data class LookupPageDto(
        val items: List<WorkCandidateDto>,
        val nextCursor: String? = null,
    )

    @Serializable
    internal data class WorkCandidateDto(
        val externalWorkId: String,
        val title: String,
        val aliases: List<String> = emptyList(),
        val url: String? = null,
        val language: String? = null,
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
    )

    @Serializable
    internal data class PageRequestDto(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val PAGE_LIST_TYPE = "page_list"
        const val MANAGED_FILE_TYPE = "managed_file"
    }
}
