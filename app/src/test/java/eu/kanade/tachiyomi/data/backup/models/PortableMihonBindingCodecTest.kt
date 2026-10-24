package eu.kanade.tachiyomi.data.backup.models

import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayload
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PortableMihonBindingCodecTest {

    private fun sourcePayload(url: String = "/title/blue-lock", mangaId: Long = 901L) =
        MihonContentBindingPayloadCodec.encode(
            MihonContentBindingPayload(
                sourceId = 42L,
                mihonMangaId = mangaId,
                sourceUrl = url,
                language = "pt-BR",
            ),
        )

    @Test
    fun `portable binding strips device local Mihon row ID and restores as unavailable`() {
        val backup = PortableMihonBindingCodec.export(
            id = "binding-1",
            canonicalTitleId = "title-1",
            addonId = "eu.kanade.tachiyomi.extension.pt.test",
            providerTitleKey = "42:/title/blue-lock",
            matchConfidence = 0.9,
            verifiedByUser = true,
            availability = "AVAILABLE",
            runtimePayload = sourcePayload(),
            createdAt = 1L,
            updatedAt = 2L,
        )

        backup.sourceUrl shouldBe "/title/blue-lock"
        backup.sourceAvailability shouldBe "AVAILABLE"
        val restored = PortableMihonBindingCodec.restoreDormant(backup)
        val decoded = MihonContentBindingPayloadCodec.decode(restored)
        decoded.sourceId shouldBe 42L
        decoded.mihonMangaId shouldBe -1L
        decoded.sourceUrl shouldBe "/title/blue-lock"
        decoded.language shouldBe "pt-BR"
        backup.toString().contains("901") shouldBe false
    }

    @Test
    fun `unknown runtime bytes and identity mismatch must fail closed`() {
        runCatching {
            PortableMihonBindingCodec.export(
                "b", "t", "addon", "42:/title/other", 0.9, true, "AVAILABLE",
                sourcePayload(), 1L, 2L,
            )
        }.isFailure shouldBe true
        runCatching {
            PortableMihonBindingCodec.export(
                "b", "t", "addon", "42:/title/blue-lock", 0.9, true, "AVAILABLE",
                byteArrayOf(1, 2, 3), 1L, 2L,
            )
        }.isFailure shouldBe true
    }

    @Test
    fun `credential URL query userinfo fragment and unsupported schemes are rejected`() {
        val unsafeUrls = listOf(
            "https://example.com/book?access_token=secret",
            "https://user:secret@example.com/book",
            "https://example.com/book#secret",
            "javascript:alert(1)",
        )
        for (url in unsafeUrls) {
            runCatching {
                PortableMihonBindingCodec.export(
                    "b", "t", "addon", "42:$url", 0.9, true, "AVAILABLE",
                    sourcePayload(url), 1L, 2L,
                )
            }.isFailure shouldBe true
        }
    }

    @Test
    fun `invalid or conflicting backup binding references are rejected`() {
        val base = PortableMihonBindingCodec.export(
            "b", "title-1", "addon", "42:/title/blue-lock", 0.9, true, "AVAILABLE",
            sourcePayload(), 1L, 2L,
        )
        val titles = listOf(BackupCoreTitle("title-1", "Work", "SOURCE_ONLY", 1L, 2L))
        BackupCanonicalCore(titles = titles, contentBindings = listOf(base)).validate()

        runCatching {
            BackupCanonicalCore(titles = titles, contentBindings = listOf(base, base.copy(id = "b2"))).validate()
        }.isFailure shouldBe true
        runCatching {
            BackupCanonicalCore(
                titles = titles,
                contentBindings = listOf(base.copy(canonicalTitleId = "other")),
            ).validate()
        }.isFailure shouldBe true
        runCatching {
            BackupCanonicalCore(titles = titles, contentBindings = listOf(base.copy(codecVersion = 7))).validate()
        }.isFailure shouldBe true
    }
}
