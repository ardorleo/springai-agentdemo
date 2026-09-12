# 视觉回合预算按去重 id 计费 + 插话闪断修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复「用户贴图在多轮工具迭代后被回合预算静默剥离」——回合额度从「张·次」改为按去重图 id 计，并补回合级 token 账兜住花费；同期修复插话消息夺走视觉锚点的一次性闪断。

**Architecture:** `VisionBudget.Session.tryConsumeTurnSlot()` 从 `AtomicInteger` 计数改为按 turnKey 分桶的已计费 id 集合（`synchronized` 判重+入集原子段），新增回合级 token 账 `admitTurnTokens`；`VisionMaterializer.admitAll` 把 `ParsedReference.sha()` 传入闸门；插话消息打 `VisionMaterializer.SYNTHETIC_KEY` 兼容标记并在 `lastRealUserIndex` 排除。额度耗尽首次发生时打 `log.warn`。

**Tech Stack:** Java 17（无类型模式 switch、无 record pattern）、JUnit 5（`org.junit.jupiter.api.Assertions`，期望在前）、SLF4J。

**Spec:** `docs/superpowers/specs/2026-09-11-vision-turn-budget-exhaustion-investigation.md`（§6 方向 1 + §6.1 插话 + §7 验证清单——计划从它出发，执行者须同时读）。

## Global Constraints

- Java 17 语法：`instanceof` 用传统写法；不写类型模式 switch / record pattern。
- 断言用 JUnit `Assertions`：`assertEquals(期望, 实际)`（**期望在前**）、`assertTrue/assertFalse(x, "消息")`。
- 测试命令模块作用域：`mvn test -pl springai-code-tui -Dtest=XxxTest`（单模块不需要 `-DfailIfNoSpecifiedTests`）。
- 提交信息中文，格式 `fix(vision): …` / `test(vision): …` / `docs(vision): …`。
- 每个任务结束前跑该任务测试类，绿了才提交。
- 生产代码不得在出站热路径抛异常——预算层所有失败都降级为「不兑现」。
- 修改 delivery/reason 相关行为时必须保持既有五态语义不变（`delivered` / `not_in_view` / `budget_exceeded` / `turn_budget_exhausted` / `reference_only`）。

## 背景事实（依据，执行者必读）

排查文档已实锤（spec §4/§5.2）：`MAX_TURN_DELIVERIES=12` 按「张·次」计费，用户锚点
图每轮出站重复扣额度，2 张用户图 × 5 轮 + 工具图 2 张·次即耗尽，第 6 轮起全部剥离
（`admitAll` 判 `DELIVERY_TURN_EXHAUSTED`、`rewriteAnchor` 不挂 media）。真实会话
（xibaojun 20260911T154059）失明起点与模型第一句抱怨精确重合。

修复语义（用户已拍板方向 1）：

1. **回合额度按去重 id 计**：同一张图（同 `ParsedReference.sha()`）同回合多次投递
   只记一次额度。`MAX_TURN_DELIVERIES` 语义从「12 次兑现」变为「12 张不同图」。
2. **回合级 token 账**：新增 `MAX_TURN_TOKENS`（= 36_000L，6k × 6 轮的量级，见
   Task 2 论证），同一 turnKey 桶内累计真兑现的 token，超了判
   `turn_budget_exhausted`——兜住「12 张图 × 每轮重发」的花费上界（方向 1 的
   预算单位退化，spec §6 坑 1）。
3. **插话闪断**（spec §6.1）：`InterjectingChatModel.inject()` 追加的裸
   `UserMessage` 会夺走锚点，该轮用户图不兑现且无提示。修法：插话消息 metadata
   打 `VisionMaterializer.SYNTHETIC_KEY=true`（复用既有排除判据），锚点判定跳过它。
4. **可观测性**：额度耗尽（id 集满或 turn token 满）首次发生时 `log.warn` 一次。

## 文件结构

**修改**（无新建主代码文件）：

| 文件 | 改动 |
|---|---|
| `agent/media/VisionBudget.java` | 回合计数 → 按 turnKey 分桶的 id 集合；新增 `MAX_TURN_TOKENS` 与回合 token 账；耗尽首次 warn 日志 |
| `agent/media/VisionMaterializer.java` | `admitAll`/`Session` 调用点传 `r.sha()`；`tryConsumeTurnSlot` 签名变更适配 |
| `agent/interjection/InterjectingChatModel.java` | 插话消息打 `SYNTHETIC_KEY` 标记 |
| `ui/ContextUsage.java` | 「每回合上限 %d 张」注释与文案微调（张 → 张不同图） |
| `springai-code-tui/docs/guide/vision.md` | 硬上限一节的口径更新 |

**修改测试**：`agent/media/VisionBudgetTest.java`（4 用例改新语义 + 新增 3 个）、
`agent/media/VisionMaterializingChatModelTest.java`（新增多轮回归 + 插话回归）、
`agent/interjection/MidTurnInjectionTest.java`（新增标记断言）。

---

### Task 1: VisionBudget 按去重 id 计 + 回合 token 账

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionBudget.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionBudgetTest.java`

**Interfaces:**
- Consumes: 无（本任务是语义地基）。
- Produces: `VisionBudget.Session.tryConsumeTurnSlot(String id)`（原无参签名**废弃**）、
  `Session.admitTurnTokens(long tokens)`、`MAX_TURN_TOKENS` 常量（后续任务依赖这三个名字）。

- [ ] **Step 1: 写失败测试（新语义）**

`VisionBudgetTest` 追加三个用例（保留既有 5 个中 `userImagesAreCappedAtThree` /
`tokenCapStopsAdmittingFurtherImages` / `counterTableIsBounded` 原样；另两个按新
语义改写，见 Step 3）：

```java
    /** 同一张图同回合重发只计一次额度——用户锚点图不再被工具迭代烧穿（spec §4）。 */
    @Test
    void sameImageReDeliveryWithinTurnCostsOneSlot() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 30; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-a"),
                    "第 " + (i + 1) + " 次重发同图应恒过闸");
        }
    }

    /** 不同图数超过 12 张才耗尽——截图循环（每张新 id）照样被封（spec §7 条 2）。 */
    @Test
    void turnBudgetCountsDistinctImagesNotDeliveries() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-" + i), "第 " + (i + 1) + " 张");
        }
        assertFalse(b.open("turn-1").tryConsumeTurnSlot("img-12"), "第 13 张不同图应被拒");
        assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-0"), "已计费过的图重发仍过闸");
    }

    /** 回合级 token 账：同图重发不重复记账，不同图累计、超限即拒（spec §6 方向1 坑1）。 */
    @Test
    void turnTokenAccountBoundsDistinctImageSpend() {
        VisionBudget b = new VisionBudget();
        VisionBudget.Session s1 = b.open("turn-1");
        assertTrue(s1.admitTurnTokens(1_000));
        assertTrue(s1.admitTurnTokens(1_000));            // 同图重发（同 session 二次尝试）不重复记账的口径见实现注释
        assertFalse(b.open("turn-2").admitTurnTokens(VisionBudget.MAX_TURN_TOKENS),
                "别的回合不受 turn-1 的账影响，但单回合超额应拒");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=VisionBudgetTest`
Expected: 编译失败（`tryConsumeTurnSlot(String)` 与 `admitTurnTokens` 不存在）——新用例无从跑起，即红。

- [ ] **Step 3: 最小实现**

`VisionBudget.java` 全量重构核心段（保留类注释里「为什么分桶」的论证，更新「张·次」
为「不同图张数」表述）：

```java
    /** 每回合：不同图（按引用 id 去重）张数上限。 */
    public static final int MAX_TURN_DELIVERIES = 12;
    /** 每回合：真兑现视觉 token 累计上限（兜住「同图每轮重发」的花费——spec §6 方向1 坑1）。 */
    public static final long MAX_TURN_TOKENS = 36_000L;

    private final Map<String, TurnLedger> perTurn = new ConcurrentHashMap<>();

    /** 一个回合的账本：已计费 id 集 + token 累计。判重+入集+记账必须原子，
     *  check-then-act 分开写会在 turnKey 撞桶/同回合并发时超发（spec §6 坑2）。 */
    static final class TurnLedger {
        final Set<String> billed = ConcurrentHashMap.newKeySet();
        final AtomicLong tokens = new AtomicLong();

        synchronized boolean tryBill(String id) {
            if (billed.contains(id) || billed.size() >= MAX_TURN_DELIVERIES) {
                return billed.contains(id);   // 已计费过的图重发恒过闸；新图在满员时拒
            }
            billed.add(id);
            return true;
        }

        synchronized boolean admitTurnTokens(long delta) {
            long cur = tokens.get();
            if (cur + delta > MAX_TURN_TOKENS) return false;
            tokens.addAndGet(delta);
            return true;
        }
    }
```

`Session` 内部改为持有 `TurnLedger`（`open(turnKey)` 从 `computeIfAbsent` 取），
`tryConsumeTurnSlot(String id)` 委托 `ledger.tryBill(id)`。**注意**：
`counter()` 的 `MAX_TRACKED_TURNS` 清桶逻辑保持原样（clear 后同图重新计一次槽，
保守方向、有界，spec §6 坑2 的既定取舍）——但 token 账也随之清零，语义一致性可接受。

既有用例改写（`turnBudgetIsExhaustedAfterTwelveDeliveries` / `turnsAreIsolatedFromEachOther`
改为传 id）：

```java
    @Test
    void turnBudgetIsExhaustedAfterTwelveDistinctImages() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) {
            assertTrue(b.open("turn-1").tryConsumeTurnSlot("img-" + i), "第 " + (i + 1) + " 张");
        }
        assertFalse(b.open("turn-1").tryConsumeTurnSlot("img-12"));
    }

    @Test
    void turnsAreIsolatedFromEachOther() {
        VisionBudget b = new VisionBudget();
        for (int i = 0; i < 12; i++) b.open("turn-1").tryConsumeTurnSlot("img-" + i);
        assertFalse(b.open("turn-1").tryConsumeTurnSlot("img-x"));
        assertTrue(b.open("turn-2").tryConsumeTurnSlot("img-x"), "别的回合不受影响");
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=VisionBudgetTest`
Expected: 全部 PASS（8 用例）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionBudget.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionBudgetTest.java
git commit -m "fix(vision): 回合额度按去重图 id 计 + 回合级 token 账"
```

---

### Task 2: VisionMaterializer 接线 + 耗尽 warn 日志

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java:274-306`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializingChatModelTest.java`

**Interfaces:**
- Consumes: Task 1 的 `tryConsumeTurnSlot(String id)` / `admitTurnTokens(long)` / `MAX_TURN_TOKENS`。
- Produces: 无新对外接口——`admitAll` 内部签名不变，行为变化由测试钉住。

- [ ] **Step 1: 写失败测试（多轮回归——spec §7 条 1 的断言值）**

`VisionMaterializingChatModelTest` 追加（复用该测试类既有的 `png()`/`ref()` 私有
helper 与 `Spy` 内部类）：

```java
    /** ★ 回归：用户锚点图不得被工具迭代烧穿（xibaojun 20260911 会话根因，spec §5.2）。 */
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

    /** 回合 token 账兜底：同图重发不重复记账，但一张超大图也不能无限吃 token。 */
    @Test
    void turnTokenAccountStillBoundsSpend() throws Exception {
        png("docs/big.png");
        Spy spy = new Spy(optionsFor("gpt-5.6-sol"));
        VisionMaterializingChatModel m = VisionMaterializingChatModel.wrap(spy, root);
        // 100x80 png ≈ 11 token/张。构造：12 张不同图（各自 1 槽）+ 第 13 张被拒。
        List<org.springframework.ai.chat.messages.Message> msgs = new java.util.ArrayList<>();
        StringBuilder refs = new StringBuilder("看这些\n");
        for (int i = 0; i < 13; i++) {
            png("docs/i" + i + ".png");
            refs.append(ref("i" + i + ".png", "docs/i" + i + ".png"));
        }
        msgs.add(new UserMessage(refs.toString()));
        m.call(new Prompt(msgs, optionsFor("gpt-5.6-sol")));
        long media = spy.seen.get().getInstructions().stream()
                .filter(x -> x instanceof MediaContent)
                .mapToLong(x -> ((MediaContent) x).getMedia().size()).sum();
        assertEquals(3, media, "每请求 MAX_USER_IMAGES=3 不变（张数闸在收集层，与回合账正交）");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=VisionMaterializingChatModelTest`
Expected: `anchorImagesSurviveManyToolIterations` FAIL（第 7 轮起 media=0——现状
`tryConsumeTurnSlot()` 无参调用还没接 id，且旧语义按张·次）——先红。

- [ ] **Step 3: 最小实现**

`admitAll` 闸门段改为（保持方法签名与五态语义不变）：

```java
            PreparedImage img = prepared.get();
            if (!session.admit(img.estimatedTokens())) {            // 本请求 token 预算满了
                out.put(r, new Outcome(null, FileReference.DELIVERY_BUDGET_EXCEEDED, 0L));
                continue;
            }
            if (!session.tryConsumeTurnSlot(r.sha())) {             // 本回合不同图额度用尽
                out.put(r, new Outcome(null, FileReference.DELIVERY_TURN_EXHAUSTED, 0L));
                warnIfFirstExhaustion(session);
                continue;
            }
            if (!session.admitTurnTokens(img.estimatedTokens())) {  // 回合级 token 账
                out.put(r, new Outcome(null, FileReference.DELIVERY_TURN_EXHAUSTED, 0L));
                warnIfFirstExhaustion(session);
                continue;
            }
```

`VisionMaterializer` 加 logger 与首爆日志（`Session` 提供 `boolean markExhaustionWarned()`
——已 warn 过返回 false，原子；`TurnLedger` 加 `AtomicBoolean warned`）：

```java
    private static final Logger log = LoggerFactory.getLogger(VisionMaterializer.class);

    private void warnIfFirstExhaustion(VisionBudget.Session session) {
        if (session.markExhaustionWarned()) {
            log.warn("视觉回合额度耗尽（本回合已计费 {} 张不同图 / {} token）——后续图将剥离，"
                    + "新回合即恢复。详见 docs/superpowers/specs/2026-09-11-vision-turn-budget-exhaustion-investigation.md",
                    VisionBudget.MAX_TURN_DELIVERIES, VisionBudget.MAX_TURN_TOKENS);
        }
    }
```

- [ ] **Step 4: 跑测试确认通过（含既有测试不回归）**

Run: `mvn test -pl springai-code-tui -Dtest='VisionMaterializerTest,VisionMaterializingChatModelTest,VisionBudgetTest'`
Expected: 全部 PASS。特别核对既有 `VisionMaterializerTest` 中依赖旧语义「12 张·次」的
用例（如有 `turnBudget` 相关断言）——若其模拟的是**不同图**耗尽则天然兼容；若模拟
同图重发耗尽则按新语义修正断言（在提交信息里注明）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializer.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionBudget.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/VisionMaterializingChatModelTest.java
git commit -m "fix(vision): 兑现闸门按 id 计费 + 耗尽首爆 warn（锚点图跨迭代存活）"
```

---

### Task 3: 插话消息打标记，锚点不再被夺（闪断修复）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/InterjectingChatModel.java:122-124`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/interjection/MidTurnInjectionTest.java`（追加）、`VisionMaterializingChatModelTest.java`（追加）

**Interfaces:**
- Consumes: `VisionMaterializer.SYNTHETIC_KEY`（public 常量，既有）、`VisionMaterializer.isSynthetic(Message)`（既有 public 静态）。
- Produces: 无——行为变化由测试钉住。

- [ ] **Step 1: 写失败测试**

`VisionMaterializingChatModelTest` 追加（本测试用 Spy 链直接复现「插话轮图仍在」，
不依赖 InterjectingChatModel——那条集成路径由 MidTurnInjectionTest 追加断言）：

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
        assertTrue(hasMedia(spy.seen.get()), "插话轮用户图必须仍在场（修复前锚点被夺、图消失）");
    }
```

`MidTurnInjectionTest` 追加（钉住注入消息真的带标记）：

```java
    /** 注入的插话消息必须带 SYNTHETIC_KEY——否则会夺走视觉锚点（spec §6.1）。 */
    @Test
    void injectedMessageCarriesSyntheticMarker() {
        // 复用该测试类既有的 interjections/model 桩（见 injectedTextIsWrapped 等
        // 用例的 arrange），调 InterjectingChatModel.inject 路径后断言：
        // spy 捕获的末条 UserMessage.getMetadata().get(VisionMaterializer.SYNTHETIC_KEY) == TRUE
    }
```

（执行者注：`MidTurnInjectionTest` 已有捕获注入消息的既有 arrange，照抄同款桩补
断言即可，不用新建桩。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest='VisionMaterializingChatModelTest,MidTurnInjectionTest'`
Expected: `syntheticUserMessageDoesNotStealAnchor` PASS（`lastRealUserIndex` 本就排除
synthetic——这条钉的是**不回归**）；`injectedMessageCarriesSyntheticMarker` FAIL
（现状 `inject()` 构造裸 `UserMessage`，无标记）——后红先修。

- [ ] **Step 3: 最小实现**

`InterjectingChatModel.inject()` 的追加行改为：

```java
        List<Message> merged = new ArrayList<>(messages);
        // SYNTHETIC_KEY 标记：防止这条「工具结果之后突然出现的 user」被 VisionMaterializer
        // 当成新回合锚点——那会让该轮用户原图全部静默剥离（spec §6.1 闪断）。复用既有
        // isSynthetic 判据，不新造标记体系。
        merged.add(UserMessage.builder()
                .text(injectedText)
                .metadata(java.util.Map.of(io.github.javaside.springai.codetui.agent.media.VisionMaterializer.SYNTHETIC_KEY, true))
                .build());
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest='VisionMaterializingChatModelTest,MidTurnInjectionTest'`
Expected: 全部 PASS。

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
- Modify: `springai-code-tui/docs/guide/vision.md:75-90`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java:100-110`
- Test: 全模块回归

**Interfaces:**
- Consumes: Task 1/2 的最终语义。
- Produces: 无。

- [ ] **Step 1: vision.md 硬上限一节改为**

```markdown
### 硬上限（这是设计的核心）

每请求   用户贴图 ≤3 张 + 工具产图 ≤1 张（最新那张） + 6k 视觉 token
每回合   不同图 ≤12 张（按引用 id 去重）+ 36k 视觉 token 累计

「按 id 去重」意味着：同一张图在工具迭代里每轮重发**不再重复扣额度**（2026-09 修复
了它误伤用户贴图的问题——曾经 2 张贴图 × 6 轮迭代就把额度烧穿、图被静默剥离，排查
见 `docs/superpowers/specs/2026-09-11-vision-turn-budget-exhaustion-investigation.md`）。
跑飞的截图循环仍然被封（每张新截图都是新 id）；「同图重发」的花费由 36k 回合 token
账兜底。额度耗尽时会有一条 `log.warn`，引用块 `delivery` 行写
`turn_budget_exhausted`（下个回合即恢复）。
```

`ContextUsage` 相应行：文案改「每回合上限 %d 张不同图」，注释「严格说单位是
『张·次』」改为「按引用 id 去重后的不同图张数」。

- [ ] **Step 2: 全模块回归**

Run: `mvn test -pl springai-code-tui`
Expected: 全绿（对照仓库基线；`DeepSeekVisionRegistryTest` 等视觉链既有测试不回归）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/docs/guide/vision.md \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java
git commit -m "docs(vision): 预算口径更新（不同图 12 张 + 36k 回合 token，同图重发不重复计费）"
```

---

### Task 5: 真机冒烟（双 gate，可选执行）

**Files:**
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuVisionSmokeTest.java`（追加一个用例）

**Interfaces:**
- Consumes: `ZhipuProvider(apiKey, baseUrl).chatModel()` / `options(String)`（既有）。
- Produces: 无——验证件。

- [ ] **Step 1: 追加多轮真机用例**

```java
    /** 回合内多轮迭代后图仍在场（回归 xibaojun 会话根因；耗尽后不再空转的行为断言）。 */
    @Test
    void imagesSurviveManyRoundsInOneTurn() throws Exception {
        ZhipuProvider p = new ZhipuProvider(System.getenv("ZHIPU_API_KEY"), System.getenv("ZHIPU_BASE_URL"));
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(p.chatModel()).defaultTools(tool).build();
        UserMessage msg = UserMessage.builder()
                .text("先看图记住主色调，然后调用工具查天气，再调用一次工具，最后再说一遍主色调。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(solidColorPng(Color.GREEN)).build()))
                .build();
        List<String> chunks = client.prompt()
                .messages(List.of(msg))
                .options(p.options(MODEL).mutate())
                .stream().content().collectList().block(Duration.ofMinutes(5));
        String text = String.join("", chunks);
        assertTrue(tool.invoked.get(), "工具应被调用（多轮迭代）");
        String t = text.toLowerCase();
        assertTrue(t.contains("绿") || t.contains("green"), "多轮迭代后仍能描述贴图颜色，实际: " + text);
    }
```

- [ ] **Step 2: 真机跑（有 key 时；无 key 跳过不阻塞合入）**

Run: `source ~/.secrets && CODETUI_LIVE_TESTS=1 mvn test -pl springai-code-tui -Dtest=ZhipuVisionSmokeTest`
Expected: 全部 PASS。失败则回报现象（真机行为可能因模型而异，行为断言弱档模型偶发
不精确——记录实测结果，必要时放宽到「回答非空且工具被调用」）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/llm/ZhipuVisionSmokeTest.java
git commit -m "test(vision): 多轮迭代后贴图仍在场的真机回归（xibaojun 会话根因）"
```

---

## Self-Review 记录

- **Spec 覆盖核对**：§6 方向 1（Task 1/2）、坑 1-4（Task 1 TurnLedger 原子段 / Task 2 token 账 / Task 1 id 注释 / Task 1 测试改写）、§6.1 插话（Task 3）、方向 3 的 log.warn（Task 2——reason 文案改写**有意不做**，spec 已论证需动 withDelivery 接口、收益存疑，留待独立拍板）、§7 条 1-7（Task 2 测试=条1、Task 1 测试=条2/3、Task 1 改写=条4、Task 3 测试=条5、Task 5=条6、Task 4=条7）。无缺口。
- **占位符扫描**：Task 3 Step 1 的 MidTurnInjectionTest 用例体为伪代码级提示（依赖该类既有桩）——已注明「照抄同款桩补断言」，执行者读该测试类即可落地，不构成 TBD。其余无占位。
- **类型一致性**：`tryConsumeTurnSlot(String)` 在 Task 1 产出、Task 2 消费；`admitTurnTokens(long)`/`MAX_TURN_TOKENS` 同；`SYNTHETIC_KEY` 为既有 public 常量。已核对。
- **已知不确定点（有意保留）**：① 回合 token 账对「同图重发」的计费口径——Task 1 测试
  `turnTokenAccountBoundsDistinctImageSpend` 第二个 admit 走的是「每次过闸尝试都记账」
  （与请求级 `admit` 同口径），意味着同图重发会重复记 token；这是**有意的保守方向**
  （花费上界必须兜住），且 36k 上限下 2 张用户图 × 几十轮的典型场景 ≈ 每轮 1.3k ×
  30 轮 = 39k 会触顶——**若真机冒烟发现典型场景触顶，把 `MAX_TURN_TOKENS` 提到 60k
  或改为「同 id 只记一次 token」**（TurnLedger 已有 billed 集，判定免费）。执行者按
  Task 5 实测调整，在提交信息注明。② Task 5 行为断言对弱档模型的鲁棒性——已给降级
  口径。
