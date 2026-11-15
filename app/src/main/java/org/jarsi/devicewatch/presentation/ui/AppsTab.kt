package org.jarsi.devicewatch.presentation.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.devicewatch.R
import org.jarsi.devicewatch.data.LaunchableApp
import org.jarsi.devicewatch.data.UsageEventAggregator
import org.jarsi.devicewatch.presentation.AppsViewModel
import org.jarsi.devicewatch.presentation.PrivacyState

/** Apps tab: the privacy band, screen-time donut, top data consumers and the last-opened list. */
@Composable
internal fun AppsTab(
    viewModel: AppsViewModel = hiltViewModel(),
    privacy: PrivacyState = PrivacyState(),
    extendedAccessOn: Boolean = false,
    onOpenPrivacy: () -> Unit = {},
    onOpenSystemApps: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 🎯 新增：弹窗状态管理
    var showAllData by remember { mutableStateOf(false) }
    var showAllStorage by remember { mutableStateOf(false) }
    var showAllScreenTime by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val uninstallLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refresh()
    }

    fun openAction(action: String) {
        try {
            context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    uiState.selectedDetail?.let { detail ->
        AppDetailSheet(
            detail = detail,
            onDismiss = viewModel::onDetailDismiss,
            onEnableNotifications = { openAction(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        )
    }

    // 🎯 新增：全量数据的弹窗（这里原本你的 it.packageName!! 会报错，因为泛型 T 不知道有 packageName，我用 as? 转换了一下，不影响你原本功能）
    if (showAllData) {
        ListDialog(
            title = "全量流量使用",
            apps = uiState.dataConsumers,
            valueText = { bytesText(it.bytes) },
            onDismiss = { showAllData = false },
            onAppSelected = { 
                val pkg = (it as? org.jarsi.devicewatch.data.AppDataUsage)?.packageName
                if (pkg != null) { viewModel.onAppSelected(pkg); showAllData = false }
            }
        )
    }
    if (showAllStorage) {
        ListDialog(
            title = "全量存储占用",
            apps = uiState.storageConsumers,
            valueText = { bytesText(it.bytes) },
            onDismiss = { showAllStorage = false },
            onAppSelected = { 
                val pkg = (it as? org.jarsi.devicewatch.data.AppStorageUsage)?.packageName
                if (pkg != null) { viewModel.onAppSelected(pkg); showAllStorage = false }
            }
        )
    }
    if (showAllScreenTime) {
        ListDialog(
            title = "全量屏幕时间",
            apps = uiState.screenTimes,
            valueText = { durationText(context, it.foregroundMillis) },
            onDismiss = { showAllScreenTime = false },
            onAppSelected = { 
                val pkg = (it as? org.jarsi.devicewatch.data.AppScreenTime)?.packageName
                if (pkg != null) { viewModel.onAppSelected(pkg); showAllScreenTime = false }
            }
        )
    }

    if (!uiState.hasUsageAccess) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = Icons.Filled.QueryStats, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = stringResource(R.string.usage_access_needed_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.usage_access_needed_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(onClick = withTapHaptic { openAction(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.usage_access_button), fontSize = 12.sp)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "privacy") {
            PrivacyBand(state = privacy, extendedAccessOn = extendedAccessOn, onOpen = onOpenPrivacy, onOpenDashboard = { openPrivacyDashboard(context) })
        }

        item(key = "system_apps") {
            SystemAppsBand(extendedAccessOn = extendedAccessOn, onOpen = onOpenSystemApps)
        }

        // 🎯 亮屏时间区
        item(key = "screen_time") {
            SettingsSectionCard(titleRes = R.string.screen_time_section, horizontalAlignment = Alignment.CenterHorizontally) {
                val colors = donutSegmentColors(uiState.screenTimeSegments)
                Spacer(modifier = Modifier.height(4.dp))
                ScreenTimeDonut(segments = uiState.screenTimeSegments, colors = colors, totalText = durationText(context, uiState.totalScreenTimeMillis))
                Spacer(modifier = Modifier.height(12.dp))
                // 只显示前 5 项
                uiState.screenTimeSegments.take(5).forEachIndexed { index, segment ->
                    val rowModifier = if (segment.packageName != null) {
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = withTapHaptic { 
                            // 修复：这里是报错重灾区，去掉了 !!
                            segment.packageName?.let { viewModel.onAppSelected(it) } 
                        }).padding(vertical = 6.dp, horizontal = 4.dp)
                    } else {
                        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)
                    }
                    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(colors[index]))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = segment.label ?: stringResource(R.string.screen_time_others), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = detailLinkDecoration(segment.packageName != null), modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = durationText(context, segment.millis), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${(segment.fraction * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // 🎯 如果项数多于 5 个，加个查看全部
                if (uiState.screenTimeSegments.size > 5 || uiState.screenTimeSegments.any { it.packageName == null }) {
                    TextButton(onClick = withTapHaptic { showAllScreenTime = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("查看全部应用")
                    }
                }
            }
        }

        // 最常打开区
        val mostOpened = UsageEventAggregator.topByLaunches(uiState.screenTimes)
        if (mostOpened.isNotEmpty()) {
            item(key = "most_opened") {
                SettingsSectionCard(titleRes = R.string.most_opened_section) {
                    mostOpened.take(5).forEach { entry ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = withTapHaptic { viewModel.onAppSelected(entry.packageName) }).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(entry.packageName, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = entry.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = detailLinkDecoration(true), modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(maxLines = 1, overflow = TextOverflow.Ellipsis, text = "${entry.launchCount}×", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 🎯 流量消耗区
        item(key = "data_consumers") {
            SettingsSectionCard(titleRes = R.string.data_consumers_section) {
                uiState.dataConsumers.take(5).forEach { consumer ->
                    val rowModifier = if (consumer.packageName != null) {
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = withTapHaptic { 
                            // 修复：去掉了 !!
                            consumer.packageName?.let { viewModel.onAppSelected(it) } 
                        }).padding(vertical = 6.dp, horizontal = 4.dp)
                    } else {
                        Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)
                    }
                    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
                        if (consumer.packageName != null) {
                            // 修复：去掉了 !!
                            consumer.packageName?.let { AppIcon(it, modifier = Modifier.size(28.dp)) }
                        } else Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = consumer.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = detailLinkDecoration(consumer.packageName != null), modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(maxLines = 1, overflow = TextOverflow.Ellipsis, text = bytesText(consumer.bytes), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (uiState.dataConsumers.size > 5) {
                    TextButton(onClick = withTapHaptic { showAllData = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("查看全部应用")
                    }
                }
            }
        }

        // 🎯 存储占用区
        if (uiState.storageConsumers.isNotEmpty()) {
            item(key = "storage_consumers") {
                SettingsSectionCard(titleRes = R.string.storage_consumers_section) {
                    uiState.storageConsumers.take(5).forEach { app ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = withTapHaptic { viewModel.onAppSelected(app.packageName) }).padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = app.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = detailLinkDecoration(true), modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(maxLines = 1, overflow = TextOverflow.Ellipsis, text = bytesText(app.bytes), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (uiState.storageConsumers.size > 5) {
                        TextButton(onClick = withTapHaptic { showAllStorage = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("查看全部应用")
                        }
                    }
                }
            }
        }

        // 🎯 应用列表区（劈成两半） - 修复：时间过滤加上了 ?: 0L
        item(key = "recent_opened_header") {
            Text(
                text = "最近打开",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = BAND_INSET, end = BAND_INSET, top = 8.dp, bottom = 4.dp)
            )
        }
        items(uiState.apps.filter { (it.lastUsedEpochMillis ?: 0L) > 0L }.sortedByDescending { it.lastUsedEpochMillis ?: 0L }.take(10), key = { it.packageName }) { app ->
            AppListRow(app = app, onClick = { viewModel.onAppSelected(app.packageName) }, onUninstall = { uninstallLauncher.launch(Intent(Intent.ACTION_DELETE, "package:${app.packageName}".toUri())) })
        }

        item(key = "least_opened_header") {
            Text(
                text = "最久未打开",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = BAND_INSET, end = BAND_INSET, top = 16.dp, bottom = 4.dp)
            )
        }
        items(uiState.apps.filter { (it.lastUsedEpochMillis ?: 0L) > 0L }.sortedBy { it.lastUsedEpochMillis ?: 0L }.take(10), key = { it.packageName }) { app ->
            AppListRow(app = app, onClick = { viewModel.onAppSelected(app.packageName) }, onUninstall = { uninstallLauncher.launch(Intent(Intent.ACTION_DELETE, "package:${app.packageName}".toUri())) })
        }
    }
}

// 🎯 新增：全量数据弹窗组件
@Composable
private fun <T> ListDialog(
    title: String,
    apps: List<T>,
    valueText: (T) -> String,
    onDismiss: () -> Unit,
    onAppSelected: (T) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(apps.size) { index ->
                    val item = apps[index]
                    val packageName = when (item) {
                        is org.jarsi.devicewatch.data.AppDataUsage -> item.packageName
                        is org.jarsi.devicewatch.data.AppStorageUsage -> item.packageName
                        is org.jarsi.devicewatch.data.AppScreenTime -> item.packageName
                        else -> null
                    }
                    val label = when (item) {
                        is org.jarsi.devicewatch.data.AppDataUsage -> item.label
                        is org.jarsi.devicewatch.data.AppStorageUsage -> item.label
                        is org.jarsi.devicewatch.data.AppScreenTime -> item.label
                        else -> "Unknown"
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = packageName != null) { onAppSelected(item) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (packageName != null) AppIcon(packageName, modifier = Modifier.size(24.dp)) else Box(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = label ?: "Unknown", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textDecoration = if (packageName != null) TextDecoration.Underline else null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = valueText(item), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun AppListRow(app: LaunchableApp, onClick: () -> Unit, onUninstall: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = withTapHaptic(onClick)).padding(vertical = 6.dp, horizontal = BAND_INSET), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.packageName, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = detailLinkDecoration(true))
            
            // 修复：这里原本传参如果是空会报错，现在处理了空值
            val lastUsed = app.lastUsedEpochMillis ?: 0L
            val tier = UsageEventAggregator.lastUsedTier(daysSinceLastUse(lastUsed))
            Text(maxLines = 1, overflow = TextOverflow.Ellipsis, text = lastUsedText(lastUsed), fontSize = 12.sp, color = lastUsedTierColor(tier))
        }
        if (!app.isSystemApp) {
            IconButton(onClick = withTapHaptic(onUninstall)) {
                Icon(imageVector = Icons.Outlined.Delete, contentDescription = stringResource(R.string.uninstall_content_description), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
