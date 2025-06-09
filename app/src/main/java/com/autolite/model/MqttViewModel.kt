package com.autolite.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

enum class UiState {
    Idle,
    Scanned,
    Connected,
    Checking,
    Daring
}

class MqttViewModel : ViewModel() {
    private val _uiState = MutableLiveData(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _reason = MutableLiveData<String>()
    val reason: LiveData<String> = _reason

    fun setState(state: UiState, reason:String) {
        _uiState.value = state
        _reason.value = reason
    }

    private val _checkResponseReceived = MutableLiveData<Boolean>()
    private val _darkResponseReceived = MutableLiveData<Boolean>()
    val checkResponseReceived: LiveData<Boolean> get() = _checkResponseReceived
    val darkResponseReceived: LiveData<Boolean> get() = _darkResponseReceived
    fun notifyCheckResponseReceived(isDark:Boolean) {
        if(isDark){
            _darkResponseReceived.value = true
        }else{
            _checkResponseReceived.value = true
        }
    }


    private val _checkResult = MutableLiveData<String>()
    val checkResult: LiveData<String> get() = _checkResult

    private val _punchResult = MutableLiveData<String>()
    val punchResult: LiveData<String> get() = _punchResult

    private val _offlineResult = MutableLiveData<String>()
    val offlineResult: LiveData<String> get() = _offlineResult


    fun updateCheckResult(result: String) {
        _checkResult.value = result
    }

    fun updateDarkResult(result: String) {
        _punchResult.value = result
    }

    fun updateOfflineResult(result: String) {
        _offlineResult.value = result
    }

}
