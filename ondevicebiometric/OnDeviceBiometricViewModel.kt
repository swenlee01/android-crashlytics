package my.gov.sarawak.spass.auth.ondevicebiometric

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import my.gov.sarawak.spass.utils.SLog
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import my.gov.sarawak.spass.Constants
import my.gov.sarawak.spass.auth.login.repositories.LoginRepository
import my.gov.sarawak.spass.utils.SharedPreferencesUtil
import androidx.core.content.edit
import my.gov.sarawak.spass.BuildConfig
import my.gov.sarawak.spass.R
import my.gov.sarawak.spass.auth.ondevicebiometric.repositories.OnDeviceBiometricRepository
import my.gov.sarawak.spass.utils.AppSession
import my.gov.sarawak.spass.utils.ErrorCodes
import my.gov.sarawak.spass.utils.ContextProvider
import my.gov.sarawak.spass.utils.SharedPreferencesUtil.Companion.SKIP_HOME_BIOMETRIC
import my.gov.sarawak.spass.utils.SharedPreferencesUtil.Companion.TOKEN_USER_MOBILE_AFTER_SKIP_HOME_BIOMETRIC
import my.gov.sarawak.spass.utils.launchBiometricEnrollSafely
import my.gov.sarawak.spass.utils.NotificationUtil.handleNotificationSubscription

class OnDeviceBiometricViewModel(
    private val onDeviceBiometricRepository: OnDeviceBiometricRepository,
    private val loginRepository: LoginRepository,
    private val sharedPreferences: SharedPreferences,
    private val contextProvider: ContextProvider,
    private val appContext: Context,
    private val log: SLog
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnDeviceBiometricUiState())
    val uiState: StateFlow<OnDeviceBiometricUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<String>()
    val navigationEvent: SharedFlow<String> = _navigationEvent

    var isDeepLinkWebLogin by mutableStateOf(false)
        private set

    var sessionId by mutableStateOf("")
        private set

    val currentStep = mutableIntStateOf(0)
    val totalSteps = mutableIntStateOf(1)

    fun updateIsDeepLinkWebLogin(inputValue: Boolean) {
        isDeepLinkWebLogin = inputValue
    }

    fun updateSessionId(inputValue: String) {
        sessionId = inputValue
    }

    fun updateStep(current: Int, total: Int) {
        currentStep.intValue = current
        totalSteps.intValue = total
    }

    private fun requestFcmToken() {
        if (BuildConfig.IS_HUAWEI) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val token = onDeviceBiometricRepository.generateHuaweiToken()
                    if (!token.isNullOrEmpty()) {
                        val username = SharedPreferencesUtil.read(sharedPreferences, Constants.USERNAME_KEY)
                        val result = onDeviceBiometricRepository.requestFcm(username, token)
                        if (!result.isSuccess) {
                            _uiState.update {
                                it.copy(
                                    dialogTitle = "HMS Error",
                                    dialogMessage = result.message,
                                    isError = true
                                )
                            }
                            log.error("requestFcm", "HMS token upload failed: ${result.message}")
                        } else {
                            handleNotificationSubscription(appContext)
                        }
                    } else {
                        log.error("requestFcm", "HMS token is empty or generation failed")
                    }
                } catch (ex: Exception) {
                    log.error("requestFcm", "Failed to upload HMS token due to an exception: ${ex.message}")
                    val errorCode = ErrorCodes.resolve(ex)
                    handleUnexpectedException(errorCode)
                }
            }
        } else {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    viewModelScope.launch {
                        try {
                            val username = SharedPreferencesUtil.read(sharedPreferences, Constants.USERNAME_KEY)
                            val result = onDeviceBiometricRepository.requestFcm(username, token)
                            if (!result.isSuccess) {
                                _uiState.update {
                                    it.copy(
                                        dialogTitle = "FCM Error",
                                        dialogMessage = result.message,
                                        isError = true
                                    )
                                }
                                log.error("requestFcm", "FCM token upload failed: ${result.message}")
                            } else {
                                handleNotificationSubscription(appContext)
                            }
                        } catch (ex: Exception) {
                            log.error("requestFcm", "Failed to upload FCM token due to an exception: ${ex.message}")
                            val errorCode = ErrorCodes.resolve(ex)
                            handleUnexpectedException(errorCode)
                        }
                    }
                } else {
                    log.error("requestFcm", "FCM token generation failed: ${task.exception?.message}")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun enableBiometric() {
        onDeviceBiometricRepository.authenticate(
            onSuccess = {
                log.info("enableBiometric", "Biometric enabled successfully")
                onDeviceBiometricRepository.setLocalBiometricSetting(true)
                sharedPreferences.edit { putBoolean(SKIP_HOME_BIOMETRIC, true) }
                sharedPreferences.edit { putBoolean(TOKEN_USER_MOBILE_AFTER_SKIP_HOME_BIOMETRIC, true) }
                AppSession.isBiometricVerified = true

                requestFcmToken()

                viewModelScope.launch {
                    _navigationEvent.emit("downloadCert")
                }
            },
            onError = {
                log.info("enableBiometric", "Biometric not enabled successfully")
                viewModelScope.launch {
                    _navigationEvent.emit("biometric_challenge_failed")
                }
            },
            onCancel = {
                log.warn("enableBiometric", "Biometric authentication CANCELLED by user")
                toggleLocalBiometricNotFoundDialog()
            },
            onLockout = { lockoutMessage ->
                log.error("enableBiometric", "Biometric LOCKOUT triggered: $lockoutMessage")
                updateErrorDialog(appContext.getString(R.string.failed), lockoutMessage)
            },
            onBiometricNotEnrolled = {
                log.warn("enableBiometric", "Biometric hardware exists but user has not enrolled")
                toggleLocalBiometricNotFoundDialog()
            },
            onBiometricHardwareUnavailable = {
                log.warn("enableBiometric", "Biometric hardware not available, use device security")
                toggleDeviceSecurityRequiredDialog()
            }
        )
    }


    fun disableBiometric() {
        onDeviceBiometricRepository.setLocalBiometricSetting(false)
    }

    private fun handleUnexpectedException(errorCode: String = "") {
        updateErrorDialog(
            contextProvider.getString(R.string.failed),
            contextProvider.getString(R.string.api_error_default_msg),
            errorCode
        )
    }

    fun updateErrorDialog(title: String, message: String, errorCode: String = "") {
        _uiState.update { currentState ->
            currentState.copy(
                dialogTitle = title,
                dialogMessage = message,
                dialogErrorCode = errorCode,
                isLoading = false,
                isError = true
            )
        }
    }

    fun dismissErrorDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                isError = true
            )
        }
    }

    fun toggleErrorDialog() {
        _uiState.update { currentState ->
            currentState.copy(
                isError = !currentState.isError
            )
        }
    }

    fun toggleLocalBiometricNotFoundDialog() {
        _uiState.update { it.copy(showLocalBiometricNotFoundDialog = !it.showLocalBiometricNotFoundDialog) }
    }

    fun toggleDeviceSecurityRequiredDialog() {
        _uiState.update { it.copy(showDeviceSecurityRequiredDialog = !it.showDeviceSecurityRequiredDialog) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun onLocalBiometricDialogConfirm() {
        _uiState.update { it.copy(showLocalBiometricNotFoundDialog = false) }

        // Launch enrollment safely
        launchBiometricEnrollSafely(appContext)
    }

    fun onDeviceSecurityDialogConfirm() {
        _uiState.update { it.copy(showDeviceSecurityRequiredDialog = false) }

        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
        appContext.startActivity(intent)
    }
}

class OnDeviceBiometricViewModelFactory(
    private val onDeviceBiometricRepository: OnDeviceBiometricRepository,
    private val loginRepository: LoginRepository,
    private val sharedPreferences: SharedPreferences,
    private val contextProvider: ContextProvider,
    private val appContext: Context,
    private val log: SLog
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OnDeviceBiometricViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OnDeviceBiometricViewModel(onDeviceBiometricRepository, loginRepository, sharedPreferences, contextProvider, appContext, log) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}