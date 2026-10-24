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
                val query = callback.queryParameterNames.associateWith { key ->
                    val values = callback.getQueryParameters(key)
                    check(values.size == 1) { "Duplicate auth callback parameter" }
                    values.single()
                }
                val payload = SupabaseCallbackCodePolicy.parse(query, callback.fragment)
                val requests = PersistentAuthCallbackRequestStore(noBackupFilesDir)
                val verifier = requests.consume(payload.state, payload.typeHint)
                    ?: error("Auth callback was not initiated here or has expired")

                val authService = SupabaseAuthService(
                    client = OkHttpClient(),
                    configuration = configuration,
                )
                val result = authService.exchangeAuthCode(payload.code, verifier)

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
}
