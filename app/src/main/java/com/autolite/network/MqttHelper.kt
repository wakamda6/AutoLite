package com.autolite.network

import android.content.Context
import android.util.Log
import com.autolite.BaseApplication
import com.autolite.model.MqttViewModel
import com.autolite.model.UiState
import com.autolite.utils.LogUtils
import com.autolite.utils.MqttAuthConfig
import com.autolite.utils.MqttConfigHolder
import com.autolite.utils.NetworkUtils
import com.autolite.utils.TlsConfig
import org.eclipse.paho.client.mqttv3.MqttException
import info.mqtt.android.service.Ack
import org.eclipse.paho.client.mqttv3.*
import java.io.*
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions

class MqttHelper(
    private val context: Context,
    private val viewModel: MqttViewModel,
    liteID:String,
    darkID:String,
)  {

    private val kTag = "MqttHelper"

    //mqtt变量
    private var mqttClient: MqttAndroidClient? = null
    private var mqttClientId = liteID
    var mqttTopicCheckAppAlive = "/topic/$darkID/checkAppAlive"
    private var mqttTopicCheckAppAliveResult = "/topic/$darkID/checkAppAliveResult"
    var mqttTopicDark = "/topic/$darkID/dark"
    private var mqttTopicDarkResult = "/topic/$darkID/darkResult"
    private var mqttTopicLastWill = "/topic/$darkID/LastWill"

    fun connectToMqtt(){
        if (!NetworkUtils.isNetworkAvailable(context)) {
            LogUtils.log(Log.WARN, kTag, "网络不可用，无法连接到 MQTT 代理")
            return
        }

        initMqttClient()

        try {
            val options = getMqttConnectOptions()

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (exception is MqttException) {
                        LogUtils.log(Log.ERROR, kTag, "MQTT 通信失败: ${exception.message}")
                        LogUtils.log(Log.ERROR, kTag, "错误码: ${exception.reasonCode}")
                        exception.printStackTrace()
                    } else {
                        LogUtils.log(Log.ERROR, kTag, "未知错误: ${exception?.message}")
                    }
                    viewModel.setState(UiState.Scanned,"连接失败")
                }
            })
        } catch (e: MqttException) {
            LogUtils.log(Log.ERROR, kTag, "MQTT 连接异常: ${e.message}")
        }
    }

    fun disConnectToMqtt(){
        try {
            if (isMqttConnected()) {
                // 取消订阅
                unsubscribeFromTopics(arrayOf(mqttTopicCheckAppAliveResult, mqttTopicDarkResult,mqttTopicLastWill))
                //断开连接
                mqttClient?.disconnect(null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        LogUtils.log(Log.DEBUG, kTag, "MQTT 断开成功")
                        mqttClient?.unregisterResources()
                        viewModel.setState(UiState.Scanned, "")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        LogUtils.log(Log.ERROR, kTag, "断开连接失败: ${exception?.message}")
                    }
                })
            }
        } catch (e: MqttException) {
            // 异常处理，捕获 MQTT 客户端断开连接时可能发生的错误
            LogUtils.log(Log.ERROR,kTag, "断开连接时出错: ${e.message}")
        } catch (e: Exception) {
            // 捕获其他异常
            LogUtils.log(Log.ERROR,kTag, "发生未知错误: ${e.message}")
            e.printStackTrace()  // 输出堆栈跟踪信息
        }
    }

    private fun isMqttConnected(): Boolean {
        return mqttClient != null && mqttClient!!.isConnected
    }

    private fun initMqttClient() {
        val url = "${TlsConfig.scheme}://${BaseApplication.instance.domainAddress}:${TlsConfig.mqttPort}"
        mqttClient = MqttAndroidClient(context.applicationContext, url, mqttClientId, Ack.AUTO_ACK)
        mqttClient?.setCallback(mqttCallback)
    }

    //mqtt连接配置项
    private fun getMqttConnectOptions(): MqttConnectOptions {
        return MqttConnectOptions().apply {
            isCleanSession = false
            connectionTimeout = 20
            keepAliveInterval = 60
            userName = MqttAuthConfig.username.ifBlank { mqttClientId }
            password = MqttAuthConfig.password.ifBlank { mqttClientId }.toCharArray()
            isAutomaticReconnect = true

            MqttConfigHolder.mqttSslContext?.let {
                socketFactory = it.socketFactory
            }
        }
    }

    private val mqttCallback = object : MqttCallbackExtended {
        override fun connectionLost(cause: Throwable?) {
            if (cause == null) {
                LogUtils.log(Log.INFO, kTag, "MQTT 已正常断开")
            } else {
                LogUtils.log(Log.ERROR, kTag, "MQTT 异常断开：${cause.message}")
                viewModel.setState(UiState.Scanned,"MQTT异常断开连接")
            }
        }

        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            LogUtils.log(Log.INFO, kTag, if (reconnect) "重连成功" else "连接成功")
            subscribeToTopics(arrayOf(mqttTopicCheckAppAliveResult, mqttTopicDarkResult,mqttTopicLastWill), intArrayOf(2, 2, 2))
            viewModel.setState(UiState.Connected,"")
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            val msg = message?.toString() ?: return
            LogUtils.log(Log.DEBUG, kTag, "收到主题 $topic 的消息: $msg")

            when (topic) {
                mqttTopicCheckAppAliveResult -> {
                    viewModel.updateCheckResult(message.toString())
                    viewModel.notifyCheckResponseReceived(false)
                }
                mqttTopicDarkResult -> {
                    viewModel.updateDarkResult(message.toString())
                    viewModel.notifyCheckResponseReceived(true)
                }
                mqttTopicLastWill -> {
                    viewModel.updateOfflineResult(message.toString())
                }
            }
            viewModel.setState(UiState.Connected,"")

        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) {
            LogUtils.log(Log.DEBUG, kTag, "消息发送成功：${token?.message?.toString()}")
        }
    }

    private fun subscribeToTopics(topics: Array<String>, qos: IntArray) {
        try {
            mqttClient?.subscribe(topics, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.log(Log.DEBUG, kTag,"成功订阅主题: ${topics.joinToString(", ")}")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.log(Log.ERROR, kTag,"订阅失败: ${exception?.message}")
                    mqttClient?.disconnect()
                    viewModel.setState(UiState.Scanned,"主题订阅失败，连接已断开")
                }
            })
        } catch (e: MqttException) {
            LogUtils.log(Log.ERROR, kTag,"订阅异常: ${e.message}")
            val stackTrace = StringWriter().also { writer ->
                e.printStackTrace(PrintWriter(writer))
            }.toString()
            LogUtils.log(Log.ERROR, kTag, "堆栈信息：\n$stackTrace")
        }
    }

    //mqtt解除订阅
    private fun unsubscribeFromTopics(topics: Array<String>) {
        LogUtils.log(Log.DEBUG,kTag, "尝试解除订阅主题: ${topics.joinToString(", ")}")

        try {
            mqttClient?.unsubscribe(topics, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.log(Log.DEBUG,kTag, "成功解除订阅主题: ${topics.joinToString(", ")}")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.log(Log.ERROR,kTag,  "解除订阅失败: ${exception?.message}")
                }
            })
        } catch (e: MqttException) {
            LogUtils.log(Log.ERROR,kTag,  "解除订阅异常: ${e.message}")
            val stackTrace = StringWriter().also { writer ->
                e.printStackTrace(PrintWriter(writer))
            }.toString()
            LogUtils.log(Log.ERROR, kTag, "堆栈信息：\n$stackTrace")
        }
    }

    //mqtt 发布
    fun publishMessage(topic: String, message: String, qos: Int = 2) {
        LogUtils.log(Log.DEBUG,kTag, "尝试发布消息到主题 $topic: $message")
        try {
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                this.qos = qos // 设置质量服务级别
                isRetained = false
            }
            mqttClient?.publish(topic, mqttMessage, null, null)
        } catch (e: MqttException) {
            LogUtils.log(Log.ERROR,kTag, "消息发布失败: ${e.message}")
        }
    }
}