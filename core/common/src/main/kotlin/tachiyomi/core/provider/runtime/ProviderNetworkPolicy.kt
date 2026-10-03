package tachiyomi.core.provider.runtime

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

fun interface ProviderAddressResolver {
    fun lookup(host: String): List<InetAddress>
}

class ProviderNetworkPolicyException(message: String, cause: Throwable? = null) :
    SecurityException(message, cause)

class ProviderNetworkPolicy(
    allowedOrigins: Set<String>,
    val allowLocalNetwork: Boolean = false,
    private val addressResolver: ProviderAddressResolver = ProviderAddressResolver { host ->
        InetAddress.getAllByName(host).toList()
    },
) {

    private val originRules = allowedOrigins.map(ProviderOriginRule::parse)

    init {
        require(originRules.isNotEmpty()) { "At least one Provider network origin is required" }
    }

    fun validate(
        rawUrl: String,
        resolveAddress: Boolean = true,
    ): HttpUrl {
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw ProviderNetworkPolicyException("Provider URL must be an absolute HTTP(S) URL")
        if (url.scheme != "http" && url.scheme != "https") {
            throw ProviderNetworkPolicyException("Provider URL scheme is not allowed")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw ProviderNetworkPolicyException("Provider URL credentials are not allowed")
        }
        if (originRules.none { it.matches(url) }) {
            throw ProviderNetworkPolicyException("Provider URL origin is not allowed")
        }

        if (!allowLocalNetwork) {
            if (isObviouslyLocalHost(url.host)) {
                throw ProviderNetworkPolicyException("Provider local-network access is not allowed")
            }
            if (resolveAddress) {
                resolvePublicAddresses(url.host)
            }
        }

        return url
    }

    fun resolvePublicAddresses(host: String): List<InetAddress> {
        val addresses = try {
            addressResolver.lookup(host)
        } catch (error: Exception) {
            throw ProviderNetworkPolicyException("Provider host address resolution failed", error)
        }
        if (addresses.isEmpty()) {
            throw ProviderNetworkPolicyException("Provider host did not resolve to an address")
        }
        if (!allowLocalNetwork && addresses.any(InetAddress::isProviderLocalAddress)) {
            throw ProviderNetworkPolicyException("Provider local-network access is not allowed")
        }
        return addresses
    }

    private fun isObviouslyLocalHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

        val literal = when {
            ':' in host -> runCatching { InetAddress.getByName(host) }.getOrNull()
            host.all { it.isDigit() || it == '.' } -> runCatching { InetAddress.getByName(host) }.getOrNull()
            else -> null
        }
        return literal?.isProviderLocalAddress() == true
    }
}

private data class ProviderOriginRule(
    val scheme: String,
    val host: String,
    val wildcardSubdomains: Boolean,
    val port: Int,
) {
    fun matches(url: HttpUrl): Boolean {
        if (url.scheme != scheme || url.port != port) return false

        val candidate = url.host.lowercase()
        return if (wildcardSubdomains) {
            candidate != host && candidate.endsWith(".$host")
        } else {
            candidate == host
        }
    }

    companion object {
        private val ORIGIN = Regex(
            "^(https?)://(\\*\\.)?(\\[[0-9A-Fa-f:]+]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$",
        )

        fun parse(raw: String): ProviderOriginRule {
            val match = ORIGIN.matchEntire(raw)
                ?: throw IllegalArgumentException("Provider allowed origin is invalid")

            val scheme = match.groupValues[1].lowercase()
            val wildcard = match.groupValues[2].isNotEmpty()
            val rawHost = match.groupValues[3]
            val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()
            require(host.isNotBlank() && !host.contains("..") && !host.startsWith(".") && !host.endsWith(".")) {
                "Provider allowed origin host is invalid"
            }
            require(!wildcard || ':' !in host) {
                "Provider wildcard origin cannot target an IP literal"
            }

            val port = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.toIntOrNull()
                ?: defaultPort(scheme)
            require(port in 1..65535) { "Provider allowed origin port is invalid" }

            return ProviderOriginRule(
                scheme = scheme,
                host = host,
                wildcardSubdomains = wildcard,
                port = port,
            )
        }

        private fun defaultPort(scheme: String): Int = when (scheme) {
            "http" -> 80
            "https" -> 443
            else -> error("Unsupported Provider origin scheme")
        }
    }
}

private fun InetAddress.isProviderLocalAddress(): Boolean {
    if (
        isAnyLocalAddress ||
        isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress ||
        isMulticastAddress
    ) {
        return true
    }

    return when (this) {
        is Inet4Address -> {
            val octets = address.map { it.toInt() and 0xFF }
            when {
                octets[0] == 0 -> true
                octets[0] == 100 && octets[1] in 64..127 -> true
                octets[0] == 192 && octets[1] == 0 && octets[2] == 0 -> true
                octets[0] == 198 && octets[1] in 18..19 -> true
                octets[0] >= 240 -> true
                else -> false
            }
        }
        is Inet6Address -> {
            val first = address.firstOrNull()?.toInt()?.and(0xFF) ?: return true
            first and 0xFE == 0xFC
        }
        else -> true
    }
}
