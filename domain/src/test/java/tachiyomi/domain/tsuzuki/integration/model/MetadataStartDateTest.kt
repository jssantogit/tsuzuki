package tachiyomi.domain.tsuzuki.integration.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MetadataStartDateTest {

    @Test
    fun `metadata year accepts factual year month and valid calendar date`() {
        metadataStartYear("2020") shouldBe "2020"
        metadataStartYear("2020-06") shouldBe "2020"
        metadataStartYear("2020-06-12") shouldBe "2020"
        metadataStartYear(" 2020-02-29 ") shouldBe "2020"
    }

    @Test
    fun `metadata year omits absent and malformed dates`() {
        metadataStartYear(null) shouldBe null
        metadataStartYear("unknown") shouldBe null
        metadataStartYear("2020-13") shouldBe null
        metadataStartYear("2019-02-29") shouldBe null
        metadataStartYear("2020-06-") shouldBe null
        metadataStartYear("0000") shouldBe null
    }
}
