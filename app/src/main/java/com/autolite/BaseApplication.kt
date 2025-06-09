package com.autolite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.autolite.utils.LogUtils

class BaseApplication : Application() {
    companion object {
        lateinit var instance: BaseApplication
        lateinit var liteID: String
    }

    private var activityCount = 0

    private val kTag = "BaseApplication"

    override fun onCreate() {
        super.onCreate()
        instance = this
        liteID = getUUID()  // 在应用启动时就初始化

        // 初始化 LogUtils
        LogUtils.initialize(this)
        LogUtils.log(Log.INFO, kTag, "应用启动成功，ID:$liteID")

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                if (activityCount == 1) {
                    // 应用进入前台
                    LogUtils.log(Log.INFO, kTag, "App进入前台")
                }
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount == 0) {
                    // 应用进入后台
                    LogUtils.log(Log.INFO, kTag, "App进入后台")
                    // 如果有 mqttHelper，可以在此断开连接
                    (activity as? MainActivity)?.let { mainActivity ->
                        if (mainActivity.isMqttHelperInitialized()) {
                            mainActivity.mqttHelper.disConnectToMqtt()
                        }
                    }
                }
            }

            // 其他方法可以不实现
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @SuppressLint("HardwareIds")
    private fun getUUID(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID)
    }
}
