package eu.kanade.tachiyomi

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag

/** Disposable host for opt-in Default Skin instrumentation; it is absent from release builds. */
class DefaultSkinValidationActivity : ComponentActivity() {
    lateinit var composeView: ComposeView
        private set

    var validationThemeForEvidence: String = "light"
        private set

    var validationSceneIdForEvidence: String = ""
        private set

    var validationSceneGeneration: Int = 0
        private set

    private var validationSceneTag by mutableStateOf("")
    val validationSceneTagForEvidence: String
        get() = validationSceneTag

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this)
        setContentView(composeView)
    }

    fun setValidationContent(
        sceneId: String,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        validationThemeForEvidence = if (dark) "dark" else "light"
        updateValidationScene(sceneId)
        val fixtureConfiguration = Configuration(resources.configuration).apply {
            val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        composeView.disposeComposition()
        composeView.setContent {
            Box(Modifier.fillMaxSize().testTag(validationSceneTag)) {
                CompositionLocalProvider(LocalConfiguration provides fixtureConfiguration) {
                    val renderedTheme = if (isSystemInDarkTheme()) "dark" else "light"
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag("validation_rendered_theme_$renderedTheme"),
                    ) {
                        MaterialExpressiveTheme(
                            colorScheme = if (dark) {
                                darkColorScheme(surface = Color(0xFF1C1E1F))
                            } else {
                                lightColorScheme(surface = Color.White)
                            },
                            content = content,
                        )
                    }
                }
            }
        }
    }

    fun markValidationScene(sceneId: String) {
        updateValidationScene(sceneId)
    }

    private fun updateValidationScene(sceneId: String) {
        require(sceneId.isNotBlank())
        validationSceneIdForEvidence = sceneId
        validationSceneGeneration += 1
        validationSceneTag = "validation_scene_${validationSceneGeneration}_$sceneId"
    }
}
