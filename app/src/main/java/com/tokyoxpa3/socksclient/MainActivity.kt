package com.tokyoxpa3.socksclient

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import android.os.PowerManager
import android.net.Uri
import android.annotation.SuppressLint
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN_PERMISSION = 100
        private const val REQ_NOTIFICATION_PERMISSION = 200
        private const val REQ_WIFI_SCAN_PERMISSION = 201
        const val EXTRA_AUTO_START = "auto_start_from_tile"
        private const val KEY_UI_ADVANCED_EXPANDED = "ui_advanced_expanded"
    }

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etDns1: EditText
    private lateinit var etDns2: EditText
    private lateinit var etProfileName: EditText
    private lateinit var cbUdpInTcp: CheckBox
    private lateinit var cbRemoteDns: CheckBox
    private lateinit var cbAutoStart: CheckBox
    private lateinit var modeGroup: RadioGroup
    private lateinit var btnToggle: Button
    private lateinit var btnSelectApps: Button
    private lateinit var btnSaveProfile: Button
    private lateinit var btnDeleteProfile: Button
    private lateinit var btnExportProfiles: Button
    private lateinit var btnImportProfiles: Button
    private lateinit var spinnerProfile: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusTitle: TextView
    private lateinit var statusDot: View

    /** 開啟時讀到的上次閃退／錯誤訊息；未連線時在狀態卡用紅色顯示。 */
    private var startupNotice: String? = null
    private lateinit var tvKillSwitch: TextView
    private lateinit var btnKillSwitch: Button
    private lateinit var btnBattery: Button
    private lateinit var cbCoexist: CheckBox
    private lateinit var tvCoexist: TextView

    private var pendingAutoStart = false
    private var autoFinishPending = false
    private val profileNames = mutableListOf<String>()

    /** 由程式同步 cbCoexist 時抑制其監聽器，避免「同步 → 觸發 → 再寫入」的回圈。 */
    private var suppressCoexistWrite = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TunSocksService.ACTION_STATUS) {
                updateStatus(intent.getStringExtra("status"))
                // 開機自動啟動的「閃一下」：隧道建立成功後自動關閉
                if (autoFinishPending && TunSocksService.isActive) {
                    autoFinishPending = false
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        // 螢幕旋轉等設定變更會重建 Activity。重建時不可重跑「開啟即連線」或「閃一下啟動」，
        // 否則使用者按了停止之後，只要轉一下螢幕隧道就會自己起來。
        val isRecreation = savedInstanceState != null
        pendingAutoStart = !isRecreation &&
            (intent?.getBooleanExtra(EXTRA_AUTO_START, false) ?: false)
        buildUi()
        // 開啟 App 即自動連線（像 sockstun）：非「閃一下」啟動、隧道未在跑、有已存設定 → 自動建立 VPN
        if (!isRecreation && !pendingAutoStart && !TunSocksService.isActive &&
            !(Config.prefs(this).getString(Config.KEY_HOST, "gateway") ?: "").isBlank()
        ) {
            attemptStart()
        }
        if (savedInstanceState == null) {
            startupNotice = DebugLog.getLastCrash()?.let { crash ->
                getString(R.string.last_crash_prefix, crash)
            } ?: DebugLog.getLastError()?.let { err ->
                getString(R.string.last_error_prefix, err)
            }
            updateStatus(TunSocksService.lastStatus)
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAutoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false)
    }

    override fun onResume() {
        super.onResume()
        // Android 14+ (targetSdk 34)：context-registered receiver 必須指定 export flag，
        // 否則 SecurityException 直接崩潰（小米 Pad Mini / Android 15 上閃退的主因）。
        // ContextCompat.registerReceiver 於 API < 33 忽略 flag、API ≥ 33 套用 NOT_EXPORTED，
        // 單一呼叫涵蓋全版本，避免三參數 overload（API 33+）在舊裝置 NoSuchMethodError。
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(TunSocksService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateStatus(TunSocksService.lastStatus)
        refreshKillSwitch()
        selfUpdater?.onResume()
        wifiWaker?.onResume()
        // 從 App 清單頁回來後，勾選清單可能已變動（例如取消了 Pro）→ 重算開關狀態。
        // 也一併重探 Pro 是否已安裝／被解除安裝。
        syncCoexistSwitch()
        refreshSummary()

        // 開機「閃一下」：若在背景停留期間隧道已連上，回到前台時立即關閉
        if (autoFinishPending && TunSocksService.isActive) {
            autoFinishPending = false
            finish()
            return
        }

        if (pendingAutoStart) {
            pendingAutoStart = false
            if (!TunSocksService.isActive) {
                Toast.makeText(this, R.string.toast_starting, Toast.LENGTH_SHORT).show()
                attemptStart()
                autoFinishPending = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        wifiWaker?.onPause()
    }

    // ── UI 樣式工具（只用內建元件 + GradientDrawable，不引入 Material）──
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 依主題文字顏色判斷深淺色，避免 OEM 的 DeviceDefault 是淺色時卡片配色錯誤。 */
    private val isDark: Boolean by lazy {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, Color.WHITE)
        a.recycle()
        (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 > 128
    }
    private val cPageBg get() = if (isDark) 0xFF111216.toInt() else 0xFFF2F2F7.toInt()
    private val cCardBg get() = if (isDark) 0xFF1E1F24.toInt() else 0xFFFFFFFF.toInt()
    private val cFieldBg get() = if (isDark) 0xFF26272D.toInt() else 0xFFF7F7FA.toInt()
    private val cStroke get() = if (isDark) 0xFF3A3B43.toInt() else 0xFFD8D8DE.toInt()
    private val cText get() = if (isDark) 0xFFF5F5F7.toInt() else 0xFF1C1C1E.toInt()
    private val cTextSecondary get() = if (isDark) 0xFF9A9AA2.toInt() else 0xFF6C6C72.toInt()
    private val cAccent = 0xFF0A84FF.toInt()
    private val cGreen = 0xFF30D158.toInt()
    private val cYellow = 0xFFFFC300.toInt()
    private val cRed = 0xFFFF453A.toInt()
    private val cGray = 0xFF8E8E93.toInt()
    private val cWaiting = 0xFF64D2FF.toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun lp(topDp: Int = 0, bottomDp: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(topDp); bottomMargin = dp(bottomDp)
        }

    private fun card(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCardBg, 16)
        setPadding(dp(16), dp(10), dp(16), dp(14))
        layoutParams = lp(bottomDp = 4)
        children.forEach { addView(it) }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 13f
        letterSpacing = 0.04f
        setTextColor(cTextSecondary)
        setPadding(dp(4), dp(18), dp(4), dp(6))
    }

    private fun styleField(et: EditText) {
        et.background = rounded(cFieldBg, 10, cStroke)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.textSize = 16f
        et.setTextColor(cText)
        et.setHintTextColor(cTextSecondary)
        et.layoutParams = lp(topDp = 8)
    }

    private fun styleSecondaryButton(b: Button) {
        b.background = rounded(cFieldBg, 10)
        b.isAllCaps = false
        b.stateListAnimator = null
        b.textSize = 15f
        b.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(cGray, cAccent)
        ))
        if (b.layoutParams == null) b.layoutParams = lp(topDp = 8)
    }

    private fun styleCompound(v: TextView) {
        v.setTextColor(cText)
        v.textSize = 15f
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        etHost = EditText(this).apply { hint = getString(R.string.hint_host); isSingleLine = true }
        etPort = EditText(this).apply {
            hint = getString(R.string.hint_port)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        etDns1 = EditText(this).apply { hint = getString(R.string.hint_dns1); isSingleLine = true }
        etDns2 = EditText(this).apply { hint = getString(R.string.hint_dns2); isSingleLine = true }
        etUser = EditText(this).apply { hint = getString(R.string.hint_user); isSingleLine = true }
        etPass = EditText(this).apply {
            hint = getString(R.string.hint_pass)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            // 密碼欄位預設等寬字型，改回一般字型與其他欄位一致
            typeface = Typeface.DEFAULT
        }
        etProfileName = EditText(this).apply { hint = getString(R.string.hint_profile_name); isSingleLine = true }
        listOf(etHost, etPort, etDns1, etDns2, etUser, etPass, etProfileName).forEach { styleField(it) }

        // ── 狀態卡：圓點 + 大字狀態 + 小字詳情 ──
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cGray) }
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(14) }
        }
        tvStatusTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        }
        tvStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(cTextSecondary)
            setPadding(0, dp(2), 0, 0)
        }
        val statusTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvStatusTitle)
            addView(tvStatus)
        }
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cCardBg, 16)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = lp()
            addView(statusDot)
            addView(statusTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ── 主要按鈕：未連線藍色「啟動」，執行中紅色「停止」──
        btnToggle = Button(this).apply {
            text = getString(R.string.btn_start_tunnel)
            isAllCaps = false
            stateListAnimator = null
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(cAccent, 14)
            layoutParams = lp(topDp = 12, bottomDp = 4, height = dp(56))
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (TunSocksService.isActive) {
                    startService(Config.stopIntent(this@MainActivity))
                } else {
                    attemptStart()
                }
            }
        }
        btnSelectApps = Button(this).apply {
            text = getString(R.string.btn_select_apps)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppListActivity::class.java))
            }
        }

        cbUdpInTcp = CheckBox(this).apply { text = getString(R.string.label_udp_in_tcp) }
        cbRemoteDns = CheckBox(this).apply { text = getString(R.string.label_remote_dns) }
        cbAutoStart = CheckBox(this).apply { text = getString(R.string.label_auto_start) }
        tvKillSwitch = TextView(this).apply {
            textSize = 14f
            setTextColor(cTextSecondary)
            setPadding(dp(4), dp(10), 0, 0)
        }
        btnKillSwitch = Button(this).apply {
            text = getString(R.string.btn_vpn_settings)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            }
        }
        btnBattery = Button(this).apply {
            text = getString(R.string.btn_battery_optimization)
            setOnClickListener { onBatteryClick() }
        }

        // 隧道模式
        modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        fun modeBtn(text: String, id: Int) = RadioButton(this).apply {
            this.text = text
            id.let { setId(it) }
            styleSegment(this)
        }
        listOf(
            R.string.seg_mode_global to Config.MODE_GLOBAL,
            R.string.seg_mode_allowlist to Config.MODE_ALLOWLIST,
            R.string.seg_mode_exclude to Config.MODE_EXCLUDE
        ).forEachIndexed { i, (res, id) ->
            modeGroup.addView(modeBtn(getString(res), id), RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }

        // 同機共存開關（僅在偵測到 5G Proxy Pro 時顯示）。
        // 它是 (隧道模式, 勾選清單) 的衍生值 —— 開＝排除模式且已勾選 Pro，不持有自己的狀態。
        // 因此把它打開會連帶把模式切到「排除勾選的 App」：電台按鈕會同步跳過去，是可見行為。
        cbCoexist = CheckBox(this).apply {
            text = getString(R.string.label_coexist)
            setPadding(paddingLeft, dp(10), paddingRight, 0)
        }
        tvCoexist = TextView(this).apply {
            textSize = 12f
            setTextColor(cTextSecondary)
            setPadding(dp(4), 0, 0, dp(4))
        }
        listOf(cbUdpInTcp, cbRemoteDns, cbAutoStart, cbCoexist).forEach {
            styleCompound(it)
            it.setPadding(it.paddingLeft, dp(8), it.paddingRight, dp(8))
        }

        // 設定檔
        spinnerProfile = Spinner(this).apply { layoutParams = lp(topDp = 8) }
        btnSaveProfile = Button(this).apply { text = getString(R.string.btn_save_profile) }
        btnDeleteProfile = Button(this).apply { text = getString(R.string.btn_delete_profile) }
        btnExportProfiles = Button(this).apply { text = getString(R.string.btn_export_profiles) }
        btnImportProfiles = Button(this).apply { text = getString(R.string.btn_import_profiles) }
        fun pairRow(a: Button, b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(topDp = 8)
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        }
        listOf(btnSaveProfile, btnDeleteProfile, btnExportProfiles, btnImportProfiles).forEach { styleSecondaryButton(it) }
        listOf(btnSelectApps, btnBattery).forEach { styleSecondaryButton(it) }
        // 設定檔：只留下拉選單，儲存／刪除／匯出／匯入收進旁邊的「⋯」選單。
        // 原本的按鈕保留（不顯示）當動作入口，選單直接 performClick，邏輯不變。
        btnProfileMenu = TextView(this).apply {
            text = "⋯"
            textSize = 22f
            setTextColor(cAccent)
            gravity = Gravity.CENTER
            background = rounded(cFieldBg, 10)
            setOnClickListener { showProfileMenu(it) }
        }
        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(topDp = 8)
            (spinnerProfile.parent as? ViewGroup)?.removeView(spinnerProfile)
            addView(spinnerProfile, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(btnProfileMenu, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        }
        // 斷線保護：一行說明＋「去設定 ›」
        btnKillSwitch.visibility = View.GONE
        val ksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(topDp = 4)
            (tvKillSwitch.parent as? ViewGroup)?.removeView(tvKillSwitch)
            tvKillSwitch.setPadding(dp(4), 0, dp(8), 0)
            addView(tvKillSwitch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(linkText(getString(R.string.link_go_settings)) {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            })
        }

        // ── 面板內容 ──
        val connectBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(getString(R.string.label_server)).apply { setPadding(dp(4), dp(4), dp(4), dp(0)) })
            addView(etHost); addView(etPort)
            addView(sectionTitle(getString(R.string.label_mode)))
            addView(modeGroup, lp(topDp = 2, height = dp(44)))
            addView(btnSelectApps)
            addView(cbCoexist); addView(tvCoexist)
        }
        val advancedBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(getString(R.string.label_other)).apply { setPadding(dp(4), dp(4), dp(4), dp(0)) })
            addView(cbRemoteDns); addView(cbUdpInTcp); addView(cbAutoStart)
            addView(ksRow)
            addView(btnBattery)
            addView(sectionTitle(getString(R.string.label_dns)))
            addView(etDns1); addView(etDns2)
            addView(sectionTitle(getString(R.string.label_auth)))
            addView(etUser); addView(etPass)
            addView(sectionTitle(getString(R.string.label_profiles)))
            addView(profileRow)
        }
        val connectPanel = makePanel(R.string.label_connect_settings, connectBody)
        val advancedPanel = makePanel(R.string.label_advanced, advancedBody)

        // ── 連線摘要卡：會連到哪裡、什麼模式；點一下打開連線設定 ──
        tvSummaryServer = TextView(this).apply {
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            setTextColor(cText)
            setPadding(0, dp(2), 0, 0)
        }
        tvSummaryVia = TextView(this).apply { textSize = 13f; setTextColor(cTextSecondary) }
        tvSummaryMode = TextView(this).apply {
            textSize = 15f
            setTextColor(cText)
            setPadding(0, dp(10), 0, 0)
        }
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCardBg, 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(topDp = 12)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.label_server)
                    textSize = 13f
                    setTextColor(cTextSecondary)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.link_edit)
                    textSize = 14f
                    setTextColor(cAccent)
                })
            })
            addView(tvSummaryServer)
            addView(tvSummaryVia)
            addView(tvSummaryMode)
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                connectPanel.show()
            }
        }

        // 狀態卡下方的「下一步」連結（連不上時才出現）
        tvStatusAction = linkText(getString(R.string.link_wifi_settings)) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }.apply { visibility = View.GONE; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0) }
        statusTexts.addView(tvStatusAction)
        if (WifiWaker.enabledFor(packageName)) {
            val w = WifiWaker(this, REQ_WIFI_SCAN_PERMISSION)
            wifiWaker = w
            statusTexts.addView(w.view { t, onClick -> linkText(t, onClick) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
            })
        }

        fun entryButton(titleRes: Int, panel: android.app.Dialog) = TextView(this).apply {
            text = getString(titleRes) + "  ›"
            textSize = 15f
            setTextColor(cAccent)
            gravity = Gravity.CENTER
            background = rounded(cCardBg, 12)
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                panel.show()
            }
        }
        val entryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(entryButton(R.string.label_connect_settings, connectPanel),
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
            addView(entryButton(R.string.label_advanced, advancedPanel),
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        }
        val appLabel = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(dp(4), dp(4), 0, dp(8))
        }
        btnToggle.layoutParams = lp(topDp = 12, height = dp(56))
        // 按下微縮，放開彈回；點擊時輕震
        btnToggle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }
            false
        }

        // 版面（固定一頁）：App 名稱 → 狀態卡 → 連線摘要 →（空白）→ [連線設定][進階設定] → 主按鈕
        actionBar?.hide()
        root.setPadding(dp(16), dp(8), dp(16), dp(16))
        root.addView(appLabel)
        root.addView(statusCard)
        root.addView(summaryCard)
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(entryRow, lp(topDp = 12, height = dp(44)))
        root.addView(btnToggle)
        // GW 版才有：App 內更新（SelfUpdate.kt）
        if (SelfUpdate.enabledFor(packageName)) {
            val u = SelfUpdater(this)
            selfUpdater = u
            root.addView(u.view(cTextSecondary, cAccent, dp(40)), lp(topDp = 4))
        }

        // 保底：字體很大放不下時才能捲
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(cPageBg)
            addView(root)
        }
        setContentView(scroll)

        // targetSdk 35 強制 edge-to-edge，把系統列 insets 加進 ScrollView padding，避免內容被狀態列/導覽列遮住
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        listOf(connectPanel, advancedPanel).forEach { it.setOnDismissListener { refreshSummary() } }

        val prefs = Config.prefs(this)
        etHost.setText(prefs.getString(Config.KEY_HOST, "gateway"))
        etPort.setText(prefs.getString(Config.KEY_PORT, Config.DEFAULT_PORT.toString()))
        etUser.setText(Config.getSecret(this, Config.KEY_USER))
        etPass.setText(Config.getSecret(this, Config.KEY_PASS))
        etDns1.setText(prefs.getString(Config.KEY_DNS1, Config.DEFAULT_DNS1))
        etDns2.setText(prefs.getString(Config.KEY_DNS2, Config.DEFAULT_DNS2))
        cbUdpInTcp.isChecked = prefs.getBoolean(Config.KEY_UDP_IN_TCP, false)
        cbRemoteDns.isChecked = prefs.getBoolean(Config.KEY_REMOTE_DNS, Config.DEFAULT_REMOTE_DNS)
        cbAutoStart.isChecked = prefs.getBoolean(Config.KEY_AUTO_START, false)
        // 勾選/取消當下立即存檔，確保重開機使用最新設定（不必等到按下「啟動」）
        cbAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Config.KEY_AUTO_START, checked).apply()
        }

        // 同機共存預設**不開啟**：不代使用者動模式或勾選清單，開關只反映現況。
        modeGroup.check(prefs.getInt(Config.KEY_MODE, Config.MODE_GLOBAL))

        // 模式一改就立即落地。原本只在按下「啟動」時才寫 KEY_MODE，導致「先改模式、
        // 未按啟動就進 App 清單頁」時，該頁讀到的是舊模式（提示文字與實際不符）。
        modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putInt(Config.KEY_MODE, id).apply()
            // 切到「指定 App」時把 Pro 擋在白名單外 —— Pro 不得進隧道（見 Coexist.sanitize）
            enforceCoexistInvariant()
            syncCoexistSwitch()
            refreshSummary()
        }

        cbCoexist.setOnCheckedChangeListener { _, checked ->
            if (suppressCoexistWrite) return@setOnCheckedChangeListener
            val cur = prefs.getInt(Config.KEY_MODE, Config.MODE_GLOBAL)
            val sel = currentSelectedApps()
            val next = if (checked) Coexist.turnOn(cur, sel) else Coexist.turnOff(cur, sel)
            applyCoexistState(next)
            // 模式可能被 turnOn 改成排除 → 電台按鈕同步跳過去（可見，不是隱藏行為）
            if (modeGroup.checkedRadioButtonId != next.mode) modeGroup.check(next.mode)
            syncCoexistSwitch()
        }

        syncCoexistSwitch()

        setupProfileUi()

        refreshProfileSpinner()
        refreshSummary()
    }

    // ── GW 首頁一頁：摘要卡、面板、連結 ──
    private var selfUpdater: SelfUpdater? = null
    private lateinit var tvSummaryServer: TextView
    private lateinit var tvSummaryVia: TextView
    private lateinit var tvSummaryMode: TextView
    private lateinit var tvStatusAction: TextView
    private var wifiWaker: WifiWaker? = null
    private lateinit var btnProfileMenu: TextView

    private fun linkText(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(cAccent)
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(8), 0, 0, 0)
        setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    /** 模式切換鈕：一排三格，選中的格子填主色。 */
    private fun styleSegment(rb: RadioButton) {
        rb.buttonDrawable = null
        rb.gravity = Gravity.CENTER
        rb.textSize = 14f
        rb.setPadding(dp(4), 0, dp(4), 0)
        rb.maxLines = 1
        rb.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rounded(cAccent, 10))
            addState(intArrayOf(), rounded(cFieldBg, 10))
        }
        rb.setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.WHITE, cGray, cText)
        ))
    }

    /** 從下往上滑出的面板：背景變暗、點外面、按完成或往下拉標題就收起。 */
    private fun makePanel(titleRes: Int, body: View): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCardBg)
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@MainActivity).apply { background = rounded(cStroke, 3) },
                LinearLayout.LayoutParams(dp(36), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = getString(titleRes)
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(cText)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(linkText(getString(R.string.btn_done)) { dialog.dismiss() }.apply {
                    textSize = 16f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
            })
        }
        var downY = 0f
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> { sheet.translationY = (e.rawY - downY).coerceAtLeast(0f); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > dp(80)) dialog.dismiss()
                    else sheet.animate().translationY(0f).setDuration(200).start()
                    true
                }
                else -> false
            }
        }
        sheet.addView(header)
        sheet.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener { sheet.translationY = 0f }
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }

    private fun showProfileMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.menu_profile_save)
        menu.menu.add(0, 2, 1, R.string.menu_profile_delete)
        menu.menu.add(0, 3, 2, R.string.menu_profile_export)
        menu.menu.add(0, 4, 3, R.string.menu_profile_import)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val input = EditText(this).apply {
                        hint = getString(R.string.hint_profile_name)
                        setText(etProfileName.text)
                        isSingleLine = true
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.menu_profile_save)
                        .setView(LinearLayout(this).apply {
                            setPadding(dp(20), dp(8), dp(20), 0)
                            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                        })
                        .setPositiveButton(R.string.btn_save) { _, _ ->
                            etProfileName.setText(input.text.toString().trim())
                            btnSaveProfile.performClick()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                2 -> {
                    val name = etProfileName.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.toast_profile_pick_first, Toast.LENGTH_SHORT).show()
                    } else {
                        android.app.AlertDialog.Builder(this)
                            .setMessage(getString(R.string.confirm_delete_profile, name))
                            .setPositiveButton(R.string.btn_delete) { _, _ -> btnDeleteProfile.performClick() }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
                3 -> btnExportProfiles.performClick()
                4 -> btnImportProfiles.performClick()
            }
            true
        }
        menu.show()
    }

    /** 連線摘要：伺服器（gateway 時順便顯示實際閘道 IP）與隧道模式。 */
    private fun refreshSummary() {
        if (!::tvSummaryServer.isInitialized) return
        val p = prefs()
        val host = etHost.text.toString().trim().ifEmpty { p.getString(Config.KEY_HOST, "gateway") ?: "gateway" }
        val port = etPort.text.toString().trim().toIntOrNull() ?: Config.DEFAULT_PORT
        val ip = if (GatewayResolver.isAuto(host)) {
            TunSocksService.currentServerIp ?: try { GatewayResolver.wifiGatewayV4(this) } catch (e: Exception) { null }
        } else host
        if (GatewayResolver.isAuto(host)) {
            tvSummaryServer.text = if (ip != null) "$ip:$port" else getString(R.string.summary_no_gateway)
            tvSummaryVia.text = getString(R.string.summary_via_gateway)
            GatewayResolver.wifiLocalV4(this)?.let { me -> tvSummaryVia.append(getString(R.string.summary_my_ip, me)) }
        } else {
            tvSummaryServer.text = "$host:$port"
            tvSummaryVia.text = getString(R.string.summary_fixed_ip)
            GatewayResolver.wifiLocalV4(this)?.let { me -> tvSummaryVia.append(getString(R.string.summary_my_ip, me)) }
        }
        val n = currentSelectedApps().size
        tvSummaryMode.text = when (p.getInt(Config.KEY_MODE, Config.MODE_GLOBAL)) {
            Config.MODE_ALLOWLIST -> getString(R.string.summary_mode_allow, n)
            Config.MODE_EXCLUDE -> getString(R.string.summary_mode_exclude, n)
            else -> getString(R.string.summary_mode_global)
        }
        btnSelectApps.text = getString(R.string.btn_select_apps_count, n)
        btnSelectApps.visibility =
            if (p.getInt(Config.KEY_MODE, Config.MODE_GLOBAL) == Config.MODE_GLOBAL) View.GONE else View.VISIBLE
    }

    /** 連不上時給下一步：實際連的位址＋「是不是沒連到 Pro 的熱點？」。 */
    private fun unreachableHint(): String {
        val port = etPort.text.toString().trim().toIntOrNull() ?: Config.DEFAULT_PORT
        val host = etHost.text.toString().trim()
        val ip = TunSocksService.currentServerIp
            ?: if (GatewayResolver.isAuto(host)) try { GatewayResolver.wifiGatewayV4(this) } catch (e: Exception) { null } else host
        return getString(R.string.status_hint_unreachable, if (ip != null) "$ip:$port" else host)
    }

    private fun currentSelectedApps(): Set<String> =
        prefs().getStringSet(AppListActivity.KEY_APPS, emptySet()) ?: emptySet()

    /** 模式與勾選清單必須同時寫入，否則中途讀取會看到「新模式 + 舊清單」的不一致組合。 */
    private fun applyCoexistState(state: Coexist.State) {
        prefs().edit()
            .putInt(Config.KEY_MODE, state.mode)
            .putStringSet(AppListActivity.KEY_APPS, state.selected)
            .apply()
    }

    /** 5G Proxy Pro 是否已安裝（本 App 具備 QUERY_ALL_PACKAGES，看得到）。 */
    private fun isProInstalled(): Boolean = try {
        packageManager.getPackageInfo(Coexist.PRO_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 把「同機共存」開關與其說明同步成 (隧道模式, 勾選清單) 的衍生結果。
     *
     * 開關只反映狀態、不持有狀態：在 App 清單頁把 Pro 取消勾選、或在此切換模式，
     * 下一次同步就會反映出來 —— 所以不存在「開關說開了、清單卻沒勾」的不一致。
     * Pro 未安裝時整個區塊隱藏（這個概念不適用）。
     */
    /** 把「Pro 不得進隧道」的不變式套用到勾選清單（只有指定 App 模式會實際變動）。 */
    private fun enforceCoexistInvariant() {
        val mode = prefs().getInt(Config.KEY_MODE, Config.MODE_GLOBAL)
        val cur = currentSelectedApps()
        val fixed = Coexist.sanitize(mode, cur)
        if (fixed != cur) prefs().edit().putStringSet(AppListActivity.KEY_APPS, fixed).apply()
    }

    /** 同機共存開關可否操作：隧道未執行（設定已凍結），且該模式允許關閉。 */
    private fun coexistSwitchEnabled(): Boolean =
        !TunSocksService.isRunning &&
            !Coexist.isLocked(prefs().getInt(Config.KEY_MODE, Config.MODE_GLOBAL))

    private fun syncCoexistSwitch() {
        if (!::cbCoexist.isInitialized) return
        val installed = isProInstalled()
        cbCoexist.visibility = if (installed) View.VISIBLE else View.GONE
        tvCoexist.visibility = cbCoexist.visibility
        if (!installed) return

        val mode = prefs().getInt(Config.KEY_MODE, Config.MODE_GLOBAL)
        val on = Coexist.isOn(mode, currentSelectedApps())
        cbCoexist.isEnabled = coexistSwitchEnabled()
        if (cbCoexist.isChecked != on) {
            suppressCoexistWrite = true
            cbCoexist.isChecked = on
            suppressCoexistWrite = false
        }
        tvCoexist.text = getString(
            when {
                // 指定 App 模式下共存自動成立且不可關閉 → 顯示為已鎖定
                on && Coexist.isLocked(mode) -> R.string.coexist_locked
                on -> R.string.coexist_on
                mode == Config.MODE_EXCLUDE -> R.string.coexist_off_exclude
                else -> R.string.coexist_off_other_mode
            }
        )
    }

    private fun attemptStart() {
        try {
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull()
            // 保留 port == null 判斷以觸發智慧轉型；isServerValid 為分離函式，無法 smart-cast port 為非空
            if (port == null || !ConfigValidator.isServerValid(host, port)) {
                Toast.makeText(this, getString(R.string.toast_invalid_input), Toast.LENGTH_SHORT).show()
                return
            }
            val dns1 = etDns1.text.toString().trim()
            val dns2 = etDns2.text.toString().trim()
            if (!ConfigValidator.isDnsValid(dns1)) {
                Toast.makeText(this, getString(R.string.err_invalid_dns, dns1), Toast.LENGTH_LONG).show()
                return
            }
            if (!ConfigValidator.isDnsValid(dns2)) {
                Toast.makeText(this, getString(R.string.err_invalid_dns, dns2), Toast.LENGTH_LONG).show()
                return
            }

            prefs().edit()
                .putString(Config.KEY_HOST, host)
                .putString(Config.KEY_PORT, etPort.text.toString().trim())
                .putString(Config.KEY_DNS1, dns1)
                .putString(Config.KEY_DNS2, dns2)
                .putBoolean(Config.KEY_UDP_IN_TCP, cbUdpInTcp.isChecked)
                .putBoolean(Config.KEY_REMOTE_DNS, cbRemoteDns.isChecked)
                .putBoolean(Config.KEY_AUTO_START, cbAutoStart.isChecked)
                .putInt(Config.KEY_MODE, modeGroup.checkedRadioButtonId)
                .apply()
            Config.putSecret(this, Config.KEY_USER, etUser.text.toString().trim())
            Config.putSecret(this, Config.KEY_PASS, etPass.text.toString().trim())

            // 僅允許模式尚未勾選任何 App 時直接擋下，避免啟動後隧道不涵蓋任何 App
            val selectedApps =
                Config.prefs(this).getStringSet(AppListActivity.KEY_APPS, emptySet()) ?: emptySet()
            if (PerAppMode.isEmptyAllowlist(Config.mode(this), selectedApps)) {
                Toast.makeText(this, R.string.toast_allowlist_empty, Toast.LENGTH_LONG).show()
                return
            }

            Toast.makeText(this, getString(R.string.toast_starting), Toast.LENGTH_SHORT).show()
            android.util.Log.i("MainActivity", "Start button pressed, preparing VPN")

            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                Toast.makeText(this, getString(R.string.toast_allow_vpn), Toast.LENGTH_LONG).show()
                startActivityForResult(vpnIntent, REQ_VPN_PERMISSION)
            } else {
                startTunnel(host, port)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Start click error", e)
            DebugLog.recordError("點擊啟動時錯誤：${e.message}")
            Toast.makeText(this, getString(R.string.toast_start_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                val port = etPort.text.toString().trim().toIntOrNull() ?: Config.DEFAULT_PORT
                startTunnel(etHost.text.toString().trim(), port)
            } else {
                Toast.makeText(this, getString(R.string.toast_vpn_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        wifiWaker?.onPermissionResult(requestCode)
        if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, R.string.toast_notification_permission, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startTunnel(host: String, port: Int) {
        val intent = Intent(this, TunSocksService::class.java).apply {
            action = TunSocksService.ACTION_START
            putExtra(TunSocksService.EXTRA_HOST, host)
            putExtra(TunSocksService.EXTRA_PORT, port)
            putExtra(TunSocksService.EXTRA_USER, etUser.text.toString().trim())
            putExtra(TunSocksService.EXTRA_PASS, etPass.text.toString().trim())
            putExtra(TunSocksService.EXTRA_UDP_IN_TCP, cbUdpInTcp.isChecked)
            putExtra(TunSocksService.EXTRA_REMOTE_DNS, cbRemoteDns.isChecked)
        }
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "startForegroundService error", e)
            DebugLog.recordError(getString(R.string.toast_service_start_failed, e.message))
            Toast.makeText(this, getString(R.string.toast_service_start_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun setupProfileUi() {
        spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == 0) return  // 佔位項目（載入設定檔…）
                val name = profileNames.getOrNull(position - 1) ?: return
                Profiles.find(this@MainActivity, name)?.let { p ->
                    etHost.setText(p.host)
                    etPort.setText(p.port)
                    etUser.setText(p.user)
                    etPass.setText(p.pass)
                    etDns1.setText(p.dns1)
                    etDns2.setText(p.dns2)
                    cbUdpInTcp.isChecked = p.udpInTcp
                    cbRemoteDns.isChecked = p.remoteDns
                    modeGroup.check(p.mode)
                    etProfileName.setText(p.name)
                    // 把此設定檔的 per-app 選擇與模式寫回 prefs，使下次啟動反映完整設定
                    Config.prefs(this@MainActivity).edit()
                        .putStringSet(AppListActivity.KEY_APPS, p.apps)
                        .putInt(Config.KEY_MODE, p.mode)
                        .apply()
                    // 設定檔可能把模式與勾選清單一起換掉 → 先套用不變式，再重算開關
                    enforceCoexistInvariant()
                    syncCoexistSwitch()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnSaveProfile.setOnClickListener {
            val name = etProfileName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.toast_profile_name_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Profiles.save(this, currentProfile(name))
            Toast.makeText(this, R.string.toast_profile_saved, Toast.LENGTH_SHORT).show()
            refreshProfileSpinner()
        }

        btnDeleteProfile.setOnClickListener {
            val name = etProfileName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.toast_profile_name_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Profiles.delete(this, name)
            Toast.makeText(this, R.string.toast_profile_deleted, Toast.LENGTH_SHORT).show()
            refreshProfileSpinner()
        }

        btnExportProfiles.setOnClickListener {
            val list = Profiles.load(this)
            if (list.isEmpty()) {
                Toast.makeText(this, R.string.toast_no_profiles, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val arr = org.json.JSONArray()
            list.forEach { arr.put(it.toExportJson()) }
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("profiles", arr.toString()))
            Toast.makeText(this, R.string.toast_profiles_exported_no_secrets, Toast.LENGTH_SHORT).show()
        }

        btnImportProfiles.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = try {
                cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            } catch (e: Exception) {
                null
            }
            if (text.isNullOrBlank()) {
                Toast.makeText(this, R.string.toast_profiles_invalid, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            var imported = 0
            try {
                if (text.startsWith("[")) {
                    val arr = org.json.JSONArray(text)
                    for (i in 0 until arr.length()) {
                        try {
                            Profiles.save(this, Profile.fromJson(arr.getJSONObject(i)))
                            imported++
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "import profile[$i] failed: ${e.message}")
                        }
                    }
                } else if (text.startsWith("{")) {
                    Profiles.save(this, Profile.fromJson(org.json.JSONObject(text)))
                    imported++
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "import profiles parse failed: ${e.message}")
            }
            if (imported > 0) {
                refreshProfileSpinner()
                Toast.makeText(this, getString(R.string.toast_profiles_imported, imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_profiles_invalid, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun currentProfile(name: String): Profile = Profile(
        name = name,
        host = etHost.text.toString().trim(),
        port = etPort.text.toString().trim(),
        user = etUser.text.toString().trim(),
        pass = etPass.text.toString().trim(),
        udpInTcp = cbUdpInTcp.isChecked,
        remoteDns = cbRemoteDns.isChecked,
        dns1 = etDns1.text.toString().trim(),
        dns2 = etDns2.text.toString().trim(),
        mode = modeGroup.checkedRadioButtonId,
        apps = Config.prefs(this).getStringSet(AppListActivity.KEY_APPS, emptySet()) ?: emptySet()
    )

    private fun refreshProfileSpinner() {
        profileNames.clear()
        profileNames.addAll(Profiles.names(this))
        val display = mutableListOf(getString(R.string.btn_load_profile))
        display.addAll(profileNames)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, display).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerProfile.adapter = adapter
        spinnerProfile.setSelection(0)
    }

    private fun prefs() = Config.prefs(this)

    // 讀取系統 Always-on VPN / 封鎖無 VPN 設定，顯示斷線保護三態。
    // Android 12+ 起 always_on_vpn_app / always_on_vpn_lockdown 為 @hide 設定鍵，
    // 第三方 App 直接讀取會拋 SecurityException；隧道執行中改用服務端以 VpnService
    // 公開 API（isAlwaysOn / isLockdownEnabled）快取的值，未執行時才退回讀系統設定。
    private fun refreshKillSwitch() {
        val statusRes = if (TunSocksService.isRunning) {
            when (KillSwitch.statusFromServiceFlags(TunSocksService.lastLockdown, TunSocksService.lastAlwaysOn)) {
                KillSwitchStatus.NONE -> R.string.ks_status_none
                KillSwitchStatus.ALWAYS_ON -> R.string.ks_status_always_on
                KillSwitchStatus.LOCKDOWN -> R.string.ks_status_locked
            }
        } else {
            try {
                val pkg = Settings.Secure.getString(contentResolver, "always_on_vpn_app")
                val locked = Settings.Secure.getString(contentResolver, "always_on_vpn_lockdown") == "1"
                when (KillSwitch.status(packageName, pkg, locked)) {
                    KillSwitchStatus.NONE -> R.string.ks_status_none
                    KillSwitchStatus.ALWAYS_ON -> R.string.ks_status_always_on
                    KillSwitchStatus.LOCKDOWN -> R.string.ks_status_locked
                }
            } catch (e: Exception) {
                R.string.ks_status_unreadable
            }
        }
        tvKillSwitch.text = getString(R.string.label_kill_switch_status, getString(statusRes))
    }

    // 引導使用者將本 App 加入電池最佳化白名單，降低常駐 VPN 前台服務被 OEM 省電機制
    // 在背景悄悄終止的風險。此 App 以 F-Droid 發布，不受 Google Play 電池權限審核限制，
    // 故刻意使用 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（lint 的 BatteryLife 警告在此情境適用）。
    @SuppressLint("BatteryLife")
    private fun onBatteryClick() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.toast_battery_already, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "request ignore battery optimizations failed", e)
            Toast.makeText(this, getString(R.string.toast_battery_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION_PERMISSION)
        }
    }

    private fun updateStatus(text: String?) {
        val raw = text ?: ""
        val running = TunSocksService.isActive
        val waitingPro = TunSocksService.isWaitingPro
        // 服務送出的字串以 ✅／⚠️ 開頭區分成功與錯誤，這裡只拿來分類，不改服務端的訊息。
        val detail = raw.removePrefix("✅").removePrefix("⚠️").trim()
        val idleTexts = setOf("", getString(R.string.status_not_started), getString(R.string.status_stopped))
        val (titleRes, dotColor, shownDetail) = when {
            waitingPro -> Triple(R.string.status_title_waiting_pro, cWaiting, getString(R.string.status_waiting_pro_detail))
            running && raw.startsWith("✅") -> Triple(R.string.status_title_connected, cGreen, connectedDetail(detail))
            running && raw.contains("⚠️") -> Triple(R.string.status_title_error, cRed, detail)
            running -> Triple(R.string.status_title_connecting, cYellow, detail)
            raw in idleTexts && startupNotice != null ->
                Triple(R.string.status_title_error, cRed, startupNotice!!.removePrefix("⚠️").trim())
            raw in idleTexts -> Triple(R.string.status_title_idle, cGray, detail)
            else -> Triple(R.string.status_title_error, cRed, detail)
        }
        if (running) startupNotice = null
        // 伺服器沒回應：說清楚連的是哪裡、下一步怎麼做
        val reconnectPrefix = getString(R.string.status_auto_reconnect, 0).substringBefore("0").trim()
        val unreachable = running && titleRes == R.string.status_title_connecting && raw.startsWith(reconnectPrefix)
        val finalDetail = if (unreachable) unreachableHint() else shownDetail
        if (::tvStatusAction.isInitialized) tvStatusAction.visibility = if (unreachable || waitingPro) View.VISIBLE else View.GONE
        wifiWaker?.setWaiting(waitingPro)
        tvStatusTitle.text = getString(titleRes)
        tvStatusTitle.setTextColor(if (dotColor == cRed) cRed else cText)
        tvStatus.text = finalDetail
        tvStatus.visibility = if (finalDetail.isBlank()) View.GONE else View.VISIBLE
        (statusDot.background as GradientDrawable).setColor(dotColor)
        // 啟動/停止共用單一按鈕：執行中紅色「停止隧道」，停止時藍色「啟動隧道」
        btnToggle.text = getString(if (running) R.string.btn_stop_tunnel else R.string.btn_start_tunnel)
        btnToggle.background = rounded(if (running) cRed else cAccent, 14)
        setConfigFieldsEnabled(!running)
        refreshSummary()
    }

    /** 已連線時顯示實際 IP:埠；伺服器填 gateway/auto 時註明是 Wi-Fi 閘道。 */
    private fun connectedDetail(fallback: String): String {
        val ip = TunSocksService.currentServerIp ?: return fallback
        val p = Config.prefs(this)
        val host = p.getString(Config.KEY_HOST, "gateway") ?: ""
        val port = p.getString(Config.KEY_PORT, Config.DEFAULT_PORT.toString())?.toIntOrNull() ?: Config.DEFAULT_PORT
        return if (GatewayResolver.isAuto(host)) getString(R.string.status_detail_gateway, ip, port)
        else getString(R.string.status_detail_server, ip, port)
    }

    // 隧道執行中鎖定組態欄位（伺服器/DNS/驗證/模式/App 選擇/連線選項/設定檔）：
    // 這些值在 VpnService.Builder.establish() 時就已固定、改動須等下次啟動才生效，
    // 禁止編輯可避免「改了卻沒用」的困惑。僅保留啟動/停止按鈕與開機自啟可操作。
    private fun setConfigFieldsEnabled(enabled: Boolean) {
        etHost.isEnabled = enabled
        etPort.isEnabled = enabled
        etUser.isEnabled = enabled
        etPass.isEnabled = enabled
        etDns1.isEnabled = enabled
        etDns2.isEnabled = enabled
        cbUdpInTcp.isEnabled = enabled
        cbRemoteDns.isEnabled = enabled
        btnSelectApps.isEnabled = enabled
        // 同機共存開關改的是「模式 + 勾選清單」，同樣在 establish() 時就固定 → 一併鎖住。
        // 指定 App 模式下共存強制成立、不可關閉，那裡也維持鎖定（見 Coexist.isLocked）。
        cbCoexist.isEnabled = coexistSwitchEnabled()
        // RadioGroup 的 isEnabled 不會停用子 RadioButton，需逐一設定
        for (i in 0 until modeGroup.childCount) {
            modeGroup.getChildAt(i).isEnabled = enabled
        }
        etProfileName.isEnabled = enabled
        spinnerProfile.isEnabled = enabled
        btnSaveProfile.isEnabled = enabled
        btnDeleteProfile.isEnabled = enabled
        btnExportProfiles.isEnabled = enabled
        btnImportProfiles.isEnabled = enabled
        if (::btnProfileMenu.isInitialized) {
            btnProfileMenu.isEnabled = enabled
            btnProfileMenu.alpha = if (enabled) 1f else 0.4f
        }
    }
}