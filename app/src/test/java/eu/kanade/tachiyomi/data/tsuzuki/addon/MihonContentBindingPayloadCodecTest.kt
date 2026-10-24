package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MihonContentBindingPayloadCodecTest {

    @Test
    fun `dormant restored binding decodes for metadata but cannot be used operationally`() {
        val dormant = MihonContentBindingPayloadCodec.encode(
            MihonContentBindingPayload(42L, -1L, "/title/blue-lock", "pt-BR"),
        )

        MihonContentBindingPayloadCodec.decode(dormant).mihonMangaId shouldBe -1L
        shouldThrow<IllegalArgumentException> {
            MihonContentBindingPayloadCodec.decodeOperational(dormant)
        }
    }

    @Test
    fun `zero manga ID cannot become an operational binding`() {
        val unmaterialized = MihonContentBindingPayloadCodec.encode(
            MihonContentBindingPayload(42L, 0L, "/title/blue-lock", "pt-BR"),
        )
        shouldThrow<IllegalArgumentException> {
            MihonContentBindingPayloadCodec.decodeOperational(unmaterialized)
        }
    }

    @Test
    fun `materialized local binding remains eligible for chapter access`() {
        val live = MihonContentBindingPayloadCodec.encode(
            MihonContentBindingPayload(42L, 901L, "/title/blue-lock", "pt-BR"),
        )

        MihonContentBindingPayloadCodec.decodeOperational(live).mihonMangaId shouldBe 901L
    }
}
