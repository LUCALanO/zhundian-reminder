package io.github.zhundianapp.zhundian

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.zhundianapp.zhundian.R
import io.github.zhundianapp.zhundian.service.ReminderService
import io.github.zhundianapp.zhundian.ui.calendar.CalendarScreen
import io.github.zhundianapp.zhundian.ui.edit.EventEditScreen
import io.github.zhundianapp.zhundian.ui.edit.ReminderEditScreen
import io.github.zhundianapp.zhundian.ui.list.ReminderListScreen
import io.github.zhundianapp.zhundian.ui.settings.CalendarSettingsScreen
import io.github.zhundianapp.zhundian.ui.settings.KeepAliveSettingsScreen
import io.github.zhundianapp.zhundian.ui.theme.IntervalReminderTheme
import io.github.zhundianapp.zhundian.util.AppLanguage
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 结果由列表页权限提示横幅实时反映，无需在此处理
        }

    // 电池优化白名单授权对话框须由前台 Activity 拉起
    private val requestIgnoreBatteryOptimizations =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 结果由列表页在回到前台时重新评估，无需在此处理
        }

    // Android 8–12 上应用已保存的应用内语言（Android 13+ 走系统 LocaleManager）
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrapContext(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as IntervalReminderApp).container
        val permissionManager = container.permissionManager
        // 首次启动请求通知权限（Android 13+）
        if (!permissionManager.hasNotificationPermission()) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 启动常驻前台服务：保活 + 进程内计时（前台启动，必然允许）
        try {
            ReminderService.start(this)
        } catch (_: Exception) {
            // 受限 ROM 兜底：前台服务启动失败不影响 App 正常使用
        }

        val initialReminderId = intent?.getLongExtra(EXTRA_REMINDER_ID, -1L) ?: -1L

        setContent {
            IntervalReminderTheme {
                AppNavHost(
                    initialHighlightedReminderId = initialReminderId.takeIf { it > 0 },
                    onOpenBatterySettings = { openBatteryOptimizationSettings() }
                )
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        val permissionManager = (application as IntervalReminderApp).container.permissionManager
        requestIgnoreBatteryOptimizations.launch(permissionManager.ignoreBatteryOptimizationsIntent())
    }
}

@Composable
private fun AppNavHost(
    initialHighlightedReminderId: Long?,
    onOpenBatterySettings: () -> Unit
) {
    val navController = rememberNavController()
    val highlightedReminderId = remember { initialHighlightedReminderId }

    // 底部导航选中态与显隐由当前路由决定。
    // 「日历」Tab 只对应全局日历；单提醒日历（calendar/{id}）与编辑页同属任务性全屏页，不显示底部栏。
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isCalendarRoute = currentRoute == "calendar"
    val showBottomBar = currentRoute == "list" || isCalendarRoute

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == "list",
                        onClick = {
                            // 直接弹回列表根：从任意深层页面都能可靠切回
                            if (!navController.popBackStack("list", false)) {
                                navController.navigate("list") { launchSingleTop = true }
                            }
                        },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_tab_reminders)) }
                    )
                    NavigationBarItem(
                        selected = isCalendarRoute,
                        onClick = {
                            // 已停在全局日历则无操作，否则进入全局日历
                            if (!navController.popBackStack("calendar", false)) {
                                navController.navigate("calendar") {
                                    popUpTo(navController.graph.findStartDestination().id)
                                    launchSingleTop = true
                                }
                            }
                        },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_tab_calendar)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "list",
            modifier = Modifier.padding(padding),
            // 关闭页面过渡动画：功能切换瞬时响应，避免动画 + 组合叠加造成的等待感
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable("list") {
                ReminderListScreen(
                    onAddClick = { navController.navigate("edit") },
                    onEditClick = { id -> navController.navigate("edit/$id") },
                    onOpenCalendar = { id ->
                        if (id == null) navController.navigate("calendar") else navController.navigate("calendar/$id")
                    },
                    highlightedReminderId = highlightedReminderId,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAutostart = { navController.navigate("keep-alive-settings") }
                )
            }
            composable("calendar") {
                CalendarScreen(
                    reminderId = null,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate("calendar-settings") },
                    onAddEvent = { date ->
                        // 预填选中日期（本地零点毫秒）；未选中则新建页默认今天
                        val millis = date?.atStartOfDay(ZoneId.systemDefault())
                            ?.toInstant()?.toEpochMilli() ?: -1L
                        navController.navigate("event-edit?date=$millis")
                    }
                )
            }
            composable(
                route = "calendar/{reminderId}",
                arguments = listOf(navArgument("reminderId") { type = NavType.LongType })
            ) { backStackEntry ->
                CalendarScreen(
                    reminderId = backStackEntry.arguments?.getLong("reminderId"),
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { }, // 单提醒日历不显示日程设置入口
                    onAddEvent = { } // 单提醒日历固定触发记录，不提供新建日程
                )
            }
            composable("calendar-settings") {
                CalendarSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("keep-alive-settings") {
                KeepAliveSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "event-edit?date={date}",
                arguments = listOf(
                    navArgument("date") {
                        type = NavType.LongType
                        defaultValue = -1L
                    }
                )
            ) { backStackEntry ->
                val dateMillis = backStackEntry.arguments?.getLong("date") ?: -1L
                EventEditScreen(
                    initialDate = if (dateMillis > 0) {
                        Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                    } else null,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("edit") {
                ReminderEditScreen(
                    reminderId = null,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "edit/{reminderId}",
                arguments = listOf(navArgument("reminderId") { type = NavType.LongType })
            ) { backStackEntry ->
                ReminderEditScreen(
                    reminderId = backStackEntry.arguments?.getLong("reminderId"),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
