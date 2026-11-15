package org.jarsi.devicewatch.presentation.ui

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
import org.jarsi.devicewatch.R
import org.jarsi.devicewatch.data.UNAVAILABLE_TEXT
import org.jarsi.devicewatch.presentation.DashboardUiState
import org.jarsi.devicewatch.presentation.DashboardViewModel
import org.jarsi.devicewatch.presentation.KernelBandState
import org.jarsi.devicewatch.presentation.RootStatus

/**
 * Device tab: static device facts plus the live SIM and Wi-Fi sections, led on a
 * rooted phone by the KERNEL band.
 */
@Composable
internal fun DeviceTab(
    uiState: DashboardUiState,
    kernelBand: KernelBandState = KernelBandState(),
    onOpenKernel: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val currentStats = uiState.stats ?: return
    val context = LocalContext.current

    // 🎯 新增：全量免优化名单的弹窗状态
    var showExemptDialog by remember { mutableStateOf(false) }

    // 🎯 新增：全量免优化名单的弹窗实现
    if (showExemptDialog) {
        AlertDialog(
            onDismissRequest = { showExemptDialog = false },
            title = { Text("全量免电池优化名单", fontWeight = FontWeight.Bold) },
            text = {
                if (uiState.isExemptAppsLoading) {
                    Row(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.exemptAppsList.isNullOrEmpty()) { 
                    Text("暂无数据（请确保已开启 Shizuku 或 Root 权限）")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(uiState.exemptAppsList) { rawItem ->
                            // 🎯 核心修复：把纯数字(UID)转换成包名，进而获取应用名字和图标
                            val actualPackage = if (rawItem.toIntOrNull() != null) {
                                try {
                                    context.packageManager.getPackagesForUid(rawItem.toInt())?.firstOrNull()
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                rawItem
                            }

                            val appLabel = if (actualPackage != null) {
                                try {
                                    val appInfo = context.packageManager.getApplicationInfo(actualPackage, 0)
                                    context.packageManager.getApplicationLabel(appInfo).toString()
                                } catch (e: Exception) {
                                    actualPackage
                                }
                            } else {
                                "未知应用 ($rawItem)"
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 🎯 核心修复：显示 App 图标
                                if (actualPackage != null) {
                                    AppIcon(actualPackage, modifier = Modifier.size(24.dp))
                                } else {
                                    Spacer(modifier = Modifier.size(24.dp))
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                // 🎯 核心修复：显示 App 名字
                                Text(text = appLabel, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                
                                IconButton(onClick = { viewModel.removeExemptApp(actualPackage ?: rawItem) }) {
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (uiState.rootStatus == RootStatus.ON) KernelBand(kernelBand, onOpenKernel)

        uiState.deviceInfo?.let { info ->
            SettingsSectionCard(titleRes = R.string.device_info_section) {
                DeviceFact(R.string.device_info_manufacturer, info.manufacturer)
                DeviceFact(R.string.device_info_model, info.model)
                DeviceFact(R.string.device_info_codename, info.codename)
                DeviceFact(R.string.device_info_android, info.androidVersion)
                DeviceFact(R.string.device_info_security_patch, info.securityPatch)
                DeviceFact(R.string.device_info_build, info.buildNumber)
                DeviceFact(R.string.device_info_bootloader, info.bootloader)
                DeviceFact(R.string.device_info_radio, info.radioVersion)
                DeviceFact(R.string.device_info_soc, info.soc)
                DeviceFact(R.string.device_info_abis, info.supportedAbis)
                DeviceFact(R.string.device_info_kernel, info.kernelVersion)
                DeviceFact(R.string.device_info_cpu_clusters, info.cpuClusters)
                DeviceFact(R.string.device_info_cpu_governor, info.cpuGovernor)
                DeviceFact(R.string.device_info_gnss, info.gnssHardware)
                DeviceFact(R.string.device_info_gnss_capabilities, info.gnssCapabilities)
                DeviceFact(R.string.device_info_gpu, info.gpuRenderer)
                DeviceFact(R.string.device_info_gl, info.glVersion)
                DeviceFact(R.string.device_info_vulkan, info.vulkanVersion)
                DeviceFact(R.string.device_info_resolution, info.screenResolution)
                DeviceFact(R.string.device_info_density, info.screenDensity)
                DeviceFact(R.string.device_info_physical_size, info.physicalSize)
                DeviceFact(R.string.device_info_refresh, info.refreshRate)
                DeviceFact(R.string.device_info_hdr, info.hdr)
                DeviceFact(R.string.device_info_decoders, info.hardwareDecoders)
                DeviceFact(R.string.device_info_widevine, info.widevineLevel)
                DeviceFact(R.string.device_info_ram, info.totalRam)
                DeviceFact(R.string.device_info_ram_advertised, info.advertisedRam)
                uiState.deviceState?.let { state -> DeviceFact(R.string.memory_swap, state.swap) }
                DeviceFact(R.string.device_info_storage, info.totalStorage)
                DeviceFact(R.string.device_info_battery_tech, info.batteryTechnology)
                DeviceFact(R.string.device_info_battery_capacity, info.batteryCapacityMah)
                uiState.deviceState?.let { state ->
                    DeviceFact(R.string.battery_full_capacity, state.batteryFullCapacity)
                    if (state.batteryHealth != UNAVAILABLE_TEXT) {
                        DeviceFact(R.string.battery_health_label, state.batteryHealth)
                    }
                    if (state.batteryCycles != UNAVAILABLE_TEXT) {
                        DeviceFact(R.string.battery_cycles_label, state.batteryCycles)
                    }
                    if (state.batteryFirstUse != UNAVAILABLE_TEXT) {
                        DeviceFact(R.string.battery_first_use_label, state.batteryFirstUse)
                    }
                    if (state.batteryManufactured != UNAVAILABLE_TEXT) {
                        DeviceFact(R.string.battery_manufactured_label, state.batteryManufactured)
                    }
                }
            }

            SettingsSectionCard(titleRes = R.string.camera_info_section) {
                DeviceFact(R.string.camera_count, info.cameraCount)
                DeviceFact(R.string.camera_rear, info.rearCamera)
                DeviceFact(R.string.camera_front, info.frontCamera)
                DeviceFact(R.string.camera_flash, info.cameraFlash)
                if (info.cameraZoom != UNAVAILABLE_TEXT) DeviceFact(R.string.camera_zoom, info.cameraZoom)
                if (info.cameraRaw != UNAVAILABLE_TEXT) DeviceFact(R.string.camera_raw, info.cameraRaw)
                if (info.cameraManual != UNAVAILABLE_TEXT) DeviceFact(R.string.camera_manual, info.cameraManual)
                if (info.cameraLevel != UNAVAILABLE_TEXT) DeviceFact(R.string.camera_level, info.cameraLevel)
            }

            SettingsSectionCard(titleRes = R.string.sensors_section) {
                DeviceFact(R.string.sensors_count, info.sensorCount)
                DeviceFact(R.string.sensors_present, info.sensors)
            }

            SettingsSectionCard(titleRes = R.string.system_info_section) {
                DeviceFact(R.string.system_locale, info.locale)
                DeviceFact(R.string.system_timezone, info.timezone)
                DeviceFact(R.string.system_webview, info.webViewVersion)
                DeviceFact(R.string.system_play_services, info.playServicesVersion)
                DeviceFact(R.string.system_play_update, info.playSystemUpdate)
                DeviceFact(R.string.system_mainline_modules, info.mainlineModules)
                DeviceFact(R.string.system_features, info.deviceFeatures)
                DeviceFact(R.string.device_info_boot_count, info.bootCountTotal)
            }
        }

        uiState.deviceState?.let { state ->
            SettingsSectionCard(titleRes = R.string.power_section) {
                DeviceFact(R.string.power_save_mode, state.powerSaveMode)
                DeviceFact(R.string.power_device_idle, state.deviceIdle)
                DeviceFact(R.string.power_battery_optimization_exempt, state.batteryOptimizationExempt)
                
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
                        text = state.otherExemptApps ?: "未知", 
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

        SettingsSectionCard(titleRes = R.string.sim_info_section) {
            DeviceFact(R.string.sim_operator, currentStats.operatorName)
            DeviceFact(R.string.sim_country, currentStats.networkCountry)
            DeviceFact(R.string.sim_network, currentStats.mobileNetworkType)
            DeviceFact(R.string.sim_signal, dbmText(currentStats.mobileSignalDbm))
            uiState.deviceState?.let { state ->
                DeviceFact(R.string.sim_signal_details, state.cellSignalDetails)
                DeviceFact(R.string.sim_signal_bars, state.cellSignalBars)
                DeviceFact(R.string.sim_band, state.cellBand)
            }
            DeviceFact(R.string.sim_status, currentStats.simState)
            DeviceFact(R.string.sim_slots, countText(currentStats.simSlots))
            DeviceFact(simDataLabelRes(uiState.dataCounterMode), gbTodayText(currentStats.mobileDataUsedGb))
        }

        SettingsSectionCard(titleRes = R.string.wifi_info_section) {
            DeviceFact(R.string.wifi_name, currentStats.wifiSsidName)
            DeviceFact(R.string.wifi_band_label, currentStats.wifiBand)
            DeviceFact(R.string.wifi_standard, currentStats.wifiStandard)
            uiState.deviceState?.let { state ->
                DeviceFact(R.string.wifi_security, state.wifiSecurity)
                DeviceFact(R.string.wifi_mlo, state.wifiMloLinks)
            }
            uiState.deviceInfo?.let { info -> DeviceFact(R.string.wifi_capabilities, info.wifiCapabilities) }
            DeviceFact(R.string.wifi_signal, dbmText(currentStats.wifiRssiDbm))
            DeviceFact(R.string.wifi_link_speed, mbpsText(currentStats.wifiLinkSpeedMbps))
            DeviceFact(R.string.wifi_ip, currentStats.ipAddress)
            DeviceFact(wifiDataLabelRes(uiState.dataCounterMode), gbTodayText(currentStats.wifiBytesTodayGb))
            uiState.deviceState?.let { state ->
                DeviceFact(R.string.network_internet, state.internetAccess)
                DeviceFact(R.string.network_bandwidth, state.bandwidthEstimate)
                DeviceFact(R.string.network_metered, state.networkMetered)
            }
            uiState.deviceInfo?.let { info ->
                DeviceFact(R.string.wifi_vpn, info.vpnActive)
                DeviceFact(R.string.wifi_dns, info.dnsServers)
                DeviceFact(R.string.wifi_private_dns, info.privateDns)
                DeviceFact(R.string.wifi_interface, info.networkInterface)
                if (info.mtu != UNAVAILABLE_TEXT) DeviceFact(R.string.wifi_mtu, info.mtu)
            }
        }
    }
}
