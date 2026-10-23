package eu.kanade.tachiyomi.ui.tsuzuki.account

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.tsuzuki.supabase.PersistentAuthCallbackRequestStore
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAuthService
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseConfiguration
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import okhttp3.OkHttpClient

class SupabaseAuthCallbackActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callback = intent?.data
        if (callback == null || !callback.isAllowedCallback()) {
            finish()
            return
        }

        val configuration = SupabaseConfiguration.fromBuildConfig()
        if (!configuration.isConfigured) {
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val values = callback.callbackValues()
                val type = values["type"]
                    ?.takeIf { it in ALLOWED_TYPES }
                    ?: error("Unsupported Supabase callback type")
                // Read only the original URI query, never a fragment-provided
                // lookalike. Supabase session tokens normally live in fragment.
                val state = callback.getQueryParameter("tsz_state")
                    ?: error("Auth callback is missing request state")
                val requests = PersistentAuthCallbackRequestStore(noBackupFilesDir)
                check(requests.consume(type, state)) {
                    "Auth callback was not initiated here, expired, or was already consumed"
                }

                val authService = SupabaseAuthService(
                    client = OkHttpClient(),
                    configuration = configuration,
                )
                val result = when {
                    values["token_hash"] != null -> {
                        authService.verifyCallbackToken(
                            tokenHash = requireNotNull(values["token_hash"]),
                            type = type,
                        )
                    }
                    values["access_token"] != null && values["refresh_token"] != null -> {
                        authService.verifyAccessToken(
                            accessToken = requireNotNull(values["access_token"]),
                            refreshToken = requireNotNull(values["refresh_token"]),
                            expiresInSeconds = values["expires_in"]?.toLongOrNull() ?: 0L,
                        )
                    }
                    else -> error("Supabase callback did not contain a supported token")
                }

                val session = requireNotNull(result.session) {
                    "Supabase callback did not create a session"
                }
                // Use the same scoped repository observed by Account/Sync UI.
                // A separate instance would leave an older in-memory user active.
                val graph: AppGraph = applicationContext.metroGraph()
                graph.callbackAccountRepository.acceptSession(session)
            }

            withContext(Dispatchers.Main) {
                startActivity(
                    Intent(this@SupabaseAuthCallbackActivity, MainActivity::class.java)
                        .setAction(Intent.ACTION_APPLICATION_PREFERENCES)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
            }
        }
    }

    private fun Uri.isAllowedCallback(): Boolean =
        scheme == SupabaseConfiguration.CALLBACK_SCHEME &&
            host == SupabaseConfiguration.CALLBACK_HOST &&
            port == -1 &&
            encodedPath.isNullOrEmpty()

    private fun Uri.callbackValues(): Map<String, String> {
        val values = buildMap {
            queryParameterNames.forEach { name ->
                getQueryParameter(name)?.let { put(name, it) }
            }
        }.toMutableMap()

        fragment?.takeIf(String::isNotBlank)?.let { rawFragment ->
            val fragmentUri = Uri.parse("tsuzuki://fragment?$rawFragment")
            fragmentUri.queryParameterNames.forEach { name ->
                fragmentUri.getQueryParameter(name)?.let { values[name] = it }
            }
        }
        return values
    }

    companion object {
        private val ALLOWED_TYPES = setOf("signup", "recovery")
    }
}
