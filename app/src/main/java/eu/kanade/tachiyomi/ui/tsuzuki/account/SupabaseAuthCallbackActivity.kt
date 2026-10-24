package eu.kanade.tachiyomi.ui.tsuzuki.account

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.data.tsuzuki.supabase.AndroidKeystoreSessionStore
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAccountRepository
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAuthService
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseConfiguration
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            val action = runCatching {
                val store = AndroidKeystoreSessionStore(this@SupabaseAuthCallbackActivity)
                val authService = SupabaseAuthService(
                    client = OkHttpClient(),
                    configuration = configuration,
                )
                val repository = SupabaseAccountRepository(
                    authService = authService,
                    sessionStore = store,
                )
                acceptSupabaseAuthCallback(callback.callbackValues(), authService, repository)
            }.getOrNull()

            withContext(Dispatchers.Main) {
                if (action != null) {
                    startActivity(
                        Intent(this@SupabaseAuthCallbackActivity, MainActivity::class.java)
                            .setAction(action)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
                finish()
            }
        }
    }

    private fun Uri.isAllowedCallback(): Boolean =
        scheme == SupabaseConfiguration.CALLBACK_SCHEME &&
            host == SupabaseConfiguration.CALLBACK_HOST

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
}
