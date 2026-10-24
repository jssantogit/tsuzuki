package eu.kanade.tachiyomi.ui.tsuzuki.account

/** Reject implicit-flow credentials before any network call or session write. */
internal data class SupabaseCallbackCode(
    val state: String,
    val code: String,
    val typeHint: String?,
)

internal object SupabaseCallbackCodePolicy {

    fun parse(
        query: Map<String, String>,
        fragment: String?,
    ): SupabaseCallbackCode {
        check(fragment.isNullOrEmpty()) {
            "Supabase callback fragments are not accepted"
        }
        check(
            listOf("access_token", "refresh_token", "token_hash", "expires_in")
                .none(query::containsKey),
        ) { "Callback must not contain bearer credentials" }

        val state = query["tsz_state"]
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{16,128}")) }
            ?: error("Missing or malformed callback state")
        val code = query["code"]
            ?.takeIf { it.isNotBlank() && it.length <= 2048 }
            ?: error("Missing or malformed PKCE authorization code")
        val type = query["type"]?.also {
            check(it == "signup" || it == "recovery") {
                "Callback type is not an initiated flow"
            }
        }
        return SupabaseCallbackCode(state, code, type)
    }
}
