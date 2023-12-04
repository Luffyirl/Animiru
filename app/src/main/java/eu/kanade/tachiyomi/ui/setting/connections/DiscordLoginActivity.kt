// AM (DISCORD) -->

// Original library from https://github.com/dead8309/KizzyRPC (Thank you)
// Thank you to the 最高 man for the refactored and simplified code
// https://github.com/saikou-app/saikou
package eu.kanade.tachiyomi.ui.setting.connections

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.injectLazy
import java.io.File

class DiscordLoginActivity : BaseActivity() {

    private val connectionsManager: ConnectionsManager by injectLazy()
    private val connectionsPreferences: ConnectionsPreferences by injectLazy()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.discord_login_activity)
        val webView = findViewById<WebView>(R.id.webview)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.databaseEnabled = true
            settings.domStorageEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.endsWith("/app")) {
                    webView.stopLoading()
                    webView.evaluateJavascript(
                        """
                        (function() {
                            const wreq = webpackChunkdiscord_app.push([[Symbol()], {}, w => w])
                            webpackChunkdiscord_app.pop()
                            const token = Object.values(wreq.c).find(m => m.exports?.Z?.getToken).exports.Z.getToken();
                            return token;
                        })()
                        """.trimIndent(),
                    ) {
                        login(it.trim('"'))
                    }
                }
            }
        }
        webView.loadUrl("https://discord.com/login")
    }

    private fun login(token: String) {
        connectionsPreferences.connectionsToken(connectionsManager.discord).set(token)
        connectionsPreferences.setConnectionsCredentials(connectionsManager.discord, "Discord", "Logged In")
        toast(R.string.login_success)
        applicationInfo.dataDir.let { File("$it/app_webview/").deleteRecursively() }
        finish()
    }
}
// <-- AM (DISCORD)
