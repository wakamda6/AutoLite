package com.autolite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.eclipse.paho.client.mqttv3.*
import java.io.*
import java.util.*


class MainActivity : AppCompatActivity() {

    private val kTag = "main"

    //ID
    private var liteID:String = ""
    private var darkID:String = ""

    //邮件发送间隔倒计时
    private var onlineCheckTimeoutHandler: Handler? = null
    private var onlineCheckTimeoutRunnable: Runnable? = null
    private var darkCheckTimeoutHandler: Handler? = null
    private var darkCheckTimeoutRunnable: Runnable? = null

    //UI组件
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var tvTimeout: TextView
    private lateinit var tvId: TextView
    private lateinit var btnCheckOnline: Button
    private lateinit var btnDark: Button
    private lateinit var tvCheckResult: TextView
    private lateinit var tvDarkResult: TextView
    private lateinit var tvOfflineReport: TextView

    private val certViewModel: InitViewModel by viewModels()
    private val mqttViewModel: MqttViewModel by viewModels()
    private lateinit var mqttHelper: MqttHelper

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 获取ID，以便后续验证证书
        liteID = BaseApplication.get(this).liteID

        // 初始化 LogUtils
        LogUtils.initialize(this)
        LogUtils.log(Log.INFO, kTag, "应用启动成功，ID:$liteID")

        // 绑定视图
        btnScan = findViewById(R.id.btn_scan)
        btnConnect = findViewById(R.id.btn_connect)
        tvTimeout = findViewById(R.id.tv_timeout)
        tvId= findViewById(R.id.tv_id)
        btnCheckOnline = findViewById(R.id.btn_check_online)
        btnDark = findViewById(R.id.btn_dark)
        tvCheckResult = findViewById(R.id.tv_check_result)
        tvDarkResult = findViewById(R.id.tv_punch_result)
        tvOfflineReport = findViewById(R.id.tv_offline_report)

        //验证证书
        certViewModel.initState.observe(this) { state ->
            when (state) {
                is InitState.Success -> {
                    tvId.text = "本机ID：$liteID"
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

        // 设置连接按钮
        btnConnect.setOnClickListener {
            if (btnConnect.text == "断开连接") {
                // 断开连接逻辑
                mqttHelper.disConnectToMqtt()
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
                    mqttHelper.connectToMqtt()
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
            mqttViewModel.setState(UiState.Daring,"正在打卡，20s内返回结果")

            mqttHelper.publishMessage(mqttHelper.mqttTopicDark,"dark", 2)//保证送达
            // 设置 20 秒倒计时
            darkCheckTimeoutHandler = Handler(Looper.getMainLooper())
            darkCheckTimeoutRunnable = Runnable {
                tvDarkResult.text = "打卡失败或网络缓慢，请稍后重试"
                if(btnConnect.text == "连接"){
                    mqttViewModel.setState(UiState.Scanned,"打卡失败或网络缓慢")
                }else if (btnConnect.text == "断开连接"){
                    mqttViewModel.setState(UiState.Connected,"打卡失败或网络缓慢")
                }
            }
            darkCheckTimeoutHandler?.postDelayed(darkCheckTimeoutRunnable!!, 20000)
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

    override fun onDestroy() {
        super.onDestroy()
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
                    scanEnabled = true,
                    scanColor = lightBlue,
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
                    scanEnabled = true,
                    scanColor = lightBlue,
                    scanText = "重新扫码",
                    connectEnabled = true,
                    connectColor = lightGreen,
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
                    scanEnabled = true,
                    scanColor = lightBlue,
                    scanText = "重新扫码",
                    connectEnabled = true,
                    connectColor = lightGreen,
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
}