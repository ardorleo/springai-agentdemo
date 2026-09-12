# 视觉回合预算按去重 id 计费 + 插话闪断修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复「用户贴图在多轮工具迭代后被回合预算静默剥离」——回合额度从「张·次」改为按去重图 id 计，并补回合级 token 账兜住花费；同期修复插话消息夺走视觉锚点的一次性闪断。

**Architecture:** `VisionBudget` 的回合计数从 `AtomicInteger` 改为按 turnKey 分桶的 `TurnLedger`（`synchronized` 的 id 判重+入集+token 记账原子段；**同 id 重发免槽免记账**）；`VisionMaterializer.admitAll` 把 `ParsedReference.sha()` 传入闸门并新增回合 token 闸；插话消息打 `VisionMaterializer.SYNTHETIC_KEY` 兼容标记（`lastRealUserIndex` 既有判据自动排除）。额度耗尽首次发生时 `log.warn`。

**Tech Stack:** Java 17（无类型模式 switch、无 record pattern）、JUnit 5（`org.junit.jupiter.api.Assertions`，期望在前）、SLF4J。

**Spec:** `docs/superpowers/specs/2026-09-11-vision-turn-budget-exhaustion-investigation.md`（§6 方向 1 + §6.1 插话 + §7 验证清单——计划从它出发，执行者须同时读）。

## Global Constraints

- Java 17 语法：`instanceof` 用传统写法；不写类型模式 switch / record pattern。
- 断言用 JUnit `Assertions`：`assertEquals(期望, 实际)`（**期望在前**）、`assertTrue/assertFalse(x, "消息")`。
- 测试命令模块作用域：`mvn test -pl springai-code-tui -Dtest=XxxTest`（单模块不需要 `-DfailIfNoSpecifiedTests`）。
- 提交信息中文，格式 `fix(vision): …` / `test(vision): …` / `docs(vision): …`。
- 每个任务结束前跑该任务测试类，绿了才提交。
- **每个任务的提交必须保持主代码编译通过**（`tryConsumeTurnSlot` 签名变更与调用点接线必须同任务提交）。
- 生产代码不得在出站热路径抛异常——预算层所有失败都降级为「不兑现」。
- 修改 delivery/reason 相关行为时必须保持既有五态语义不变（`delivered` / `not_in_view` / `budget_exceeded` / `turn_budget_exhausted` / `reference_only`）。

## 背景事实（依据，执行者必读）

排查文档已实锤（spec §4/§5.2）：`MAX_TURN_DELIVERIES=12` 按「张·次」计费，用户锚点
图每轮出站重复扣额度，2 张用户图 × 5 轮 + 工具图 2 张·次即耗尽，第 6 轮起全部剥离
（`admitAll` 判 `DELIVERY_TURN_EXHAUSTED`、`rewriteAnchor` 不挂 media）。真实会话
（xibaojun 20260911T154059）失明起点与模型第一句抱怨精确重合。

修复语义（用户已拍板方向 1，token 口径已随计划评审拍板）：

1. **回合额度按去重 id 计**：同一张图（同 `ParsedReference.sha()`）同回合多次投递
   只记一次额度。`MAX_TURN_DELIVERIES` 语义从「12 次兑现」变为「12 张不同图」。
2. **回合级 token 账，同 id 只记一次**：新增 `MAX_TURN_TOKENS = 36_000L`，只累计
   **不同图首次兑现**的 token 之和（同图重发记 0——其花费已由每请求 6000 上限封住，
   spec §4.1 明确定性回合层重计它是「重复计费」；若按次重记，2 张 ~1.3k 锚点图
   ×14 轮即触顶 36k，本 bug 换个阈值重现）。36k 账兜的是「12 张不同图」的花费窗口
   （12 × 3k = 36k 恰好容纳满员截图循环；spec §6 坑 1 的预算单位退化由此封住）。
3. **插话闪断**（spec §6.1）：`InterjectingChatModel.inject()` 追加的裸
   `UserMessage` 会夺走锚点，该轮用户图不兑现且无提示。修法：插话消息 metadata
   打 `VisionMaterializer.SYNTHETIC_KEY=true`（复用既有 `isSynthetic` 判据）。
   顺带消除 spec §6.1 危害②：锚点不再被夺 → turnKey 不变 → 不再因一串插话触顶
   `MAX_TRACKED_TURNS` 清掉在飞回合的账。
4. **可观测性**：额度耗尽（id 集满或 turn token 满）首次发生时 `log.warn` 一次，
   打**当前账**（billed 数 / token 数）而非常量上限。

**有意不做**（与 spec 一致，防执行者自作主张）：`reason` 文案改写（需把
`FileReference.withDelivery` 扩展为 delivery+reason 成对改写，接口变化、弱档模型
能否读到存疑，留待独立拍板）；spec §7 条 6 的「耗尽后模型不再重试 Read」行为断言
（log.warn 面向运维不面向模型，该断言在 log.warn 方案下无从验证——若日后做
reason 改写再补）。

## 文件结构

**修改**（无新建主代码文件）：

| 文件 | 改动 | 任务 |
|---|---|---|
| `agent/media/VisionBudget.java` | `TurnLedger`（id 集 + token 账 + warned 标记）替换 `AtomicInteger` perTurn；`tryConsumeTurnSlot(String, long)`（双参原子，无单参重载）；只读 getter | 1、2 |
| `agent/media/VisionMaterializer.java` | L297 调用点传 `r.sha(), img.estimatedTokens()`（Task 1，防编译断裂）；Task 2 只加 `warnIfFirstExhaustion` | 1、2 |
| `agent/interjection/InterjectingChatModel.java` | L122-124 插话消息打 `SYNTHETIC_KEY` | 3 |
| `ui/ContextUsage.java` | L106-109 文案与注释（张 → 张不同图） | 4 |
| `springai-code-tui/docs/guide/vision.md` | L78-86 硬上限口径（**只替换限额代码块与 21.6k 段**，token 估算 blockquote、五态枚举、「每轮重传」注记保留） | 4 |

**修改测试**：

| 测试 | 改动 | 任务 |
|---|---|---|
| `agent/media/VisionBudgetTest.java` | 3 个用例改造（`counterTableIsBounded` 仅补参数）；新增 3 个（同图重发/不同图耗尽/token 账） | 1 |
| `agent/media/VisionMaterializerTest.java` | **3 个既有用例按新语义重写**（同 sha 重发永不耗尽，旧断言必红）：`turnBudgetExhaustionStopsDelivery` / `turnBudgetExhaustionIsMarkedDistinctly` / `snapshotStopsGrowingOnceTurnBudgetIsExhausted` | 1 |
| `agent/media/VisionMaterializingChatModelTest.java` | 新增 `anchorImagesSurviveManyToolIterations`（主回归）、`turnTokenAccountStopsDistinctImageFlood` + `bigPng` helper（Step 5b）、`syntheticUserMessageDoesNotStealAnchor`（Task 3） | 1、3 |
| `agent/interjection/MidTurnInjectionTest.java` | 新增 `injectedMessageCarriesSyntheticMarker`（完整代码见 Task 3） | 3 |
| `agent/llm/ZhipuVisionSmokeTest.java` | 新增端到端多轮用例 | 5 |

---

### Task 1: TurnLedger 按去重 id 计 + 调用点接线（语义变更单元）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionBudget.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java:297`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionBudgetTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializerTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializingChatModelTest.java`

**Interfaces:**
- Consumes: 无（本任务是语义地基）。
- Produces: `Session.tryConsumeTurnSlot(String id, long delta)`（**签名变更+token 原子入闸，本任务内完成全部调用点**）、
  `Session.markExhaustionWarned()`、`Session.billedCount()` / `Session.turnTokens()`
  （只读 getter，Task 2 warn 用）、`MAX_TURN_TOKENS` 常量。Task 2 依赖这些名字。

- [ ] **Step 1: 写失败测试（VisionBudgetTest 追加 3 个 + 改造 4 个）**

追加：

```java
    /** 同一张图同回合重发只计一次额度——用户锚点图不再被工具迭代烧穿（spec §4）。 */
    @Test
    void sameImageReDeliveryWithinTurnCostsOneSlot() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 30; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-a", 1L),
                    "第 " + (i + 1) + " 次重发同图应恒过闸");
        }
    }

    /** 不同图数超过 12 张才耗尽——截图循环（每张新 id）照样被封（spec §7 条 2）。
     *  第二参 1L：让 token 远离 36k，本用例只考 size 闸。 */
    @Test
    void turnBudgetCountsDistinctImagesNotDeliveries() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-" + i, 1L), "第 " + (i + 1) + " 张");
        }
        assertFalse(b.open("turn-1").tryConsumeTurnSlot("img-12", 1L), "第 13 张不同图应被拒");
        assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-0", 1L), "已计费过的图重发仍过闸");
    }

    /** 回合 token 账只累计不同图首次兑现：★ spec §7 条 3 的钉子——同 id 重发 token 不重记。
     *  若实现退化成「每次过闸都记账」，本测试第二个断言（turnTokens() 仍 3278）会红。 */
    @Test
    void turnTokenAccountBoundsDistinctImageSpend() {
        VisionBudget b = new VisionBudget();
        VisionBudget.Session s = b.open("turn-1");
        assertTrue(s.tryConsumeTurnSlot("img-a", 3_278));
        assertTrue(s.tryConsumeTurnSlot("img-a", 3_278), "同图重发过闸");
        assertEquals(3_278L, s.turnTokens(), "同图重发不得重复记 token（spec §7 条 3）");
        assertTrue(s.tryConsumeTurnSlot("img-b", 32_722), "不同图累计恰好到达上限算过（含端点）");
        assertFalse(s.tryConsumeTurnSlot("img-c", 1), "超额即拒");
        assertTrue(b.open("turn-2").tryConsumeTurnSlot("img-c", 18_000), "别的回合不受 turn-1 的账影响");
    }
```

改造（签名走双参；**id 配方必须用不同图**——同 id 在新语义下永不耗尽，循环内
统一 `"img-" + i`，循环后的 assertFalse 用**新 id**）：`turnBudgetIsExhaustedAfterTwelveDeliveries`
循环 12 次 `tryConsumeTurnSlot("img-" + i, 1L)`，随后
`assertFalse(b.open("turn-1").tryConsumeTurnSlot("img-12", 1L))`；`turnsAreIsolatedFromEachOther`
（注意实际用例名无 Budgets）同样 12 次 `"img-" + i` 耗尽 turn-1，随后
`assertFalse(open("turn-1").tryConsumeTurnSlot("img-x", 1L))`（新 id）与
`assertTrue(open("turn-2").tryConsumeTurnSlot("img-x", 1L))`（别的回合不受影响）；
`counterTableIsBounded` 的 `tryConsumeTurnSlot()` → `tryConsumeTurnSlot("img", 1L)`；
`tokenCapStopsAdmittingFurtherImages` / `userImagesAreCappedAtThree` 不动。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=VisionBudgetTest`
Expected: 编译失败（双参 `tryConsumeTurnSlot(String, long)` 不存在）——即红。

- [ ] **Step 3: 最小实现（VisionBudget + VisionMaterializer 调用点）**

`VisionBudget.java`：`perTurn` 换 `Map<String, TurnLedger>`，`counter()` 清桶逻辑
原样保留（clear 后同图重新计一次槽+token——保守方向、有界，spec §6 坑2 既定取舍，
实现注释写明）；类注释「张·次」表述更新为「不同图（按引用 id 去重）」。

```java
    /** 每回合：不同图（按引用 id 去重）张数上限。 */
    public static final int MAX_TURN_DELIVERIES = 12;
    /** 每回合：不同图首次兑现的视觉 token 累计上限（同图重发记 0——见类注释口径论证）。 */
    public static final long MAX_TURN_TOKENS = 36_000L;

    /**
     * 一个回合的账本。id 口径注意：引用 id 是混合口径（artifacts 内=内容 sha、项目内
     * 普通文件=绝对路径 sha）——按 id 计费意味着「同图挪路径」计 2 槽、「同路径换内容
     * （非 artifacts）」计 1 槽，这是 spec §6 坑3 既定取舍。
     *
     * <p><b>槽位与 token 必须在一个原子段里判</b>：拆成 tryBill + admitTurnTokens 两步，
     * 锚点图每轮重发会在第二步被重复记账——2 张 ~1.3k 图 × 14 轮即触顶 36k，本 bug
     * 换个阈值重现（计划评审第 2 轮 blocker）。同图重发（billed 命中）免槽且免记账。
     */
    static final class TurnLedger {
        private final Set<String> billed = new HashSet<>();
        private long tokens;
        private boolean warned;

        /** 判重+入集+记账原子：返回 false = 拒（槽满或 token 满），已计费 id 恒 true。 */
        synchronized boolean tryBill(String id, long delta) {
            if (billed.contains(id)) return true;              // 同图重发：免槽、token 不重记
            if (billed.size() >= MAX_TURN_DELIVERIES) return false;
            if (tokens + delta > MAX_TURN_TOKENS) return false;
            billed.add(id);
            tokens += delta;
            return true;
        }

        synchronized boolean markWarned() {
            if (warned) return false;
            warned = true;
            return true;
        }

        synchronized int billedCount() { return billed.size(); }
        synchronized long tokenTotal() { return tokens; }
    }
```

`Session` 持有 `TurnLedger`，`open(turnKey)` 改 `computeIfAbsent(turnKey, k -> new TurnLedger())`；
`tryConsumeTurnSlot(String id, long delta)` 委托 `ledger.tryBill(id, delta)`（**没有单参重载**——
逼调用方在同一行带上 token，防止「先计槽后记账」拆步回归）；`markExhaustionWarned()` /
`billedCount()` / `turnTokens()` 委托同 ledger。
（全 synchronized 后无需 `ConcurrentHashMap.newKeySet()`，普通 `HashSet` 即可。）
同时更新 `VisionBudget` 类级 javadoc 的「12 × ~1.8k ≈ 21.6k token」段：回合花费上界
改为「`MAX_TURN_TOKENS`（36k，只记不同图首兑现）」表述（spec §6 坑1 点名的类注释口径）。

`VisionMaterializer.java:297` 单行改动（注意双参——id 与 token 同一行过闸）：

```java
            if (!session.tryConsumeTurnSlot(r.sha(), img.estimatedTokens())) {   // 回合额度（id 槽 + token 账原子判）
```

- [ ] **Step 4: 重写 VisionMaterializerTest 三个必红既有用例**

同一 sha 重发在新语义下**永不耗尽**，以下三个用例按「多轮不同图」重写（重写配方：
锚点文本固定 → turnKey 恒定；循环里**消息表追加**含第 i 张新图引用的 tool 结果，
每轮 `new Prompt(msgs, ...)` 出站——这正是生产回合形态）：

```java
    /** 改写自 turnBudgetExhaustionStopsDelivery：12 张不同图耗尽（同图重发已由
     *  anchorImagesSurviveManyToolIterations 覆盖——同 sha 永不耗尽是新语义的要点）。 */
    @Test
    void turnBudgetExhaustionStopsDelivery() throws Exception {
        for (int i = 0; i < 12; i++) png("docs/i" + i + ".png");
        VisionMaterializer m = materializer();
        List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        msgs.add(new UserMessage("固定提问"));
        for (int i = 0; i < 12; i++) {
            msgs.add(toolCall("c" + i));
            msgs.add(toolResult("c" + i, ref("i" + i + ".png", "docs/i" + i + ".png")));
            assertEquals(msgs.size() + 1, m.materialize(new Prompt(msgs), true).getInstructions().size(),
                    "第 " + (i + 1) + " 张不同图应仍在额度内（合成消息 +1）");
        }
        msgs.add(toolCall("c12"));
        msgs.add(toolResult("c12", ref("i12.png", "docs/i12.png")));
        png("docs/i12.png");
        assertEquals(msgs.size(), m.materialize(new Prompt(msgs), true).getInstructions().size(),
                "第 13 张不同图不该再兑现");
    }
```

（执行者注：`toolCall()`/`toolResult()` 若无现成 helper 则照该测试类既有用例的
assistant(tool_calls)/ToolResponseMessage 构造方式补私有 helper；上面
`toolCall("c"+i)` 形参是 call id。

`turnBudgetExhaustionIsMarkedDistinctly` 的重写**不能**按原思路断言出站文本含
`DELIVERY_TURN_EXHAUSTED`——新语义下该状态在出站文本层**不可达**（已计费的锚点
引用 `tryBill` 恒过闸；未计费的先被每请求 3 张闸判成 `budget_exceeded`；工具消息
一个字不能改、无文本载体）。（**常规路径**不可达：已计费的锚点引用
`tryBill` 恒过闸；未计费的多因 `admit` 恒拒被判 `budget_exceeded` 到不了 tryBill；
工具消息一个字不能改、无文本载体——极端 prepare 失败腾预算的时序下理论可达，不作
断言依据）。改为两个可观测等价断言：① 账本满 12 后第 13 张**不同
工具图**不再产生合成消息（出站 size 不 +1）；② 同回合锚点带已计费引用时锚点图
**仍然兑现**（不被回合额度挤掉）。`TURN_EXHAUSTED` 与 `BUDGET_EXCEEDED` 的语义区分
下沉到 `VisionBudgetTest` 单元级（同一图第 4 张 → `budget_exceeded` 语境；第 13 张
不同图 → 回合语境——即 `turnBudgetCountsDistinctImagesNotDeliveries` 已覆盖）。

`snapshotStopsGrowingOnceTurnBudgetIsExhausted` 同洪水配方重写：每轮追加一张新图
tool 结果，快照 images 累到 12、第 13 轮兑现 0 → images 停在 12、token 停涨。）

- [ ] **Step 5: 写主回归（VisionMaterializingChatModelTest 追加）**

```java
    /** ★ 回归：用户锚点图不得被工具迭代烧穿（xibaojun 20260911 会话根因，spec §5.2）。
     *  修复前第 7 轮起 media=0（同 sha 每轮 2 槽 × 6 轮 = 12 耗尽）；修复后同 id 恒过闸。 */
    @Test
    void anchorImagesSurviveManyToolIterations() throws Exception {
        png("docs/a.png");
        png("docs/b.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        VisionMaterializingChatModel m = VisionMaterializingChatModel.wrap(spy, root);

        List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        msgs.add(new UserMessage("看这两张\n" + ref("a.png", "docs/a.png") + ref("b.png", "docs/b.png")));
        for (int round = 1; round <= 10; round++) {
            msgs.add(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + round, "function", "Read", "{}")))
                    .build());
            msgs.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("c" + round, "Read",
                            ref("a.png", "docs/a.png"))))   // 模型反复 Read 同一张（同 sha 被 seen 去重）
                    .build());
            m.call(new Prompt(new java.util.ArrayList<>(msgs), optionsFor("gpt-5.6-sol")));
            long media = spy.seen.get().getInstructions().stream()
                    .filter(x -> x instanceof MediaContent)
                    .mapToLong(x -> ((MediaContent) x).getMedia().size()).sum();
            assertEquals(2, media, "第 " + round + " 轮：用户锚点图必须始终在场");
        }
    }
```

（需补 import：`org.springframework.ai.chat.messages.ToolResponseMessage`。）

- [ ] **Step 5b: 追加洪水测试（回合 token 账的集成钉子，原 Task 2 Step 1 前移）**

```java
    /** 回合 token 账兜底：不同图洪水在 36k 处被拒——若 tryBill 忘了 token 判定（只剩
     *  id 去重），本测试红（11 张 < 12 张 id 上限，id 闸放行全部）。
     *  ★ 边界算术：1700×1700 被 ImagePreparer 缩到 1568×1568 = 3278 token/张；
     *  10 张 32,780 ≤ 36k 过，第 11 张 36,058 > 36k 拒。 */
    @Test
    void turnTokenAccountStopsDistinctImageFlood() throws Exception {
        for (int i = 0; i < 11; i++) bigPng("docs/f" + i + ".png", 1700, 1700);
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        VisionMaterializingChatModel m = VisionMaterializingChatModel.wrap(spy, root);
        List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        msgs.add(new UserMessage("看这些"));
        for (int i = 0; i < 11; i++) {
            msgs.add(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "Read", "{}")))
                    .build());
            msgs.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("c" + i, "Read",
                            ref("f" + i + ".png", "docs/f" + i + ".png"))))
                    .build());
            m.call(new Prompt(new java.util.ArrayList<>(msgs), optionsFor("gpt-5.6-sol")));
            long media = spy.seen.get().getInstructions().stream()
                    .filter(x -> x instanceof MediaContent)
                    .mapToLong(x -> ((MediaContent) x).getMedia().size()).sum();
            if (i < 10) {
                assertEquals(1, media, "第 " + (i + 1) + " 张（累计 " + ((i + 1) * 3278L) + " ≤ 36k）应兑现");
            } else {
                assertEquals(0, media, "第 11 张（累计 36,058 > 36k）应被回合 token 账拒");
            }
        }
    }

    /** 大图 helper：png() 只写 100×80，洪水测试需要会被缩放的大图。 */
    private void bigPng(String rel, int w, int h) throws Exception {
        Path p = root.resolve(rel);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
    }
```

（每轮恰好 1 张工具图兑现——`MAX_TOOL_IMAGES=1` 取最新；请求级 `admit`（6000）
容得下单张 3278。）

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest='VisionBudgetTest,VisionMaterializerTest,VisionMaterializingChatModelTest'`
Expected: 全部 PASS。特别核对重写的三个用例、`anchorImagesSurviveManyToolIterations`
与洪水测试。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionBudget.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionBudgetTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializerTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializingChatModelTest.java
git commit -m "fix(vision): 回合额度按去重图 id 计（锚点图跨工具迭代存活）"
```

---

### Task 2: 耗尽首爆 warn 接线

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java`（logger + warnIfFirstExhaustion + admitAll 拒绝分支调用）
- Test: 仅续跑既有测试确认零行为变化（`VisionMaterializingChatModelTest` 本任务不修改、不提交）

**Interfaces:**
- Consumes: Task 1 的 `markExhaustionWarned()` / `billedCount()` / `turnTokens()` / `MAX_TURN_TOKENS`。
- Produces: 无新接口——warn 不做自动化断言（本模块无 `ExpectedLog` 设施，它在
  springai-tamboui-inline-patch 模块），由 Task 5 真机冒烟人工观察。

- [ ] **Step 1: 跑既有洪水测试确认基线绿**

Run: `mvn test -pl springai-code-tui -Dtest=VisionMaterializingChatModelTest`
Expected: 全部 PASS（Task 1 已实现双参 tryBill + token 账，闸门行为正确；本任务的
warn 接线是纯增量——不改变任何兑现决策，只加观测）。

- [ ] **Step 2: 最小实现**

`admitAll` 闸门段（`PreparedImage img = prepared.get()` 之后）——Task 1 已把 id 槽与
token 账合并进双参 `tryConsumeTurnSlot`，本任务只加 `warnIfFirstExhaustion` 调用：

```java
            PreparedImage img = prepared.get();
            if (!session.admit(img.estimatedTokens())) {            // 本请求 token 预算满了
                out.put(r, new Outcome(null, FileReference.DELIVERY_BUDGET_EXCEEDED, 0L));
                continue;
            }
            if (!session.tryConsumeTurnSlot(r.sha(), img.estimatedTokens())) {  // 回合额度（id 槽 + token 原子）
                out.put(r, new Outcome(null, FileReference.DELIVERY_TURN_EXHAUSTED, 0L));
                warnIfFirstExhaustion(session);
                continue;
            }
```

`VisionMaterializer` 加 logger 与首爆方法（打**当前账**，非上限常量）：

```java
    private static final Logger log = LoggerFactory.getLogger(VisionMaterializer.class);

    /** 耗尽首爆：一个回合只 warn 一次（后续每轮都打会刷屏）。绝不抛异常——出站热路径。 */
    private void warnIfFirstExhaustion(VisionBudget.Session session) {
        if (session.markExhaustionWarned()) {
            log.warn("视觉回合额度耗尽（本回合已计费 {} 张不同图 / {} token，上限 12 张 / {}）——"
                    + "后续图将剥离，新回合即恢复。排查背景见 docs/superpowers/specs/"
                    + "2026-09-11-vision-turn-budget-exhaustion-investigation.md",
                    session.billedCount(), session.turnTokens(), VisionBudget.MAX_TURN_TOKENS);
        }
    }
```

（注：本模块无 `ExpectedLog` 测试设施（它在 springai-tamboui-inline-patch 模块），
warn 不做自动化断言，由 Task 5 真机冒烟人工观察。）

- [ ] **Step 3: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest='VisionMaterializerTest,VisionMaterializingChatModelTest,VisionBudgetTest'`
Expected: 全部 PASS（warn 接线零行为变化——若任何用例变红，说明接线动了决策路径，回查）。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java
git commit -m "fix(vision): 视觉回合额度耗尽首爆 warn（打当前账，一回合一次）"
```

---

### Task 3: 插话消息打标记，锚点不再被夺（闪断修复）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/InterjectingChatModel.java:122-124`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/interjection/MidTurnInjectionTest.java`（追加）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializingChatModelTest.java`（追加）

**Interfaces:**
- Consumes: `VisionMaterializer.SYNTHETIC_KEY`（public 常量，既有）、`isSynthetic(Message)`（既有 public 静态）。
- Produces: 无。

- [ ] **Step 1: 写失败测试（两处）**

`VisionMaterializingChatModelTest` 追加（钉行为不回归；`lastRealUserIndex` 本就排除
synthetic，这条在修复前也应 PASS——它是防将来有人改掉排除判据）：

```java
    /** ★ 回归：插话消息（SYNTHETIC_KEY 标记的 user）不得夺走锚点（spec §6.1 闪断）。 */
    @Test
    void syntheticUserMessageDoesNotStealAnchor() throws Exception {
        png("docs/a.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        VisionMaterializingChatModel m = VisionMaterializingChatModel.wrap(spy, root);
        List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        msgs.add(new UserMessage("看这张\n" + ref("a.png", "docs/a.png")));
        msgs.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("c1", "Read", "ok")))
                .build());
        // 插话注入形态：追加一条带 SYNTHETIC_KEY 的 user（InterjectingChatModel 修复后如此构造）
        msgs.add(UserMessage.builder().text("[interjection] 继续加油 [/interjection]")
                .metadata(java.util.Map.of(VisionMaterializer.SYNTHETIC_KEY, true))
                .build());
        m.call(new Prompt(msgs, optionsFor("gpt-5.6-sol")));
        assertTrue(hasMedia(spy.seen.get()), "插话轮用户图必须仍在场（锚点被夺则图消失）");
    }
```

`MidTurnInjectionTest` 追加（钉注入消息真的带标记——**这条是红测试**，现状裸
`UserMessage` 无该键）。arrange 逐行转写自 `interjectionArrivesAtNextModelCall`
（L127-148，含 `DefaultSessionService`/`InMemorySessionRepository` 那几行，勿省略），
仅断言替换。**补 import**：`org.springframework.ai.chat.messages.UserMessage`（该测试
类现状没有它）：

```java
    /** 注入的插话消息必须带 SYNTHETIC_KEY——否则会夺走视觉锚点（spec §6.1）。 */
    @Test
    void injectedMessageCarriesSyntheticMarker() throws Exception {
        // arrange 与 interjectionArrivesAtNextModelCall 完全同款：
        // SessionService + entered/gate 两个 CountDownLatch + CapturingModel +
        // Interjections + ChatClient.builder(InterjectingChatModel.wrap(model, interjections))
        //     .defaultTools((Object) slowTool(entered, gate)).build()
        // 后台线程跑 client.prompt().user("原始提问").stream().chatClientResponse().blockLast()
        // 主线程 await entered → interjections.offer("改用方案 B") → gate.countDown()
        List<Prompt> prompts = model.awaitPrompts(2);
        List<Message> second = prompts.get(1).getInstructions();
        Message last = second.get(second.size() - 1);
        assertInstanceOf(UserMessage.class, last);
        assertEquals(Boolean.TRUE,
                last.getMetadata().get(io.github.javaside.springai.codetui.agent.media.VisionMaterializer.SYNTHETIC_KEY),
                "插话消息不带 SYNTHETIC_KEY 会夺走视觉锚点（spec §6.1 闪断）");
        turn.join(5000);
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest='VisionMaterializingChatModelTest,MidTurnInjectionTest'`
Expected: `injectedMessageCarriesSyntheticMarker` FAIL（metadata 无 SYNTHETIC_KEY）；
`syntheticUserMessageDoesNotStealAnchor` PASS。

- [ ] **Step 3: 最小实现**

`InterjectingChatModel.inject()` L122-124 改为：

```java
        List<Message> merged = new ArrayList<>(messages);
        // SYNTHETIC_KEY 标记：防止这条「工具结果之后突然出现的 user」被 VisionMaterializer
        // 当成新回合锚点——那会让该轮用户原图全部静默剥离（spec §6.1 闪断）。复用既有
        // isSynthetic 判据，不新造标记体系。
        merged.add(UserMessage.builder()
                .text(injectedText)
                .metadata(java.util.Map.of(
                        io.github.javaside.springai.codetui.agent.media.VisionMaterializer.SYNTHETIC_KEY, true))
                .build());
        return new Prompt(merged, prompt.getOptions());
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest='VisionMaterializingChatModelTest,MidTurnInjectionTest'`
Expected: 全部 PASS（含既有 interjection 用例不回归——插话文本与结构未变，只多了 metadata）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/InterjectingChatModel.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/interjection/MidTurnInjectionTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializingChatModelTest.java
git commit -m "fix(vision): 插话消息打 SYNTHETIC_KEY，不再夺走视觉锚点（闪断修复）"
```

---

### Task 4: 口径同步（vision.md + ContextUsage）与全量回归

**Files:**
- Modify: `springai-code-tui/docs/guide/vision.md:78-86`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java:106-109`
- Test: 全模块回归

**Interfaces:** Consumes Task 1/2 最终语义；Produces 无。

- [ ] **Step 1: vision.md L78-86（仅限额代码块与 21.6k 段，其余保留）改为**

```markdown
```
每请求   用户贴图 ≤3 张 + 工具产图 ≤1 张（最新那张） + 6k 视觉 token
每回合   不同图 ≤12 张（按引用 id 去重）+ 36k 视觉 token 累计（只记不同图首兑现）
```

「按 id 去重」意味着：同一张图在工具迭代里每轮重发**不再重复扣额度**（2026-09 修复
了它误伤用户贴图的问题——曾经 2 张贴图 × 6 轮迭代就把额度烧穿、图被静默剥离，排查
见 `docs/superpowers/specs/2026-09-11-vision-turn-budget-exhaustion-investigation.md`）。
跑飞的截图循环仍然被封（每张新截图都是新 id），其花费上界由 36k 回合 token 账兜底
（12 张 × ~3k）。额度耗尽的信号是一条 `log.warn`（面向运维，一回合一次）；
`turn_budget_exhausted` 状态保留在五态枚举中（新语义下常规路径到不了它——已计费
的图恒过闸、新图先撞每请求 3 张闸），下个回合额度即恢复。
```

`ContextUsage.java` L106-109：**保持**数据源不动（`VisionSnapshot.accumulate` 按兑现
次数累计，面板数字口径仍是张·次——改它要动 accumulate 的去重逻辑，本轮不做），只把
上限文案改准确：格式串改为「本回合兑现 %d 张·次（上限 12 张不同图，不计入上方合计）」；
注释「严格说单位是张·次（同一张图跨迭代重发计两次，与上限同一口径）」改为
「张·次是兑现口径；上限口径是 12 张**不同图**（按 id 去重）——两者不同属正常，
重发不占额度但占当轮上下文」。

- [ ] **Step 2: 全模块回归**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿（对照仓库基线）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/docs/guide/vision.md \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java
git commit -m "docs(vision): 预算口径更新（不同图 12 张 + 36k 回合 token，同图重发不重复计费）"
```

---

### Task 5: 真机端到端冒烟（双 gate，可选执行）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuVisionSmokeTest.java`（追加）

**Interfaces:** Consumes `ZhipuProvider(apiKey, baseUrl).chatModel()` / `options(String)`、
`VisionMaterializingChatModel.wrap(ChatModel, Path)`（既有）。Produces 无。

- [ ] **Step 1: 追加端到端用例（走完整兑现链，非裸 provider）**

```java
    /** 端到端：引用块文本（磁盘真文件）→ VisionMaterializingChatModel 兑现 → 真机多轮
     *  工具迭代后模型仍能描述贴图。覆盖 xibaojun 会话根因的完整链路（预算层在链上）。 */
    @Test
    void referencedImageSurvivesToolRoundsEndToEnd() throws Exception {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"));
        Path tmp = Files.createTempDirectory("codetui-vision-e2e");
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ImageIO.write(img, "png", tmp.resolve("poster.png").toFile());
        ChatModel wrapped = VisionMaterializingChatModel.wrap(p.chatModel(), tmp);
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(wrapped).defaultTools(tool).build();
        String ref = "[file reference]\nid: sha256:e2e\nkind: image\nmime_type: image/png\n"
                + "size_bytes: 500\nname: poster.png\npath: poster.png\n"
                + "delivery: not_in_view\nreason: not currently in view\n[/file reference]";
        List<String> chunks = client.prompt()
                .user("先调用工具查天气，再调用一次工具，最后告诉我图片是什么颜色。三件事都做。\n" + ref)
                .options(p.options(MODEL).mutate())
                .stream().content().collectList().block(Duration.ofMinutes(5));
        String text = String.join("", chunks);
        assertTrue(tool.invoked.get(), "工具应被调用（多轮迭代）");
        String t = text.toLowerCase();
        assertTrue(t.contains("橙") || t.contains("orange"), "多轮迭代后仍能描述贴图颜色，实际: " + text);
    }
```

（import 需补：`io.github.javaside.springai.codetui.agent.media.VisionMaterializingChatModel`、
`java.nio.file.Files`/`Path`、`java.awt.Graphics2D`、`java.awt.image.BufferedImage`、
`javax.imageio.ImageIO`。）

- [ ] **Step 2: 真机跑（有 key 时；无 key 跳过不阻塞合入）**

Run: `source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest`
Expected: 全部 PASS。失败则记录实测现象回报；行为断言（颜色词）对弱档模型偶发不精确
时可降级为「回答非空且工具被调用 ≥2 次」，在提交信息注明降级理由。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuVisionSmokeTest.java
git commit -m "test(vision): 引用块兑现链端到端真机回归（多轮工具迭代后贴图在场）"
```

---

## Self-Review 记录

- **Spec 覆盖核对**：§6 方向 1 坑 1（**Task 1** 双参 tryBill 的 token 原子闸 + 36k 只记不同图）、坑 2（Task 1 synchronized 原子段 + 清桶取舍注释）、坑 3（Task 1 TurnLedger javadoc 混合口径）、坑 4（Task 1 改造 4 用例 + 3 个既有用例重写）；§6.1 插话（Task 3，含危害②消除说明）；方向 3 ① log.warn（Task 2，打当前账）、② reason 改写**有意不做**（背景事实节显式声明，与 spec 论证一致）；§7 条 1（Task 1 Step 5）、条 2（Task 1 同名用例）、条 3（Task 1 Step 5b 洪水 + VisionBudgetTest token 用例的双重钉子——同 id 不重记 token）、条 4（Task 1 全量改造）、条 5（Task 3）、条 6（Task 5——行为断言「不再重试 Read」按「有意不做」节声明放弃，只保「贴图在场」）、条 7（Task 4 口径同步；VisionBudget 类注释 21.6k 段已在 Task 1 Step 3 更新）。无缺口。
- **评审修订记录**（第 2 轮，防回归）：① token 记账与槽位合并为双参 `tryBill(id, delta)`
  原子段——拆两步会让锚点图重发重复记账、14 轮触顶 36k 复发本 bug；② 洪水测试边界
  按 1568 缩放实算（3278/张，第 11 张拒）并前移进 Task 1；③ `turnBudgetExhaustionIsMarkedDistinctly`
  重写为可观测等价断言（新语义下 `TURN_EXHAUSTED` 出站文本不可达）；④ MidTurnInjectionTest
  补 UserMessage import；⑤ vision.md 不再承诺观测不到的 delivery 行；⑥ ContextUsage
  保持张·次数据口径、只改上限文案。
- **占位符扫描**：Task 1 Step 4 的 `toolCall()`/`toolResult()` helper 与 Task 3 Step 1 的 arrange 均为「照既有用例抄」的明确指示（指了具体用例名与构成件），非 TBD；Task 2 Step 1 注明了 token 估算出入时的调参口径。其余无占位。
- **类型一致性**：`tryConsumeTurnSlot(String, long)` Task 1 产出并在同任务接线（无编译断裂窗口、无单参重载防拆步回归）；`markExhaustionWarned()`/`billedCount()`/`turnTokens()` Task 1 产出、Task 2 消费；`SYNTHETIC_KEY` 既有 public。已核对。
- **已知不确定点（有意保留）**：① 洪水测试的 token 边界以 `PreparedImage.estimatedTokens()` 实际值为准（1700×1700 经 1568 缩放实发 3278/张、第 11 张拒——若实现改动缩放逻辑导致边界漂移，按实际值调整断言的 i 阈值，断言结构不变）；② Task 5 行为断言的弱档模型鲁棒性——已给降级口径；③ `MAX_TRACKED_TURNS` 清桶后同图重计一次槽+token——保守方向、有界，Task 1 实现注释承认。
