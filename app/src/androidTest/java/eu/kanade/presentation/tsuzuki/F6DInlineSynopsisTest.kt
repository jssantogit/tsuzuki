package eu.kanade.presentation.tsuzuki

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.detail.InlineSynopsis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Cheap opt-in behavioral coverage; not part of the historical rendered harness. */
class F6DInlineSynopsisTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun expansionCollapsesInPlaceAndSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp)) {
                    InlineSynopsis("one", List(24) { "A factual multiline synopsis." }.joinToString("\n"))
                }
            }
        }
        val node = compose.onNodeWithTag("canonical_details_inline_synopsis")
        val collapsed = node.fetchSemanticsNode().boundsInRoot.height
        node.performClick()
        val expanded = node.fetchSemanticsNode().boundsInRoot.height
        assertTrue(expanded > collapsed)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(expanded, node.fetchSemanticsNode().boundsInRoot.height)
        node.performClick()
        assertEquals(collapsed, node.fetchSemanticsNode().boundsInRoot.height)
    }

    @Test
    fun missingSynopsisHasNoInteractiveEmptyBlock() {
        val text = mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { InlineSynopsis("one", text.value) } }
        compose.onNodeWithTag("canonical_details_inline_synopsis").assertDoesNotExist()
        compose.runOnIdle { text.value = "  " }
        compose.onNodeWithTag("canonical_details_inline_synopsis").assertDoesNotExist()
    }
}
