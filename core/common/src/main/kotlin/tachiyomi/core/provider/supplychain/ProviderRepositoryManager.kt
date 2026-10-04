package tachiyomi.core.provider.supplychain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderPackageActivator
import java.util.concurrent.ConcurrentHashMap

interface ProviderRepositoryTransport {
    suspend fun fetchIndex(indexUrl: String): SignedProviderRepositoryIndex

    suspend fun fetchArtifact(artifactUrl: String): ByteArray
}

enum class ProviderRepositoryEntryStatus {
    AVAILABLE,
    INSTALLED,
    UPDATE_AVAILABLE,
    INSTALLED_NEWER,
    REVOKED,
    INCOMPATIBLE,
    ORIGIN_CONFLICT,
}

data class ProviderRepositoryCatalogEntry(
    val repositoryId: String,
    val descriptor: ProviderArtifactDescriptor,
    val installedVersionCode: Long?,
    val status: ProviderRepositoryEntryStatus,
)

data class ProviderRepositorySnapshot(
    val repository: EnrolledProviderRepository,
    val sequence: Long,
    val verifiedKeyId: String,
    val entries: List<ProviderRepositoryCatalogEntry>,
)

class ProviderRepositoryManager(
    private val hostApiVersion: Int,
    private val enrollmentStore: ProviderRepositoryEnrollmentStore,
    private val trustStore: ProviderRepositoryTrustStore,
    private val artifactStore: ProviderArtifactStore,
    private val packageActivator: ProviderPackageActivator,
    private val transport: ProviderRepositoryTransport,
) {

    private val sessions = ConcurrentHashMap<String, RepositorySession>()
    private val repositoryLocks = ConcurrentHashMap<String, Mutex>()

    init {
        require(hostApiVersion > 0) { "Host API version must be positive" }
    }

    fun repositories(): List<EnrolledProviderRepository> =
        enrollmentStore.list()

    suspend fun enroll(repository: EnrolledProviderRepository) {
        val repositoryId = repository.enrollment.repositoryId
        repositoryLock(repositoryId).withLock {
            enrollmentStore.save(repository)
            sessions.remove(repositoryId)
        }
    }

    suspend fun removeRepository(repositoryId: String): Boolean =
        repositoryLock(repositoryId).withLock {
            sessions.remove(repositoryId)
            val enrollmentRemoved = enrollmentStore.remove(repositoryId)
            val trustRemoved = trustStore.remove(repositoryId)
            enrollmentRemoved || trustRemoved
        }

    suspend fun refresh(repositoryId: String): ProviderRepositorySnapshot =
        repositoryLock(repositoryId).withLock {
            refreshLocked(repositoryId)
        }

    private suspend fun refreshLocked(repositoryId: String): ProviderRepositorySnapshot {
        val repository = enrollmentStore.get(repositoryId)
            ?: throw ProviderSupplyChainException("Provider repository is not enrolled")

        val trust = ProviderRepositoryTrust.fromEnrollment(
            enrollment = repository.enrollment,
            hostApiVersion = hostApiVersion,
            stateStore = trustStore,
        )
        val signed = transport.fetchIndex(repository.enrollment.indexUrl)
        val verified = try {
            trust.verifyAndAccept(signed)
        } catch (acceptError: ProviderSupplyChainException) {
            try {
                trust.verifyCurrent(signed)
            } catch (_: ProviderSupplyChainException) {
                throw acceptError
            }
        }

        trust.applyRevocations(verified, artifactStore)
        sessions[repositoryId] = RepositorySession(
            trust = trust,
            verified = verified,
        )
        return buildSnapshot(repository, verified)
    }

    fun snapshot(repositoryId: String): ProviderRepositorySnapshot? {
        val repository = enrollmentStore.get(repositoryId) ?: return null
        val session = sessions[repositoryId] ?: return null
        return buildSnapshot(repository, session.verified)
    }

    fun installed(): List<StoredProviderArtifact> =
        artifactStore.listInstalled()

    fun canRollback(providerId: String): Boolean {
        val current = artifactStore.current(providerId) ?: return false
        val previous = artifactStore.previous(providerId) ?: return false
        return !previous.revoked &&
            previous.repositoryId == current.repositoryId &&
            previous.versionCode < current.versionCode
    }

    fun previous(providerId: String): StoredProviderArtifact? =
        artifactStore.previous(providerId)

    suspend fun install(
        repositoryId: String,
        providerId: String,
    ): ParsedProviderPackage =
        repositoryLock(repositoryId).withLock {
            val repository = enrollmentStore.get(repositoryId)
                ?: throw ProviderSupplyChainException("Provider repository is not enrolled")
            val session = sessions[repositoryId] ?: run {
                refreshLocked(repositoryId)
                sessions.getValue(repositoryId)
            }
            val descriptor = session.verified.index.providers
                .singleOrNull { it.providerId == providerId }
                ?: throw ProviderSupplyChainException("Provider is not available from this repository")

            if (descriptor.minHostApi > hostApiVersion) {
                throw ProviderSupplyChainException("Provider requires a newer Host API")
            }
            if (
                session.verified.index.revokedArtifactSha256.any {
                    it.equals(descriptor.sha256, ignoreCase = true)
                }
            ) {
                throw ProviderSupplyChainException("Provider artifact has been revoked by the repository")
            }

            val installed = artifactStore.current(providerId)
            if (
                installed != null &&
                (
                    installed.repositoryId != repositoryId ||
                        installed.repositoryTrustAnchorSha256 != repository.keyFingerprintSha256
                    )
            ) {
                throw ProviderSupplyChainException(
                    "Installed Provider belongs to a different repository trust origin",
                )
            }

            val artifactBytes = transport.fetchArtifact(descriptor.artifactUrl)
            val verifiedArtifact = session.trust.verifyArtifact(
                repository = session.verified,
                providerId = providerId,
                artifactBytes = artifactBytes,
                installedVersionCode = installed?.versionCode,
            )
            packageActivator.activate(verifiedArtifact)
        }

    fun rollback(providerId: String) {
        artifactStore.rollback(providerId)
    }

    private fun buildSnapshot(
        repository: EnrolledProviderRepository,
        verified: VerifiedProviderRepository,
    ): ProviderRepositorySnapshot {
        val revoked = verified.index.revokedArtifactSha256
            .map(String::lowercase)
            .toSet()

        val entries = verified.index.providers
            .map { descriptor ->
                val installed = artifactStore.current(descriptor.providerId)
                ProviderRepositoryCatalogEntry(
                    repositoryId = repository.enrollment.repositoryId,
                    descriptor = descriptor,
                    installedVersionCode = installed?.versionCode,
                    status = entryStatus(
                        repositoryId = repository.enrollment.repositoryId,
                        repositoryTrustAnchorSha256 = repository.keyFingerprintSha256,
                        descriptor = descriptor,
                        installed = installed,
                        revokedArtifactSha256 = revoked,
                    ),
                )
            }
            .sortedBy { entry -> entry.descriptor.providerId }

        return ProviderRepositorySnapshot(
            repository = repository,
            sequence = verified.index.sequence,
            verifiedKeyId = verified.verifiedKeyId,
            entries = entries,
        )
    }

    private fun entryStatus(
        repositoryId: String,
        repositoryTrustAnchorSha256: String,
        descriptor: ProviderArtifactDescriptor,
        installed: StoredProviderArtifact?,
        revokedArtifactSha256: Set<String>,
    ): ProviderRepositoryEntryStatus {
        if (descriptor.sha256.lowercase() in revokedArtifactSha256) {
            return ProviderRepositoryEntryStatus.REVOKED
        }
        if (descriptor.minHostApi > hostApiVersion) {
            return ProviderRepositoryEntryStatus.INCOMPATIBLE
        }
        if (installed == null) {
            return ProviderRepositoryEntryStatus.AVAILABLE
        }
        if (
            installed.repositoryId != repositoryId ||
            installed.repositoryTrustAnchorSha256 != repositoryTrustAnchorSha256
        ) {
            return ProviderRepositoryEntryStatus.ORIGIN_CONFLICT
        }
        if (installed.revoked && descriptor.versionCode <= installed.versionCode) {
            return ProviderRepositoryEntryStatus.REVOKED
        }

        return when {
            descriptor.versionCode > installed.versionCode ->
                ProviderRepositoryEntryStatus.UPDATE_AVAILABLE
            descriptor.versionCode == installed.versionCode ->
                ProviderRepositoryEntryStatus.INSTALLED
            else ->
                ProviderRepositoryEntryStatus.INSTALLED_NEWER
        }
    }

    private fun repositoryLock(repositoryId: String): Mutex =
        repositoryLocks.computeIfAbsent(repositoryId) { Mutex() }

    private data class RepositorySession(
        val trust: ProviderRepositoryTrust,
        val verified: VerifiedProviderRepository,
    )
}
