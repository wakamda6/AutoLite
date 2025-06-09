package com.autolite.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.autolite.utils.CertCheckResult
import com.autolite.utils.CertificateManager
import kotlinx.coroutines.launch


class InitViewModel(application: Application) : AndroidViewModel(application) {

    private val _initState = MutableLiveData<InitState>()
    val initState: LiveData<InitState> get() = _initState

    //判断是否需要重启mqtt
    object SharedMqttState {
        var hasCertCheckFailedOnce: Boolean = false
    }

    fun initCertificateCheck(ID: String) {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext

            when (val result = CertificateManager.getAndCheckCA(context, ID)) {
                is CertCheckResult -> {
                    when (result.status) {
                        CertCheckResult.Status.CASuccess -> {
                            val shouldRestartMqtt = SharedMqttState.hasCertCheckFailedOnce
                            SharedMqttState.hasCertCheckFailedOnce = false // 重置
                            _initState.postValue(
                                InitState.Success(
                                    result.message,
                                    shouldRestartMqtt
                                )
                            ) // 传递剩余时长
                        }
                        CertCheckResult.Status.CAGetFailed -> {
                            SharedMqttState.hasCertCheckFailedOnce = true
                            _initState.postValue(InitState.Failed("证书获取失败\n请联系开发者 ID:$ID"))
                        }
                        CertCheckResult.Status.CADecodeFailed -> {
                            SharedMqttState.hasCertCheckFailedOnce = true
                            _initState.postValue(InitState.Failed("证书解密失败\n请联系开发者 ID:$ID"))
                        }
                        CertCheckResult.Status.CAisRevoked -> {
                            SharedMqttState.hasCertCheckFailedOnce = true
                            _initState.postValue(InitState.Failed("证书已过期\n请联系开发者 ID:$ID"))
                        }
                        CertCheckResult.Status.CheckCertRevokedError -> {
                            SharedMqttState.hasCertCheckFailedOnce = true
                            _initState.postValue(InitState.Failed("证书验证错误\n请联系开发者 ID:$ID"))
                        }
                        CertCheckResult.Status.SSLError -> {
                            SharedMqttState.hasCertCheckFailedOnce = true
                            _initState.postValue(InitState.Failed("SSL 初始化失败\n请联系开发者 ID:$ID"))
                        }
                    }
                }
            }
        }
    }
}

sealed class InitState {
    data class Success(val remaining: String, val forceRestartMqtt: Boolean = false): InitState()
    data class Failed(val reason: String) : InitState()
}

