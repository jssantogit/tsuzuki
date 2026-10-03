package tachiyomi.core.provider.supplychain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

class ProviderRepositoryTrust(
    private val repositoryId: String,
    private val hostApiVersion: Int,
    trustedKeys: Map<String, ByteArray>,
) {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val verificationToken = Any()
    private val trustedKeyBytes = trustedKeys
        .mapValues { (_, value) -> value.copyOf() }
        .toMutableMap()
    private var highestAcceptedSequence = 0L

    init {
        require(repositoryId.isNotBlank()) { "Repository ID must not be blank" }
        require(hostApiVersion > 0) { "Host API version must be positive" }
        require(trustedKeyBytes.isNotEmpty()) { "At least one repository signing key is required" }
        trustedKeyBytes.forEach { (keyId, encodedKey) ->
            require(keyId.isNotBlank()) { "Repository signing key ID must not be blank" }
            parseP256PublicKey(encodedKey)
        }
    }

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

        highestAcceptedSequence = index.sequence
        return VerifiedProviderRepository(
            index = index,
            verifiedKeyId = signed.keyId,
            verificationToken = verificationToken,
        )
    }

    fun acceptSigningKeyRotation(repository: VerifiedProviderRepository) {
        requireVerifiedRepository(repository)
        val rotation = repository.index.nextSigningKey
            ?: fail("Verified repository index does not introduce a signing key")
        if (rotation.keyId.isBlank()) {
            fail("Rotated signing key ID must not be blank")
        }

        val encodedKey = try {
            Base64.getDecoder().decode(rotation.publicKeyBase64)
        } catch (error: IllegalArgumentException) {
            throw ProviderSupplyChainException("Rotated signing key is not valid Base64", error)
        }
        parseP256PublicKey(encodedKey)

        val existing = trustedKeyBytes[rotation.keyId]
        if (existing != null && !MessageDigest.isEqual(existing, encodedKey)) {
            fail("Rotated signing key ID already belongs to different key material")
        }
        trustedKeyBytes[rotation.keyId] = encodedKey.copyOf()
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
            if (!PROVIDER_ID.matches(descriptor.providerId)) {
                fail("Provider ID is invalid")
            }
            if (!providerIds.add(descriptor.providerId)) {
                fail("Provider ID is duplicated in repository index")
            }
            if (descriptor.versionName.isBlank()) {
                fail("Provider version name must not be blank")
            }
            if (descriptor.versionCode <= 0L) {
                fail("Provider version code must be positive")
            }
            if (descriptor.artifactUrl.isBlank()) {
                fail("Provider artifact URL must not be blank")
            }
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
        }
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

    private fun normalizeSha256(value: String): String {
        val normalized = value.lowercase()
        if (!SHA256_HEX.matches(normalized)) {
            fail("SHA-256 value must contain exactly 64 hexadecimal characters")
        }
        return normalized
    }

    private fun fail(message: String): Nothing =
        throw ProviderSupplyChainException(message)

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        val p256Parameters: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec("secp256r1"))
                getParameterSpec(ECParameterSpec::class.java)
            }
        }
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

data class StoredProviderArtifact(
    val providerId: String,
    val versionCode: Long,
)

class ProviderArtifactStore(
    private val root: File,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    init {
        if (!root.exists() && !root.mkdirs()) {
            throw ProviderSupplyChainException("Provider artifact store could not be created")
        }
        if (!root.isDirectory) {
            throw ProviderSupplyChainException("Provider artifact store root is not a directory")
        }
    }

    fun activate(artifact: VerifiedProviderArtifact) {
        val providerId = artifact.providerId
        validateProviderId(providerId)
        val versions = versionsDirectory(providerId)
        if (!versions.exists() && !versions.mkdirs()) {
            throw ProviderSupplyChainException("Provider version directory could not be created")
        }

        val target = artifactFile(providerId, artifact.versionCode)
        if (target.exists()) {
            if (!MessageDigest.isEqual(target.readBytes(), artifact.bytes)) {
                throw ProviderSupplyChainException("Immutable Provider version already contains different bytes")
            }
        } else {
            atomicWrite(target, artifact.bytes)
        }

        val previousState = readState(providerId)
        val nextState = ArtifactStoreState(
            currentVersionCode = artifact.versionCode,
            previousVersionCode = previousState
                ?.currentVersionCode
                ?.takeIf { it != artifact.versionCode },
        )
        atomicWrite(
            stateFile(providerId),
            json.encodeToString(nextState).encodeToByteArray(),
        )
    }

    fun current(providerId: String): StoredProviderArtifact? {
        validateProviderId(providerId)
        val state = readState(providerId) ?: return null
        requireArtifactExists(providerId, state.currentVersionCode)
        return StoredProviderArtifact(providerId, state.currentVersionCode)
    }

    fun previous(providerId: String): StoredProviderArtifact? {
        validateProviderId(providerId)
        val versionCode = readState(providerId)?.previousVersionCode ?: return null
        requireArtifactExists(providerId, versionCode)
        return StoredProviderArtifact(providerId, versionCode)
    }

    fun rollback(providerId: String) {
        validateProviderId(providerId)
        val state = readState(providerId)
            ?: throw ProviderSupplyChainException("Provider has no active version to roll back")
        val rollbackVersion = state.previousVersionCode
            ?: throw ProviderSupplyChainException("Provider has no previous version to roll back to")

        requireArtifactExists(providerId, rollbackVersion)
        requireArtifactExists(providerId, state.currentVersionCode)

        atomicWrite(
            stateFile(providerId),
            json.encodeToString(
                ArtifactStoreState(
                    currentVersionCode = rollbackVersion,
                    previousVersionCode = state.currentVersionCode,
                ),
            ).encodeToByteArray(),
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

    private fun atomicWrite(
        target: File,
        bytes: ByteArray,
    ) {
        val parent = target.parentFile
            ?: throw ProviderSupplyChainException("Provider artifact target has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw ProviderSupplyChainException("Provider artifact parent directory could not be created")
        }

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
                throw ProviderSupplyChainException(
                    "Provider artifact store requires atomic same-filesystem moves",
                    error,
                )
            }
        } catch (error: ProviderSupplyChainException) {
            throw error
        } catch (error: IOException) {
            throw ProviderSupplyChainException("Provider artifact write failed", error)
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun requireArtifactExists(
        providerId: String,
        versionCode: Long,
    ) {
        if (!artifactFile(providerId, versionCode).isFile) {
            throw ProviderSupplyChainException("Provider activation state points to a missing artifact")
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

    private fun validateProviderId(providerId: String) {
        if (!PROVIDER_ID.matches(providerId)) {
            throw ProviderSupplyChainException("Provider ID is invalid for artifact storage")
        }
    }

    @Serializable
    private data class ArtifactStoreState(
        val currentVersionCode: Long,
        val previousVersionCode: Long? = null,
    )

    private companion object {
        val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
