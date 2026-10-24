# AgentM 开源参考 → 模块落地映射（Integration Notes）

> task-8 交付物之二。与 [opensource-research.md](./opensource-research.md) 配套：那篇讲「各项目有什么」，本篇讲「AgentM 每个模块抄什么、对接哪个 API、风险在哪」。
> 源码参考路径：`AgentM/x-langgraph`、`x-autogen(-py)`、`x-agents(2)`、`x-crewai(2)`、`x-mobileagent`、`x-appagent`、`x-droidbot`、`osrc-ollama/`。

## 模块 → 开源参考总映射

| AgentM 模块 | 主参考 | 次参考 | 抄什么（一句话） |
|---|---|---|---|
| `core`（域模型 / LLM Gateway / Tool 协议） | LiteLLM | OpenAI Agents SDK、OneAPI | 统一 `LlmRequest/Response` + 通道链 fallback + 工具注册/Function Calling 解析 |
| `orchestrator`（多 agent 调度） | LangGraph（图模型） | AutoGen（终止条件/事件流）、OpenAI Agents（Runner 循环/guardrail）、CrewAI（agent 配置三件套） | 节点-边状态机 + 可插拔终止谓词 + 事件流 + 轮次上限 + 输入输出双闸 |
| `guiagent`（无障碍 + 视觉 agent） | Mobile-Agent（循环） | AppAgent（编号元素+grid）、DroidBot（状态快照+事件对象+双通道）、CogAgent（纯视觉兜底） | 截图→VLM→封闭动作→执行→验证循环；`GuiAgentState` 快照；连续失败升级重规划 |
| `localllm`（Ollama 客户端） | Ollama API | open-webui（模型卡片/来源抽象）、LiteRT（二期形态占位） | 端点封装 + NDJSON 流式 + 模型管理（list/pull/show/ps）+ 可配 host |
| `chat`（UI / 流式渲染 / 设置页） | open-webui | Ollama（keep_alive 设置项） | 流式块逐 token 渲染契约 + 模型选择卡片（本地/云端双源） |

## 1. core 模块

### 抄什么
- **`LlmGateway` 双通道接口**（来源：LiteLLM `Router` + OneAPI 渠道）
  ```
  interface LlmGateway {
      fun chat(req: LlmRequest, onChunk: (LlmChunk) -> Unit): LlmResponse   // 同步收流
      fun isAvailable(channel: LlmChannel): Boolean                          // 快速探活
  }
  ```
  - 通道链：`primary: OllamaChannel(127.0.0.1:11434)` → `fallback: OpenAiCompatibleChannel(base_url, key, model)`
  - 失败切通道条件：**连接失败 / 超时 / 5xx** 才切；**4xx 不切**（参数/鉴权错切了也错）——来源 LiteLLM 的 cooldown 思路简化版
  - 切通道时发事件：`AgentEvent.LlmFallbackOccurred(from, to, reason)`，并给坏通道记 60s 冷却（内存 map 即可）

- **`LlmRequest/LlmResponse` 对齐 OpenAI 协议**（来源：OpenAI Agents + Ollama `/api/chat` 双兼容）
  - `messages: List<ChatMessage>`；`ChatMessage(role, content: ContentPart, toolCalls?, toolName?, images?)`
  - `ContentPart = Text(text) | Image(dataUri)`（Ollama 的 `images` 字段 = base64 列表；OpenAI 兼容是 `image_url.data`——两个后端的序列化差异在各自 channel 内消化，core 只留统一模型）
  - `tools: List<ToolSpec>`：`ToolSpec(name, description, parametersSchema)`（JSON schema）——来源 Ollama `/api/chat` 的 `tools` 与 OpenAI 的 `tools` 同构，**schema 直接透传**
  - 响应：`LlmResponse(content, toolCalls?, usage?, finishReason)`；`toolCalls` 与 OpenAI 的 `message.tool_calls` 同构

- **`ToolRegistry` + Function Calling 解析**（来源：OpenAI Agents 的 `@function_tool` 装饰器、Ollama tools 协议）
  - `ToolRegistry`：`register(spec, handler: (JsonArgs) -> ToolResult)`；LLM 返回 `toolCalls` → 逐个查 handler → 结果以 `role=tool` 消息回填
  - 解析健壮性：tool call JSON 解析失败 → 作为「工具错误」消息回给模型（不崩），来源 OpenAI Agents 的 `_function_tool_arguments.py` 容错思路

- **`AgentEvent` 基类**（来源：AutoGen 的事件/消息二分 + LangGraph 的流事件）
  ```
  sealed class AgentEvent {
      data class NodeStart(name, payload)
      data class NodeEnd(name, ok, error?)
      data class AgentTurn(round, agentName, stopReason?)     // 一次 LLM 调用+工具执行 = 1 turn
      data class LlmChunk(delta, toolCallDelta?, done, stats?)
      data class LlmFallbackOccurred(from, to, reason)
      data class ToolInvoked(tool, args)
      data class ToolCompleted(tool, result)
      data class AgentStopped(stopReason: StopReason)          // MaxRounds/StopKeyword/Timeout/Error
  }
  ```

### 风险
- OpenAI 兼容端点（LiteLLM/OneAPI/各家代理）对 `tools` 支持程度不一（有的不支持 vision + tools 同时）→ `LlmChannel` 要有 `supportsTools/supportsVision` 能力位，gateway 按能力选通道
- 4xx 与 5xx 区分：部分网关把限流（429）归 4xx——**429 要重试/切通道**，把它列进「可切」白名单
- 冷却时间用 `AgentEvent` 时间戳维护，别用挂起（avoid leak）

## 2. orchestrator 模块

### 抄什么
- **`GraphSpec` 节点-边状态机**（来源：LangGraph `StateGraph`）
  ```
  data class GraphSpec(
      val stateSchema: KClass<AgentState>,        // 强类型共享状态
      val nodes: Map<String, AgentNode>,          // 节点 = (AgentState) -> AgentEvent 流 + 更新后 state
      val edges: Map<String, EdgeSpec>,           // 静态边 / 条件边（fun(AgentState) -> String）
      val entry: String, val terminal: String = "END"
  )
  ```
  - 执行引擎 = Pregel 超步循环的简化版：每步「当前节点跑完 → 按出边选下一节点（条件边调用）→ 更新 state → 发事件 → 到 END 停」
  - **不用 LangGraph 的 checkpoint 引擎**，但保留 `AgentState` 可序列化（每轮存盘）= 轻量 checkpoint（失败可恢复）

- **主 agent 路由 + 子 agent 派发**（来源：AutoGen 群聊 speaker selection + OpenAI 的 handoff）
  - router 节点：收用户输入 → LLM 判意图 → 条件边返回目标子 agent 名（`ui`/`code`/`search`/`tools`/END）
  - 子 agent 注册表：`Map<String, AgentSpec>`；`AgentSpec = 配置对象`（来源 OpenAI `Agent` dataclass）：`(role, goal, backstory, model, systemPrompt, tools, handoffs)`——CrewAI 三件套让 prompt 可维护
  - **转交工具化**（来源 AutoGen `Handoff` + OpenAI `handoffs`）：给每个 agent 注入 `transfer_to_<name>(message)` 工具，LLM 调用即完成转交；`transfer` 工具名进 ToolRegistry

- **终止条件 = 可插拔谓词列表**（来源 AutoGen `conditions/_terminations.py`）
  ```
  interface Termination { suspend fun check(events: List<AgentEvent>): String? }  // 返回 stopReason 即停
  MaxRoundsTermination(n) / StopKeywordTermination("FINISH") / TimeoutTermination(ms) / FunctionalTermination(pred)
  ```
  - 每轮结束跑一遍列表，任一命中 → `AgentEvent.AgentStopped(reason)`（对齐 AutoGen 的 `stop_reason`）
  - 轮次语义（来源 OpenAI Runner）：**一次 LLM 调用 + 其后全部工具执行 = 1 turn**；`max_turns` 超限发 `AgentStopped(MaxTurns)`，**可控降级**（交回 router 重新规划一次，再不行才真停）

- **输入/输出 Guardrail**（来源 OpenAI `guardrail.py`）
  - 每轮 LLM 调用前：`InputGuardrail(event, context) -> block?`（敏感词/越权工具拦截）
  - 调用后：`OutputGuardrail(output, context) -> block?`（输出质检）
  - 命中 → 发事件 + 停止本轮（不静默吞）

- **可观测**（来源 LangGraph stream + AutoGen `run_stream`）
  - 引擎暴露 `asFlow(): Flow<AgentEvent>`；chat 模块订阅渲染；`AgentTurn` 事件带 `round` 序号，UI 可显示「第 3 轮」

### 风险
- handoff 死循环（A→B→A）：加 **禁止转回来源 + 转交深度上限 2**（CrewAI `allow_delegation` 的教训）
- 子 agent 数量 >5 时条件路由 prompt 会很长：意图分类器单独一个轻量模型（端侧 1B 级）做初筛，再进图
- 协程引擎每步要可取消（`CancellationToken` 对齐 AutoGen `run(cancellation_token)`）

## 3. guiagent 模块

### 抄什么
- **主循环**（来源 Mobile-Agent v2 `chat.py` + AppAgent `task_executor.py`）
  ```
  while round < maxStep && !verified:
      state = refreshState()                 // 节点树 + 前台 activity + 截图（DroidBot DeviceState）
      prompt = buildPrompt(state, instruction, history, memory, lastError?)
      action = vlm.predict(prompt)           // 封闭 7 动作 enum（Mobile-Agent）
      execute(action)                        // AccessibilityService 主 / input 命令兜底
      verify(action, stateAfter)             // 状态 diff / 反思（AppAgent 前后截图对比思路）
  ```
  - 动作空间（Mobile-Agent 6 动作 + 适配）：`TAP(x,y)` / `LONG_PRESS(x,y)` / `TYPE(text)` / `SWIPE(x1,y1,x2,y2)` / `SYSTEM_BUTTON(back|home|enter)` / `OPEN_APP(name)` / `ANSWER(text)`
  - 定位双模式（AppAgent）：
    - **编号模式**：`AccessibilityService` 节点树取 `isClickable` 元素 → 按树序编号 1..N → 编号画进截图叠加层 → LLM 输出 `TAP(#k)`
    - **grid 兜底**：目标元素无编号（canvas/游戏）时切 R×C 网格，`TAP(area, subarea)`，subarea ∈ {center, top-left, top, ...}（九子区，行优先 `row*R+col` 换算坐标，参考 AppAgent `area_to_xy`）

- **状态快照 `GuiAgentState`**（来源 DroidBot `DeviceState`）
  - 字段：`nodes: List<UiNode>`（class/text/contentDesc/bounds/clickable/stableId）、`foregroundActivity`、`screenshotPath`、`keyboardActive`
  - 每轮执行前刷新；**可序列化到文件**（失败恢复 / 回放，DroidBot `to_json` 思路）

- **失败恢复**（来源 MobileAgent-E `err_to_manager_thresh`）
  - `ActionReflector`：执行后判断「上一步是否达到预期」（截图前后 diff + 目标关键词）；不符 → `error_flag=true` 进下轮 prompt（Mobile-Agent 的 `Last operation` 段：「你之前想 X 执行了 Y，但没达到预期，请反思修正」）
  - 连续 **N=2** 次失败 → 升级给 Manager（重新出 plan）；这阈值做成可配（对齐 `err_to_manager_thresh`）
  - 整任务 `max_step=25`（Mobile-Agent v3 默认），超限写 `task_result.json`（`hit_step_limit=1`）并停

- **双通道取视图**（来源 DroidBot `get_views` 的多适配器）
  - 主：AccessibilityService 节点树（结构化、快、准，且免 adb）
  - 兜底：截图 + VLM（节点树拿不到的区域）；纯视觉参考 CogAgent 的相对坐标网格（**不本地跑 7B**，用云端 VLM）

### 风险
- 坐标系：VLM 看到缩放图，坐标必须**按原始分辨率出**（prompt 里注明宽高像素，Mobile-Agent 做法：`This image is a phone screenshot. Its width is {width} pixels...`）；编号模式下编号是稳定 ID（resource-id 哈希，AppAgent 思路），**跨轮不换号**
- 中文输入：`input text` 只支持 ASCII → 走 `am broadcast -a ADB_INPUT_TEXT --es msg "..."`（Mobile-Agent v2 `controller.py` 的逐字符策略）或自研 IME（DroidBot `droidbot_ime`）
- 无障碍服务被系统降级/回收 → 循环要感知 `onInterrupt`，自动停并发 `AgentEvent`（别死循环空转）
- `screencap`/`uiautomator dump` 在应用身份下 rc=0 但结果残缺（见本机特权说明）：执行动作统一走 `shell`/AccessibilityService，**不靠应用身份**
- 验证（verify）别做成像素 diff（太脆），用「目标关键词/元素是否出现」弱验证

## 4. localllm 模块

### 抄什么
- **Ollama 端点封装**（来源：`osrc-ollama/api.md`，已逐端点确认）
  | 方法 | 端点 | 说明 |
  |---|---|---|
  | `listModels(): List<OllamaModel>` | `GET /api/tags` | 名称/大小/量化（`digest`） |
  | `showModel(name): OllamaModelInfo` | `POST /api/show` | Modelfile/参数 |
  | `isLoaded(name): Boolean` | `GET /api/ps` | 是否驻留内存 |
  | `pullModel(name, onProgress)` | `POST /api/pull` | NDJSON 进度流（`status` 增量） |
  | `chat(messages, model, tools, stream): Flow<LlmChunk>` | `POST /api/chat` | `stream=true` 逐行 JSON；末帧 `done=true` + 统计；`message.tool_calls` 回填 |
  | `embeddings(texts)` | `POST /api/embeddings` | 可选 |
  - 公共参数透传：`keep_alive`（默认 5m，设置页可调）、`options`（temperature/num_ctx）
  - 模型命名带 tag（`llama3.2:8b-q5_0`），缺省 tag=`latest`（Ollama 约定）

- **NDJSON 流式解析**（来源 Ollama + open-webui 的流式 UI 契约）
  - `OkHttp` `streamingResponseBody`，逐行 `bufferedReader` 解析；`done=true` 结束
  - `LlmChunk(delta, toolCallDelta?, done, stats?)` 直接进 core 的 `AgentEvent.LlmChunk`
  - 容忍空 `content` 帧（思考型模型先出 `thinking`，Ollama `message.thinking` 字段）

- **模型管理卡片**（来源 open-webui `functions.py` 的模型来源抽象）
  - `LocalModelCard(name, size, quant, modalities, supportsTools, supportsVision)`：`/api/tags` + `/api/show` 拉一次缓存进 Room/文件；设置页列表渲染
  - host 可配：`127.0.0.1:11434`（本机 Termux/proot）或局域网机；`connectTimeout=2s` 快速失败（别卡 UI）

### 风险
- Ollama 未起 / proot 崩溃 → `isAvailable` 快速 false，gateway 直接切云端通道（core 的 fallback）
- 拉模型几 GB：走 `WorkManager` 后台 + `Flow` 进度；断点续传靠 Ollama 层（客户端只管发请求）
- 量化信息（`q5_0`）要展示给用户但**不校验**（Ollama 返回啥就显示啥）
- `keep_alive` 过长吃内存（7B 模型常驻 ~5GB）：默认 5m，设置页给「会话后 N 分钟」选项

## 5. chat 模块

### 抄什么
- **流式渲染契约**（来源 open-webui + Ollama 流式）
  - ViewModel 订阅 `orchestrator.flow()`：`AgentEvent.LlmChunk` → 当前气泡逐 token 追加；`AgentTurn` → 轮次徽标（「第 3 轮」）；`ToolInvoked/ToolCompleted` → 工具调用气泡（展开/收起，显示 args/result 摘要）；`AgentStopped(reason)` → 结束条
  - 块式数据 `LlmChunk(delta, done, stats)` 与 [SpringSpec](../core/src/main/java/com/agentm/core/ui/SpringSpec.kt) 的流式字符动画（`motion-checklist.md` 第 4 项）对接：每个 delta 触发一次字符显现
- **工具调用可视化**（来源 OpenAI Agents 的 `RunResult.items` 分层）
  - 消息 = `user/assistant/tool` 三分；tool 消息用 `tool_name` 渲染图标 + 名称
- **设置页（双通道配置）**（来源 open-webui 模型来源抽象 + OneAPI 三件套）
  - 本地：Ollama host + 模型选择（`/api/tags`）+ keep_alive
  - 云端：`{base_url, api_key, model}` 三件套（OpenAI 兼容，可指向 LiteLLM/OneAPI/官方）+ 能力开关（tools/vision）
  - 主/备顺序 + 自动 fallback 开关（对应 core gateway 的通道链配置）
  - API key 存 `EncryptedFile`（Keystore），设置页只显示尾 4 位

### 风险
- 流式 delta 过碎 → UI 帧率：delta 攒 ~16ms 一批再上屏（Compose `snapshot`），别逐 token 触发重组
- 工具气泡展开的长 JSON：首屏只显示摘要（args 键名 + result 前 200 字符），展开才全显

## 全局 fallback 策略（跨模块）

| 故障点 | 检测 | 降级路径 |
|---|---|---|
| Ollama 不可达 | `isAvailable` 2s 超时 | 切云端 OpenAI 兼容通道（core 通道链） |
| 云端 5xx/超时 | 响应码/超时 | 试下一通道 + 60s 冷却（4xx 不切，429 例外重试） |
| 纯视觉坐标偏差 | 编号模式验证失败 | 切 grid 兜底（guiagent 双模式） |
| 节点树缺失 | `nodes` 为空 | 纯视觉 VLM（CogAgent 式） |
| 子 agent 卡死 | 单 agent 轮次 > 阈值 | 终止条件命中 → 回 router 重新规划（深度 ≤1） |
| 整任务超步 | `round == max_step` | 写 task_result（`hit_step_limit`），交回用户 |
| 无障碍服务被回收 | `onInterrupt` | 暂停循环 + 通知 orchestrator 发事件 |

## 实现顺序建议（供 lead 排期）

1. `core`（契约层）：`ChatMessage/LlmRequest/LlmResponse/ToolSpec/AgentEvent/StopReason/Termination` 数据类 + `LlmGateway` 接口 + 两个 channel 实现（Ollama/OpenAI 兼容）+ `ToolRegistry`
2. `orchestrator`：`GraphSpec` + 超步引擎（协程）+ 条件边 + 终止谓词组合 + guardrail + `asFlow()`
3. `localllm`：Ollama 端点（上表）+ NDJSON 流 + `OllamaRepository`（list/pull/show/ps）
4. `guiagent`：`GuiAgentState` + 无障碍树编号 + 7 动作 enum + 主循环 + 双通道取视图 + 失败升级
5. `chat`：流式渲染 + 工具气泡 + 设置页（双通道配置）
6. 端到端：user 输入 → router → 子 agent（可含 guiagent 作为 tool）→ 流式回 chat

## 与本机环境的约束（必须满足）

- **不搬任何 Python/服务端代码**：只抄「模式」，Kotlin 原生实现（orchestrator 协程、guiagent AccessibilityService、localllm OkHttp）
- 应用身份下 `input`/`screencap`/`dumpsys` 结果不可信（rc=0 但没生效）：guiagent 动作执行统一走 `shell`/AccessibilityService，截图校验也走特权通道
- 云端 key 用 `EncryptedFile`，Ollama host 默认 `127.0.0.1:11434`
- LiteRT 端侧嵌入**一期不做**，`LlmBackend` 留第三实现位
