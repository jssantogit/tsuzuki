package tachiyomi.core.provider.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetAddress

class ProviderNetworkPolicyTest {

    @Test
    fun `matches exact and wildcard origins without granting apex access`() {
        val policy = ProviderNetworkPolicy(
            allowedOrigins = setOf(
                "https://reader.example",
                "https://*.cdn.example",
            ),
            addressResolver = publicResolver(),
        )

        policy.validate("https://reader.example/title/1").host shouldBe "reader.example"
        policy.validate("https://img.cdn.example/page.jpg").host shouldBe "img.cdn.example"

        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("https://cdn.example/page.jpg")
        }
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("https://evil.example/page.jpg")
        }
    }

    @Test
    fun `origin rules are scheme and port specific`() {
        val policy = ProviderNetworkPolicy(
            allowedOrigins = setOf("http://reader.example:8080"),
            allowLocalNetwork = true,
            addressResolver = publicResolver(),
        )

        policy.validate("http://reader.example:8080/api").toString() shouldBe
            "http://reader.example:8080/api"

        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("https://reader.example:8080/api")
        }
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("http://reader.example/api")
        }
    }

    @Test
    fun `public providers fail closed for loopback and private resolved addresses`() {
        val loopbackPolicy = ProviderNetworkPolicy(
            allowedOrigins = setOf("http://localhost:8080"),
            addressResolver = ProviderAddressResolver { listOf(InetAddress.getByName("127.0.0.1")) },
        )
        shouldThrow<ProviderNetworkPolicyException> {
            loopbackPolicy.validate("http://localhost:8080/data")
        }

        val rebindingPolicy = ProviderNetworkPolicy(
            allowedOrigins = setOf("https://reader.example"),
            addressResolver = ProviderAddressResolver { listOf(InetAddress.getByName("192.168.1.10")) },
        )
        shouldThrow<ProviderNetworkPolicyException> {
            rebindingPolicy.validate("https://reader.example/data")
        }
    }

    @Test
    fun `explicit local network permission allows private endpoints but not undeclared origins`() {
        val policy = ProviderNetworkPolicy(
            allowedOrigins = setOf("http://192.168.1.20:5000"),
            allowLocalNetwork = true,
            addressResolver = ProviderAddressResolver { listOf(InetAddress.getByName("192.168.1.20")) },
        )

        policy.validate("http://192.168.1.20:5000/api").host shouldBe "192.168.1.20"
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("http://192.168.1.21:5000/api")
        }
    }

    @Test
    fun `allowed origin declarations reject credentials paths queries and invalid wildcard shapes`() {
        listOf(
            "https://user:pass@reader.example",
            "https://reader.example/path",
            "https://reader.example?x=1",
            "https://*reader.example",
            "ftp://reader.example",
        ).forEach { origin ->
            shouldThrow<IllegalArgumentException> {
                ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    addressResolver = publicResolver(),
                )
            }
        }
    }

    @Test
    fun `MangaDex forcePort443 image grants accept only reviewed HTTPS hosts`() {
        val policy = ProviderNetworkPolicy(
            allowedOrigins = setOf(
                "https://api.mangadex.org",
                "https://uploads.mangadex.org",
                "https://*.mangadex.network",
            ),
            addressResolver = publicResolver(),
        )
        policy.validate("https://uploads.mangadex.org/data/hash/001.png").host shouldBe
            "uploads.mangadex.org"
        policy.validate("https://node.mangadex.network/temporary/data/hash/001.png").host shouldBe
            "node.mangadex.network"
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("https://node.mangadex.network:12345/temporary/data/hash/001.png")
        }
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("https://mangadex.network/temporary/data/hash/001.png")
        }
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("https://node.mangadex.network.evil.example/data/hash/001.png")
        }
        shouldThrow<ProviderNetworkPolicyException> {
            policy.validate("http://node.mangadex.network/data/hash/001.png")
        }
    }

    private fun publicResolver() = ProviderAddressResolver {
        listOf(InetAddress.getByName("93.184.216.34"))
    }
}
