package com.tokyoxpa3.socksclient

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * [GW] 跟 Pro 報名：探測時 CONNECT「<ID>.<名字hex>.gw-id.invalid」，
 * Pro GW 認得就記下「這個 IP 是哪台」，原版 Pro 只會當成連線失敗，不影響。
 */
object GwIdentity {
    private const val KEY_ID = "gw_device_id"
    const val SUFFIX = ".gw-id.invalid"
    private const val NAME_MAX_BYTES = 24

    /** 第一次用時產生 8 碼亂數，之後固定。 */
    fun id(ctx: Context): String {
        val sp = Config.prefs(ctx)
        sp.getString(KEY_ID, null)?.takeIf { it.matches(Regex("[0-9a-f]{8}")) }?.let { return it }
        val id = (1..8).joinToString("") { "0123456789abcdef"[java.security.SecureRandom().nextInt(16)].toString() }
        sp.edit().putString(KEY_ID, id).apply()
        return id
    }

    /** 系統設定裡的裝置名稱，沒有就用型號。 */
    fun name(ctx: Context): String =
        try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (e: Exception) { null }
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"

    /** 名字截到 24 bytes（不切壞中文字）再轉 hex。 */
    fun host(id: String, name: String): String {
        var n = name.trim()
        while (n.toByteArray(Charsets.UTF_8).size > NAME_MAX_BYTES) n = n.dropLast(1)
        val hex = n.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return "$id.$hex$SUFFIX"
    }

    /** SOCKS5 CONNECT（ATYP=網域、port 1）。 */
    fun connectRequest(host: String): ByteArray {
        val h = host.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf(0, 1)
    }
}
