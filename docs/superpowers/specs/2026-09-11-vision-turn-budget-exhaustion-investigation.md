# 视觉回合预算误伤用户贴图排查（图片「走不了视觉通道」根因调查）

> 状态：根因已实锤复现，修复方向已给出，**未实施任何代码改动**。
> 排查日期：2026-09-11 深夜 ～ 2026-09-12。

## 1. 背景：报告的现象

xibaojun 项目会话 `20260911T154059-213a8e.json`（2026-09-11 15:50 UTC，模型
`zhipu:glm-5.3-flash`，构建 1.21.2 dist）中，用户在首条消息里贴了 3 张竞彩海报样图
（1 张文本路径 + 2 张 `[file reference]` 附件）。整个会话里模型反复表示看不见图：

- 「图片太大没有直接进视觉通道，我把它缩小后分块读取」
- 「图片似乎没能进入视觉通道，我用 Python 裁切压缩后再试」
- 「图片始终无法进入视觉通道。这个会话的图片读取通道有问题」
- 最终被迫转 OCR，靠 `rapidocr` 把三张样图的内容读出来继续任务

glm-5.3-flash 在 `VisionModels.VISION_PREFIXES` 名单里（`f48ef17c`/`42877ec2`，
2026-09-01 接入且真机探针全绿），模型本身支持视觉。**为什么图就是到不了模型眼里？**

## 2. 先澄清一个容易误判的证据

会话 JSON 里所有引用块从头到尾都是 `delivery: not_in_view`——**这不是故障证据**。
按设计，`delivery` 行的改写只作用于出站副本、会话存储保持原状（`VisionMaterializer.
rewriteAnchor` 与 `FileReference.withDelivery` 的方法注释都明写「存储里永远不该出现
delivered」）。工具结果里写 `not_in_view`（而非 `reference_only`）恰恰说明**当晚能力判定
是通过的**：`TextReferenceMediaHandler.canDeliver` 只有在 `supportsImageInput()=true`
时才写 `not_in_view`——glm-5.3-flash 在名单里，生效了。

真正的证据是**模型行为**：它看见了引用块、按提示去 Read、Read 成功返回了引用，
但它仍然看不见图。

## 3. 模型实际收到的消息链（根因的全貌）

> 术语：下文的「轮」= 工具循环的一次迭代 = 一次出站请求（全文统一用「轮」）。

视觉兑现的完整链路（`springai-code-tui/docs/guide/vision.md` 有全景）：用户贴图/工具 Read →
`[file reference]` 文本引用 → 出站前 `VisionMaterializingChatModel` 把「当轮」引用
兑现成真 `Media` 挂到 user 消息上。**关键在「当轮」的定义**：锚点（最后一条非合成
user 消息）及之后的引用，每轮出站都重新兑现——即每轮重新投递一次。

理想情况下，第一回合里模型每轮收到的消息列表（兑现后）：

```
[system 大提示词]
[user]    APP端，竞彩蓝球和竞彩足球合并… [file reference] delivery: delivered ← 挂 2 张 Media
[assistant tool_calls] Read(...)
[tool]    [file reference] delivery: not_in_view …
[assistant tool_calls] Read(...)          ← 第二轮
[tool]    …
```

出问题的会话里，同一回合图像挣扎阶段就有 18 轮（事件 1→35），整回合 41 轮
（事件 0→82）；从**第 6 轮**起变成（本会话实况，逐轮重放见 §5.2）：

```
[system 大提示词]
[user]    … [file reference] delivery: turn_budget_exhausted ← Media 没了
[assistant tool_calls] Read(...)
[tool]    [file reference] delivery: not_in_view …
```

图从请求里**静默消失**：不报错、不崩、delivery 行虽有改写但埋在长上下文里，
弱档模型几乎注意不到。模型只观察到「我又 Read 了一次，还是看不见」，于是自然
得出「视觉通道坏了」的结论，开始徒劳地缩小/切分/转格式——**全部无效，因为额度
按张·次计，与图片尺寸无关**。

## 4. 根因

`VisionBudget`
（`springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionBudget.java`）：

```java
/** 每回合：累计兑现次数（张·次）上限。 */
public static final int MAX_TURN_DELIVERIES = 12;

public boolean tryConsumeTurnSlot() {
    return turnCounter.incrementAndGet() <= MAX_TURN_DELIVERIES;
}
```

两个语义叠加出了误伤：

1. **计费口径是「张·次」而非「去重张数」**：用户贴在锚点消息上的 2 张图是「当轮
   全部意图」，按设计必须在**每轮**出站请求里都在场（这是功能正确性，不是浪费）；
   但 `tryConsumeTurnSlot` 对它们每个请求都再记一次额度。
2. **额度跨整个回合累计**：一个回合几十轮工具循环——本会话 2 张用户图 × 5 轮 +
   工具图 2 张·次即兑现满 12 槽（逐轮重放见 §5.2），第 6 轮起全部剥离。

烧光之后 `admitAll` 把所有引用（包括用户锚点图）判为
`DELIVERY_TURN_EXHAUSTED`，`rewriteAnchor` 把出站副本的 delivery 改写、`media`
列表不挂（`deliveredMedia` 为空时不调 `b.media(...)`，而锚点消息在存储里本来就没有
media）——**用户的原始贴图在回合中途被剥离出视野**。之后模型无论怎么 Read 都
撞同一个计数器，回合内持续失明。

> **「持续失明」的两个例外**（都只会让症状「间歇性恢复」，不影响根因成立）：
> ① 回合中途触发自动压缩会删改消息 → 锚点下标变化 → turnKey 变 → 额度桶重置；
> ② 并发子 agent 开新回合使 `MAX_TRACKED_TURNS=8` 触顶 `clear()`，在飞回合的桶
> 被一并清掉、额度重置。长回合观测到「它有时又看见了」不能反驳本根因。

### 4.1 设计意图与实际行为的错位

`VisionBudget` 类注释写的意图是封住「截图循环一个回合产几十张图」的花费——那
个场景里「张·次」口径是合理的（每张都是新图）。但用户锚点图是**同一张图反复在场**，
它的花费已经被 `MAX_REQUEST_TOKENS = 6000`（每请求 token 硬上限）封住了——回合
额度这层对它是**重复计费**，且失效模式恰好打在最典型的用法上：「照这张稿子改」
+ 多轮工具迭代。

## 5. 排查与排除过程（证据链）

### 5.1 静态排查（全部排除）

| # | 嫌疑 | 验证方式 | 结论 |
|---|------|----------|------|
| 1 | 名单缺 glm-5.3-flash | 反编译 m2 1.21.1 jar 与 `~/springai-code-tui-1.21.2/springai-code-tui.jar`（9/11 实际运行的构建），`VisionModels.class` 常量池均含 `glm-5.3-flash` | 排除 |
| 2 | xibaojun 跑的是旧构建 | `model.json` = zhipu/glm-5.3-flash；`~/.zsh_history` 显示启动方式为 `~/springai-code-tui-1.21.2/bin/code-tui`；dist jar 构建于 9/10 11:28（源码与 m2 1.21.1 同源） | 排除 |
| 3 | 兑现层 bug（解析/越界/预算首轮就拒） | 用会话**首条消息原文 + 真实 root + 生产同款链路**（`ZhipuProvider.capabilities` 谓词 + glm-5.3-flash options）单次复现：2 张 Media 挂上出站、delivery 改写 `delivered` | 排除（单次） |
| 4 | 图片太大/格式 | `ImagePreparer` 长边 1568 缩放（1080×5467 → 310×1568 ≈ 648 token，远低于单图与请求预算）；会话里 75KB/800×727 的小图同样「看不见」——尺寸无关 | 排除 |
| 5 | 智谱端点不收图/HTTP 序列化丢图 | 真 key + Coding Plan 端点（`ZHIPU_BASE_URL`）+ 真实海报 5 组 live 探针：干净文本+真图 / 生产消息形状（tool_calls 链 + 合成 user 带图）/ 大系统提示+工具+双图，模型全部精准读出（深紫底色、篮球 301 编号、单关标红等细节全对） | 排除 |
| 6 | 引用文本 `not_in_view` 误导模型（自相矛盾信号） | live 探针 B3/E 组带同样的引用文本，模型照样看图 | 排除 |
| 7 | 用户插话移动锚点 | 代码路径属实但本案未触发：第一回合（事件 0→82）无任何 USER 事件（全会话 USER 下标 `[0, 83, 95, 101, 114, 190, 205, 229, 359]`，83 起已是第二回合）。详情与「一次性闪断」定性见脚注 ¹ 与 §6.1 | 排除（本案） |

¹ 插话嫌疑详情：`InterjectingChatModel.inject()` 追加裸 `UserMessage`（无
`SYNTHETIC_KEY` 标记）且装饰在 Vision 层外——插话会夺走锚点，承载插话的那一轮
无图且无任何 delivery 改写，失效签名与本案**相似**（但为一次性闪断而非持续失明，
机制见 §6.1）。

### 5.2 决定性复现（多迭代回合形态）

单次复现通过、live 探针全过，但会话失败——差异只剩「**一个回合内几十轮出站**」。
复刻生产回合形态（锚点 user 带 2 图引用 + 逐轮追加 assistant(tool_calls)/tool(Read 引用)，
每轮经 `VisionMaterializingChatModel` 重新出站），跑在 **9/11 实际运行的 1.21.2 dist
jar** 上：

```text
轮  1: media=2 deliveries=[delivered, delivered]
轮  2: media=2 deliveries=[delivered, delivered]
轮  3: media=2 deliveries=[delivered, delivered]
轮  4: media=2 deliveries=[delivered, delivered]
轮  5: media=2 deliveries=[delivered, delivered]
轮  6: media=2 deliveries=[delivered, delivered]
轮  7: media=0 deliveries=[turn_budget_exhausted, turn_budget_exhausted]   ← 图消失
轮  8~16: media=0 deliveries=[turn_budget_exhausted, …]                    ← 持续失明
```

**算术前提**：复现里每轮工具 Read 的引用与锚点两图**同 sha**——`collectUserRefs`
先把锚点图加进 `seen`，`collectToolRefs` 对同 sha 去重，因此工具重 Read 不占新槽，
每轮恰好消耗 2（2 张用户图 × 6 轮 = 12 = `MAX_TURN_DELIVERIES`），第 7 轮起剥离。

真实会话比这**耗尽得更早**（存在新 sha 的图）。按 `VisionMaterializer` 的收集/去重/
闸门规则对会话 JSON 逐轮重放（**注意两条时序规则**：第 k 轮出站发生在事件 2k-1
之前，当轮工具结果尚不可见；`seen` 是每次 materialize 新建的请求内去重集，不是回合
级状态——留在消息表里的工具引用从下一轮起每轮都被 `MAX_TOOL_IMAGES=1` 重新选中
并计费）：

```text
轮次 | 产出事件 | 该轮工具   | 出站可见的工具图引用 | 本轮兑现 | 计数器累计
 1  |  ev1  | Read×2  | —                    |   2   |   2
 2  |  ev3  | Read    | —（103 引用在 ev4）   |   2   |   4
 3  |  ev5  | Read    | ev4: 103（新 sha）    |   3   |   7   ← 工具图自此每轮重选计费
 4  |  ev7  | Read    | 103（重选）           |   3   |  10
 5  |  ev9  | Bash    | 103（重选）           |   2   |  13   ← 第 12 槽满：锚点图1=11、图2=12，工具图第 13 次被拒
 6  |  ev11 | Bash    | 103（重选）           |   0   |  16   ← 全部剥离，完全失明
 7+ | ev13+ | Read/Bash | ev14 起 016a889d 等 |   0   |  每轮 +3
```

（表中「103」= 样图 3 `微信图片_20260911234849_103_1.jpg`，Read 后引用 id
`sha256:3833280a…`。注意 `tryConsumeTurnSlot` 的 `incrementAndGet` 对**被拒尝试**
同样自增，故累计可超 12——轮 5 的 13 即第 13 次被拒的那次。）

时间线精确重合：第一次完全失明的出站请求正是产生**事件 11** 的那次（轮 6），而
事件 11 的正文就是模型第一句抱怨——「图片太大没有直接进视觉通道，我把它缩小后
分块读取」；事件 23/29 的 sips/PIL 缩图命令全是失明后的徒劳挣扎。且 Bash 产出的
新图文件不产生引用（有意设计，见 vision.md「不支持的场景」），要等模型后续 Read
（ev14 的 016a889d，已过耗尽点）才进视野——从未消耗过槽位。复现里「轮 7 才耗尽」
是同 sha 的下界情形，真实会话第 5 轮兑现满、第 6 轮起失明。

> 复现探针为临时诊断代码（`/tmp/vision-repro/`），验证后已删除，未进仓库。探针形态：
> Spy ChatModel 记录 delegate 收到的出站 Prompt → 锚点 user（会话首条消息原文，2 图
> 引用）+ 循环逐轮追加 assistant(`tool_calls: Read`)/tool(引用块)，每轮
> `wrapped.call(...)` 后统计 Media 数与 delivery 行。实施修复时应按 §7 第 1 条沉淀为回归
> 测试（含「按消息序列重放槽位消耗」的确定性 fixture——本次人工重放第一版就推错了
> 时序，说明这项必须产品化），不再依赖一次性探针。live 探针（5 组）与多迭代复现
> 分属两类证据：前者证明「智谱端点 + 真图 + 生产消息形状」可用，后者钉死预算耗尽
> 边界；B2/B3/E 编号只存在于已删探针中，重建说明见 §5.2 与 §6.1 第二条。

### 5.3 影响面与用户侧规避（修复落地前）

**影响面**：触发条件 = 视觉模型（`VISION_PREFIXES` 名单内全部模型，不限
glm-5.3-flash）+ 贴图或 Read 图 + 回合内 ≥5~6 轮出站（用户图 ≥2 张时更快）。与
模型强弱无关——强模型同样失明，只是更可能读到 delivery 行而少空转（此点未证实）。
最典型命中场景是「照这张稿子改」类 UI/设计/海报任务：贴图 + 长工具迭代是常态。

**修复落地前的规避**：

1. 最有效——**把依赖图的阶段拆成短回合**：先让模型看图并产出文字结论（写进文件
   或让它复述确认），再开长工具循环。额度按回合重置，短回合烧不穿。
2. 失明已发生时，**新开一回合**（重新提问/`/continue` 后再贴图或让模型 Read 一次
   目标图）——额度立刻恢复。
3. 不要指望模型自己恢复：delivery 行的 `turn_budget_exhausted` 信号弱档模型读不到
   （本案已证），继续追问只会空转烧钱；把「缩小/重试」引到新回合里做。
4. 回合中途自动压缩引发的额度重置（§4 例外①）是不可依赖的偶然副作用，别当手段。

## 6. 修复方向（待拍板，未实施）

按推荐顺序：

1. **回合额度按去重 id 计**（推荐）：`VisionBudget.Session` 的回合计数从
   `AtomicInteger` 张·次改为按回合记录已计费的图 id 集合——同一张图同回合多次投递
   只记一次额度。截图循环照样被封（每张新截图都是新 sha），用户锚点图只花 1 个槽位。
   落地时四个坑必须一并处理：
   - **预算单位退化**：现状「12 张·次」给出每回合绝对上界 12 × ~1.8k ≈ 21.6k
     token（`VisionBudget` 类注释与 `vision.md` §预算都明文依赖这个数）；改去重后
     上界变为「12 张不同图 × 每迭代重发」= 迭代次数 × 6000。必须补一层「回合级
     token 账」（或「同图免槽重发次数上限」）兜住花费，否则 `vision.md` 与
     `ContextUsage`（注释明写「严格说单位是张·次」并把 12 显示为每回合上限）两处
     口径同步失真。
   - **并发原子性**：桶内普通 `HashSet` 的「size<12 再 add」是 check-then-act 非
     原子（现状 `incrementAndGet` 是原子的）；须用 `ConcurrentHashMap.newKeySet()`
     并把判重+计数做成原子段（或退化 `synchronized`，此处吞吐无关紧要）。
   - **id 口径**：id 是混合口径——artifacts 内内容寻址文件 = 内容 sha（`MediaExternalizingCallback
     .contentHashIfInStore` 按文件名反解），项目内普通文件 = 绝对路径 sha。按 id
     去重意味着「同图挪路径」计 2 槽、「同路径换内容（非 artifacts）」计 1 槽——后者
     对本 bug 恰是想要的行为，但实现注释要写明按 `id` 计而非按内容计。
   - **契约测试**：`VisionBudgetTest` 5 个用例中 4 个按「张·次」语义写死（12 次
     `open("turn-1")` 无 id 参数），改造后这些测试要连语义一起改。
2. **或：用户锚点图豁免回合额度**：`admitAll` 对来源为 user 的引用不调
   `tryConsumeTurnSlot`。**注意安全论证不能引用「MAX_USER_IMAGES=3 + 请求 token
   预算双重封顶」**——那两个都是*每请求*上限，而 `VisionBudget` 类注释明文「每请求
   上限封不住循环，MAX_TURN_DELIVERIES 是唯一能真正封住单回合花费的机制」；豁免
   用户图 = 用户图的回合级花费失去上界（3 张 × 每轮 ~6k × 几十轮）。若选
   此方向，必须补替代上界（如用户图免槽但计入回合级 token 账）。另 `admitAll`
   现签名拿不到引用来源，需加参数或拆方法——改动量评估宜按「中等」而非「更小」。
3. **辅助（无论选哪个）：失效可见性**。当前失效模式是静默的——delivery 行虽改写为
   `turn_budget_exhausted`，但埋在几十 KB 上下文里弱档模型读不到信号，只会徒劳
   重试。两层改进：① **可观测性**（低成本高价值）：额度耗尽首次发生时打一次
   `log.warn`（含 turnKey 与计数），`/context` 显示累计张数但「耗尽」本身现在
   不可见，生产环境无从发现；② 若要改 `reason` 文案（如「本回合图片额度已用尽，
   重试无意义，请基于已有描述继续或等下一回合」），需先把 `FileReference.withDelivery`
   从「只改 delivery 行」扩展为「delivery+reason 成对改写」（现在 `reason:` 行逐字
   保留），是接口变化不是文案修改；且工具结果里的引用块一个字都不能改
   （VisionMaterializer 类注释），此方向只能覆盖锚点引用。弱档模型是否读得到
   reason 本身存疑——本案已证明它读不到 delivery 行——验证标准应定为行为性的：
   真机冒烟里耗尽后模型**不再重试 Read**。

### 6.1 顺带发现的潜伏 bug 与事实（未修，仅记录）

- **插话会造成一次「图闪断」**（本案未触发但代码路径存在）：`InterjectingChatModel.
  inject()` 用 `merged.add(new UserMessage(...))` 追加插话——裸 `UserMessage`、无
  `SYNTHETIC_KEY` 标记，且装饰链 `Interjecting(Vision(...))` 中插话层在视觉层**外**。
  用户在工具循环期间插话 → **承载插话的那一轮**锚点跳到插话消息，用户原图
  落在锚点之前不兑现、且改写只作用于锚点那条、连 `turn_budget_exhausted` 提示都
  没有。注意影响是**一次性的**：装饰层追加的消息不回流进下一轮（`ToolCallingAdvisor`
  从 advisor 层派生下一轮，装饰器在整条 advisor 链下游——同层 VisionMaterializer
  类注释用字节码核实过的事实），插话队列也是一次性 drain，下一轮锚点回到原
  用户消息、图恢复兑现。真实危害有二：① 间歇性「图闪断」无任何信号，弱模型可能
  就此开始怀疑视觉通道（与本案症状相似但为**间歇**而非持续）；② 该次请求 turnKey
  变化另开预算桶，一回合一连串插话可触顶 `MAX_TRACKED_TURNS=8` 引发 `clear()`、
  把在飞回合的额度清零重置（账目漂移）。修法方向：插话消息打上可识别标记并在
  `lastRealUserIndex` 排除，或插话改走「折进下一条真实 user 消息」。
- 排查中 B2 探针（tool 消息前无 tool_calls 的 assistant）会导致智谱端点**挂起到
  超时**而非报错——OpenAI 格式不合法消息序列的失效模式是挂起，排查时曾浪费一轮。
  这解释了为何生产链路的 `trimDanglingToolCalls` 净化如此重要。
- 会话 JSON 顶层 `version` 是仓储**变更计数**（`FileSessionRepository` 对 append 与
  replaceEvents 各 +1，写作时为 115、会话续写后增长到 400+），不是事件数、更不能
  用于锁定构建版本；锁定构建靠 `~/.zsh_history` + dist jar 时间戳。

## 7. 验证清单（实施修复时用）

1. 多迭代复现探针转成回归测试：锚点 2 图 + >6 轮工具往返，断言**每一轮**出站
   user 消息 Media 数恒为 2（修复前第 7 轮起为 0——先红后绿）。
2. 截图循环场景不回归：同回合 N 张**不同**工具图仍受额度约束（防修复把预算整个
   打穿）；含新 id 的图数超过 12 时仍剥离。
3. 回合级花费上界不回归：方向 1 的「回合 token 账」（或等价机制）有测试钉住——
   同一张图重发 N 次的回合 token 总额不随 N 无界增长。
4. `VisionBudgetTest` 按新语义**改造后**全绿（既有 5 个用例中 4 个按张·次写死，须连同
   契约一起改，不是简单回归）；`VisionMaterializerTest` /
   `VisionMaterializingChatModelTest` 全绿；并发子 agent 分桶语义有既有测试钉着。
5. 若同期修 §6.1 插话 bug：插话注入后用户原图仍兑现（锚点不被插话夺走）的回归
   测试。
6. 真机冒烟：glm-5.3-flash + 用户贴图 + 连续 10+ 轮工具循环，模型在最后一轮仍能
   描述贴图内容；若实现方向 3（可见性），追加行为断言：额度耗尽后模型**不再重试
   Read**。
7. 口径同步：`vision.md`（≤12 张·次 / 21.6k token 表述）与 `ContextUsage`
   （「每回合上限 %d 张」面板文案及其注释）随实现同步更新。
