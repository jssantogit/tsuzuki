package eu.kanade.tachiyomi.ui.tsuzuki.entry

internal enum class EntryChoice(val destination: EntryDestination) {
    CREATE_ACCOUNT(EntryDestination.REGISTER),
    SIGN_IN(EntryDestination.LOGIN),
    GUEST(EntryDestination.HOME),
}

internal enum class EntryDestination {
    REGISTER,
    LOGIN,
    HOME,
}

internal object EntryFlowPolicy {
    fun shouldShowWelcome(firstInstallComplete: Boolean): Boolean = !firstInstallComplete

    fun choose(
        choice: EntryChoice,
        markComplete: () -> Unit,
        navigate: (EntryDestination) -> Unit,
    ) {
        markComplete()
        navigate(choice.destination)
    }
}
