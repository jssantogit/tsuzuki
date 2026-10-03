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
        return state
    }

    override fun save(state: ProviderRepositoryTrustState) {
        validateIdentifier(state.repositoryId, "Repository ID")
        if (state.highestAcceptedSequence < 0L) {
            throw ProviderSupplyChainException("Repository sequence state cannot be negative")
        }
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

    init {
        validateIdentifier(repositoryId, "Repository ID")
        require(hostApiVersion > 0) { "Host API version must be positive" }
        require(trustedKeys.isNotEmpty()) { "At least one repository signing key is required" }

        val persisted = stateStore.load(repositoryId)
        persisted?.let { state ->
            highestAcceptedSequence = state.highestAcceptedSequence
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

        val nextKeys = trustedKeysWithRotation(index.nextSigningKey)
        persistState(index.sequence, nextKeys)
        highestAcceptedSequence = index.sequence
        trustedKeyBytes.clear()
        trustedKeyBytes.putAll(nextKeys)

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
        persistState(highestAcceptedSequence, nextKeys)
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
            descriptor = descriptor,
            bytes = artifactBytes.copyOf(),
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
    ) {
        stateStore.save(
            ProviderRepositoryTrustState(
                repositoryId = repositoryId,
                highestAcceptedSequence = sequence,
                trustedKeysBase64 = keys.mapValues { (_, bytes) ->
                    Base64.getEncoder().encodeToString(bytes)
                },
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
    val providerId: String,
    val versionCode: Long,
    val sha256: String,
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
        if (previousState?.current?.versionCode == artifact.versionCode) {
            if (previousState.current.sha256 != expectedDigest) {
                throw ProviderSupplyChainException("Active Provider version has conflicting immutable identity")
            }
            return
        }

        val nextState = ArtifactStoreState(
            current = StoredArtifactState(
                versionCode = artifact.versionCode,
                sha256 = expectedDigest,
            ),
            previous = previousState?.current,
        )
        atomicWrite(
            stateFile(providerId),
            json.encodeToString(nextState).encodeToByteArray(),
            "Provider activation state",
        )
    }

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
        return artifactFile(providerId, active.versionCode).readBytes()
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
        val versionCode: Long,
        val sha256: String,
    ) {
        fun toPublic(providerId: String) = StoredProviderArtifact(
            providerId = providerId,
            versionCode = versionCode,
            sha256 = sha256,
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

private fun validateIdentifier(
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

private fun ensureDirectory(
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

private fun atomicWrite(
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
