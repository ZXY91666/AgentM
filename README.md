# AgentM — 移动端多智能体应用

一款纯 Android（Kotlin + Jetpack Compose）的多智能体软件，融合多智能体编排、GUI 自动化、
低代码图编排、本地 LLM 运行四大能力，参考 LangGraph / AutoGen / OpenAI Agents SDK /
Mobile-Agent / AppAgent / Ollama / open-webui 等开源项目的核心抽象，以 Kotlin 协程重新实现。

## 模块结构

| 模块 | 职责 | 关键类 |
|---|---|---|
| `:app` | 应用入口 / 主题 / 导航 / DI 容器 | `AgentMApplication`, `MainActivity`, `AgentMApp` |
| `:core` | 跨模块契约 + LLM 网关 + 工具协议 | `LlmGateway`, `OpenAICompatibleGateway`, `OllamaGateway`, `LlmGatewayFactory`, `ToolRegistry`, `FunctionCallParser`, `GatewayConfigStore` |
| `:orchestrator` | 多智能体图编排引擎（协程） | `Orchestrator`, `GraphExecutor`, `MainRouter`, `GraphSpec`, `SubAgents` |
| `:guiagent` | 无障碍服务 + GUI Agent 循环 | `GuiAgentService`, `GuiAgentLoop`, `GuiAgentController` |
| `:localllm` | Ollama 本地模型管理 | `OllamaClient`, `OllamaRepository`, `OllamaHostDiscovery` |
| `:chat` | Compose UI 各屏（聊天/编排画布/GUI 任务/设置） | `ChatScreen`, `GraphCanvasScreen`, `GuiAgentScreen`, `SettingsScreen` |

## 构建

> ⚠️ **本仓库当前所在设备（Termux/Android）无 JDK、无 Android SDK，未做编译验证。**
> 需在装有 JDK 17 + Android SDK 34 + Gradle 的机器上构建。

```bash
# 首次：补齐 gradle-wrapper.jar（本仓库未提交二进制 jar）
gradle wrapper --gradle-version 8.7

# 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 编译 + 打包
./gradlew :app:compileDebugKotlin     # 仅编译 Kotlin
./gradlew :app:assembleDebug          # 打包 APK（输出 app/build/outputs/apk/debug/）
```

## 运行条件

- **minSdk 26 / targetSdk 34**
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`BIND_ACCESSIBILITY_SERVICE`
- 无障碍服务需在系统设置中手动开启：「设置 → 无障碍 → AgentM GUI Agent」

## LLM 配置（设置页）

### 本地 Ollama
1. 启动 Ollama：`ollama serve`（同机）或在局域网主机上启动并允许 LAN 访问
2. App → Settings → 本地 → 输入 host（默认 `http://127.0.0.1:11434`）→ 测试连接
3. 拉模型列表 → 选择或拉取新模型

### 云端 OpenAI 兼容
- baseUrl 形如 `https://api.openai.com/v1`（也支持 DeepSeek / Moonshot / OpenRouter / OneAPI / LiteLLM）
- 填 apiKey 与 model，保存后立即生效

### 双通道 fallback
`LlmGatewayFactory` 默认 **本地主、云端备**：
- 主通道失败（UNREACHABLE / SERVER / AUTH）→ 自动切备通道，60s 冷却
- BAD_REQUEST 不切（参数问题换通道无效）

## 参考的开源项目

详见 `docs/opensource-research.md`（13 个项目逐条调研：核心抽象、借鉴点、对本仓的映射）。

## 文档

- `docs/motion-checklist.md` — 弹簧动画参数与组件映射（阻尼 0.6–0.9 甜区）
- `docs/integration-notes.md` — 模块对接点 + 全局 fallback 策略 + 本机环境约束
- `docs/opensource-research.md` — 开源参考调研

## 已知限制

- Ollama 无原生 function-calling：`OllamaGateway` 走提示词协议 + `FunctionCallParser` 解析，
  模型回 `tool_call <name>(<argsJson>)` 或 `<tool_call>{...}</tool_call>` 格式可识别
- GUI Agent 截图走 `MediaProjection`，部分设备需用户手动授权；未授权时降级 AccessibilityService 的 `takeScreenshot`（API 30+）
- 双栏宽屏布局占位（`isWide=false` 硬编码），待后续接 WindowSizeClass
