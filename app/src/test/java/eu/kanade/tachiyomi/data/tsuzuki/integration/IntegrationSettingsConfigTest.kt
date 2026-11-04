package eu.kanade.tachiyomi.data.tsuzuki.integration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability

class IntegrationSettingsConfigTest {

    @Test
    fun `chapter inventory is opt-in even when the integration and legacy capabilities are enabled`() {
        val legacy = IntegrationSettingsConfig.decode("""{"search":true}""")
        legacy.capabilityEnabled(IntegrationCapability.SEARCH) shouldBe true
        legacy.capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY) shouldBe false

        IntegrationSettingsConfig.decode("{not-json")
            .capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY) shouldBe false

        val enabled = legacy.withCapability(IntegrationCapability.CHAPTER_INVENTORY, true)
        enabled.capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY) shouldBe true
        enabled.withCapability(IntegrationCapability.CHAPTER_INVENTORY, false)
            .capabilityEnabled(IntegrationCapability.CHAPTER_INVENTORY) shouldBe false
    }

    @Test
    fun `typed config preserves unknown legacy keys while changing capability`() {
        val config = IntegrationSettingsConfig.decode(
            """{"chapter_evidence":false,"custom":"keep","search":true}""",
        )

        val updated = config.withCapability(IntegrationCapability.DISCOVERY, false)
        val encoded = updated.encode()

        IntegrationSettingsConfig.decode(encoded)
            .capabilityEnabled(IntegrationCapability.SEARCH) shouldBe true
        IntegrationSettingsConfig.decode(encoded)
            .capabilityEnabled(IntegrationCapability.DISCOVERY) shouldBe false
        IntegrationSettingsConfig.decode(encoded)
            .capabilityEnabled("chapter_evidence") shouldBe false
        encoded.contains("\"custom\":\"keep\"") shouldBe true
    }

    @Test
    fun `invalid config fails safely to defaults`() {
        val config = IntegrationSettingsConfig.decode("{not-json")

        config.isDefault() shouldBe true
        config.capabilityEnabled(IntegrationCapability.SEARCH) shouldBe true
    }
}
