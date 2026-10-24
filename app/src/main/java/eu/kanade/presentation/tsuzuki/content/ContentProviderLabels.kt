package eu.kanade.presentation.tsuzuki.content

/** Optional public terminology supplied by Details; legacy consumers retain their own copy. */
data class ContentProviderLabels(
    val settings: String,
    val choose: String,
    val searching: (Int) -> String,
    val failed: (Int) -> String,
    val noEnabled: String,
    val confirmationRequired: String,
    val timedOut: String,
    val notFound: String,
    val bindingTitle: String,
    val bindingEmpty: String,
    val bindingChoice: String,
    val error: String,
)
