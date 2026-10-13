package eu.kanade.tachiyomi

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration

/** Disposable host for opt-in Default Skin instrumentation; it is absent from release builds. */
class DefaultSkinValidationActivity : ComponentActivity() {
    lateinit var composeView: ComposeView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        composeView = ComposeView(this)
        setContentView(composeView)
    }

    fun setValidationContent(
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        val fixtureConfiguration = Configuration(resources.configuration).apply {
            val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        composeView.setContent {
            CompositionLocalProvider(LocalConfiguration provides fixtureConfiguration) {
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
