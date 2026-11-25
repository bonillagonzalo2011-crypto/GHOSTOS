package com.ghostos.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pending: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        setContentView(web)
    }

    inner class Bridge {
        // Botón VPN: conecta o desconecta el VpnService
        @JavascriptInterface
        fun vpn(on: Boolean) = runOnUiThread {
            if (on) startVpn(GhostVpnService.ACTION_START) else send(GhostVpnService.ACTION_STOP)
        }

        // Botón FREEZER: solo funciona con la VPN prendida; corta (true) y restablece (false)
        @JavascriptInterface
        fun freezer(on: Boolean) = runOnUiThread {
            send(if (on) GhostVpnService.ACTION_FREEZE else GhostVpnService.ACTION_UNFREEZE)
        }

        @JavascriptInterface
        fun stopAll() = runOnUiThread {
            send(GhostVpnService.ACTION_STOP)
            startService(
                Intent(this@MainActivity, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_HIDE)
            )
        }

        // Muestra (o quita) los botones flotantes sobre otras apps
        @JavascriptInterface
        fun floating(names: String, cfg: String) = runOnUiThread {
            startService(
                Intent(this@MainActivity, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_NAMES, names)
                    .putExtra(OverlayService.EXTRA_CFG, cfg)
            )
        }

        @JavascriptInterface
        fun permStatus(): String = permJson()

        @JavascriptInterface
        fun perm(name: String) = runOnUiThread { openPerm(name) }
    }

    // Estado real de los 3 permisos
    private fun permJson(): String {
        val vpn = try { VpnService.prepare(this) == null } catch (_: Exception) { false }
        val overlay = Settings.canDrawOverlays(this)
        val notif = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else
            getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        return "{\"VPN\":$vpn,\"Mostrar sobre otras apps\":$overlay,\"Notificaciones\":$notif}"
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.permSync&&permSync(" + permJson() + ")", null)
    }

    private fun startVpn(action: String) {
        val i = VpnService.prepare(this)
        if (i != null) {
            pending = action
            startActivityForResult(i, 1)
        } else {
            send(action)
        }
    }

    private fun send(action: String) {
        startService(Intent(this, GhostVpnService::class.java).setAction(action))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) pending?.let { send(it) }
        pending = null
    }

    private fun openPerm(name: String) {
        when (name) {
            "VPN" -> VpnService.prepare(this)?.let { startActivityForResult(it, 2) }
            "Mostrar sobre otras apps" -> startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            "Notificaciones" -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
