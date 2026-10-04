package eu.kanade.tachiyomi.provider.repository

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.supplychain.ProviderSignedIndexEnvelope
import tachiyomi.core.provider.supplychain.ProviderSupplyChainException
import java.util.Base64

class AndroidProviderRepositoryTransportTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `fetches signed index envelope and bounded artifact bytes`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val payload = """{"repositoryId":"repo.example","sequence":1}""".encodeToByteArray()
            val signature = byteArrayOf(1, 2, 3)
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        ProviderSignedIndexEnvelope(
                            keyId = "root-1",
                            payloadBase64 = Base64.getEncoder().encodeToString(payload),
                            signatureBase64 = Base64.getEncoder().encodeToString(signature),
                        ),
                    ),
                ),
            )
            server.enqueue(MockResponse().setBody("tsz-bytes"))

            val transport = AndroidProviderRepositoryTransport(
                client = okhttp3.OkHttpClient(),
                allowInsecureHttp = true,
            )

            val signed = transport.fetchIndex(server.url("/index.json").toString())
            signed.keyId shouldBe "root-1"
            signed.payload shouldBe payload
            signed.signature shouldBe signature
            transport.fetchArtifact(server.url("/reader.tsz").toString()) shouldBe
                "tsz-bytes".encodeToByteArray()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `production transport rejects insecure repository urls`() = runBlocking {
        val transport = AndroidProviderRepositoryTransport(
            client = okhttp3.OkHttpClient(),
        )

        shouldThrow<ProviderSupplyChainException> {
            transport.fetchIndex("http://repo.example/index.json")
        }
        shouldThrow<ProviderSupplyChainException> {
            transport.fetchArtifact("http://repo.example/reader.tsz")
        }
    }

    @Test
    fun `repository transport strips inherited application authority`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val payload = """{"repositoryId":"repo.example","sequence":1}""".encodeToByteArray()
            server.enqueue(
                MockResponse().setBody(
                    json.encodeToString(
                        ProviderSignedIndexEnvelope(
                            keyId = "root-1",
                            payloadBase64 = Base64.getEncoder().encodeToString(payload),
                            signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
                        ),
                    ),
                ),
            )
            val inherited = okhttp3.OkHttpClient.Builder()
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request()
                            .newBuilder()
                            .header("Authorization", "ambient-secret")
                            .build(),
                    )
                }
                .build()
            val transport = AndroidProviderRepositoryTransport(
                client = inherited,
                allowInsecureHttp = true,
            )

            transport.fetchIndex(server.url("/index.json").toString())

            server.takeRequest().headers["Authorization"] shouldBe null
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `artifact transport fails closed on body size limit`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("12345"))
            val transport = AndroidProviderRepositoryTransport(
                client = okhttp3.OkHttpClient(),
                allowInsecureHttp = true,
                maxArtifactBytes = 4,
            )

            shouldThrow<ProviderSupplyChainException> {
                transport.fetchArtifact(server.url("/large.tsz").toString())
            }
        } finally {
            server.shutdown()
        }
    }
}
