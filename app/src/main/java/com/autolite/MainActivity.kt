package com.autolite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autolite.R
import com.autolite.model.InitState
import com.autolite.model.InitViewModel
import com.autolite.model.MqttViewModel
import com.autolite.model.UiState
import com.autolite.network.MqttHelper
import com.autolite.utils.LogUtils
import com.autolite.utils.MqttAuthConfig
import com.autolite.utils.TlsConfig
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.eclipse.paho.client.mqttv3.*
import java.io.*
import java.util.*


class MainActivity : AppCompatActivity() {

    private val kTag = "main"

    //ID
    private var liteID:String = ""
    private var darkID:String = "请先扫码"

    //邮件发送间隔倒计时
    private var onlineCheckTimeoutHandler: Handler? = null
    private var onlineCheckTimeoutRunnable: Runnable? = null
    private var darkCheckTimeoutHandler: Handler? = null
    private var darkCheckTimeoutRunnable: Runnable? = null

    //UI组件
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnSettings: Button
    private lateinit var tvTimeout: TextView
    private lateinit var tvLiteId: TextView
    private lateinit var tvDarkId: TextView
    private lateinit var btnCheckOnline: Button
    private lateinit var btnDark: Button
    private lateinit var tvCheckResult: TextView
    private lateinit var tvDarkResult: TextView
    private lateinit var tvOfflineReport: TextView

    private val certViewModel: InitViewModel by viewModels()
    private val mqttViewModel: MqttViewModel by viewModels()
    lateinit var mqttHelper: MqttHelper
    fun isMqttHelperInitialized(): Boolean = this::mqttHelper.isInitialized

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 获取ID，以便后续验证证书
        liteID = BaseApplication.liteID

        // 绑定视图
        btnScan = findViewById(R.id.btn_scan)
        btnConnect = findViewById(R.id.btn_connect)
        btnSettings = findViewById(R.id.btn_settings)
        tvTimeout = findViewById(R.id.tv_timeout)
        tvLiteId= findViewById(R.id.tv_liteId)
        tvDarkId= findViewById(R.id.tv_darkId)
        btnCheckOnline = findViewById(R.id.btn_check_online)
        btnDark = findViewById(R.id.btn_dark)
        tvCheckResult = findViewById(R.id.tv_check_result)
        tvDarkResult = findViewById(R.id.tv_punch_result)
        tvOfflineReport = findViewById(R.id.tv_offline_report)

        //验证证书
        certViewModel.initState.observe(this) { state ->
            when (state) {
                is InitState.Success -> {
                    tvLiteId.text = "本机   ID：${liteID}"
                    tvDarkId.text = "Dark  ID：${darkID}"
                    tvTimeout.text = state.remaining
                }
                is InitState.Failed -> {
                    showErrorDialog(state.reason)
                }
            }
        }

        //获取darkID
        val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        val savedDarkID = prefs.getString("darkID", null)

        if (savedDarkID != null) {
            // 如果已经保存了darkID，则直接使用
            darkID = savedDarkID
            LogUtils.log(Log.INFO, kTag, "从存储中获取到darkID:$darkID")

            mqttHelper = MqttHelper(this, mqttViewModel, liteID, darkID)
            mqttViewModel.setState(UiState.Scanned,"获取darkID:$darkID")
        }

        //根据mqtt更新UI
        mqttViewModel.uiState.observe(this) { state  ->
            updateUiForState(state)
        }
        mqttViewModel.reason.observe(this) { reason  ->
            if (reason.isNotEmpty())
                Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        }
        mqttViewModel.checkResult.observe(this) { result ->
            tvCheckResult.text = result
        }
        mqttViewModel.punchResult.observe(this) { result ->
            tvDarkResult.text = result
        }
        mqttViewModel.offlineResult.observe(this) { result ->
            tvOfflineReport.text = result
        }
        //取消倒计时
        mqttViewModel.checkResponseReceived.observe(this) { received ->
            if (received == true) {
                onlineCheckTimeoutHandler?.removeCallbacks(onlineCheckTimeoutRunnable!!)
            }
        }
        mqttViewModel.darkResponseReceived.observe(this) { received ->
            if (received == true) {
                darkCheckTimeoutHandler?.removeCallbacks(darkCheckTimeoutRunnable!!)
            }
        }

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

        // 设置按钮
        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // 设置连接按钮
        btnConnect.setOnClickListener {
            if (btnConnect.text == "断开连接") {
                // 断开连接逻辑
                if (isMqttHelperInitialized()) {
                    mqttHelper.disConnectToMqtt()
                }
            } else  {
                // 判断ID是否存在
                if (darkID.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("提示")
                        .setMessage("请先扫码以获取ID")
                        .setPositiveButton("确定", null)
                        .show()
                    return@setOnClickListener  // 中断后续操作
                }else {
                    if (isMqttHelperInitialized()) {
                        mqttHelper.connectToMqtt()
                    }
                }

            }
        }

        //检查按钮
        btnCheckOnline.setOnClickListener {
            if(btnConnect.text == "连接"){
                AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("请先连接")
                    .setPositiveButton("确定", null)
                    .show()
                return@setOnClickListener  // 中断后续操作
            }
            // 更新UI
            mqttViewModel.setState(UiState.Checking,"开始检查是否在线，8s内返回结果")

            if (isMqttHelperInitialized())
                mqttHelper.publishMessage(mqttHelper.mqttTopicCheckAppAlive, "isAlive?", 2)
            // 设置 8 秒倒计时
            onlineCheckTimeoutHandler = Handler(Looper.getMainLooper())
            onlineCheckTimeoutRunnable = Runnable {
                tvCheckResult.text = "不在线或网络缓慢，请稍后重试"
                if(btnConnect.text == "连接"){
                    mqttViewModel.setState(UiState.Scanned,"不在线或网络缓慢")
                }else if(btnConnect.text == "断开连接"){
                    mqttViewModel.setState(UiState.Connected,"不在线或网络缓慢")
                }
            }
            onlineCheckTimeoutHandler?.postDelayed(onlineCheckTimeoutRunnable!!, 8000)
        }

        //设置打卡按钮
        btnDark.setOnClickListener {
            if(btnConnect.text == "连接"){
                AlertDialog.Builder(this)
                    .setTitle("提示")
                    .setMessage("请先连接")
                    .setPositiveButton("确定", null)
                    .show()
                return@setOnClickListener  // 中断后续操作
            }
            // 更新UI
            mqttViewModel.setState(UiState.Daring,"正在打卡，60s内返回结果")

            if (isMqttHelperInitialized())
                mqttHelper.publishMessage(mqttHelper.mqttTopicDark,"dark", 2)//保证送达
            // 设置 60 秒倒计时
            darkCheckTimeoutHandler = Handler(Looper.getMainLooper())
            darkCheckTimeoutRunnable = Runnable {
                tvDarkResult.text = "打卡失败或网络缓慢，请稍后重试"
                if(btnConnect.text == "连接"){
                    mqttViewModel.setState(UiState.Scanned,"打卡失败或网络缓慢")
                }else if (btnConnect.text == "断开连接"){
                    mqttViewModel.setState(UiState.Connected,"打卡失败或网络缓慢")
                }
            }
            darkCheckTimeoutHandler?.postDelayed(darkCheckTimeoutRunnable!!, 60000)
        }
    }

    //扫码结果回调
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            darkID = result.contents.toString()
            LogUtils.log(Log.INFO, kTag, "拿到扫码结果id:$darkID")
            // 保存darkID到内部存储
            val prefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
            with(prefs.edit()) {
                putString("darkID", darkID)
                apply()
            }
            mqttHelper = MqttHelper(this, mqttViewModel, liteID, darkID)
            mqttViewModel.setState(UiState.Scanned,"扫码成功，获取darkID:$darkID")

        } else {
            mqttViewModel.setState(UiState.Idle,"取消扫码")
        }
    }

    private fun showErrorDialog(reason: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("初始化失败")
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton("重试") { _, _ ->
                certViewModel.initCertificateCheck(liteID)
            }
            .setNegativeButton("退出") { _, _ ->
                finish()
            }
            .show()
    }

    //各个状态时UI组件显示
    private val lightGreen = Color.parseColor("#90EE90")  // 浅绿色
    private val lightBlue = Color.parseColor("#ADD8E6")  //浅蓝色
    private fun updateUiForState(state: UiState) {
        when (state) {
            //未扫码
            UiState.Idle -> {
                setButtonState(
                    scanEnabled = true,
                    scanColor = lightBlue,
                    scanText = "扫码",
                    connectEnabled = false,
                    connectColor = Color.GRAY,
                    connectText = "连接",
                    checkOnlineEnabled = false,
                    checkOnlineColor = Color.GRAY,
                    checkOnlineText = "检查是否在线",
                    darkEnabled = false,
                    darkColor = Color.GRAY,
                    darkText = "打卡"
                )
            }

            UiState.Scanned -> {
                setButtonState(
                    scanEnabled = true,
                    scanColor = lightBlue,
                    scanText = "重新扫码",
                    connectEnabled = true,
                    connectColor = lightBlue,
                    connectText = "连接",
                    checkOnlineEnabled = false,
                    checkOnlineColor = Color.GRAY,
                    checkOnlineText = "检查是否在线",
                    darkEnabled = false,
                    darkColor = Color.GRAY,
                    darkText = "打卡"
                )
            }

            UiState.Connected -> {
                setButtonState(
                    scanEnabled = false,
                    scanColor = Color.GRAY,
                    scanText = "重新扫码",
                    connectEnabled = true,
                    connectColor = lightGreen,
                    connectText = "断开连接",
                    checkOnlineEnabled = true,
                    checkOnlineColor = lightBlue,
                    checkOnlineText = "检查是否在线",
                    darkEnabled = true,
                    darkColor = lightBlue,
                    darkText = "打卡"
                )
            }

            UiState.Checking -> {
                setButtonState(
                    scanEnabled = false,
                    scanColor = Color.GRAY,
                    scanText = "重新扫码",
                    connectEnabled = false,
                    connectColor = Color.GRAY,
                    connectText = "断开连接",
                    checkOnlineEnabled = false,
                    checkOnlineColor = Color.GRAY,
                    checkOnlineText = "正在检查是否在线",
                    darkEnabled = false,
                    darkColor = Color.GRAY,
                    darkText = "打卡",
                    checkResult = ""
                )
            }

            UiState.Daring -> {
                setButtonState(
                    scanEnabled = false,
                    scanColor = Color.GRAY,
                    scanText = "重新扫码",
                    connectEnabled = false,
                    connectColor = Color.GRAY,
                    connectText = "断开连接",
                    checkOnlineEnabled = false,
                    checkOnlineColor = Color.GRAY,
                    checkOnlineText = "检查是否在线",
                    darkEnabled = false,
                    darkColor = Color.GRAY,
                    darkText = "正在打卡...",
                    darkResult = ""
                )
            }
        }
    }

    private fun setButtonState(
        scanEnabled: Boolean,
        scanColor: Int,
        scanText: String,
        connectEnabled: Boolean,
        connectColor: Int,
        connectText: String,
        checkOnlineEnabled: Boolean,
        checkOnlineColor: Int,
        checkOnlineText: String,
        darkEnabled: Boolean,
        darkColor: Int,
        darkText: String,
        checkResult: String? = null,
        darkResult: String? = null
    ) {
        //扫码按钮
        btnScan.isEnabled = scanEnabled
        btnScan.setBackgroundColor(scanColor)
        btnScan.text = scanText
        //连接按钮
        btnConnect.isEnabled = connectEnabled
        btnConnect.setBackgroundColor(connectColor)
        btnConnect.text = connectText
        //检查按钮
        btnCheckOnline.isEnabled = checkOnlineEnabled
        btnCheckOnline.setBackgroundColor(checkOnlineColor)
        btnCheckOnline.text = checkOnlineText
        //打卡按钮
        btnDark.isEnabled = darkEnabled
        btnDark.setBackgroundColor(darkColor)
        btnDark.text = darkText

        checkResult?.let {
            tvCheckResult.text = it
        }
        darkResult?.let {
            tvDarkResult.text = it
        }

    }

    override fun onResume() {
        super.onResume()
        //证书检查
        certViewModel.initCertificateCheck(liteID)
    }

    // 设置弹窗
    private fun showSettingsDialog() {
        val items = arrayOf("服务器地址", "连接方式", "MQTT账号")
        android.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showDomainInputDialog()
                    1 -> showModeDialog()
                    2 -> showMqttAccountDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 服务器地址
    private fun showDomainInputDialog() {
        val input = EditText(this).apply {
            setText(BaseApplication.instance.domainAddress)
            hint = "请输入服务器地址（域名或IP）"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setView(input)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val address = input.text.toString().trim()
            if (address.isEmpty()) {
                input.error = "地址不能为空！"
            } else {
                BaseApplication.instance.domainAddress = address
                // 根据地址类型设置默认连接方式：IP→无加密，域名→单向
                TlsConfig.mode = if (TlsConfig.isIpAddress(address)) TlsConfig.MODE_NONE else TlsConfig.MODE_ONE_WAY
                dialog.dismiss()
                Toast.makeText(this, "已保存，请重新连接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 连接方式
    private fun showModeDialog() {
        val values = arrayOf(TlsConfig.MODE_NONE, TlsConfig.MODE_ONE_WAY, TlsConfig.MODE_MUTUAL)
        val labels = arrayOf("无加密", "单向TLS", "双向TLS")
        val current = values.indexOf(TlsConfig.mode).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("连接方式")
            .setMessage("无加密：只需服务器+broker，明文（不安全）\n单向TLS：另需域名+公网证书（较安全）\n双向TLS：另需域名+自建CA+客户端证书（最安全）")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val newMode = values[which]
                if (newMode != TlsConfig.mode) {
                    // 切到单向/双向前，服务器地址必须是域名
                    if (newMode != TlsConfig.MODE_NONE && TlsConfig.isIpAddress(BaseApplication.instance.domainAddress)) {
                        Toast.makeText(this, "当前是IP地址，切换到单向/双向需要域名", Toast.LENGTH_LONG).show()
                    } else {
                        TlsConfig.switchTo(newMode)
                    }
                }
                dialog.dismiss()
                Toast.makeText(this, "已保存，请重新连接", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // MQTT账号
    private fun showMqttAccountDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        val userEdit = EditText(this).apply {
            hint = "MQTT用户名（留空=设备ID）"
            maxLines = 1
            setText(MqttAuthConfig.username)
        }
        val pwdEdit = EditText(this).apply {
            hint = "MQTT密码（留空=设备ID）"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(MqttAuthConfig.password)
        }
        container.addView(userEdit)
        container.addView(pwdEdit)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("MQTT账号")
            .setView(container)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            MqttAuthConfig.username = userEdit.text.toString().trim()
            MqttAuthConfig.password = pwdEdit.text.toString().trim()
            dialog.dismiss()
            Toast.makeText(this, "已保存，请重新连接", Toast.LENGTH_SHORT).show()
        }
    }
}