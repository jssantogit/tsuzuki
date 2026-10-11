package eu.kanade.presentation.tsuzuki.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.profile.model.ProfileSnapshot
import tachiyomi.domain.tsuzuki.profile.model.ProfileTrackerState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TsuzukiProfileScreen(
    snapshot: ProfileSnapshot?,
    continueReading: List<HomeContinueReadingItem>,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    onTracking: () -> Unit,
    onContinueReading: (HomeContinueReadingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val lightSurface = colorScheme.surface.luminance() > 0.5f
    val colors = remember(lightSurface, colorScheme) {
        ProfileColors(
            background = if (lightSurface) Color(0xFFF7F7F8) else colorScheme.background,
            surface = if (lightSurface) Color.White else colorScheme.surface,
            foreground = if (lightSurface) Color(0xFF17181A) else colorScheme.onBackground,
            secondary = if (lightSurface) Color(0xFF50565C) else colorScheme.onSurfaceVariant,
            accent = if (lightSurface) Color(0xFF275A80) else colorScheme.primary,
            divider = if (lightSurface) Color(0xFFDDE0E3) else colorScheme.outlineVariant,
        )
    }
    val readingItems = remember(continueReading) { continueReading.take(MAX_PROFILE_READING_ITEMS) }
    val pagerState = rememberPagerState(pageCount = { readingItems.size })
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = PROFILE_CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Settings,
                        contentDescription = stringResource(MR.strings.tsuzuki_profile_settings),
                        tint = colors.secondary,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }

            IdentityHeader(
                account = snapshot?.account,
                colors = colors,
                onAccount = onAccount,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
            )

            if (snapshot == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = colors.accent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(MR.strings.tsuzuki_profile_loading),
                            color = colors.secondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            } else {
                ProfileStatistics(
                    snapshot = snapshot,
                    colors = colors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, start = 24.dp, end = 24.dp),
                )
            }

            if (readingItems.isNotEmpty()) {
                ProfileSectionTitle(
                    title = stringResource(MR.strings.tsuzuki_profile_continue_reading),
                    colors = colors,
                    modifier = Modifier.padding(top = 36.dp, start = 24.dp, end = 24.dp, bottom = 12.dp),
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    pageSpacing = 12.dp,
                    key = { page -> readingItems[page].canonicalTitleId },
                ) { page ->
                    val item = readingItems[page]
                    ContinueReadingCard(
                        item = item,
                        colors = colors,
                        accessibilityPosition = stringResource(
                            MR.strings.tsuzuki_profile_pager_position,
                            page + 1,
                            readingItems.size,
                        ),
                        onClick = { onContinueReading(item) },
                    )
                }
                if (readingItems.size > 1) {
                    PagerIndicator(
                        count = readingItems.size,
                        selected = pagerState.currentPage,
                        colors = colors,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 14.dp),
                    )
                }
            }

            if (snapshot != null && snapshot.trackers.isNotEmpty()) {
                TrackingSummary(
                    trackers = snapshot.trackers,
                    colors = colors,
                    onClick = onTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (readingItems.isEmpty()) 36.dp else 34.dp)
                        .padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun IdentityHeader(
    account: AccountState?,
    colors: ProfileColors,
    onAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Person,
                contentDescription = stringResource(MR.strings.tsuzuki_profile_avatar_description),
                tint = colors.secondary,
                modifier = Modifier.size(46.dp),
            )
        }

        when (account) {
            null -> Unit
            AccountState.LoggedOut -> {
                Text(
                    text = stringResource(MR.strings.tsuzuki_profile_guest),
                    color = colors.foreground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Button(
                    onClick = onAccount,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(MR.strings.tsuzuki_profile_guest_action))
                }
            }
            is AccountState.Authenticated -> {
                Text(
                    text = account.account.email,
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            is AccountState.EmailConfirmationRequired -> {
                Text(
                    text = stringResource(MR.strings.tsuzuki_profile_confirmation_title),
                    color = colors.foreground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = account.email,
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(
                    onClick = onAccount,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(MR.strings.tsuzuki_profile_confirmation_action))
                }
            }
        }
    }
}

@Composable
private fun ProfileStatistics(
    snapshot: ProfileSnapshot,
    colors: ProfileColors,
    modifier: Modifier = Modifier,
) {
    val completedLabel = stringResource(MR.strings.tsuzuki_profile_completed_titles)
    val chaptersLabel = stringResource(MR.strings.tsuzuki_profile_chapters_read)
    val pagesLabel = stringResource(MR.strings.tsuzuki_profile_pages_read)
    val metrics = buildList {
        add(completedLabel to snapshot.reading.completedTitles.toString())
        add(chaptersLabel to snapshot.reading.chaptersRead.toString())
        snapshot.reading.pagesRead?.let { pages ->
            add(pagesLabel to pages.toString())
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        metrics.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(34.dp)
                        .background(colors.divider),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = value,
                    color = colors.foreground,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    softWrap = false,
                )
                Text(
                    text = label,
                    color = colors.secondary,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ProfileSectionTitle(
    title: String,
    colors: ProfileColors,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        color = colors.foreground,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
private fun ContinueReadingCard(
    item: HomeContinueReadingItem,
    colors: ProfileColors,
    accessibilityPosition: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 156.dp)
            .semantics { stateDescription = accessibilityPosition },
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.72f)),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                data = item.coverUrl ?: item.sourceCover,
                fallbackData = listOf(item.sourceCover, item.sourceCover?.url),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(86.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.title,
                    color = colors.foreground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        MR.strings.tsuzuki_profile_reading_checkpoint,
                        item.chapterDisplayNumber,
                    ),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PagerIndicator(
    count: Int,
    selected: Int,
    colors: ProfileColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == selected) 7.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (index == selected) colors.accent else colors.divider),
            )
        }
    }
}

@Composable
private fun TrackingSummary(
    trackers: List<ProfileTrackerState>,
    colors: ProfileColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectedTrackers = trackers.filter { it.isLoggedIn }
    val connectedNames = connectedTrackers.joinToString(separator = ", ") { it.name }
    val statusSummary = stringResource(
        MR.strings.tsuzuki_profile_tracking_summary,
        connectedTrackers.size,
        trackers.size,
    )
    Column(modifier = modifier) {
        ProfileSectionTitle(
            title = stringResource(MR.strings.tsuzuki_profile_tracking),
            colors = colors,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            contentColor = colors.foreground,
            border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.72f)),
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (connectedNames.isNotBlank()) {
                    Text(
                        text = connectedNames,
                        color = colors.foreground,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = statusSummary,
                    color = colors.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(MR.strings.tsuzuki_profile_open_tracking),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private data class ProfileColors(
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val secondary: Color,
    val accent: Color,
    val divider: Color,
)

private const val MAX_PROFILE_READING_ITEMS = 5
private val PROFILE_CONTENT_MAX_WIDTH = 720.dp
