package my.gov.sarawak.spass.auth.ondevicebiometric

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import my.gov.sarawak.spass.MainActivity
import my.gov.sarawak.spass.R
import my.gov.sarawak.spass.composables.BottomRightButton
import my.gov.sarawak.spass.composables.ErrorDialog
import my.gov.sarawak.spass.composables.StepTracker
import my.gov.sarawak.spass.composables.TitleDescription
import my.gov.sarawak.spass.composables.TopBarWithBackButton
import my.gov.sarawak.spass.home.Home
import my.gov.sarawak.spass.main.DownloadCert
import my.gov.sarawak.spass.main.MainNavigationActions

@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun OnDeviceBiometricScreen(
    mainNavigationActions: MainNavigationActions,
    onDeviceBiometricViewModel: OnDeviceBiometricViewModel,
    onDeviceBiometricUiState: OnDeviceBiometricUiState
) {
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val currentStep = onDeviceBiometricViewModel.currentStep.intValue
    val totalSteps = onDeviceBiometricViewModel.totalSteps.intValue

    BackHandler {
        onDeviceBiometricViewModel.disableBiometric()
        mainNavigationActions.navigateUp()
    }

    val windowInfo = LocalConfiguration.current
    val verticalPadding = with(LocalDensity.current) {
        when (windowInfo.screenHeightDp) {
            in 0..360 -> 20.dp // Small screen
            in 360..720 -> 40.dp // Medium screen
            else -> 60.dp // Large screen
        }
    }
    val horizontalPadding = with(LocalDensity.current) {
        // Adjust horizontal padding based on window size if needed
        16.dp // Example: fixed horizontal padding
    }

    LaunchedEffect(onDeviceBiometricViewModel.navigationEvent) {
        onDeviceBiometricViewModel.navigationEvent.collect { destination ->
            when (destination) {
                "home" -> {
                    mainNavigationActions.navigateToHome(
                        Home(
                            isAppLinkWebLogin = onDeviceBiometricViewModel.isDeepLinkWebLogin,
                            sessionId = onDeviceBiometricViewModel.sessionId
                        )
                    )
                }

                "downloadCert" -> {
                    mainNavigationActions.navigateToDownloadCert(
                        DownloadCert(
                            isDeepLinkWebLogin = onDeviceBiometricViewModel.isDeepLinkWebLogin,
                            sessionId = onDeviceBiometricViewModel.sessionId
                        )
                    )
                }

                "biometric_challenge_failed" -> {
                    activity.finishAndRemoveTask()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopBarWithBackButton(onBackPressed = {
                mainNavigationActions.navigateUp()
            })
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = verticalPadding,
                        bottom = verticalPadding,
                        start = horizontalPadding,
                        end = horizontalPadding
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepTracker(
                    currentStep = currentStep,
                    totalSteps = totalSteps,
                )
                BottomRightButton(
                    label = stringResource(R.string.next),
                    onClick = {
                        onDeviceBiometricViewModel.enableBiometric()
                        MainActivity.DownloadCertFlag.isDownloadingDiCert = true
                    },
                    enabled = true,
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.chevron),
                            contentDescription = null,
                        )
                    }
                )
            }
        }
    ) { innerPadding ->

        // Main UI
        OnDeviceBiometricUI (innerPadding)

        ErrorDialog(
            title = onDeviceBiometricUiState.dialogTitle,
            message = onDeviceBiometricUiState.dialogMessage,
            isShow = onDeviceBiometricUiState.isError,
            onDismissRequest = { onDeviceBiometricViewModel.toggleErrorDialog() },
            confirmButton = {}
        )

        // Local biometric not found dialog
        ErrorDialog(
            title = "Biometric Not Setup",
            message = stringResource(R.string.biometrics_authentication_is_not_set_up_on_this_device_please_set_it_up_in_settings_to_use_it_for_authentication),
            isShow = onDeviceBiometricUiState.showLocalBiometricNotFoundDialog,
            onDismissRequest = { /* prevent outside tap */ },
            confirmButton = {
                TextButton(onClick = { onDeviceBiometricViewModel.onLocalBiometricDialogConfirm() }) {
                    Text(text = "OK")
                }
            },
            dismissButton = null // hide dismiss button
        )

        // Device security required dialog
        ErrorDialog(
            title = "Device Security Required",
            message = stringResource(R.string.device_security_required_message),
            isShow = onDeviceBiometricUiState.showDeviceSecurityRequiredDialog,
            onDismissRequest = { /* prevent outside tap */ },
            confirmButton = {
                TextButton(onClick = { onDeviceBiometricViewModel.onDeviceSecurityDialogConfirm() }) {
                    Text(text = "OK")
                }
            },
            dismissButton = null
        )
    }
}

@Composable
fun OnDeviceBiometricUI (innerPadding: PaddingValues) {

    fun boldTarget(text: String, target: String): AnnotatedString {
        return buildAnnotatedString {
            val start = text.indexOf(target)
            append(text)
            if (start >= 0) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + target.length)
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(innerPadding)
            .padding(24.dp)
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TitleDescription(
            title = stringResource(R.string.device_biometrics),
            description = stringResource(R.string.biometric_authentication_is_required_to_secure_your_account),
            horizontalAlignment = Alignment.CenterHorizontally,
            titleAlignment = TextAlign.Center,
            descriptionAlignment = TextAlign.Center
        )

        Image(
            painter = painterResource(id = R.drawable.enable_biometrics),
            contentDescription = null,
            modifier = Modifier.size(250.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.click_next_to_turn_on_device_biometric),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(), // makes alignment matter
                textAlign = TextAlign.Start
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    painter = painterResource(R.drawable.info),
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = boldTarget(
                        stringResource(R.string.biometric_authentication_is_mandatory_to_safeguard_your_identity_and_enable_secure_convenient_access_to_SarawakPass_services),
                        "mandatory"
                    ),
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF4D648D))
                )
            }
        }

    }
}

@Preview(
    name = "OnDeviceBiometricUI Preview",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF // optional, for better contrast
)
@Composable
fun OnDeviceBiometricUIPreview() {
    val samplePadding = PaddingValues(all = 16.dp)

    OnDeviceBiometricUI(innerPadding = samplePadding)
}