package com.agentm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.agentm.app.theme.AgentMTheme

/**
 * 唯一 Activity 入口：edge-to-edge + Compose setContent { AgentMApp() }。
 * 系统栏颜色跟随当前主题色（随深浅主题与动态取色更新）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AgentMApp()
        }
    }
}
