package org.jarsi.devicewatch.presentation.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.devicewatch.R
import org.jarsi.devicewatch.data.UNAVAILABLE_TEXT
import org.jarsi.devicewatch.presentation.DashboardUiState
import org.jarsi.devicewatch.presentation.DashboardViewModel
import org.jarsi.devicewatch.presentation.KernelBandState
import org.jarsi.devicewatch.presentation.RootStatus

@Composable
internal fun DeviceTab(
    uiState: DashboardUiState,
    kernelBand: KernelBandState = KernelBandState(),
    onOpenKernel: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(), // 🎯 需要拿 ViewModel 来执行命令
) {
    val currentStats = uiState.stats ?: return
    val context = LocalContext.current
    var showExemptDialog by remember { mutableStateOf(false) }

    // 🎯 全量免优化名单的弹窗
    if (showExemptDialog) {
        AlertDialog(
            onDismissRequest = { showExemptDialog = false },
            title = { Text("全量免电池优化名单", fontWeight = FontWeight.Bold) },
            text = {
                if (uiState.isExemptAppsLoading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.exemptAppsList.isEmpty()) {
                    Text("暂无数据（请确保已开启 Shizuku 或 Root 权限）")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(uiState.exemptAppsList) { pkg ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = pkg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.removeExemptApp(pkg) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "移出白名单",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExemptDialog = false }) { Text("关闭") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (uiState.rootStatus == RootStatus.ON) KernelBand(kernelBand, onOpenKernel)

        // ... (中间省略系统信息、摄像头、传感器等内容，保持你原来的代码不变) ...
        
        // 这下面是咱们的重点
        uiState.deviceState?.let { state ->
            SettingsSectionCard(titleRes = R.string.power_section) {
                DeviceFact(R.string.power_save_mode, state.powerSaveMode)
                DeviceFact(R.string.power_device_idle, state.deviceIdle)
                DeviceFact(R.string.power_battery_optimization_exempt, state.batteryOptimizationExempt)
                
                // 🎯 模块三：点击弹出全量面板
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            showExemptDialog = true 
                            viewModel.loadExemptApps()
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.power_other_exempt_apps),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.otherExemptApps,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "👉 点我查看全量名单并移除",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            SettingsSectionCard(titleRes = R.string.settings_state_section) {
                DeviceFact(R.string.display_brightness, state.brightness)
                DeviceFact(R.string.display_timeout, state.screenTimeout)
                DeviceFact(R.string.display_font_size, state.fontSize)
                DeviceFact(R.string.display_size, state.displaySize)
                DeviceFact(R.string.display_dark_theme, state.darkTheme)
                DeviceFact(R.string.system_developer_options, state.developerOptions)
                DeviceFact(R.string.system_usb_debugging, state.usbDebugging)
                DeviceFact(R.string.system_auto_time, state.automaticTime)
                DeviceFact(R.string.system_auto_time_zone, state.automaticTimeZone)
            }

            SettingsSectionCard(titleRes = R.string.security_section) {
                DeviceFact(R.string.security_screen_lock, state.screenLock)
                DeviceFact(R.string.security_biometrics, state.biometrics)
                DeviceFact(R.string.security_biometric_enrollment, state.biometricEnrollment)
                DeviceFact(R.string.security_strongbox, state.strongBox)
                DeviceFact(R.string.security_nfc, state.nfc)
            }

            SettingsSectionCard(titleRes = R.string.external_section) {
                if (state.removableVolumes.isEmpty()) {
                    DeviceFact(R.string.external_volumes, stringResource(R.string.external_volumes_none))
                } else {
                    state.removableVolumes.forEach { (name, value) -> DeviceFact(name, value) }
                }
                DeviceFact(R.string.usb_devices, state.usbDevices)
            }

            SettingsSectionCard(titleRes = R.string.audio_section) {
                DeviceFact(R.string.audio_devices, state.audioDevices)
                if (state.microphones != UNAVAILABLE_TEXT) DeviceFact(R.string.audio_microphones, state.microphones)
                if (state.spatialAudio != UNAVAILABLE_TEXT) DeviceFact(R.string.audio_spatial, state.spatialAudio)
            }
        }

        // ... (下面保持你原来的 SIM, WiFi 等代码不变) ...
    }
}
