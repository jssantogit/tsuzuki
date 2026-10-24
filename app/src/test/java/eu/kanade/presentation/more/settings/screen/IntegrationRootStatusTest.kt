package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IntegrationRootStatusTest {

    @Test
    fun `disabled takes precedence over local configuration state`() {
        integrationRootStatus(enabled = false, hasRequiredConfiguration = false) shouldBe
            IntegrationRootStatus.DISABLED
        integrationRootStatus(enabled = false, hasRequiredConfiguration = true) shouldBe
            IntegrationRootStatus.DISABLED
        integrationRootStatus(enabled = false, hasRequiredConfiguration = null) shouldBe
            IntegrationRootStatus.DISABLED
    }

    @Test
    fun `enabled integrations report only truthful local configuration states`() {
        integrationRootStatus(enabled = true, hasRequiredConfiguration = false) shouldBe
            IntegrationRootStatus.NOT_CONFIGURED
        integrationRootStatus(enabled = true, hasRequiredConfiguration = true) shouldBe
            IntegrationRootStatus.CONFIGURED
        integrationRootStatus(enabled = true, hasRequiredConfiguration = null) shouldBe
            IntegrationRootStatus.ACTIVE
    }
}
