package com.agentm.app.navigation

/** 四大模块路由常量。NavHost 与 NavigationRail 共用这一份。 */
object Routes {
    const val CHAT = "chat"
    const val ORCHESTRATOR = "orchestrator"
    const val GUI_AGENT = "gui_agent"
    const val SETTINGS = "settings"

    const val START = CHAT

    /** 所有 tab 路由（NavigationRail 顺序即此列表顺序）。 */
    val TABS: List<Tab> = listOf(
        Tab(CHAT, "Chat", "chat"),
        Tab(ORCHESTRATOR, "Orchestrator", "device_hub"),
        Tab(GUI_AGENT, "GUI Agent", "smart_display"),
        Tab(SETTINGS, "Settings", "settings"),
    )

    /** 一个 tab 定义：路由 + 显示名 + 图标键（由 UI 层映射到具体 Icon）。 */
    data class Tab(val route: String, val title: String, val iconKey: String)
}
