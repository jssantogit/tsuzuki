package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.tsuzuki.components.DefaultSkinCard
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isNightlyBuildType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Instant

object AboutScreen : Screen() {

    @Composable
    override fun Content() {
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        val colors = defaultSkinColors()

        Scaffold(
            containerColor = colors.canvas,
            contentColor = colors.foreground,
            topBar = { scrollBehavior ->
                AppBar(
                    title = "",
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    Text(
                        text = stringResource(MR.strings.pref_category_about),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    )
                }

                item {
                    LogoHeader(
                        iconPadding = PaddingValues(vertical = 36.dp),
                        showDivider = false,
                    )
                }

                item {
                    DefaultSkinCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    ) {
                        Column {
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.version),
                                subtitle = getVersionName(withBuildDate = true),
                                onPreferenceClick = {
                                    val context = Injekt.get<Context>()
                                    val deviceInfo = context.appGraph.crashLogUtil.getDebugInfo()
                                    context.copyToClipboard("Debug information", deviceInfo)
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = colors.outline.copy(alpha = 0.6f),
                            )
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.tsuzuki_project_source),
                                onPreferenceClick = {
                                    uriHandler.openUri("https://github.com/jssantogit/tsuzuki")
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = colors.outline.copy(alpha = 0.6f),
                            )
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.tsuzuki_upstream_mihon),
                                onPreferenceClick = {
                                    uriHandler.openUri("https://github.com/mihonapp/mihon")
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = colors.outline.copy(alpha = 0.6f),
                            )
                            TextPreferenceWidget(
                                title = stringResource(MR.strings.licenses),
                                onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                            )
                        }
                    }
                }
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            isNightlyBuildType -> {
                "Nightly r${BuildConfig.COMMIT_COUNT}".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            else -> {
                val channel = if (isFossBuildType) "FOSS" else "Stable"
                "$channel v${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            Instant.parse(BuildConfig.BUILD_TIME)
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<Context>().appGraph.uiPreferences.dateFormat.get(),
                    ),
                )
        } catch (_: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
