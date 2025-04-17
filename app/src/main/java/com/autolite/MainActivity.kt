package com.autolite

import android.app.Service
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import info.mqtt.android.service.Ack
import org.eclipse.paho.client.mqttv3.*
import java.io.*
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import info.mqtt.android.service.MqttAndroidClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest


class MainActivity : AppCompatActivity() {

    private val kTag = "main"

    // 定义SharedPreferences常量
    private val PREFS_NAME = "MyPrefs"
    private val DARK_ID_KEY = "darkID"

    //UI组件
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnCheckOnline: Button
    private lateinit var btnDark: Button
    private lateinit var tvCheckResult: TextView
    private lateinit var tvDarkResult: TextView
    private lateinit var tvOfflineReport: TextView
    private var btnIsConnected = false

    val lightGreen = Color.parseColor("#90EE90")  // 浅绿色
    val lightBlue = Color.parseColor("#ADD8E6")  //浅蓝色

    //mqtt变量
    private var liteID:String = ""
    private var darkID:String = ""
    private lateinit var mqttServerUrl: String
    private lateinit var mqttClientId: String
    private lateinit var user: String
    private lateinit var pwd: String
    private lateinit var mqttClient: MqttAndroidClient
    private lateinit var mqttTopicCheckAppAlive: String
    private lateinit var mqttTopicCheckAppAliveResult: String
    private lateinit var mqttTopicDark: String
    private lateinit var mqttTopicDarkResult: String
    private lateinit var mqttTopicLastWill: String

    //网络变量
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    //扫码结果回调
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            Toast.makeText(this, "扫码成功", Toast.LENGTH_LONG).show()
            LogUtils.log(Log.DEBUG,"main","拿到扫码结果id:$darkID")
            darkID = result.contents.toString()
            // 保存darkID到内部存储
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            with(prefs.edit()) {
                putString(DARK_ID_KEY, darkID)
                apply()
            }

            initWhenDarkIdIsReady()
        } else {
            Toast.makeText(this, "取消扫码", Toast.LENGTH_SHORT).show()
        }
    }

    //darkID就绪时初始化
    private fun initWhenDarkIdIsReady() {
        if(darkID.isNotEmpty()){
            //scan按钮设置
            btnScan.setBackgroundColor(Color.GRAY)
            btnScan.text = "已导入"
            btnScan.isEnabled = false
            //Connect按钮设置
            btnConnect.setBackgroundColor(lightBlue)
            //mqtt变量获取
            mqttServerUrl = "ssl://***REMOVED***:8883"
            mqttClientId = liteID
            mqttTopicCheckAppAlive = "/topic/$darkID/checkAppAlive"
            mqttTopicCheckAppAliveResult = "/topic/$darkID/checkAppAliveResult"
            mqttTopicDark = "/topic/$darkID/dark"
            mqttTopicDarkResult = "/topic/$darkID/darkResult"
            mqttTopicLastWill = "/topic/$darkID/LastWill"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 LogUtils
        LogUtils.initialize(this)
        LogUtils.log(Log.INFO, kTag, "应用启动成功")

        // 绑定视图
        btnScan = findViewById(R.id.btn_scan)
        btnConnect = findViewById(R.id.btn_connect)

        btnCheckOnline = findViewById(R.id.btn_check_online)
        btnDark = findViewById(R.id.btn_dark)
        tvCheckResult = findViewById(R.id.tv_check_result)
        tvDarkResult = findViewById(R.id.tv_punch_result)
        tvOfflineReport = findViewById(R.id.tv_offline_report)

        // 设置扫码按钮
        btnScan.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("请将二维码/条形码置于框内")
                setBeepEnabled(true)
                setOrientationLocked(true)
                setBarcodeImageEnabled(true)
            }
            barcodeLauncher.launch(options)
        }

        // 设置连接按钮
        btnConnect.setOnClickListener {
            if (btnIsConnected) {
                // 断开连接逻辑
                disConnectToMqtt()
            } else {
                // 判断ID是否存在
                if (darkID.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("提示")
                        .setMessage("请先扫码以获取ID")
                        .setPositiveButton("确定", null)
                        .show()
                    return@setOnClickListener  // 中断后续操作
                }
                connectToMqtt()
            }
        }

        //检查按钮
        btnCheckOnline.setOnClickListener {
            if(!btnIsConnected){
                AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("请先连接")
                    .setPositiveButton("确定", null)
                    .show()
                return@setOnClickListener  // 中断后续操作
            }

            // 禁用按钮并置灰
            btnCheckOnline.isEnabled = false
            btnCheckOnline.setBackgroundColor(lightGreen)
            btnCheckOnline.text = "正在检查在线状态..."

            publishMessage(mqttTopicCheckAppAlive, "isAlive?", 1)
        }

        //设置打卡按钮
        btnDark.setOnClickListener {
            if(!btnIsConnected){
                AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("请先连接")
                    .setPositiveButton("确定", null)
                    .show()
                return@setOnClickListener  // 中断后续操作
            }

            // 禁用按钮并置灰
            btnDark.isEnabled = false
            btnDark.setBackgroundColor(lightGreen)
            btnDark.text = "正在打卡..."

            publishMessage(mqttTopicDark,"dark", 1)
        }

        // 获取设备的 Android ID，对于控制设备来说仅用来登录
        liteID = getUUID()
        user = liteID
        pwd = liteID
        LogUtils.log(Log.DEBUG,"main", "设备唯一ID：$liteID")
        LogUtils.log(Log.DEBUG,"main", "加载 MQTT 配置文件")

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedDarkID = prefs.getString(DARK_ID_KEY, null)

        if (savedDarkID != null) {
            // 如果已经保存了darkID，则直接使用
            darkID = savedDarkID
            LogUtils.log(Log.DEBUG, "main", "从存储中获取到darkID:$darkID")
            initWhenDarkIdIsReady()
        }

        // 初始化网络相关配置
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogUtils.log(Log.DEBUG,"main", "网络连接可用")
            }

            override fun onLost(network: Network) {
                // 网络丢失时可以选择执行其他操作
                Toast.makeText(this@MainActivity, "网络异常", Toast.LENGTH_LONG).show()
            }
        }
        // 注册网络回调
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        //判断证书是否存在，因为涉及文件下载，安卓强制非阻塞
        lifecycleScope.launch(Dispatchers.IO) {
            val success = initCertsBlocking(this@MainActivity, liteID)
            if (!success) {
                // 回到主线程再弹窗
                launch(Dispatchers.Main) {
                    showRetryDialog(this@MainActivity, liteID)
                }
            }else {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "CA证书获取成功", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showRetryDialog(context: Context, id: String) {
        AlertDialog.Builder(context)
            .setTitle("证书文件下载失败")
            .setMessage("请将页面截图发送给开发者后重试\nID: $id")
            .setPositiveButton("重试") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = initCertsBlocking(context, id)
                    if (!success) {
                        // 回到主线程再弹窗
                        launch(Dispatchers.Main) {
                            showRetryDialog(context, id)
                        }
                    } else {
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "CA证书获取成功", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setCancelable(false) // 如果需要设置为不可取消
            .show()
    }

    private fun initCertsBlocking(context: Context, id: String): Boolean {
        val clientEnPath = File(context.filesDir, "$id.en")
        val caEnPath = File(context.filesDir, "ca.en")

        val baseUrl = "https://***REMOVED***/certs/${id}/en_${id}"
        val clientEnUrl = "$baseUrl/${id}.en"
        val caEnUrl = "$baseUrl/ca.en"

        if (!clientEnPath.exists()) {
            downloadFileSuspend(clientEnUrl, clientEnPath)
        } else {
            LogUtils.log(Log.DEBUG, kTag, "客户端证书已存在")
        }

        if (!caEnPath.exists()) {
            downloadFileSuspend(caEnUrl, caEnPath)
        }else {
            LogUtils.log(Log.DEBUG, kTag, "CA证书已存在")
        }

        if (!clientEnPath.exists() || !caEnPath.exists()) {
            LogUtils.log(Log.ERROR, kTag, "证书文件下载失败")
            return false
        }

        return true
    }

    private fun downloadFileSuspend(urlStr: String, destFile: File){
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.doInput = true

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val input = connection.inputStream
                val output = FileOutputStream(destFile)
                input.copyTo(output)
                output.close()
                input.close()
                LogUtils.log(Log.DEBUG,kTag, "下载成功：${destFile.name}")
            } else {
                LogUtils.log(Log.DEBUG,kTag, "下载失败：$urlStr，code=${connection.responseCode}")
            }

            connection.disconnect()
        } catch (e: Exception) {
            LogUtils.log(Log.DEBUG,kTag, "异常下载 $urlStr: ${e.message}")
        }
    }

    private fun disConnectToMqtt(){
        LogUtils.log(Log.DEBUG,kTag, "尝试断开 MQTT 代理")
        try {
            if (mqttClient.isConnected) {
                mqttClient.disconnect()
                LogUtils.log(Log.DEBUG,kTag, "连接已成功断开")

                //修改UI
                btnConnect.setBackgroundColor(lightBlue)
                btnConnect.text = "连接"
                btnCheckOnline.setBackgroundColor(Color.GRAY)  // 假设断开时按钮颜色设置为灰色
                btnDark.setBackgroundColor(Color.GRAY)  // 断开时，设置按钮颜色为灰色
                btnIsConnected = false
            } else {
                // 如果当前没有连接，提示用户
                LogUtils.log(Log.DEBUG,kTag, "当前没有连接")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "当前没有连接", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: MqttException) {
            // 异常处理，捕获 MQTT 客户端断开连接时可能发生的错误
            LogUtils.log(Log.DEBUG,kTag, "断开连接时出错: ${e.message}")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "断开连接时出错: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            // 捕获其他异常
            LogUtils.log(Log.DEBUG,kTag, "发生未知错误: ${e.message}")
            e.printStackTrace()  // 输出堆栈跟踪信息
            runOnUiThread {
                Toast.makeText(this@MainActivity, "发生未知错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectToMqtt() {
        LogUtils.log(Log.DEBUG,kTag, "尝试连接到 MQTT 代理")

        lateinit var encryptedP12File: File
        lateinit var encryptedCaFile: File

        // 确保网络连接
        if (!NetworkUtils.isNetworkAvailable(this)) {
            LogUtils.log(Log.WARN,kTag, "网络不可用，无法连接到 MQTT 代理")
            Toast.makeText(this@MainActivity, "网络异常", Toast.LENGTH_LONG).show()
            return
        }

        try {
            // 尝试加载加密文件
            encryptedP12File = File(applicationContext.filesDir, "$liteID.en")

            encryptedCaFile = File(applicationContext.filesDir, "ca.en")
        } catch (e: Resources.NotFoundException) {
            // 如果文件不存在，打印异常信息
            LogUtils.log(Log.WARN, kTag, "加密文件不存在: ${e.message}")
            return // 直接返回
        }

        val key = generateKeyFromString(liteID)
        if (key.isEmpty()) {
            LogUtils.log(Log.ERROR, kTag, "密钥生成失败")
            // 处理解密失败的情况，比如返回或终止操作
            return
        }

        val p12Bytes = FileInputStream(encryptedP12File).use { inputStream ->
            aesDecryptInMemory(inputStream, key)
        }
        if (p12Bytes.isEmpty()) {
            LogUtils.log(Log.ERROR, kTag, "解密证书文件失败")
            // 处理解密失败的情况，比如返回或终止操作
            Toast.makeText(this@MainActivity, "解密证书文件失败", Toast.LENGTH_LONG).show()
            return
        }
        val caBytes = FileInputStream(encryptedCaFile).use { inputStream ->
            aesDecryptInMemory(inputStream, key)
        }
        if (caBytes.isEmpty()) {
            LogUtils.log(Log.ERROR, kTag, "解密 CA 文件失败")
            // 处理解密失败的情况，比如返回或终止操作
            Toast.makeText(this@MainActivity, "解密 CA 文件失败", Toast.LENGTH_LONG).show()
            return
        }

        // 加载 .p12 文件
        val p12P = liteID.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        val p12InputStream = p12Bytes.inputStream()
        try {
            keyStore.load(p12InputStream, p12P)
            LogUtils.log(Log.INFO,kTag, "P12 证书加载成功")
        } catch (e: Exception) {
            LogUtils.log(Log.WARN,kTag, "P12 证书加载失败: ${e.message}")
        }

        // 创建 KeyManagerFactory 来管理客户端证书和私钥
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, p12P)

        // 加载 CA 根证书
        val caInputStream = caBytes.inputStream()
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val caCertificate = certificateFactory.generateCertificate(caInputStream)

        // 创建一个包含 CA 证书的 KeyStore
        val caKeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        caKeyStore.load(null, null)
        caKeyStore.setCertificateEntry("ca", caCertificate)

        // 初始化 TrustManagerFactory
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(caKeyStore)
        //trustManagerFactory.init(null as KeyStore?)  // 默认使用系统信任的证书.不使用系统默认证书，保证内网通信

        // 使用证书和密钥进行进一步的 SSLContext 设置，确保连接安全
        val sslContext = SSLContext.getInstance("TLSv1.3")
        try {
            sslContext.init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, null)
            LogUtils.log(Log.DEBUG,kTag, "SSLContext 初始化成功")
        } catch (e: Exception) {
            LogUtils.log(Log.WARN,kTag, "SSLContext 初始化失败: ${e.message}")
        }


        mqttClient = MqttAndroidClient(applicationContext, mqttServerUrl, mqttClientId, Ack.AUTO_ACK)
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
            userName = user
            password = pwd.toCharArray()

            // 设置遗嘱消息
            val willQoS = 2

            // 获取当前时间戳
            val willMessage = "darkPhone_offline_at_" + System.currentTimeMillis().timestampToCompleteDate()

            setWill(mqttTopicLastWill, willMessage.toByteArray(), willQoS, true)

            // 使用自定义的 SSLContext
            socketFactory = sslContext.socketFactory
        }
        options.isAutomaticReconnect = true

        try {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.log(Log.DEBUG,kTag, "$mqttServerUrl 连接成功")

                    // 连接成功后设置按钮状态
                    runOnUiThread {
                        btnConnect.setBackgroundColor(lightGreen)
                        btnConnect.text = "已连接"
                        btnConnect.isEnabled = true  // 可以再次点击断开连接
                        btnCheckOnline.setBackgroundColor(lightBlue)
                        btnDark.setBackgroundColor(lightBlue)
                        btnIsConnected = true
                    }

                    val topicsToSubscribe = arrayOf(mqttTopicDarkResult,mqttTopicCheckAppAliveResult)
                    val qosLevels = intArrayOf(1,1) // QoS 级别
                    subscribeToTopics(topicsToSubscribe, qosLevels) // 连接成功后订阅主题
                    Toast.makeText(this@MainActivity, "Mqtt主题订阅成功", Toast.LENGTH_LONG).show()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (exception is MqttException) {
                        // 打印 MqttException 的详细信息
                        LogUtils.log(Log.ERROR, kTag, "MQTT 通信失败: ${exception.message}")
                        LogUtils.log(Log.ERROR, kTag, "MqttException 错误码: ${exception.reasonCode}")
                        LogUtils.log(Log.ERROR, kTag, "MqttException 错误详细信息: ${exception.localizedMessage}")

                        // 打印堆栈跟踪，帮助进一步排查
                        exception.printStackTrace()
                    } else {
                        // 其他异常类型
                        LogUtils.log(Log.ERROR, kTag, "未知错误: ${exception?.message}")
                    }
                    Toast.makeText(this@MainActivity, "mqtt通信失败", Toast.LENGTH_LONG).show()
                    btnIsConnected = false
                }
            })
        } catch (e: MqttException) {
            LogUtils.log(Log.ERROR,kTag, "MQTT 连接异常: ${e.message}")
        }

        mqttClient.setCallback(object : MqttCallbackExtended {
            override fun connectionLost(cause: Throwable?) {
                if (cause != null) {
                    LogUtils.log(Log.ERROR, kTag, "MQTT 连接断开：${cause.message}")
                    val stackTrace = StringWriter().also { writer ->
                        cause.printStackTrace(PrintWriter(writer))
                    }.toString()
                    LogUtils.log(Log.ERROR, kTag, "堆栈信息：\n$stackTrace")
                }
                btnIsConnected = false
            }

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    LogUtils.log(Log.INFO, kTag, "重连成功")
                    Toast.makeText(this@MainActivity, "mqtt重连成功", Toast.LENGTH_LONG).show()
                } else {
                    LogUtils.log(Log.INFO, kTag, "初次连接成功")
                    return
                }
                val topicsToSubscribe = arrayOf(mqttTopicCheckAppAliveResult,mqttTopicDarkResult)
                val qosLevels = intArrayOf(1,1) // QoS 级别
                subscribeToTopics(topicsToSubscribe, qosLevels) // 连接成功后订阅主题
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                message?.let {
                    val msg = String(it.payload) // 将消息体转换为字符串
                    LogUtils.log(Log.DEBUG,kTag, "收到主题 $topic 的消息: $msg")

                    when (topic) {
                        mqttTopicCheckAppAliveResult -> {
                            runOnUiThread {
                                LogUtils.log(Log.DEBUG, kTag, "tvCheckResult visibility: ${tvCheckResult.visibility}")
                                LogUtils.log(Log.DEBUG, kTag, "设置tvCheckResult的文本为: $msg")
                                tvCheckResult.visibility = View.VISIBLE
                                tvCheckResult.text = msg
                                btnCheckOnline.isEnabled = true
                                btnCheckOnline.setBackgroundColor(lightBlue)
                                btnCheckOnline.text = "检查是否在线"
                            }
                        }
                        mqttTopicDarkResult -> {
                            runOnUiThread {
                                LogUtils.log(Log.DEBUG, kTag, "正在更新 UI，主题: $topic")
                                tvDarkResult.visibility = View.VISIBLE
                                tvDarkResult.text = msg
                                btnDark.isEnabled = true
                                btnDark.setBackgroundColor(lightBlue)
                                btnDark.text = "连接"
                            }
                        }
                        mqttTopicLastWill -> {
                            runOnUiThread {
                                tvOfflineReport.visibility = View.VISIBLE
                                tvOfflineReport.text = msg
                            }
                        }
                    }
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                LogUtils.log(Log.DEBUG,kTag, "消息发送成功：${token?.message?.toString()}")
            }
        })
    }

    //mqtt订阅
    private fun subscribeToTopics(topics: Array<String>, qos: IntArray) {
        try {
            mqttClient.subscribe(topics, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.log(Log.DEBUG, kTag,"成功订阅主题: ${topics.joinToString(", ")}")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    LogUtils.log(Log.ERROR, kTag,"订阅失败: ${exception?.message}")
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
            mqttClient.unsubscribe(topics, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    LogUtils.log(Log.DEBUG,"AuToDark.connectToMqtt", "成功解除订阅主题: ${topics.joinToString(", ")}")
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
    private fun publishMessage(topic: String, message: String, qos: Int = 1) {
        LogUtils.log(Log.DEBUG,kTag, "尝试发布消息到主题 $topic: $message")

        try {
            val mqttMessage = MqttMessage(message.toByteArray()).apply {
                this.qos = qos // 设置质量服务级别
            }
            mqttClient.publish(topic, mqttMessage, null, null)
        } catch (e: MqttException) {
            LogUtils.log(Log.ERROR,kTag, "消息发布失败: ${e.message}")
        }
        Toast.makeText(this@MainActivity, "mqtt发布消息成功", Toast.LENGTH_LONG).show()
    }

    // 计算字符串的SHA-256哈希
    private fun hashString(input: String): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(input.toByteArray(Charsets.UTF_8))
    }

    private fun generateKeyFromString(inputString: String): ByteArray {
        return try {
            // 计算字符串的哈希值
            val stringHash = hashString(inputString)

            // 将哈希值每个字节加1，并将结果转换为 ByteArray
            val transformedHash = stringHash.map {
                ((it.toInt() + 1) % 256).toByte()
            }.toByteArray()

            // 使用前16字节
            transformedHash.take(16).toByteArray()

        } catch (e: Exception) {
            // 捕获任何异常并记录日志
            LogUtils.log(Log.ERROR, kTag, "生成密钥时发生异常: ${e.message}")
            ByteArray(0)  // 返回空字节数组表示生成密钥失败
        }
    }

    // 解密文件并在内存中处理（不保存到文件）
    private fun aesDecryptInMemory(inputStream: InputStream, key: ByteArray): ByteArray {
        try {
            // 读取加密文件，获取IV（前16字节）
            val iv = ByteArray(16) // AES的IV长度是16字节
            val bytesRead = inputStream.read(iv) // 读取IV
            if (bytesRead != 16) {
                LogUtils.log(Log.ERROR, kTag, "IV长度不正确，解密失败")
                return ByteArray(0)  // 返回空字节数组
            }

            // 使用AES解密
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val ivSpec = IvParameterSpec(iv)
            val secretKey = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            CipherInputStream(inputStream, cipher).use { cipherInputStream ->
                ByteArrayOutputStream().use { outputStream ->
                    val buffer = ByteArray(4096)
                    var bytesReadInLoop: Int
                    while (cipherInputStream.read(buffer).also { bytesReadInLoop = it } != -1) {
                        outputStream.write(buffer, 0, bytesReadInLoop)
                    }
                    return outputStream.toByteArray()
                }
            }

        } catch (e: Exception) {
            LogUtils.log(Log.ERROR, kTag, "解密失败: ${e.message}")
        }

        // 出现任何错误时返回空字节数组
        return ByteArray(0)
    }

    //获取设备唯一ID
    private fun getUUID(): String {
        return Settings.Secure.getString(this.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun Long.timestampToCompleteDate(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        return dateFormat.format(Date(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 取消订阅
            unsubscribeFromTopics(arrayOf(mqttTopicCheckAppAlive, mqttTopicDark))
            //断开连接
            mqttClient.disconnect()
            // 注销网络回调
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

}