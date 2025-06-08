package com.autolite

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.provider.Settings

class BaseApplication : Application() {
    lateinit var liteID: String
        private set

    override fun onCreate() {
        super.onCreate()
        liteID = getUUID()  // 在应用启动时就初始化
    }

    @SuppressLint("HardwareIds")
    private fun getUUID(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID)
    }

    companion object {
        fun get(context: Context): BaseApplication {
            return context.applicationContext as BaseApplication
        }
    }
}
