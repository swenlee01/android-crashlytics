package my.gov.sarawak.spass.auth.ondevicebiometric.repositories

import android.content.Context
import android.content.SharedPreferences
import android.os.CountDownTimer
import com.huawei.hms.aaid.HmsInstanceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import my.gov.sarawak.spass.BuildConfig
import my.gov.sarawak.spass.Constants
import my.gov.sarawak.spass.R
import my.gov.sarawak.spass.api.ApiClient
import my.gov.sarawak.spass.api.ApiEndpoints
import my.gov.sarawak.spass.utils.ApiHelper
import my.gov.sarawak.spass.utils.BiometricAuthManager
import my.gov.sarawak.spass.utils.LogUtil
import my.gov.sarawak.spass.utils.SLog
import my.gov.sarawak.spass.utils.SharedPreferencesUtil
import my.gov.sarawak.spass.utils.SharedRepositoryComponents
import okhttp3.MultipartBody
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OnDeviceBiometricRepository(
    private val context: Context,
    private val biometricAuthManager: BiometricAuthManager
) {
    private val sharedPreferences = context.getSharedPreferences(Constants.PREFS_KEY, Context.MODE_PRIVATE)
    val editor: SharedPreferences.Editor = sharedPreferences.edit()
    private val sharedComponents = SharedRepositoryComponents(context)
    private val logUtil = sharedComponents.logUtil
    private val appVersion = sharedComponents.appVersion
    private val phoneIp = sharedComponents.phoneIp
    private val log = SLog(context)

    private val _isLocalBiometricEnabled = MutableStateFlow(false)
    val isLocalBiometricEnabled: StateFlow<Boolean> = _isLocalBiometricEnabled

    init {
        val initialValue = SharedPreferencesUtil.Companion.read(sharedPreferences, Constants.LOCAL_BIOMETRIC_ENABLED_KEY) == "true"
        _isLocalBiometricEnabled.value = initialValue
    }

    // Check if the user is locked out
    private fun checkLockoutStatus(currentTime: Long): Long {
        val savedLockoutEndTime = sharedPreferences.getLong("lockout_end_time", 0L)
        return maxOf(0, (savedLockoutEndTime - currentTime) / 1000)
    }

    // Start the lockout countdown timer
    private fun startLockoutTimer(lockoutDurationInMillis: Long) {
        val countDownTimer = object : CountDownTimer(lockoutDurationInMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                editor.putLong("remaining_time", millisUntilFinished / 1000)
                editor.apply()
            }
            override fun onFinish() {
                editor.remove("remaining_time")
                editor.apply()
            }
        }
        countDownTimer.start()
    }

    fun authenticate(
        onSuccess: () -> Unit,
        onError: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onLockout: (String) -> Unit,
        onBiometricNotEnrolled: () -> Unit,
        onBiometricHardwareUnavailable: () -> Unit
    ) {
        val currentTime = System.currentTimeMillis()
        val remainingTime = checkLockoutStatus(currentTime)

        if (remainingTime > 0) {
            onLockout(context.getString(R.string.biometric_lockout_message))
            startLockoutTimer(remainingTime * 1000)
            return
        }

        biometricAuthManager.authenticate(
            onSuccess = onSuccess,
            onError = {
                onError?.invoke()
            },
            onCancel = onCancel,
            onLockout = { lockoutMessage ->
                val lockoutDurationInMillis = 40 * 1000L
                editor.putLong("lockout_end_time", System.currentTimeMillis() + lockoutDurationInMillis).apply()
                onLockout(lockoutMessage)
                startLockoutTimer(lockoutDurationInMillis)
            },
            onBiometricNotEnrolled = onBiometricNotEnrolled,
            onBiometricHardwareUnavailable = onBiometricHardwareUnavailable
        )
    }

    fun authenticateWithDeviceCredentialOnly(
        onSuccess: () -> Unit,
        onCancel: (() -> Unit)? = null,
        onLockout: ((String) -> Unit)? = null,
        onError: (() -> Unit)? = null,
        onFail: (() -> Unit)? = null
    ) {
        biometricAuthManager.authenticateWithDeviceCredentialOnly(
            onSuccess = onSuccess,
            onCancel = onCancel,
            onLockout = onLockout,
            onError = onError,
            onFail = onFail
        )
    }

    fun getRemainingLockoutTimeSeconds(): Long {
        return checkLockoutStatus(System.currentTimeMillis())
    }

    fun handleLockoutTimer(
        durationSec: Long,
        onTick: (Long) -> Unit,
        onFinish: () -> Unit
    ) {
        object : CountDownTimer(durationSec * 1000, 1000) {
            override fun onTick(ms: Long) {
                editor.putLong("remaining_time", ms / 1000).apply()
                onTick(ms / 1000)
            }

            override fun onFinish() {
                editor.remove("remaining_time").apply()
                onFinish()
            }
        }.start()
    }

    fun applyLockout(durationSec: Long) {
        val lockoutEnd = System.currentTimeMillis() + (durationSec * 1000)
        editor.putLong("lockout_end_time", lockoutEnd).apply()
    }

    suspend fun requestFcm(
        userId: String,
        fcmToken: String
    ): OnDeviceBiometricResponseModel.RequestFcmResponse {
        var code: Int
        var message: String

        val requestBody = MultipartBody.Builder().apply {
            setType(MultipartBody.Companion.FORM)
            addFormDataPart("usr_id", userId)
            addFormDataPart("fcm_token", fcmToken)
            if (BuildConfig.IS_HUAWEI) {
                addFormDataPart("is_huawei", "1")
            }
        }.build()

        val requestParams = logUtil.extractFormData(requestBody)
        log.info("requestFcm", "API Request: $requestParams")

        return try {
            val call = ApiClient.apiService.requestFcm(requestBody)
            log.debug("requestFcm", "API Response: $call")

            val status = call.get("status").asString
            message = call.get("message").asString

            if (call.has("data") && call.get("data").isJsonObject) {
                val data = call.getAsJsonObject("data")
                log.debug("requestFcm", "API Data: $data")
            }

            OnDeviceBiometricResponseModel.RequestFcmResponse(
                isSuccess = status == "success",
                message = message
            )
        } catch (ex: HttpException) {
            val errorCode = ex.code()
            val errorResponseBody = ex.response()?.errorBody()?.string()
            val endpoint = ApiEndpoints.REQUEST_FCM

            val (errMsg, errCode) = ApiHelper.handleHttpError(
                context = context,
                userId = userId,
                icNumber = "",
                passport = "",
                errorCode = errorCode,
                errorResponseBody = errorResponseBody,
                requestBody = requestBody,
                endpoint = endpoint,
                logUtil = logUtil,
                appVersion = appVersion,
                phoneIpProvider = { phoneIp.await().toString() }
            )

            message = errMsg
            code = errCode

            log.error("requestFcm", "HttpException $errorCode: $errorResponseBody")
            OnDeviceBiometricResponseModel.RequestFcmResponse(
                isSuccess = false,
                message = message,
                code = code
            )
        } catch (ex: UnknownHostException) {
            log.error("requestFcm", "UnknownHost: ${ex.message}")
            message = context.getString(R.string.no_internet_connection_please_try_again)
            OnDeviceBiometricResponseModel.RequestFcmResponse(
                isSuccess = false,
                message = message
            )
        } catch (ex: SocketTimeoutException) {
            log.error("requestFcm", "Timeout: ${ex.message}")
            message = context.getString(R.string.session_time_out_please_try_again)
            OnDeviceBiometricResponseModel.RequestFcmResponse(
                isSuccess = false,
                message = message
            )
        } catch (ex: Exception) {
            log.error("requestFcm", "Error: ${ex.stackTraceToString()}")
            message = "Unexpected error: ${ex.localizedMessage}"
            OnDeviceBiometricResponseModel.RequestFcmResponse(
                isSuccess = false,
                message = message
            )
        }
    }

    fun generateHuaweiToken(): String? {
        return try {
            val appId = if (BuildConfig.IS_INTERNAL_TEST) {
                // TNT app Id
                "114719121"
            } else {
                // Production app Id
                "112082203"
            }

            val tokenScope = "HCM"
            val token = HmsInstanceId.getInstance(context).getToken(appId, tokenScope)
            LogUtil.Companion.d("HuaweiToken", "Generated token: $token")
            if (!token.isNullOrEmpty()) token else null
        } catch (ex: Exception) {
            LogUtil.Companion.e("HuaweiToken", "Failed to generate token", ex)
            null
        }
    }

    fun setLocalBiometricSetting(enabled: Boolean) {
        SharedPreferencesUtil.Companion.updateLocalBiometricSetting(sharedPreferences, enabled)
        log.debug("OnDeviceBiometricRepository", "Local biometric successfully set")
        log.debug("OnDeviceBiometricRepository", Constants.LOCAL_BIOMETRIC_ENABLED_KEY)
        _isLocalBiometricEnabled.value = enabled
    }
}