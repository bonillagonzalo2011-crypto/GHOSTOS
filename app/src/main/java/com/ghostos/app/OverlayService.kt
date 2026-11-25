package com.ghostos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs

// Botones flotantes que se ven encima de otras apps (YouTube, juegos, etc.)
class OverlayService : Service() {

    companion object {
        const val ACTION_HIDE = "ghostos.hide"
        const val EXTRA_NAMES = "names"
        const val EXTRA_CFG = "cfg"
    }

    private lateinit var wm: WindowManager
    private val views = HashMap<String, TextView>()
    private val painters = HashMap<String, () -> Unit>()
    private val pos = HashMap<String, IntArray>()
    private val on = HashMap<String, Boolean>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val names = (intent?.getStringExtra(EXTRA_NAMES) ?: "").split(",").filter { it.isNotEmpty() }
        if (intent?.action == ACTION_HIDE || names.isEmpty() || !Settings.canDrawOverlays(this)) {
            clear()
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        foreground()
        show(names, try { JSONObject(intent?.getStringExtra(EXTRA_CFG) ?: "{}") } catch (_: Exception) { JSONObject() })
        return START_NOT_STICKY
    }

    private fun col(s: String, def: Int): Int =
        try { Color.parseColor(s) } catch (_: Exception) { if (s == "rainbow") Color.WHITE else def }

    private fun show(names: List<String>, cfg: JSONObject) {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        views.keys.toList().forEach { remove(it) }

        names.forEachIndexed { idx, n ->
            val c = cfg.optJSONObject(n) ?: JSONObject()
            val size = (c.optInt("size", 78) * d).toInt()
            val bw = (c.optInt("border", 3) * d).toInt()
            val offC = col(c.optString("off", "#8a8a8a"), Color.GRAY)
            val onC = col(c.optString("on", "#3be86b"), Color.GREEN)
            val alpha = c.optInt("alpha", 40) * 255 / 100
            val shape = c.optString("shape", "circle")

            val bg = GradientDrawable()
            when (shape) {
                "square" -> { bg.shape = GradientDrawable.RECTANGLE; bg.cornerRadius = 8 * d }
                "rounded" -> { bg.shape = GradientDrawable.RECTANGLE; bg.cornerRadius = size * 0.28f }
                else -> bg.shape = GradientDrawable.OVAL
            }
            bg.setColor(Color.argb(alpha, 0, 0, 0))

            val tv = TextView(this)
            tv.text = if (c.optBoolean("name", true)) n else ""
            tv.gravity = Gravity.CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * 0.14f)
            tv.setTypeface(null, Typeface.BOLD)
            tv.background = bg

            val paint = {
                val colr = if (on[n] == true) onC else offC
                bg.setStroke(bw, colr)
                tv.setTextColor(colr)
            }
            paint()

            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            val p = pos[n] ?: intArrayOf(
                resources.displayMetrics.widthPixels - size - (16 * d).toInt(),
                (120 * d).toInt() + idx * (size + (12 * d).toInt())
            )
            lp.x = p[0]; lp.y = p[1]

            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            tv.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) + abs(dy) > 16) moved = true
                        if (moved) {
                            lp.x = ox + dx; lp.y = oy + dy
                            wm.updateViewLayout(tv, lp)
                            pos[n] = intArrayOf(lp.x, lp.y)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) tap(n)
                }
                true
            }
            wm.addView(tv, lp)
            views[n] = tv
            painters[n] = paint
        }
    }

    private fun tap(n: String) {
        if (n == "VPN") {
            if (on["VPN"] != true) {
                if (VpnService.prepare(this) != null) {
                    Toast.makeText(this, "Activá el permiso VPN en GHOSTOS", Toast.LENGTH_SHORT).show()
                    return
                }
                on["VPN"] = true
                vpn(GhostVpnService.ACTION_START)
            } else {
                on["VPN"] = false
                on["FREEZER"] = false
                vpn(GhostVpnService.ACTION_STOP)
            }
        } else if (n == "FREEZER") {
            if (on["VPN"] != true) {
                Toast.makeText(this, "Primero enciende el VPN", Toast.LENGTH_SHORT).show()
                return
            }
            val f = on["FREEZER"] != true
            on["FREEZER"] = f
            vpn(if (f) GhostVpnService.ACTION_FREEZE else GhostVpnService.ACTION_UNFREEZE)
        }
        painters.values.forEach { it() }
    }

    private fun vpn(action: String) {
        startService(Intent(this, GhostVpnService::class.java).setAction(action))
    }

    private fun remove(n: String) {
        views.remove(n)?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        painters.remove(n)
    }

    private fun clear() {
        if (::wm.isInitialized) views.keys.toList().forEach { remove(it) }
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ghostos_overlay", "GHOSTOS botones", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ghostos_overlay")
            .setContentTitle("GHOSTOS")
            .setContentText("Botones flotantes activos")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(2, n)
    }

    override fun onDestroy() {
        clear()
        super.onDestroy()
    }
}
