package com.ghostos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

class GhostVpnService : VpnService() {

    companion object {
        const val ACTION_START = "ghostos.start"
        const val ACTION_STOP = "ghostos.stop"
        const val ACTION_FREEZE = "ghostos.freeze"
        const val ACTION_UNFREEZE = "ghostos.unfreeze"
    }

    private var tun: ParcelFileDescriptor? = null
    private var frozen = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { frozen = false; foreground(); connect() }
            ACTION_FREEZE -> { if (tun != null) { frozen = true; connect() } }
            ACTION_UNFREEZE -> { frozen = false; if (tun != null) connect() }
            ACTION_STOP -> { disconnect(); stopForeground(true); stopSelf() }
        }
        return START_NOT_STICKY
    }

    // VPN conectada: interfaz sin rutas, el internet sigue normal.
    // FREEZER: interfaz con rutas a todo y los paquetes se descartan (se corta).
    // Al tocar FREEZER otra vez se reemplaza por una sin rutas (se restablece).
    // La VPN queda prendida todo el tiempo, solo se apaga con el botón VPN.
    private fun connect() {
        val old = tun
        val b = Builder().setSession("GHOSTOS").addAddress("10.8.0.2", 32)
        if (frozen) {
            b.addAddress("fd00:1::2", 128)
            b.addRoute("0.0.0.0", 0)
            b.addRoute("::", 0)
        }
        // Se crea la nueva interfaz ANTES de cerrar la vieja: la VPN nunca se cae
        val fd = b.establish() ?: return
        tun = fd
        try { old?.close() } catch (_: Exception) { }
        if (frozen) {
            Thread {
                try {
                    val input = FileInputStream(fd.fileDescriptor)
                    val buf = ByteArray(32767)
                    while (input.read(buf) >= 0) { /* descarta todo */ }
                } catch (_: Exception) { }
            }.start()
        }
    }

    private fun disconnect() {
        try { tun?.close() } catch (_: Exception) { }
        tun = null
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ghostos", "GHOSTOS", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ghostos")
            .setContentTitle("GHOSTOS")
            .setContentText("VPN activa")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, n)
    }

    override fun onRevoke() {
        disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
