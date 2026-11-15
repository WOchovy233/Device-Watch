package org.jarsi.devicewatch.presentation.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.jarsi.devicewatch.R
import org.jarsi.devicewatch.presentation.AppsViewModel
import org.jarsi.devicewatch.presentation.DashboardViewModel
import org.jarsi.devicewatch.presentation.KernelViewModel
import org.jarsi.devicewatch.presentation.PrivacyState
import org.jarsi.devicewatch.presentation.PrivacyViewModel
import org.jarsi.devicewatch.presentation.KernelBandState
import org.jarsi.devicewatch.presentation.RootStatus
import org.jarsi.devicewatch.presentation.ShizukuStatus
import org.jarsi.devicewatch.presentation.SystemAppsViewModel
import org.jarsi.devicewatch.system.SystemMonitorService

private val SELECTED_TAB_RULE = 3.dp

internal enum class DashboardTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Overview(R.string.tab_overview, Icons.Filled.Home),
    Apps(R.string.tab_apps, Icons.Filled.Apps),
    Device(R.string.tab_device, Icons.Filled.PhoneAndroid),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemDashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    appsViewModel: AppsViewModel = hiltViewModel(),
    kernelViewModel: KernelViewModel = hiltViewModel(),
    privacyViewModel: PrivacyViewModel = hiltViewModel(),
    systemAppsViewModel: SystemAppsViewModel = hiltViewModel(),
    openOverviewRequest: Int = 0,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val appsUiState by appsViewModel.uiState.collectAsStateWithLifecycle()

    fun startSystemMonitorService() {
        val serviceIntent = Intent(context, SystemMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startSystemMonitorService()
        viewModel.refresh()
    }

    val pagerState = rememberPagerState(pageCount = { DashboardTab.entries.size })
    val selectedTab = DashboardTab.entries[pagerState.currentPage]
    val scope = rememberCoroutineScope()

    var showHistory by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHistory) { showHistory = false }

    var showSinceCharge by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSinceCharge) { showSinceCharge = false }

    var showKernel by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showKernel) { showKernel = false }
    LaunchedEffect(uiState.rootStatus) {
        if (uiState.rootStatus != RootStatus.ON) showKernel = false
    }

    val extendedAccessOn = uiState.rootStatus == RootStatus.ON || uiState.shizukuStatus == ShizukuStatus.ON
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showPrivacy) { showPrivacy = false }
    LaunchedEffect(extendedAccessOn) {
        if (!extendedAccessOn) showPrivacy = false
    }

    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSystemApps) { showSystemApps = false }
    LaunchedEffect(extendedAccessOn) {
        if (!extendedAccessOn) showSystemApps = false
    }

    LaunchedEffect(openOverviewRequest) {
        if (openOverviewRequest > 0) {
            showHistory = false
            showSinceCharge = false
            showKernel = false
            showPrivacy = false
            showSystemApps = false
            pagerState.scrollToPage(DashboardTab.Overview.ordinal)
        }
    }

    val onboardingCompleted = uiState.onboardingCompleted
    var replayOnboarding by rememberSaveable { mutableStateOf(false) }
    var permissionsAskedInIntro by remember { mutableStateOf(false) }
    BackHandler(enabled = replayOnboarding) { replayOnboarding = false }

    LaunchedEffect(Unit) {
        startSystemMonitorService()
        viewModel.refresh()
        viewModel.loadWidgetAppearance()
        viewModel.loadDataCounterSettings()
        viewModel.loadDeviceInfo()
    }

    LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted == true && !permissionsAskedInIntro) {
            val missingPermissions = missingRuntimePermissions(context)
            if (missingPermissions.isNotEmpty()) {
                context.markRuntimePermissionsRequested()
                runtimePermissionLauncher.launch(missingPermissions)
            }
        }
    }

    if (onboardingCompleted == false || replayOnboarding) {
        OnboardingPage(
            usageAccessGranted = uiState.usageAccessEnabled,
            notificationAccessGranted = uiState.notificationAccessEnabled,
            onRequestRefresh = viewModel::refresh,
            onFinish = { requestedPermissions ->
                if (replayOnboarding) {
                    replayOnboarding = false
                } else {
                    permissionsAskedInIntro = requestedPermissions
                    viewModel.completeOnboarding()
                }
            },
        )
        return
    }

    if (showHistory) {
        HistoryPage(onBack = { showHistory = false })
        return
    }

    if (showSinceCharge) {
        SinceChargePage(onBack = { showSinceCharge = false })
        return
    }

    if (showKernel && uiState.rootStatus == RootStatus.ON) {
        KernelPage(onBack = { showKernel = false }, viewModel = kernelViewModel)
        return
    }

    if (showPrivacy && extendedAccessOn) {
        PrivacyPage(onBack = { showPrivacy = false }, viewModel = privacyViewModel)
        return
    }

    if (showSystemApps && extendedAccessOn) {
        SystemAppsPage(onBack = { showSystemApps = false }, viewModel = systemAppsViewModel)
        return
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.app_name).uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = SECTION_TITLE_TRACKING,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.main_title),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = withTapHaptic {
                        viewModel.refresh()
                        if (selectedTab == DashboardTab.Apps) {
                            appsViewModel.refresh()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                NavigationBar {
                    val indicator = MaterialTheme.colorScheme.primary
                    DashboardTab.entries.forEachIndexed { index, tab ->
                        val selected = index == pagerState.currentPage
                        NavigationBarItem(
                            selected = selected,
                            onClick = withTapHaptic {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            modifier = Modifier.drawBehind {
                                if (selected) {
                                    val rule = SELECTED_TAB_RULE.toPx()
                                    drawRect(
                                        color = indicator,
                                        topLeft = Offset(0f, size.height - rule),
                                        size = Size(size.width, rule)
                                    )
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = {
                                Text(
                                    stringResource(tab.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        if (uiState.stats != null) {
            val isPullRefreshing = uiState.isRefreshing ||
                (selectedTab == DashboardTab.Apps && appsUiState.isRefreshing)
            PullToRefreshBox(
                isRefreshing = isPullRefreshing,
                onRefresh = {
                    view.performTapHaptic()
                    viewModel.pullRefresh()
                    if (selectedTab == DashboardTab.Apps) {
                        appsViewModel.pullRefresh()
                    }
                },
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (DashboardTab.entries[page]) {
                        DashboardTab.Overview -> OverviewTab(
                            uiState = uiState,
                            onRefresh = viewModel::refresh,
                            onOpenHistory = { showHistory = true },
                            onOpenSinceCharge = { showSinceCharge = true },
                            onHideWidgetHint = viewModel::onHideWidgetHint // 🎯 传过去
                        )

                        DashboardTab.Apps -> {
                            val privacy = if (extendedAccessOn) {
                                privacyViewModel.state.collectAsStateWithLifecycle().value
                            } else {
                                PrivacyState()
                            }
                            AppsTab(
                                viewModel = appsViewModel,
                                privacy = privacy,
                                extendedAccessOn = extendedAccessOn,
                                onOpenPrivacy = { showPrivacy = true },
                                onOpenSystemApps = { showSystemApps = true },
                            )
                        }

                        DashboardTab.Device -> {
                            val kernelBand = if (uiState.rootStatus == RootStatus.ON) {
                                kernelViewModel.band.collectAsStateWithLifecycle().value
                            } else {
                                KernelBandState()
                            }
                            DeviceTab(
                                uiState = uiState,
                                kernelBand = kernelBand,
                                onOpenKernel = { showKernel = true },
                                viewModel = viewModel // 🎯 传过去
                            )
                        }

                        DashboardTab.Settings -> SettingsTab(
                            uiState = uiState,
                            onWidgetOpacityChange = viewModel::onWidgetOpacityChange,
                            onCommitWidgetOpacity = viewModel::commitWidgetOpacity,
                            onWidgetBlackBackgroundChange = viewModel::onWidgetBlackBackgroundChange,
                            onDataCounterModeSelected = viewModel::onDataCounterModeSelected,
                            onCycleStartDayChange = viewModel::onCycleStartDayChange,
                            onCommitCycleStartDay = viewModel::commitCycleStartDay,
                            onDataQuotaChange = viewModel::onDataQuotaChange,
                            onCommitDataQuota = viewModel::onCommitDataQuota,
                            onChargeLimitChange = viewModel::onChargeLimitChange,
                            onCommitChargeLimit = viewModel::onCommitChargeLimit,
                            onChargeLimitSoundToggle = viewModel::onChargeLimitPlaySoundToggle,
                            onChargeLimitVibrateToggle = viewModel::onChargeLimitVibrateToggle,
                            onClassicLookChange = viewModel::onClassicLookChange,
                            onRootModeChange = viewModel::onRootModeChange,
                            onShizukuModeChange = viewModel::onShizukuModeChange,
                            onAlertToggle = viewModel::onAlertToggle,
                            onNotificationMetricToggle = viewModel::onNotificationMetricToggle,
                            onTileValueIconsChange = viewModel::onTileValueIconsChange,
                            onOverlayShownChange = viewModel::onOverlayShownChange,
                            onOverlayMetricToggle = viewModel::onOverlayMetricToggle,
                            onOverlayPermissionChecked = viewModel::onOverlayPermissionChecked,
                            onShowIntro = { replayOnboarding = true }
                        )
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
