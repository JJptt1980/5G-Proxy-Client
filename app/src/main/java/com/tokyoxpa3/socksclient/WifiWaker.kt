package com.tokyoxpa3.socksclient

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * 叫醒 Wi-Fi 搜尋（GW 版）：有些平板自己找 Wi-Fi 很慢，熱點開了很久才出現。
 * 等待 Pro 時，畫面開著就每 30 秒要系統立刻搜尋一次，也可以按「立即搜尋 Wi-Fi」；
 * 搜到已存過的熱點，系統會自己連上。Android 限制每 2 分鐘最多 4 次，超過會被系統略過。
 * 搜尋要「位置」權限（Android 規定），權限只放在 debug manifest。
 */
class WifiWaker(private val activity: Activity, private val requestCode: Int) {
    companion object {
        const val INTERVAL_MS = 30_000L
        fun enabledFor(packageName: String) = packageName.endsWith(".gw")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var waiting = false
    private lateinit var tv: TextView
    private var lastScan = -INTERVAL_MS

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed || !waiting) return
            // 回到畫面、剛允許權限都會重新排程；距離上次不到 25 秒就不再搜，免得撞到系統次數限制。
            if (hasPermission() && android.os.SystemClock.elapsedRealtime() - lastScan >= 25_000L) scan()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun view(make: (String, () -> Unit) -> TextView): TextView {
        tv = make("") { onTap() }
        tv.visibility = View.GONE
        render(null)
        return tv
    }

    fun onResume() { resumed = true; restart() }

    fun onPause() { resumed = false; handler.removeCallbacks(tick) }

    /** updateStatus 每次都呼叫：等待 Pro 時才顯示、才自動搜尋。 */
    fun setWaiting(w: Boolean) {
        if (::tv.isInitialized) tv.visibility = if (w) View.VISIBLE else View.GONE
        if (w == waiting) return
        waiting = w
        restart()
    }

    fun onPermissionResult(code: Int) {
        if (code != requestCode) return
        if (hasPermission()) { scan(); restart() } else render("沒有位置權限，沒辦法叫醒 Wi-Fi")
    }

    private fun restart() {
        handler.removeCallbacks(tick)
        if (resumed && waiting) handler.post(tick)
    }

    private fun onTap() {
        if (hasPermission()) scan()
        else activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), requestCode)
    }

    private fun hasPermission() =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun scan() {
        val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lastScan = android.os.SystemClock.elapsedRealtime()
        val ok = runCatching {
            if (!wm.isWifiEnabled) return render("Wi-Fi 沒開")
            @Suppress("DEPRECATION") wm.startScan()
        }.getOrDefault(false)
        android.util.Log.i("WifiWaker", "startScan ok=$ok")
        render(if (ok) "已叫醒 Wi-Fi 搜尋" else "系統暫時不讓搜尋（太頻繁），稍後自動再試")
    }

    private fun render(note: String?) {
        if (!::tv.isInitialized) return
        val action = if (hasPermission()) "立即搜尋 Wi-Fi ›" else "允許搜尋 Wi-Fi ›"
        tv.text = if (note == null) action else "$note · $action"
    }
}
