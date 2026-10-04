package tachiyomi.core.provider.supplychain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable
data class ProviderRepositorySigningKey(
    val keyId: String,
    val publicKeyBase64: String,
)

@Serializable
data class ProviderRepositoryEnrollment(
    val repositoryId: String,
    val indexUrl: String,
    val signingKey: ProviderRepositorySigningKey,
)

@Serializable
data class EnrolledProviderRepository(
    val displayName: String,
    val enrollment: ProviderRepositoryEnrollment,
) {
    val keyFingerprintSha256: String
        get() = providerRepositoryKeyFingerprint(enrollment.signingKey)
}

interface ProviderRepositoryEnrollmentStore {
    fun list(): List<EnrolledProviderRepository>

    fun get(repositoryId: String): EnrolledProviderRepository?

    fun save(repository: EnrolledProviderRepository)

    fun remove(repositoryId: String): Boolean
}

class FileProviderRepositoryEnrollmentStore(
    private val root: File,
) : ProviderRepositoryEnrollmentStore {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    init {
        ensureDirectory(root, "Provider repository enrollment store")
    }

    override fun list(): List<EnrolledProviderRepository> =
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.extension == "json" }
            .map { file -> readEnrollment(file) }
            .sortedBy { repository -> repository.enrollment.repositoryId }
            .toList()

    override fun get(repositoryId: String): EnrolledProviderRepository? {
        validateIdentifier(repositoryId, "Repository ID")
        val file = enrollmentFile(repositoryId)
        if (!file.exists()) return null
        return readEnrollment(file)
    }

    @Synchronized
    override fun save(repository: EnrolledProviderRepository) {
        validateEnrollment(repository)

        val repositoryId = repository.enrollment.repositoryId
        val existing = get(repositoryId)
        if (existing != null) {
            val existingKey = existing.enrollment.signingKey
            val incomingKey = repository.enrollment.signingKey
            if (
                existingKey.keyId != incomingKey.keyId ||
                existing.keyFingerprintSha256 != repository.keyFingerprintSha256
            ) {
                throw ProviderSupplyChainException(
                    "Repository signing key cannot be silently repinned",
                )
            }
        }

        atomicWrite(
            enrollmentFile(repositoryId),
            json.encodeToString(repository).encodeToByteArray(),
            "Provider repository enrollment",
        )
    }

    @Synchronized
    override fun remove(repositoryId: String): Boolean {
        validateIdentifier(repositoryId, "Repository ID")
        val file = enrollmentFile(repositoryId)
        if (!file.exists()) return false
        if (!file.delete()) {
            throw ProviderSupplyChainException("Provider repository enrollment could not be removed")
        }
        return true
    }

    private fun readEnrollment(file: File): EnrolledProviderRepository {
        val repository = try {
            json.decodeFromString<EnrolledProviderRepository>(file.readText())
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Repository enrollment state is malformed", error)
        }
        validateEnrollment(repository)
        if (file.nameWithoutExtension != repository.enrollment.repositoryId) {
            throw ProviderSupplyChainException("Repository enrollment state does not match its file identity")
        }
        return repository
    }

    private fun validateEnrollment(repository: EnrolledProviderRepository) {
        if (repository.displayName.isBlank()) {
            throw ProviderSupplyChainException("Repository display name must not be blank")
        }
        validateIdentifier(repository.enrollment.repositoryId, "Repository ID")
        validateHttpsUrl(repository.enrollment.indexUrl, "Repository index URL")

        val signingKey = repository.enrollment.signingKey
        if (signingKey.keyId.isBlank()) {
            throw ProviderSupplyChainException("Repository signing key ID must not be blank")
        }
        providerRepositoryKeyFingerprint(signingKey)
    }

    private fun enrollmentFile(repositoryId: String): File =
        File(root, "$repositoryId.json")
}

fun providerRepositoryKeyFingerprint(
    signingKey: ProviderRepositorySigningKey,
): String =
    sha256Hex(
        decodeBase64(
            signingKey.publicKeyBase64,
            "Repository signing key",
        ),
    )

@Serializable
data class ProviderArtifactDescriptor(
    val providerId: String,
    val versionName: String,
    val versionCode: Long,
    val artifactUrl: String,
    val sha256: String,
    val minHostApi: Int,
)

@Serializable
data class ProviderRepositoryIndex(
    val schemaVersion: Int,
    val repositoryId: String,
    val sequence: Long,
    val providers: List<ProviderArtifactDescriptor>,
    val revokedArtifactSha256: Set<String> = emptySet(),
    val nextSigningKey: ProviderRepositorySigningKey? = null,
)

data class SignedProviderRepositoryIndex(
    val keyId: String,
    val payload: ByteArray,
    val signature: ByteArray,
)

class ProviderSupplyChainException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class VerifiedProviderRepository internal constructor(
    val index: ProviderRepositoryIndex,
    val verifiedKeyId: String,
    internal val verificationToken: Any,
)

class VerifiedProviderArtifact internal constructor(
    val repositoryId: String,
    val descriptor: ProviderArtifactDescriptor,
    val bytes: ByteArray,
) {
    val providerId: String
        get() = descriptor.providerId

    val versionCode: Long
        get() = descriptor.versionCode
}

@Serializable
data class ProviderRepositoryTrustState(
    val repositoryId: String,
    val highestAcceptedSequence: Long,
    val trustedKeysBase64: Map<String, String>,
    val acceptedPayloadSha256: String? = null,
)

interface ProviderRepositoryTrustStore {
    fun load(repositoryId: String): ProviderRepositoryTrustState?

    fun save(state: ProviderRepositoryTrustState)
}

class FileProviderRepositoryTrustStore(
    private val root: File,
) : ProviderRepositoryTrustStore {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    init {
        ensureDirectory(root, "Provider repository trust store")
    }

    override fun load(repositoryId: String): ProviderRepositoryTrustState? {
        validateIdentifier(repositoryId, "Repository ID")
        val file = stateFile(repositoryId)
        if (!file.exists()) return null

        val state = try {
            json.decodeFromString<ProviderRepositoryTrustState>(file.readText())
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Repository trust state is malformed", error)
        }
        if (state.repositoryId != repositoryId || state.highestAcceptedSequence < 0L) {
            throw ProviderSupplyChainException("Repository trust state does not match enrollment")
        }
        state.acceptedPayloadSha256?.let(::normalizeSha256)
        return state
    }

    override fun save(state: ProviderRepositoryTrustState) {
        validateIdentifier(state.repositoryId, "Repository ID")
        if (state.highestAcceptedSequence < 0L) {
            throw ProviderSupplyChainException("Repository sequence state cannot be negative")
        }
        state.acceptedPayloadSha256?.let(::normalizeSha256)
        atomicWrite(
            stateFile(state.repositoryId),
            json.encodeToString(state).encodeToByteArray(),
            "Repository trust state",
        )
    }

    private fun stateFile(repositoryId: String): File = File(root, "$repositoryId.json")
}

class ProviderRepositoryTrust(
    private val repositoryId: String,
    private val hostApiVersion: Int,
    trustedKeys: Map<String, ByteArray>,
    private val stateStore: ProviderRepositoryTrustStore,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val verificationToken = Any()
    private val trustedKeyBytes = linkedMapOf<String, ByteArray>()
    private var highestAcceptedSequence = 0L
    private var acceptedPayloadSha256: String? = null

    init {
        validateIdentifier(repositoryId, "Repository ID")
        require(hostApiVersion > 0) { "Host API version must be positive" }
        require(trustedKeys.isNotEmpty()) { "At least one repository signing key is required" }

        val persisted = stateStore.load(repositoryId)
        persisted?.let { state ->
            highestAcceptedSequence = state.highestAcceptedSequence
            acceptedPayloadSha256 = state.acceptedPayloadSha256
            state.trustedKeysBase64.forEach { (keyId, encoded) ->
                val bytes = decodeBase64(encoded, "Persisted repository signing key")
                registerTrustedKey(keyId, bytes)
            }
        }
        trustedKeys.forEach { (keyId, bytes) ->
            registerTrustedKey(keyId, bytes)
        }
    }

    @Synchronized
    fun verifyAndAccept(signed: SignedProviderRepositoryIndex): VerifiedProviderRepository {
        val publicKeyBytes = trustedKeyBytes[signed.keyId]
            ?: fail("Repository index is signed by an untrusted key")

        verifySignature(
            publicKeyBytes = publicKeyBytes,
            payload = signed.payload,
            signatureBytes = signed.signature,
        )

        val index = try {
            json.decodeFromString<ProviderRepositoryIndex>(signed.payload.decodeToString())
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Repository payload is malformed", error)
        }

        validateIndex(index)
        if (index.sequence <= highestAcceptedSequence) {
            fail("Repository sequence is not newer than the last accepted index")
        }

        val payloadSha256 = sha256Hex(signed.payload)
        val nextKeys = trustedKeysWithRotation(index.nextSigningKey)
        persistState(
            sequence = index.sequence,
            keys = nextKeys,
            acceptedPayloadSha256 = payloadSha256,
        )
        highestAcceptedSequence = index.sequence
        acceptedPayloadSha256 = payloadSha256
        trustedKeyBytes.clear()
        trustedKeyBytes.putAll(nextKeys)

        return VerifiedProviderRepository(
            index = index,
            verifiedKeyId = signed.keyId,
            verificationToken = verificationToken,
        )
    }

    @Synchronized
    fun verifyCurrent(signed: SignedProviderRepositoryIndex): VerifiedProviderRepository {
        val publicKeyBytes = trustedKeyBytes[signed.keyId]
            ?: fail("Repository index is signed by an untrusted key")

        verifySignature(
            publicKeyBytes = publicKeyBytes,
            payload = signed.payload,
            signatureBytes = signed.signature,
        )

        val index = try {
            json.decodeFromString<ProviderRepositoryIndex>(signed.payload.decodeToString())
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Repository payload is malformed", error)
        }
        validateIndex(index)

        if (index.sequence != highestAcceptedSequence) {
            fail("Repository index is not the currently accepted sequence")
        }
        val expectedPayloadSha256 = acceptedPayloadSha256
            ?: fail("Repository current index fingerprint is unavailable")
        val actualPayloadSha256 = sha256Hex(signed.payload)
        if (!MessageDigest.isEqual(
                expectedPayloadSha256.encodeToByteArray(),
                actualPayloadSha256.encodeToByteArray(),
            )
        ) {
            fail("Repository current index payload does not match the accepted index")
        }

        return VerifiedProviderRepository(
            index = index,
            verifiedKeyId = signed.keyId,
            verificationToken = verificationToken,
        )
    }

    @Synchronized
    fun acceptSigningKeyRotation(repository: VerifiedProviderRepository) {
        requireVerifiedRepository(repository)
        if (repository.index.sequence != highestAcceptedSequence) {
            fail("Signing-key rotation must come from the latest accepted repository index")
        }
        val rotation = repository.index.nextSigningKey
            ?: fail("Verified repository index does not introduce a signing key")

        val nextKeys = trustedKeysWithRotation(rotation)
        persistState(
            sequence = highestAcceptedSequence,
            keys = nextKeys,
            acceptedPayloadSha256 = acceptedPayloadSha256,
        )
        trustedKeyBytes.clear()
        trustedKeyBytes.putAll(nextKeys)
    }

    fun verifyArtifact(
        repository: VerifiedProviderRepository,
        providerId: String,
        artifactBytes: ByteArray,
        installedVersionCode: Long?,
    ): VerifiedProviderArtifact {
        requireVerifiedRepository(repository)

        val descriptor = repository.index.providers
            .singleOrNull { it.providerId == providerId }
            ?: fail("Provider is missing or duplicated in verified repository index")

        if (descriptor.minHostApi > hostApiVersion) {
            fail("Provider requires a newer Host API")
        }
        if (installedVersionCode != null && descriptor.versionCode <= installedVersionCode) {
            fail("Provider update would not advance the installed version")
        }

        val expectedDigest = normalizeSha256(descriptor.sha256)
        if (repository.index.revokedArtifactSha256.any { normalizeSha256(it) == expectedDigest }) {
            fail("Provider artifact has been revoked by the repository")
        }

        val actualDigest = sha256Hex(artifactBytes)
        if (!MessageDigest.isEqual(expectedDigest.encodeToByteArray(), actualDigest.encodeToByteArray())) {
            fail("Provider artifact SHA-256 does not match the signed repository index")
        }

        return VerifiedProviderArtifact(
            repositoryId = repository.index.repositoryId,
            descriptor = descriptor,
            bytes = artifactBytes.copyOf(),
        )
    }

    @Synchronized
    fun applyRevocations(
        repository: VerifiedProviderRepository,
        artifactStore: ProviderArtifactStore,
    ): Set<String> {
        requireVerifiedRepository(repository)
        if (repository.index.sequence != highestAcceptedSequence) {
            fail("Artifact revocations must come from the latest accepted repository index")
        }
        return artifactStore.applyRevocations(
            repositoryId = repository.index.repositoryId,
            revokedArtifactSha256 = repository.index.revokedArtifactSha256,
        )
    }

    private fun validateIndex(index: ProviderRepositoryIndex) {
        if (index.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            fail("Unsupported repository schema version")
        }
        if (index.repositoryId != repositoryId) {
            fail("Repository identity does not match pinned trust")
        }
        if (index.sequence <= 0L) {
            fail("Repository sequence must be positive")
        }

        val providerIds = mutableSetOf<String>()
        index.providers.forEach { descriptor ->
            validateIdentifier(descriptor.providerId, "Provider ID")
            if (!providerIds.add(descriptor.providerId)) {
                fail("Provider ID is duplicated in repository index")
            }
            if (descriptor.versionName.isBlank()) {
                fail("Provider version name must not be blank")
            }
            if (descriptor.versionCode <= 0L) {
                fail("Provider version code must be positive")
            }
            validateHttpsUrl(descriptor.artifactUrl, "Provider artifact URL")
            normalizeSha256(descriptor.sha256)
            if (descriptor.minHostApi <= 0) {
                fail("Provider minimum Host API must be positive")
            }
        }
        index.revokedArtifactSha256.forEach(::normalizeSha256)
        index.nextSigningKey?.let { rotation ->
            if (rotation.keyId.isBlank() || rotation.publicKeyBase64.isBlank()) {
                fail("Repository signing-key rotation is malformed")
            }
            parseP256PublicKey(decodeBase64(rotation.publicKeyBase64, "Rotated signing key"))
        }
    }

    private fun trustedKeysWithRotation(
        rotation: ProviderRepositorySigningKey?,
    ): MutableMap<String, ByteArray> {
        val nextKeys = trustedKeyBytes
            .mapValues { (_, bytes) -> bytes.copyOf() }
            .toMutableMap()
        if (rotation == null) return nextKeys

        val encodedKey = decodeBase64(
            rotation.publicKeyBase64,
            "Rotated signing key",
        )
        parseP256PublicKey(encodedKey)

        val existing = nextKeys[rotation.keyId]
        if (existing != null && !MessageDigest.isEqual(existing, encodedKey)) {
            fail("Rotated signing key ID already belongs to different key material")
        }
        nextKeys[rotation.keyId] = encodedKey.copyOf()
        return nextKeys
    }

    private fun registerTrustedKey(
        keyId: String,
        encodedKey: ByteArray,
    ) {
        if (keyId.isBlank()) {
            fail("Repository signing key ID must not be blank")
        }
        parseP256PublicKey(encodedKey)
        val existing = trustedKeyBytes[keyId]
        if (existing != null && !MessageDigest.isEqual(existing, encodedKey)) {
            fail("Repository signing key ID belongs to conflicting key material")
        }
        trustedKeyBytes[keyId] = encodedKey.copyOf()
    }

    private fun persistState(
        sequence: Long,
        keys: Map<String, ByteArray>,
        acceptedPayloadSha256: String?,
    ) {
        stateStore.save(
            ProviderRepositoryTrustState(
                repositoryId = repositoryId,
                highestAcceptedSequence = sequence,
                trustedKeysBase64 = keys.mapValues { (_, bytes) ->
                    Base64.getEncoder().encodeToString(bytes)
                },
                acceptedPayloadSha256 = acceptedPayloadSha256,
            ),
        )
    }

    private fun requireVerifiedRepository(repository: VerifiedProviderRepository) {
        if (repository.verificationToken !== verificationToken) {
            fail("Verified repository belongs to a different trust context")
        }
        if (repository.index.repositoryId != repositoryId) {
            fail("Verified repository identity does not match trust context")
        }
    }

    private fun verifySignature(
        publicKeyBytes: ByteArray,
        payload: ByteArray,
        signatureBytes: ByteArray,
    ) {
        val publicKey = parseP256PublicKey(publicKeyBytes)
        val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(payload)
        if (!verifier.verify(signatureBytes)) {
            fail("Repository index signature is invalid")
        }
    }

    private fun parseP256PublicKey(encodedKey: ByteArray): ECPublicKey {
        val key = try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(encodedKey)) as? ECPublicKey
                ?: fail("Repository signing key is not an EC public key")
        } catch (error: ProviderSupplyChainException) {
            throw error
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Repository signing key cannot be decoded", error)
        }

        if (!sameCurve(key.params, p256Parameters)) {
            fail("Repository signing key must use secp256r1 (P-256)")
        }
        return key
    }

    private fun sameCurve(
        actual: ECParameterSpec,
        expected: ECParameterSpec,
    ): Boolean =
        actual.curve == expected.curve &&
            actual.generator == expected.generator &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor

    private fun fail(message: String): Nothing =
        throw ProviderSupplyChainException(message)

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        private val p256Parameters: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
        }

        fun fromEnrollment(
            enrollment: ProviderRepositoryEnrollment,
            hostApiVersion: Int,
            stateStore: ProviderRepositoryTrustStore,
        ): ProviderRepositoryTrust {
            validateIdentifier(enrollment.repositoryId, "Repository ID")
            validateHttpsUrl(enrollment.indexUrl, "Repository index URL")
            val key = decodeBase64(enrollment.signingKey.publicKeyBase64, "Repository signing key")
            return ProviderRepositoryTrust(
                repositoryId = enrollment.repositoryId,
                hostApiVersion = hostApiVersion,
                trustedKeys = mapOf(enrollment.signingKey.keyId to key),
                stateStore = stateStore,
            )
        }
    }
}

data class StoredProviderArtifact(
    val repositoryId: String,
    val providerId: String,
    val versionCode: Long,
    val sha256: String,
    val revoked: Boolean,
)

class ProviderArtifactStore(
    private val root: File,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    init {
        ensureDirectory(root, "Provider artifact store")
    }

    fun activate(artifact: VerifiedProviderArtifact) {
        val providerId = artifact.providerId
        validateIdentifier(providerId, "Provider ID")
        validateIdentifier(artifact.repositoryId, "Repository ID")

        val expectedDigest = normalizeSha256(artifact.descriptor.sha256)
        val actualDigest = sha256Hex(artifact.bytes)
        if (!MessageDigest.isEqual(expectedDigest.encodeToByteArray(), actualDigest.encodeToByteArray())) {
            throw ProviderSupplyChainException("Verified Provider artifact bytes no longer match their descriptor")
        }

        val versions = versionsDirectory(providerId)
        ensureDirectory(versions, "Provider version directory")

        val target = artifactFile(providerId, artifact.versionCode)
        if (target.exists()) {
            validateArtifactFile(target, expectedDigest)
        } else {
            atomicWrite(target, artifact.bytes, "Provider artifact")
        }

        val previousState = readState(providerId)
        if (
            previousState != null &&
            previousState.current.repositoryId != artifact.repositoryId
        ) {
            throw ProviderSupplyChainException(
                "Installed Provider repository origin cannot change implicitly",
            )
        }
        if (previousState?.current?.versionCode == artifact.versionCode) {
            if (
                previousState.current.repositoryId != artifact.repositoryId ||
                previousState.current.sha256 != expectedDigest
            ) {
                throw ProviderSupplyChainException("Active Provider version has conflicting immutable identity")
            }
            return
        }

        val nextState = ArtifactStoreState(
            current = StoredArtifactState(
                repositoryId = artifact.repositoryId,
                versionCode = artifact.versionCode,
                sha256 = expectedDigest,
                revoked = false,
            ),
            previous = previousState?.current,
        )
        atomicWrite(
            stateFile(providerId),
            json.encodeToString(nextState).encodeToByteArray(),
            "Provider activation state",
        )
    }

    fun listInstalled(): List<StoredProviderArtifact> =
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { directory -> directory.isDirectory && PROVIDER_ID.matches(directory.name) }
            .mapNotNull { directory ->
                val providerId = directory.name
                val state = readState(providerId) ?: return@mapNotNull null
                validateStoredArtifact(providerId, state.current)
                state.current.toPublic(providerId)
            }
            .sortedBy { artifact -> artifact.providerId }
            .toList()

    fun current(providerId: String): StoredProviderArtifact? {
        validateIdentifier(providerId, "Provider ID")
        val state = readState(providerId) ?: return null
        validateStoredArtifact(providerId, state.current)
        return state.current.toPublic(providerId)
    }

    fun previous(providerId: String): StoredProviderArtifact? {
        validateIdentifier(providerId, "Provider ID")
        val artifact = readState(providerId)?.previous ?: return null
        validateStoredArtifact(providerId, artifact)
        return artifact.toPublic(providerId)
    }

    fun rollback(providerId: String) {
        validateIdentifier(providerId, "Provider ID")
        val state = readState(providerId)
            ?: throw ProviderSupplyChainException("Provider has no active version to roll back")
        val rollback = state.previous
            ?: throw ProviderSupplyChainException("Provider has no previous version to roll back to")

        validateStoredArtifact(providerId, state.current)
        validateStoredArtifact(providerId, rollback)
        if (rollback.revoked) {
            throw ProviderSupplyChainException("Provider previous version has been revoked")
        }

        atomicWrite(
            stateFile(providerId),
            json.encodeToString(
                ArtifactStoreState(
                    current = rollback,
                    previous = state.current,
                ),
            ).encodeToByteArray(),
            "Provider activation state",
        )
    }

    fun readCurrentArtifact(providerId: String): ByteArray {
        val active = current(providerId)
            ?: throw ProviderSupplyChainException("Provider has no active artifact")
        if (active.revoked) {
            throw ProviderSupplyChainException("Provider active artifact has been revoked")
        }
        return artifactFile(providerId, active.versionCode).readBytes()
    }

    fun readCurrentArtifactForInspection(providerId: String): ByteArray {
        val active = current(providerId)
            ?: throw ProviderSupplyChainException("Provider has no active artifact")
        return artifactFile(providerId, active.versionCode).readBytes()
    }

    internal fun applyRevocations(
        repositoryId: String,
        revokedArtifactSha256: Set<String>,
    ): Set<String> {
        validateIdentifier(repositoryId, "Repository ID")
        val revokedHashes = revokedArtifactSha256.map(::normalizeSha256).toSet()
        if (revokedHashes.isEmpty()) return emptySet()

        val affectedProviders = linkedSetOf<String>()
        root.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && PROVIDER_ID.matches(it.name) }
            .forEach { providerDirectory ->
                val providerId = providerDirectory.name
                val state = readState(providerId) ?: return@forEach

                fun revokeIfMatched(artifact: StoredArtifactState): StoredArtifactState {
                    if (
                        artifact.repositoryId == repositoryId &&
                        artifact.sha256 in revokedHashes &&
                        !artifact.revoked
                    ) {
                        affectedProviders += providerId
                        return artifact.copy(revoked = true)
                    }
                    return artifact
                }

                val nextState = ArtifactStoreState(
                    current = revokeIfMatched(state.current),
                    previous = state.previous?.let(::revokeIfMatched),
                )
                if (nextState != state) {
                    atomicWrite(
                        stateFile(providerId),
                        json.encodeToString(nextState).encodeToByteArray(),
                        "Provider revocation state",
                    )
                }
            }

        return affectedProviders
    }

    private fun readState(providerId: String): ArtifactStoreState? {
        val file = stateFile(providerId)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<ArtifactStoreState>(file.readText())
        } catch (error: Exception) {
            throw ProviderSupplyChainException("Provider activation state is malformed", error)
        }
    }

    private fun validateStoredArtifact(
        providerId: String,
        artifact: StoredArtifactState,
    ) {
        validateIdentifier(artifact.repositoryId, "Stored repository ID")
        if (artifact.versionCode <= 0L) {
            throw ProviderSupplyChainException("Stored Provider version code must be positive")
        }
        normalizeSha256(artifact.sha256)

        val file = artifactFile(providerId, artifact.versionCode)
        if (!file.isFile) {
            throw ProviderSupplyChainException("Provider activation state points to a missing artifact")
        }
        validateArtifactFile(file, artifact.sha256)
    }

    private fun validateArtifactFile(
        file: File,
        expectedDigest: String,
    ) {
        val actualDigest = sha256Hex(file.readBytes())
        if (!MessageDigest.isEqual(expectedDigest.encodeToByteArray(), actualDigest.encodeToByteArray())) {
            throw ProviderSupplyChainException("Stored Provider artifact is corrupt")
        }
    }

    private fun providerDirectory(providerId: String): File =
        File(root, providerId)

    private fun versionsDirectory(providerId: String): File =
        File(providerDirectory(providerId), "versions")

    private fun artifactFile(
        providerId: String,
        versionCode: Long,
    ): File = File(versionsDirectory(providerId), "$versionCode.tsz")

    private fun stateFile(providerId: String): File =
        File(providerDirectory(providerId), "active.json")

    @Serializable
    private data class ArtifactStoreState(
        val current: StoredArtifactState,
        val previous: StoredArtifactState? = null,
    )

    @Serializable
    private data class StoredArtifactState(
        val repositoryId: String,
        val versionCode: Long,
        val sha256: String,
        val revoked: Boolean = false,
    ) {
        fun toPublic(providerId: String) = StoredProviderArtifact(
            repositoryId = repositoryId,
            providerId = providerId,
            versionCode = versionCode,
            sha256 = sha256,
            revoked = revoked,
        )
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }

private fun normalizeSha256(value: String): String {
    val normalized = value.lowercase()
    if (!SHA256_HEX.matches(normalized)) {
        throw ProviderSupplyChainException("SHA-256 value must contain exactly 64 hexadecimal characters")
    }
    return normalized
}

internal fun validateIdentifier(
    value: String,
    label: String,
) {
    if (!PROVIDER_ID.matches(value)) {
        throw ProviderSupplyChainException("$label is invalid")
    }
}

private fun validateHttpsUrl(
    value: String,
    label: String,
) {
    val uri = try {
        URI(value)
    } catch (error: Exception) {
        throw ProviderSupplyChainException("$label is malformed", error)
    }
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
        throw ProviderSupplyChainException("$label must be an HTTPS URL without user info")
    }
}

private fun decodeBase64(
    value: String,
    label: String,
): ByteArray =
    try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw ProviderSupplyChainException("$label is not valid Base64", error)
    }

internal fun ensureDirectory(
    directory: File,
    label: String,
) {
    if (!directory.exists() && !directory.mkdirs()) {
        throw ProviderSupplyChainException("$label could not be created")
    }
    if (!directory.isDirectory) {
        throw ProviderSupplyChainException("$label root is not a directory")
    }
}

internal fun atomicWrite(
    target: File,
    bytes: ByteArray,
    label: String,
) {
    val parent = target.parentFile
        ?: throw ProviderSupplyChainException("$label target has no parent directory")
    ensureDirectory(parent, "$label parent directory")

    val temporary = File.createTempFile(".provider-", ".tmp", parent)
    try {
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw ProviderSupplyChainException("$label requires atomic same-filesystem moves", error)
        }
    } catch (error: ProviderSupplyChainException) {
        throw error
    } catch (error: IOException) {
        throw ProviderSupplyChainException("$label write failed", error)
    } finally {
        if (temporary.exists()) {
            temporary.delete()
        }
    }
}

private val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
private val SHA256_HEX = Regex("[0-9a-f]{64}")
