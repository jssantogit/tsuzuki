package tachiyomi.core.provider.supplychain

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
data class ProviderSignedIndexEnvelope(
    val keyId: String,
    val payloadBase64: String,
    val signatureBase64: String,
)

private val providerRepositoryWireJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

fun decodeProviderSignedIndexEnvelope(value: String): SignedProviderRepositoryIndex {
    val envelope = try {
        providerRepositoryWireJson.decodeFromString<ProviderSignedIndexEnvelope>(value)
    } catch (error: Exception) {
        throw ProviderSupplyChainException("Repository signed-index envelope is malformed", error)
    }

    if (envelope.keyId.isBlank()) {
        throw ProviderSupplyChainException("Repository signed-index key ID must not be blank")
    }

    val payload = try {
        Base64.getDecoder().decode(envelope.payloadBase64)
    } catch (error: IllegalArgumentException) {
        throw ProviderSupplyChainException("Repository signed-index payload is not valid Base64", error)
    }
    val signature = try {
        Base64.getDecoder().decode(envelope.signatureBase64)
    } catch (error: IllegalArgumentException) {
        throw ProviderSupplyChainException("Repository signed-index signature is not valid Base64", error)
    }

    if (payload.isEmpty()) {
        throw ProviderSupplyChainException("Repository signed-index payload must not be empty")
    }
    if (signature.isEmpty()) {
        throw ProviderSupplyChainException("Repository signed-index signature must not be empty")
    }

    return SignedProviderRepositoryIndex(
        keyId = envelope.keyId,
        payload = payload,
        signature = signature,
    )
}
