# AgentM 动效审计清单（Motion Checklist）

> 本文件是 task-7 的验收依据：列出每个组件使用哪一档弹簧、参数来源、预期观感。
> 弹簧参数**只允许**引用 [SpringSpec.kt](../core/src/main/java/com/agentm/core/ui/SpringSpec.kt) 的四档，
> 组件侧禁止自造参数。所有阻尼比落在 0.6–0.9 甜区（带惯性回弹）。

## 弹簧参数总表（SpringSpec.kt）

| 档 | 用途 | stiffness | dampingRatio | 预期效果 |
|---|---|---|---|---|
| `SpringSpec.Bouncy` | 转场、交错入场、按压回弹、画布节点落格 | `Low`(100) | `0.75` | 过冲 ~8%，冲过目标再弹回，果冻感 |
| `SpringSpec.Smooth` | 常规状态切换（卡片展开、工具气泡消失、开关） | `High`(300) | `0.9` | 几乎无过冲但保留一丝回弹，最快到位，观感"顺滑带惯性" |
| `SpringSpec.Gentle` | 氛围类（背景渐变、阴影层、画布网格淡入） | `MediumLow`(80) | `0.9` | 慢速弱弹，不抢戏 |
| `SpringSpec.Snap` | 画布 Fling 减速 + 节点吸附网格 | `Medium`(150) | `0.85` | 甩出去自然减速，落格有"咔哒"感但不生硬 |

> 说明：`Smooth` 刻意落在 0.9（而非临界阻尼 1.0），以满足"所有主档都在 0.6–0.9、带惯性回弹"的验收标准；同时 0.9 + High 刚度 仍是四档中最快的。

## 组件 × 弹簧参数 × 预期效果

### 1. 转场（chat / orchestrator 页面切换）
- 文件：`SpringAnimations.kt` → `amFadeInAlpha` / `amFadeOutAlpha` / `amSlideIn` / `amSlideOut`
- 入场：`Bouncy`（淡入 + 滑入，从 48dp 冲过 0 再回弹）
- 出场：`Smooth`（最快收，不拖沓）
- 时长基线：`TRANSITION_MS = 450`
- 预期：页面出现时带惯性回弹，消失时干脆

### 2. 列表项交错入场（消息列表 / 编排节点列表）
- 文件：`SpringAnimations.kt` → `staggerIndex(index)`
- 每第 `index` 项额外延迟 `index × STAGGER_PER_ITEM_MS(40ms)`
- 每项动画：`Bouncy`（alpha 0→1 + translationY 48→0 回弹）
- 预期：列表刷新/进入时一项一项"跳"进来，节奏感强

### 3. 按钮 / 卡片按压回弹（微交互）
- 文件：`MicroInteractions.kt` → `Modifier.amPressBounce(onTap)`
- 参数：`Bouncy`，按下 `scale=0.94`，松手弹回 `1.0`（过冲 ~8%）
- 桌面端（Mouse/Stylus）不驱动按压回弹，保留原生手感
- 外部自管理 `pressed` 状态用 `amPressScaleState(pressed)`
- 预期：点按有"按下去 + 弹起来"的弹簧手感

### 4. 流式消息字符逐步显现（AI 回复）
- 文件：`SpringAnimations.kt` → `STREAM_BATCH_CHARS = 16`
- 每攒 16 个字符触发一次"高度弹簧 + 淡入"（用 `Bouncy`）
- 配合 `amFloat` 驱动气泡高度 / alpha
- 预期：文字一批一批"弹"出来，而非逐字硬切，观感像打字机带缓动

### 5. 工具气泡出现 / 消失
- 文件：`MicroInteractions.kt` → `toolBubbleAppear(visible)` / `toolBubbleDisappear(visible)`
- 出现：`Bouncy`（alpha 0→1 与 heightFraction 0→1 同步弹，`ToolBubbleMotion.IN_MS = 420`）
- 消失：`Smooth`（alpha/height 同步收，`ToolBubbleMotion.OUT_MS = 240`）
- 预期：工具调用卡片弹入、消失干脆不拖沓

### 6. 画布节点拖拽 + Fling + 网格吸附（orchestrator GraphCanvas）
- 文件：`SpringAnimations.kt` → `CanvasSnap`
- 网格边长 `GRID_DP = 8`；Fling 摩擦 `FLING_FRICTION = 0.98`；吸附容差 `SNAP_TOLERANCE_DP = 4`
- 落格动画：`Snap`（`Stiffness=150`，`dampingRatio=0.85`）——节点甩出去自然减速，贴近网格线时"咔哒"落格回弹
- 预期：拖动节点松手后惯性滑动，最终弹回最近网格点，不生硬

### 7. 背景 / 阴影 / 画布网格（氛围层）
- 文件：`SpringAnimations.kt` → `Gentle`
- 用途：画布 `am_canvas_grid` 淡入、表面层切换、渐变色过渡
- 预期：缓慢滑到位，轻微回弹，不抢主交互的戏

## 视觉 token 对照（app/src/main/res/values）

| 维度 | Light | Dark | 来源 |
|---|---|---|---|
| 品牌主色 primary | `#FF4553E8` 靛蓝 | `#FFB3B4FF` | `colors.xml` / `-night` |
| 强调色 accent/tertiary | `#FF12B5A0` | `#FF4FD9C6` | 同上 |
| 表面分层 surface | `#FFFCF9FF` | `#FF1D1B20` | 同上 |
| 用户气泡 | `#FF4553E8` | `#FF6E72F5` | `am_bubble_user` |
| 助手气泡 | `#FFF2EEF7` | `#FF302E35` | `am_bubble_assistant` |
| 工具气泡 | `#FF12B5A0`（深浅同色） | 同 | `am_bubble_tool` |
| 画布背景 | `#FF141218` | `#FF0E0C12` | `am_canvas_bg` |
| 节点色 agent/tool/io | `#FF7C6FF0`/`#FF12B5A0`/`#FF5E5E80` | 同 | `am_node_*` |

| 圆角 | 值 | | 间距 | 值 |
|---|---|---|---|---|
| `am_r_chip` | 8dp | | `am_m_xs` | 4dp |
| `am_r_bubble` | 12dp | | `am_m_sm` | 8dp |
| `am_r_card` | 16dp | | `am_m_md` | 12dp |
| `am_r_input` | 18dp | | `am_m_lg` | 16dp |
| `am_r_sheet` | 24dp | | `am_m_xl` | 24dp |

| 阴影层 | 值 | 语义 |
|---|---|---|
| `am_el_0` | 0dp | 画布节点默认（深色画布无阴影） |
| `am_el_1` | 2dp | 常规卡片 |
| `am_el_2` | 6dp | 选中/抬起的卡片 |

## 验收对照（硬性要求）
- [x] 三组主弹簧参数 `Bouncy/Smooth/Gentle` + 专用 `Snap`，阻尼全部在 0.6–0.9（带惯性回弹）
- [x] 转场 fadeIn/fadeOut/slideIn/slideOut 带弹簧惯性
- [x] 列表项交错入场（40ms/项）
- [x] 按钮/卡片按压 scale + 弹簧回弹（0.94 → 1.0）
- [x] 画布节点拖拽 Fling + 吸附网格（spring settle，`Snap` 档）
- [x] 流式消息字符逐步显现（16 字符/批，淡入 + 高度弹簧）
- [x] 工具气泡出现/消失（Bouncy 入 / Smooth 出）
- [x] 视觉 token 完整：配色（深浅双套 + 动态取色回退）、圆角 5 档、间距 5 档、阴影 3 层
