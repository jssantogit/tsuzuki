package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun InlineSynopsis(workId: String, synopsis: String?) {
    if (synopsis.isNullOrBlank()) return
    var expanded by rememberSaveable(workId) { mutableStateOf(false) }
    val actionLabel = stringResource(
        if (expanded) MR.strings.manga_info_collapse else MR.strings.manga_info_expand,
    )
    Text(
        synopsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = actionLabel) { expanded = !expanded }
            .testTag("canonical_details_inline_synopsis"),
        color = defaultSkinColors().secondary,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
    )
}
