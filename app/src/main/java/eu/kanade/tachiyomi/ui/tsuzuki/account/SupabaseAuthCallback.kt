package eu.kanade.tachiyomi.ui.tsuzuki.account

import android.content.Intent
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAccountRepository
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAuthService
import eu.kanade.tachiyomi.ui.main.MainActivity

internal suspend fun acceptSupabaseAuthCallback(
    values: Map<String, String>,
    authService: SupabaseAuthService,
    repository: SupabaseAccountRepository,
): String {
    val type = values["type"]
        ?.takeIf { it in ALLOWED_TYPES }
        ?: error("Unsupported Supabase callback type")
    val result = when {
        values["token_hash"] != null -> authService.verifyCallbackToken(
            tokenHash = requireNotNull(values["token_hash"]),
            type = type,
        )
        values["access_token"] != null && values["refresh_token"] != null -> authService.verifyAccessToken(
            accessToken = requireNotNull(values["access_token"]),
            refreshToken = requireNotNull(values["refresh_token"]),
            expiresInSeconds = values["expires_in"]?.toLongOrNull() ?: 0L,
        )
        else -> error("Supabase callback did not contain a supported token")
    }
    val session = requireNotNull(result.session) { "Supabase callback did not create a session" }
    repository.acceptSession(session)
    return if (type == "recovery") {
        MainActivity.ACTION_TSUZUKI_PASSWORD_RECOVERY
    } else {
        Intent.ACTION_APPLICATION_PREFERENCES
    }
}

private val ALLOWED_TYPES = setOf("signup", "recovery", "email_change")
