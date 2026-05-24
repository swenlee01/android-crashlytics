package my.gov.sarawak.spass.auth.ondevicebiometric

data class OnDeviceBiometricUiState(
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val dialogTitle: String = "",
    val dialogMessage: String = "",
    val dialogErrorCode: String = "",
    val showLocalBiometricNotFoundDialog: Boolean = false,
    val showDeviceSecurityRequiredDialog: Boolean = false
)
