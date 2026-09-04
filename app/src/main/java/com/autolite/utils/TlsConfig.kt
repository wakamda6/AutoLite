package com.autolite.utils

import android.content.Context
import com.autolite.BaseApplication

/**
 * MQTT 连接模式配置。
 * 三种模式：无加密（默认）、单向 TLS、双向 TLS。
 */
object TlsConfig {

    const val MODE_NONE = "none"
    const val MODE_ONE_WAY = "oneway"
    const val MODE_MUTUAL = "mutual"

    private const val KEY_MODE = "tls_mode"
    private const val KEY_PREV_MODE = "tls_prev_mode"

    // 无加密端口
    const val PORT_NONE = 1883
    // 单向 TLS 端口
    const val PORT_ONE_WAY = 8884
    // 双向 TLS 端口
    const val PORT_MUTUAL = 8883

    private val sp
        get() = BaseApplication.instance.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    // 当前连接模式，默认无加密
    var mode: String
        get() = sp.getString(KEY_MODE, MODE_NONE) ?: MODE_NONE
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    // 进入当前模式前的上一个模式（用于退出双向认证时回退）
    var previousMode: String
        get() = sp.getString(KEY_PREV_MODE, MODE_NONE) ?: MODE_NONE
        set(value) = sp.edit().putString(KEY_PREV_MODE, value).apply()

    // 是否使用 TLS（单向/双向）
    val useTls: Boolean
        get() = mode != MODE_NONE

    // 协议前缀 tcp / ssl
    val scheme: String
        get() = if (useTls) "ssl" else "tcp"

    // 当前模式对应的 MQTT 端口
    val mqttPort: Int
        get() = when (mode) {
            MODE_MUTUAL -> PORT_MUTUAL
            MODE_ONE_WAY -> PORT_ONE_WAY
            else -> PORT_NONE
        }

    // 显示名称
    fun modeName(m: String = mode): String = when (m) {
        MODE_MUTUAL -> "双向TLS"
        MODE_ONE_WAY -> "单向TLS"
        else -> "无加密"
    }

    // 风险与依赖说明
    fun modeDescription(m: String = mode): String = when (m) {
        MODE_MUTUAL -> "需：云服务器+broker+域名+自建CA+客户端证书；风险：最低（双向校验）"
        MODE_ONE_WAY -> "需：云服务器+broker+域名+公网证书；风险：低（校验服务器身份）"
        else -> "需：云服务器+broker；风险：账号密码明文传输（不安全）"
    }

    // 切换连接模式：记录上一个模式后更新
    fun switchTo(newMode: String) {
        if (newMode == mode) return
        previousMode = mode
        mode = newMode
    }

    // 退出双向认证：回退到进入双向之前的模式
    fun revertToPrevious() {
        val prev = if (previousMode == MODE_MUTUAL) MODE_NONE else previousMode
        mode = prev
    }

    // 判断字符串是否为 IP 地址（IPv4 或 IPv6）
    fun isIpAddress(address: String): Boolean {
        if (address.isBlank()) return false
        val ipv4 = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        if (ipv4.matches(address)) return true
        // IPv6 简单判断：包含冒号
        return address.contains(":")
    }
}
