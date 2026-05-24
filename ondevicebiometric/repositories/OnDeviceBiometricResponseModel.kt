package my.gov.sarawak.spass.auth.ondevicebiometric.repositories

class OnDeviceBiometricResponseModel {
    data class RequestFcmResponse(
        val isSuccess: Boolean,
        val message: String,
        val code: Int? = null,
    )
}