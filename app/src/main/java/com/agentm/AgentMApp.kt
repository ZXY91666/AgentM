package com.agentm.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.agentm.app.navigation.Routes
import com.agentm.app.theme.AgentMTheme
import com.agentm.chat.ChatScreen
import com.agentm.chat.GraphCanvasScreen
import com.agentm.chat.GuiAgentScreen
import com.agentm.chat.SettingsScreen
import com.agentm.chat.chatViewModel

/**
 * 应用根组件：Material3 根 + 四 tab 导航（Chat / Orchestrator / GUI Agent / Settings）+ NavHost。
 *
 * - 单栏（手机，默认）：[Scaffold] + 底部 [NavigationBar]
 * - 双栏（宽屏）：左侧 [NavigationRail] + 右侧内容区
 *
 * 各 tab 宿主（来自 chat 模块）：
 * - Chat → [ChatScreen]（[chatViewModel]）
 * - Orchestrator → [GraphCanvasScreen]（节点画布 + 连线 + 保存 GraphSpec JSON）
 * - GUI Agent → [GuiAgentScreen]（任务输入 + 启停 + 事件日志流）
 * - Settings → [SettingsScreen]（LLM 双通道配置 + Ollama 连接测试 / 模型列表 / 拉取）
 */
@Composable
fun AgentMApp() {
    val isDark = isSystemInDarkTheme()
    AgentMTheme(isDark = isDark) {
        val navController = rememberNavController()
        val current by navController.currentBackStackEntryAsState()

        // tab 图标（与 Routes.TABS 的 iconKey 对齐）
        val tabIcons = remember {
            mapOf(
                "chat" to Icons.AutoMirrored.Filled.Chat,
                "device_hub" to Icons.Default.DeviceHub,
                "smart_display" to Icons.Default.SmartDisplay,
                "settings" to Icons.Default.Settings
            )
        }

        // 双栏判定占位：生产环境接 windowSizeClass() >= Medium
        val isWide = false

        if (isWide) {
            WideLayout(navController = navController, current = current?.destination?.route, tabIcons = tabIcons)
        } else {
            CompactLayout(navController = navController, current = current?.destination?.route, tabIcons = tabIcons)
        }
    }
}

// ============================================================
// 单栏（手机）：底部 NavigationBar
// ============================================================

@Composable
private fun CompactLayout(
    navController: androidx.navigation.NavHostController,
    current: String?,
    tabIcons: Map<String, ImageVector>
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Routes.TABS.forEach { tab ->
                    val icon = tabIcons[tab.iconKey] ?: Icons.AutoMirrored.Filled.Chat
                    val selected = current == tab.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { onTabSelected(navController, tab) },
                        icon = { Icon(icon, contentDescription = tab.title) },
                        label = { Text(tab.title, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        AgentNavHost(
            navController = navController,
            contentPadding = innerPadding
        )
    }
}

// ============================================================
// 双栏（宽屏）：左侧 NavigationRail
// ============================================================

@Composable
private fun WideLayout(
    navController: androidx.navigation.NavHostController,
    current: String?,
    tabIcons: Map<String, ImageVector>
) {
    Box(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier
                .fillMaxSize()
                .width(88.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Routes.TABS.forEach { tab ->
                val icon = tabIcons[tab.iconKey] ?: Icons.AutoMirrored.Filled.Chat
                val selected = current == tab.route
                NavigationRailItem(
                    selected = selected,
                    onClick = { onTabSelected(navController, tab) },
                    icon = { Icon(icon, contentDescription = tab.title) },
                    label = { Text(tab.title, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(start = 88.dp)) {
            AgentNavHost(navController = navController)
        }
    }
}

// ============================================================
// 共用的 NavHost
// ============================================================

/** 四个 tab 的 NavHost（单栏/双栏共用）。 */
@Composable
private fun AgentNavHost(
    navController: androidx.navigation.NavHostController,
    contentPadding: PaddingValues = PaddingValues()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.START,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        composable(Routes.CHAT) {
            ChatScreen(viewModel = chatViewModel())
        }
        composable(Routes.ORCHESTRATOR) {
            GraphCanvasScreen()
        }
        composable(Routes.GUI_AGENT) {
            GuiAgentScreen()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}

/** 切换 tab：清栈到 start，launchSingleTop 防重复。 */
private fun onTabSelected(navController: androidx.navigation.NavHostController, tab: Routes.Tab) {
    navController.navigate(tab.route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
