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
                // Only accept a PKCE authorization code in the URI query.
                // Never accept implicit-flow bearer tokens from a deep link.
                check(callback.fragment.isNullOrEmpty()) {
                    "Implicit-flow token fragments are not accepted"
                }
                val typeHint = callback.getQueryParameter("type")
                    ?.also { check(it in ALLOWED_TYPES) { "Unsupported callback type" } }
                check(
                    callback.getQueryParameter("access_token") == null &&
                        callback.getQueryParameter("refresh_token") == null &&
                        callback.getQueryParameter("token_hash") == null
                ) { "Bearer tokens are not accepted in callback URLs" }
                val state = callback.getQueryParameter("tsz_state")
                    ?: error("Auth callback is missing state")
                val code = callback.getQueryParameter("code")
                    ?.takeIf(String::isNotBlank)
                    ?: error("Auth callback is missing its PKCE code")
                val requests = PersistentAuthCallbackRequestStore(noBackupFilesDir)
                val verifier = requests.consume(state, typeHint)
                    ?: error("Auth callback was not initiated here or has expired")

                val authService = SupabaseAuthService(
                    client = OkHttpClient(),
                    configuration = configuration,
                )
                val result = authService.exchangeAuthCode(code, verifier)

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

    companion object {
        private val ALLOWED_TYPES = setOf("signup", "recovery")
    }
}
