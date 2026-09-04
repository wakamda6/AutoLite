package com.autolite.utils

import android.content.Context
import com.autolite.BaseApplication

/**
 * MQTT 账号密码配置。
 * 由用户在页面动态填写并持久化，运行时读取。
 * 留空时回退使用设备 ID（兼容原有按设备 ID 认证的部署）。
 */
object MqttAuthConfig {

    private const val KEY_USERNAME = "mqtt_username"
    private const val KEY_PASSWORD = "mqtt_password"

    private val sp
        get() = BaseApplication.instance.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var username: String
        get() = sp.getString(KEY_USERNAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = sp.getString(KEY_PASSWORD, "") ?: ""
        set(value) = sp.edit().putString(KEY_PASSWORD, value.trim()).apply()
}
