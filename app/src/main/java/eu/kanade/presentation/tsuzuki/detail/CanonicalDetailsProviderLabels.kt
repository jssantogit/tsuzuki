package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.tsuzuki.content.ContentProviderLabels
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@Composable
fun canonicalDetailsProviderLabels(): ContentProviderLabels {
    val context = LocalContext.current
    return ContentProviderLabels(
        settings = context.stringResource(MR.strings.tsuzuki_details_providers),
        choose = context.stringResource(MR.strings.tsuzuki_details_provider_choose),
        searching = { context.stringResource(MR.strings.tsuzuki_details_provider_searching, it) },
        failed = { context.stringResource(MR.strings.tsuzuki_details_provider_failed, it) },
        noEnabled = context.stringResource(MR.strings.tsuzuki_details_provider_no_enabled),
        confirmationRequired = context.stringResource(MR.strings.tsuzuki_details_provider_confirmation),
        timedOut = context.stringResource(MR.strings.tsuzuki_details_provider_timed_out),
        notFound = context.stringResource(MR.strings.tsuzuki_details_provider_not_found),
        bindingTitle = context.stringResource(MR.strings.tsuzuki_details_provider_link_title),
        bindingEmpty = context.stringResource(MR.strings.tsuzuki_details_provider_link_empty),
        bindingChoice = context.stringResource(MR.strings.tsuzuki_details_provider_link_choice),
        error = context.stringResource(MR.strings.tsuzuki_details_chapter_error),
    )
}
