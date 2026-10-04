package eu.kanade.tachiyomi.provider.runtime

import kotlinx.coroutines.CancellationException
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.core.provider.supplychain.ProviderArtifactStore
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef
import tachiyomi.domain.tsuzuki.provider.ProviderError
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import java.util.UUID

data class ScriptProviderPackage(
    val repositoryId: String,
    val versionCode: Long,
    val bytes: ByteArray,
    val parsed: ParsedProviderPackage,
)

fun interface ScriptProviderPackageSource {
    suspend fun get(providerId: ProviderId): ScriptProviderPackage?
}

class StoredScriptProviderPackageSource(
    private val artifactStore: ProviderArtifactStore,
    private val parser: ProviderPackageParser = ProviderPackageParser(),
) : ScriptProviderPackageSource {

    override suspend fun get(providerId: ProviderId): ScriptProviderPackage? {
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

        return ScriptProviderPackage(
            repositoryId = stored.repositoryId,
            versionCode = stored.versionCode,
            bytes = bytes,
            parsed = parsed,
        )
    }
}

fun interface ScriptProviderRuntimeInvoker {
    suspend fun invoke(
        request: ProviderRuntimeInvocationRequest,
        packageBytes: ByteArray,
        inputJson: String,
        hostPolicy: ProviderHostInvocationPolicy,
    ): ProviderRuntimeInvocationResponse
}

class ScriptProviderCapabilityExecutor(
    private val registry: ProviderRegistry,
    private val packageSource: ScriptProviderPackageSource,
    private val invokePackage: ScriptProviderRuntimeInvoker,
    private val limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
    private val invocationIdFactory: (ProviderCapabilityRef) -> String = { capability ->
        "provider:" + capability.id + ":" + UUID.randomUUID()
    },
) {

    constructor(
        registry: ProviderRegistry,
        packageSource: ScriptProviderPackageSource,
        runtimeClient: ProviderRuntimeClient,
        limits: ProviderRuntimeLimitsDto = ProviderRuntimeLimitsDto(),
    ) : this(
        registry = registry,
        packageSource = packageSource,
        invokePackage = ScriptProviderRuntimeInvoker(runtimeClient::invokePackage),
        limits = limits,
    )

    suspend fun <T> invoke(
        providerId: ProviderId,
        capability: ProviderCapabilityRef,
        inputJson: String,
        decode: (String, ScriptProviderPackage) -> T,
    ): ProviderCallResult<T> {
        registry.awaitReady()
        val registration = registry.registration(providerId)
            ?: return unavailable()
        if (
            registration.lifecycleStatus != ProviderLifecycleStatus.ENABLED ||
            registration.descriptor.runtime != ProviderRuntimeKind.SCRIPT ||
            capability !in registration.enabledCapabilities
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

        val invocationId = invocationIdFactory(capability)
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
        active: ScriptProviderPackage,
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
            throw CancellationException("Provider capability invocation cancelled")
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
}
