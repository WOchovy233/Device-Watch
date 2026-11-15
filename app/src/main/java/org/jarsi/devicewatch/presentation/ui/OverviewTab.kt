package org.jarsi.devicewatch.presentation.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.devicewatch.R
import org.jarsi.devicewatch.data.ChargeSource
import org.jarsi.devicewatch.data.ChargingState
import org.jarsi.devicewatch.data.DataCounterMode
import org.jarsi.devicewatch.data.DataQuotaLogic
import org.jarsi.devicewatch.data.ThermalLevel
import org.jarsi.devicewatch.data.TrafficSplit
import org.jarsi.devicewatch.data.UNAVAILABLE_TEXT
import org.jarsi.devicewatch.presentation.AppsViewModel
import org.jarsi.devicewatch.presentation.DashboardUiState
import org.jarsi.devicewatch.presentation.PeriodComparison
import org.jarsi.devicewatch.ui.theme.STATUS_TINT_ALPHA
import org.jarsi.devicewatch.widget.mobileDataText
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewTab(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSinceCharge: () -> Unit,
    onHideWidgetHint: () -> Unit,
    appsViewModel: AppsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val currentStats = uiState.stats ?: return

    // 拿到应用页面的状态
    val appsUiState by appsViewModel.uiState.collectAsStateWithLifecycle()

    // 一进主页就强制刷新应用流量数据
    LaunchedEffect(Unit) {
        appsViewModel.refresh()
    }

    var exportTextContent by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    // 写入 UTF-8 内容
                    outputStream.write(exportTextContent.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "导出成功！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!uiState.hideWidgetHint) {
            val statusColor = if (uiState.isWidgetInstalled) statusOkColor() else statusWarnColor()
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(statusColor.copy(alpha = STATUS_TINT_ALPHA))
                        .padding(start = BAND_INSET, end = 8.dp, top = BAND_SPACING, bottom = BAND_SPACING),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        modifier = Modifier.weight(1f),
                        text = if (uiState.isWidgetInstalled) {
                            stringResource(R.string.widget_active_message)
                        } else {
                            stringResource(R.string.widget_not_added_message)
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = withTapHaptic(onHideWidgetHint)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭提示",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }

        // Battery section
        SettingsSectionCard(
            titleRes = R.string.battery_status_section,
            horizontalAlignment = Alignment.CenterHorizontally,
            trailing = {
                SectionLink(
                    text = stringResource(R.string.since_charge_title),
                    contentDescription = stringResource(R.string.since_charge_open),
                    onClick = onOpenSinceCharge
                )
            }
        ) {
            val batteryColor = if (currentStats.batteryLevel > 20) statusOkColor() else MaterialTheme.colorScheme.error
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                CircularProgressIndicator(
                    progress = { currentStats.batteryLevel.toFloat() / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 10.dp,
                    color = batteryColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MetricValue(
                        text = "${currentStats.batteryLevel}%",
                        fontSize = HERO_VALUE_SP
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(batteryColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        MetricLabel(currentStats.batteryStatus, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StackedMetricRow(
                    label = stringResource(R.string.time_remaining),
                    value = currentStats.timeRemainingText,
                    valueSize = SECONDARY_VALUE_SP,
                    modifier = Modifier.weight(1f)
                )
                StackedMetricRow(
                    label = stringResource(R.string.temperature),
                    value = "%.1f °C".format(currentStats.batteryTemp),
                    valueSize = SECONDARY_VALUE_SP,
                    modifier = Modifier.weight(1f)
                )
                StackedMetricRow(
                    label = stringResource(R.string.voltage),
                    value = "%.2f V".format(currentStats.batteryVoltage),
                    valueSize = SECONDARY_VALUE_SP,
                    modifier = Modifier.weight(1f)
                )
                if (currentStats.systemEstimateText != UNAVAILABLE_TEXT) {
                    StackedMetricRow(
                        label = stringResource(
                            if (currentStats.systemEstimatePersonalized) {
                                R.string.system_estimate_personalized_label
                            } else {
                                R.string.system_estimate_label
                            }
                        ),
                        value = currentStats.systemEstimateText,
                        valueSize = SECONDARY_VALUE_SP,
                        modifier = Modifier.weight(1f)
                    )
                }
                chargeSourceRes(currentStats.chargeSource)?.let { sourceRes ->
                    StackedMetricRow(
                        label = stringResource(R.string.charge_source_label),
                        value = stringResource(sourceRes),
                        valueSize = SECONDARY_VALUE_SP,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (currentStats.chargeSource != ChargeSource.NONE) {
                    chargingStateRes(currentStats.chargingState)?.let { stateRes ->
                        StackedMetricRow(
                            label = stringResource(R.string.charging_state_label),
                            value = stringResource(stateRes),
                            valueSize = SECONDARY_VALUE_SP,
                            valueColor = when (currentStats.chargingState) {
                                ChargingState.TOO_COLD, ChargingState.TOO_HOT -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Usage counters
        SettingsSectionCard(
            titleRes = if (uiState.dataCounterMode == DataCounterMode.DAY) {
                R.string.usage_counters_section
            } else {
                R.string.usage_counters_section_period
            },
            trailing = {
                SectionLink(
                    text = stringResource(R.string.history_title),
                    contentDescription = stringResource(R.string.history_open),
                    onClick = onOpenHistory
                )
            }
        ) {
            run {
                StackedMetricRow(
                    label = stringResource(R.string.screen_time_total_label),
                    value = if (uiState.screenTimeMillis >= 0L) {
                        durationText(context, uiState.screenTimeMillis)
                    } else {
                        UNAVAILABLE_TEXT
                    }
                )
                if (uiState.screenOnTrackingSupported) {
                    StackedMetricRow(
                        label = stringResource(R.string.screen_on_label),
                        value = if (uiState.screenOnMillis >= 0L) {
                            durationText(context, uiState.screenOnMillis)
                        } else {
                            UNAVAILABLE_TEXT
                        }
                    )
                }
                if (uiState.unlockCountingSupported) {
                    StackedMetricRow(
                        label = stringResource(R.string.unlock_count_label),
                        value = countOrDashText(uiState.unlockCount)
                    )
                }
                if (uiState.notificationAccessEnabled) {
                    StackedMetricRow(
                        label = stringResource(R.string.notification_count_label),
                        value = countOrDashText(uiState.notificationCount)
                    )
                } else {
                    LabelValueRow(
                        label = {
                            Text(
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                text = stringResource(R.string.notification_count_label),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        value = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = UNAVAILABLE_TEXT,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                TextButton(onClick = withTapHaptic {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }) {
                                    Text(
                                        stringResource(R.string.notification_access_enable),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    )
                }
                StackedMetricRow(
                    label = stringResource(R.string.boot_count_label),
                    value = uiState.bootCount.toString()
                )
                StackedMetricRow(
                    label = stringResource(R.string.charge_count_label),
                    value = uiState.chargeCount.toString()
                )
                uiState.periodComparison?.let { comparison ->
                    val screenPrev = comparison.screenTimePrevMillis
                        ?.takeIf { uiState.screenTimeMillis >= 0L }
                    val unlocksPrev = comparison.unlocksPrev
                        ?.takeIf { uiState.usageAccessEnabled && uiState.unlockCountingSupported }
                    val notificationsPrev = comparison.notificationsPrev
                        ?.takeIf { uiState.notificationAccessEnabled }
                    if (screenPrev == null && unlocksPrev == null && notificationsPrev == null) {
                        return@let
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        text = stringResource(R.string.comparison_section_label),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (screenPrev != null) {
                        ComparisonRow(
                            labelRes = R.string.screen_time_total_label,
                            previousText = durationText(context, screenPrev),
                            nowText = durationText(context, comparison.screenTimeNowMillis),
                            changePercent = PeriodComparison.changePercent(
                                comparison.screenTimeNowMillis, screenPrev
                            )
                        )
                    }
                    if (unlocksPrev != null) {
                        ComparisonRow(
                            labelRes = R.string.unlock_count_label,
                            previousText = unlocksPrev.toString(),
                            nowText = comparison.unlocksNow.toString(),
                            changePercent = PeriodComparison.changePercent(
                                comparison.unlocksNow.toLong(), unlocksPrev.toLong()
                            )
                        )
                    }
                    if (notificationsPrev != null) {
                        ComparisonRow(
                            labelRes = R.string.notification_count_label,
                            previousText = notificationsPrev.toString(),
                            nowText = comparison.notificationsNow.toString(),
                            changePercent = PeriodComparison.changePercent(
                                comparison.notificationsNow.toLong(),
                                notificationsPrev.toLong()
                            )
                        )
                    }
                    Text(
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        text = if (uiState.dataCounterMode == DataCounterMode.DAY) {
                            stringResource(R.string.comparison_yesterday)
                        } else {
                            pluralStringResource(
                                R.plurals.comparison_days,
                                comparison.daysCompared,
                                comparison.daysCompared
                            )
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 流量统计卡片
        SettingsSectionCard(titleRes = R.string.data_counter_section) {
            StackedMetricRow(
                label = stringResource(wifiDataLabelRes(uiState.dataCounterMode)),
                value = gbTodayText(currentStats.wifiBytesTodayGb)
            )
            uiState.dataBreakdown?.wifi?.let { DataSplitCaption(it) }
            val mobileLabel = stringResource(simDataLabelRes(uiState.dataCounterMode))
            val simName = currentStats.dataSimName.takeIf { it != UNAVAILABLE_TEXT }
            StackedMetricRow(
                label = if (simName != null) {
                    stringResource(R.string.label_with_sim, mobileLabel, simName)
                } else {
                    mobileLabel
                },
                value = mobileDataText(currentStats.mobileDataUsedGb, currentStats.mobileDataTotalGb)
            )
            uiState.dataBreakdown?.mobile?.let { DataSplitCaption(it) }
            uiState.dataBreakdown?.mobileRoamingBytes?.takeIf { it > 0 }?.let { roamingBytes ->
                DataCaption(
                    stringResource(R.string.data_roaming_caption, gbTodayText(roamingBytes / GB_BYTES))
                )
            }
            val quotaPercentUsed = DataQuotaLogic.percentUsed(
                quotaGb = currentStats.mobileDataTotalGb,
                usedGb = currentStats.mobileDataUsedGb,
            )
            if (quotaPercentUsed != null) {
                LinearProgressIndicator(
                    progress = { (quotaPercentUsed / 100f).coerceAtMost(1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(METER_HEIGHT)
                        .clip(RoundedCornerShape(METER_RADIUS)),
                    color = if (quotaPercentUsed >= 100) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // 导出按钮
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = withTapHaptic {
                    // 如果数据为空，提示用户稍等，并再次触发刷新
                    if (appsUiState.dataConsumers.isEmpty()) {
                        appsViewModel.refresh()
                        Toast.makeText(context, "数据拉取中，请等待3秒后再点一次导出！", Toast.LENGTH_LONG).show()
                        return@withTapHaptic
                    }

                    val sb = StringBuilder()
                    // 去除所有表情符号，保证老旧记事本不乱码
                    sb.append("【设备流量全量审计报告】\n")
                    sb.append("导出时间: ${LocalDate.now()}\n")
                    sb.append("统计周期: ${if (uiState.dataCounterMode == DataCounterMode.DAY) "今日" else "本月（计费周期）"}\n")
                    sb.append("==============================\n\n")
                    
                    val sortedData = appsUiState.dataConsumers.sortedByDescending { it.bytes }
                    var count = 0
                    
                    sortedData.forEach { consumer ->
                        if (consumer.bytes > 0) {
                            val pkgName = consumer.packageName ?: "系统服务(无包名)"
                            sb.append("应用名称: ${consumer.label}\n")
                            sb.append("应用包名: $pkgName\n")
                            sb.append("消耗流量: ${bytesText(consumer.bytes)}\n")
                            sb.append("------------------------------\n")
                            count++
                        }
                    }
                    
                    if (count == 0) {
                        sb.append("暂无任何应用产生流量。\n")
                    } else {
                        sb.append("\n共计导出 $count 个应用的数据。\n")
                    }

                    exportTextContent = sb.toString()
                    exportLauncher.launch("全量流量审计_${LocalDate.now()}.txt")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出全量流量明细 (TXT)", fontWeight = FontWeight.Bold)
            }
        }

        LiveTrafficSection()

        SettingsSectionCard(titleRes = R.string.system_resources_section) {
            Spacer(modifier = Modifier.height(4.dp))

            StackedMetricRow(
                label = stringResource(R.string.ram_title),
                value = "%.1f / %.1f GB (%d%%)".format(
                    currentStats.usedRamGb, currentStats.totalRamGb, currentStats.ramPercent
                ),
                valueColor = meterColor()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { currentStats.ramPercent.coerceAtLeast(0).toFloat() / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(METER_HEIGHT)
                    .clip(RoundedCornerShape(METER_RADIUS)),
                color = meterColor(),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))

            StackedMetricRow(
                label = stringResource(R.string.cpu_load_row),
                value = buildString {
                    if (currentStats.cpuLoadPercent >= 0) {
                        append("${currentStats.cpuLoadPercent} %")
                    } else {
                        append(UNAVAILABLE_TEXT)
                    }
                    if (currentStats.cpuFreqGhz >= 0.0) {
                        append(" · ${"%.1f".format(currentStats.cpuFreqGhz)} GHz")
                    }
                },
                valueColor = meterColor()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { currentStats.cpuLoadPercent.coerceAtLeast(0).toFloat() / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(METER_HEIGHT)
                    .clip(RoundedCornerShape(METER_RADIUS)),
                color = meterColor(),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            if (currentStats.gpuLoadPercent >= 0) {
                Spacer(modifier = Modifier.height(12.dp))
                StackedMetricRow(
                    label = stringResource(R.string.gpu_load_row),
                    value = "${currentStats.gpuLoadPercent} %",
                    valueSize = SECONDARY_VALUE_SP,
                )
            }
            val temperatures = listOfNotNull(
                currentStats.cpuTemp.takeIf { it > 0.0 }?.let { stringResource(R.string.temperature_cpu, celsiusText(it)) },
                currentStats.gpuTemp.takeIf { it > 0.0 }?.let { stringResource(R.string.temperature_gpu, celsiusText(it)) },
                currentStats.skinTemp.takeIf { it > 0.0 }?.let { stringResource(R.string.temperature_skin, celsiusText(it)) },
            )
            if (temperatures.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                StackedMetricRow(
                    label = stringResource(R.string.temperatures_row),
                    value = temperatures.joinToString(" · "),
                    valueSize = SECONDARY_VALUE_SP,
                )
            }

            thermalLevelRes(currentStats.thermalLevel)?.let { levelRes ->
                Spacer(modifier = Modifier.height(12.dp))
                val level = stringResource(levelRes)
                StackedMetricRow(
                    label = stringResource(R.string.thermal_label),
                    value = if (currentStats.thermalHeadroomPercent >= 0) {
                        stringResource(R.string.thermal_value, level, currentStats.thermalHeadroomPercent)
                    } else {
                        level
                    },
                    valueSize = SECONDARY_VALUE_SP,
                    valueColor = if (currentStats.thermalLevel >= ThermalLevel.SEVERE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (currentStats.thermalHeadroomPercent >= 0) {
                    Text(
                        text = stringResource(R.string.thermal_limit_caption),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))

            StackedMetricRow(
                label = stringResource(R.string.storage_row),
                value = "%.0f / %.0f GB (%d%%)".format(
                    currentStats.usedStorageGb, currentStats.totalStorageGb, currentStats.storagePercent
                ),
                valueColor = meterColor()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { currentStats.storagePercent.coerceAtLeast(0).toFloat() / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(METER_HEIGHT)
                    .clip(RoundedCornerShape(METER_RADIUS)),
                color = meterColor(),
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val freeStorage = currentStats.totalStorageGb - currentStats.usedStorageGb
            Text(
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                text = stringResource(R.string.widget_free_value, "%.0f GB".format(freeStorage)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            uiState.storageBreakdown?.let { storage ->
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    maxItemsInEachRow = 2,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    listOf(
                        R.string.storage_apps to storage.appsBytes,
                        R.string.storage_cache to storage.cacheBytes,
                        R.string.storage_images to storage.imageBytes,
                        R.string.storage_video to storage.videoBytes,
                        R.string.storage_audio to storage.audioBytes,
                        R.string.storage_other to storage.otherBytes,
                    ).forEach { (labelRes, bytes) ->
                        StackedMetricRow(
                            label = stringResource(labelRes),
                            value = gbTodayText(bytes / GB_BYTES),
                            valueSize = STORAGE_PART_SP,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(16.dp))

            LabelValueRow(
                label = {
                    Column {
                        Text(
                            stringResource(R.string.cpu_cores_label),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            context.resources.getQuantityString(
                                R.plurals.cpu_cores_value,
                                currentStats.cpuCores,
                                currentStats.cpuCores,
                                currentStats.cpuAbi
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                value = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(R.string.uptime_label),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            currentStats.uptimeText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            )

            if (currentStats.deepSleepText != UNAVAILABLE_TEXT) {
                Spacer(modifier = Modifier.height(8.dp))
                StackedMetricRow(
                    label = stringResource(R.string.deep_sleep_label),
                    value = currentStats.deepSleepText,
                    valueSize = SECONDARY_VALUE_SP
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = withTapHaptic(onRefresh),
            modifier = Modifier.padding(top = 28.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.refresh_data))
        }

        Text(
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            text = stringResource(R.string.last_updated, uiState.lastUpdated),
            modifier = Modifier.padding(top = 12.dp, bottom = 28.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DataSplitCaption(split: TrafficSplit) {
    DataCaption(stringResource(R.string.data_split_caption, split.foregroundPercent, split.backgroundPercent))
}

@Composable
private fun DataCaption(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

private const val GB_BYTES = 1024.0 * 1024.0 * 1024.0
private val STORAGE_PART_SP = 16.sp

private fun thermalLevelRes(level: ThermalLevel): Int? = when (level) {
    ThermalLevel.UNKNOWN -> null
    ThermalLevel.NONE -> R.string.thermal_none
    ThermalLevel.LIGHT -> R.string.thermal_light
    ThermalLevel.MODERATE -> R.string.thermal_moderate
    ThermalLevel.SEVERE -> R.string.thermal_severe
    ThermalLevel.CRITICAL -> R.string.thermal_critical
    ThermalLevel.EMERGENCY -> R.string.thermal_emergency
    ThermalLevel.SHUTDOWN -> R.string.thermal_shutdown
}

private fun chargingStateRes(state: ChargingState): Int? = when (state) {
    ChargingState.UNKNOWN -> null
    ChargingState.NORMAL -> R.string.charging_state_normal
    ChargingState.TOO_COLD -> R.string.charging_state_too_cold
    ChargingState.TOO_HOT -> R.string.charging_state_too_hot
    ChargingState.LONG_LIFE -> R.string.charging_state_long_life
    ChargingState.ADAPTIVE -> R.string.charging_state_adaptive
}

private fun chargeSourceRes(source: ChargeSource): Int? = when (source) {
    ChargeSource.NONE -> null
    ChargeSource.AC -> R.string.charge_source_ac
    ChargeSource.USB -> R.string.charge_source_usb
    ChargeSource.WIRELESS -> R.string.charge_source_wireless
    ChargeSource.DOCK -> R.string.charge_source_dock
}

@Composable
private fun ComparisonRow(
    @StringRes labelRes: Int,
    previousText: String,
    nowText: String,
    changePercent: Int?,
) {
    LabelValueRow(
        modifier = Modifier.padding(vertical = 3.dp),
        label = {
            Text(
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                text = stringResource(labelRes),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        value = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    text = stringResource(R.string.comparison_values, previousText, nowText),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    text = changePercent?.let {
                        stringResource(R.string.comparison_change, if (it > 0) "+" else "", it)
                    } ?: UNAVAILABLE_TEXT,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        changePercent == null || changePercent == 0 ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                        changePercent > 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    )
}
