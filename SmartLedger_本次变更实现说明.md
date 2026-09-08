# SmartLedger 本次二开变更实现说明（基于 base 版本）

> 本文记录本次二开相对 base（v1.0.28）的**全部功能变更与实现方案**，按模块组织。
> 核心边界：**不侵入已稳定的自动记账主链路**，AI 只在两个用户主动入口出现，
> 且 AI 不入库、不改预算、不上传敏感数据。
>
> 工程验证基线：`./gradlew testDebugUnitTest` 共 **325 个纯 JVM 单测全绿**（12 个测试类），
> `assembleRelease`（R8 minify）通过，Room 迁移经 `tools/verify_migration.py` 逐列校验。

---

## 0. 变更总览

本次在 base 上新增三类能力，并为其做了必要的工程基线改造：

| # | 能力 | 触发方式 | 是否联网 |
|---|---|---|---|
| A | AI 财务顾问统一配置 | 设置页手动配置 | 仅测试连接 |
| B | AI 消费体检（周报/月报） | 统计页点击「生成报告」 | 是（用户主动） |
| C | 自然语言 / 语音快捷记账 | 记账页点击「AI 解析」 | 是（用户主动） |
| D | 动态预算与超支预测 | 首页预算卡（纯本地） | 否，可离线 |

配套的工程改造：Room 1→2 迁移、API Key 加密存储、隐私政策更新、备份排除、
测试脚手架、UI 设计系统重构与底部导航调整。

**零改动**：`service/` 目录下所有自动记账链路文件（通知监听、短信兜底、
保活重绑、确认弹窗、悬浮窗），唯一例外是记账保存成功后调用现有
`SmartCategorizer.saveMerchantCategory` 这一 public 方法。

---

## 1. 工程基线改造（发版前置）

### 1.1 Room 迁移 version 1 → 2

`data/db/AppDatabase.kt`：
- `entities` 增加 `AiReportEntity`；`version = 2`；`exportSchema = true`（导出 `app/schemas/…/2.json` 供逐列校验）。
- 新增 `abstract fun aiReportDao(): AiReportDao`。
- 注册 `MIGRATION_1_2`，**不加** `fallbackToDestructiveMigration`（防止清空用户账单）。

`MIGRATION_1_2` 只做加法，绝不 DROP / 重建表：
1. `CREATE TABLE ai_reports` + `(periodStart, periodEnd)` 复合索引；
2. `ALTER TABLE budgets ADD COLUMN source TEXT`（**可空、无 DEFAULT**）；
3. 先按 `yearMonth` 去重（保留 `MAX(id)`），再建 `yearMonth` 唯一索引。

**关键决策（避坑）**：
- `budgets.source` 必须**可空且无 defaultValue**。若用 `NOT NULL DEFAULT 'MANUAL'`，
  Room 导出的期望 schema 无 defaultValue，两边不一致会在打开库时抛
  `IllegalStateException: Migration didn't properly handle budgets`，导致**所有升级用户一启动就崩**。
- 不能用「建新表→拷数据→DROP→RENAME」的常规改列法：`category_budgets`
  对 `budgets(id)` 有 `ON DELETE CASCADE` 外键，DROP 父表会**连带删光分类预算**。
- 建唯一索引前必须先去重，否则库里已有重复月份的用户升级即迁移失败。

`Budget` 实体：新增 `val source: String? = BudgetSource.MANUAL.name`，
并提供扩展属性 `Budget.budgetSource: BudgetSource`（NULL / 脏值一律回落 `MANUAL`）。

### 1.2 依赖与构建（`build.gradle.kts`）

- **零新增运行时依赖**：AI 的 HTTP 用 `HttpURLConnection`，JSON 用平台自带 `org.json`，
  与既有 `UpdateChecker` 范式一致，无 OkHttp / kotlinx.serialization / Retrofit / DataStore / security-crypto / Markdown 库。
- 新增测试依赖：`junit:junit:4.13.2` + `org.json:json:20240303`
  （android.jar 里的 org.json 是运行期抛 `Stub!` 的桩，单测需要真实现）。
- `testOptions.unitTests.isReturnDefaultValues = true`（纯逻辑单测不引 Robolectric）。
- `ksp { arg("room.schemaLocation", ...) }` 导出 schema。
- AI 真链路集成测试（`DeepSeekIntegrationTest`）默认排除，需 `-PaiIntegration=true` 才跑，
  Key 从 `local.properties` 读（已 gitignore），缺失时 `assumeTrue` 自动跳过。

`proguard-rules.pro` 追加：keep `data.analytics.model.**` 与 `CategoryTotal`（Room 投影 POJO）；
keep 所有会被持久化的枚举成员名（preset / periodType / source 会写进 prefs 与 DB，混淆即读不出）。

### 1.3 隐私与备份合规

- `PrivacyPolicyScreen` 新增「四、AI 财务顾问（可选功能）」章节：默认关闭、直连无中转、
  发送/不发送清单、语音录音不经手、可随时清除。
- 首次保存 AI 配置弹一次性告知（`ai_privacy_ack`）。
- `AndroidManifest.xml` 增加 `dataExtractionRules` / `fullBackupContent`，
  `res/xml/backup_rules.xml`、`data_extraction_rules.xml` 将 `smart_ledger_ai.xml`（含加密 Key）
  从云备份与换机迁移中排除——AndroidKeyStore 密钥不随备份走，密文迁过去也解不开。
- 新增 `<queries>` 声明语音识别 Intent（Android 11+ 包可见性），**不申请 RECORD_AUDIO**。

---

## 2. 数据层变更

### 2.1 新增实体 / DAO

- `AiReportEntity`（表 `ai_reports`）：缓存 AI 报告。字段含 `periodType / periodStart / periodEnd /
  periodLabel / dataHash / model / markdown / suggestedBudget / fromReasoningFallback / truncated / createdAt`。
- `AiReportDao`：`insert`、`findReusable`（周期+指纹+模型+TTL）、`findLatest`、`findById`、
  `pruneOld(keep)`（保留最近 20 条）、`count`、`clearAll`。
- `BudgetSource` 枚举：`MANUAL` / `AI_SUGGESTED`，含 `fromName` 安全解析。

### 2.2 TransactionDao 新增（AI 聚合用，字段口径修正）

字段口径务必注意（原方案文档写错处）：时间列是 `transactionTime`（非 timestamp），
分类列是 `categoryId`（非 category、可空），type 存**小写** `'expense'`/`'income'`。

- `getExpenseSumOnce` / `getIncomeSumOnce`：一次性收支合计（供聚合链路）。
- `getExpenseCountInRange`：**本次次要项修复新增**，周期内支出笔数，替代「拉全量投影再 count」。
- `getPointsInRange`：轻量投影（只取 amount/type/categoryId/merchant/transactionTime 五列，
  **刻意不取 note/notificationKey**，从取数阶段就杜绝敏感字段进内存）。
- `getLatestCreatedAt`：`MAX(createdAt)`，用于判断报告缓存是否已陈旧。

### 2.3 BudgetDao 重构

- **删除** `insertBudget`(REPLACE) 与 `updateBudget`：`yearMonth` 唯一索引下，REPLACE 命中冲突会删旧行
  并触发 `category_budgets` 的 CASCADE，静默删光分类预算。
- 唯一写入口 `upsertExpenseLimit`：SQLite `ON CONFLICT(yearMonth) DO UPDATE`，原子写，
  **刻意不动 `totalIncomeTarget`**（改支出上限不该清空收入目标）。
- 新增 `getBudgetsByMonths`、`observeAllBudgets`。

---

## 3. AI 顾问统一配置（能力 A）

### 3.1 数据模型

- `AiProviderPreset`：DeepSeek（默认，含 `deepseek-chat` 及 v4 推理模型选项）/ Kimi / 通义千问 /
  OpenAI / 自定义。全部走 OpenAI Compatible 协议。`fromName` 未知值回落 CUSTOM。
- `AiConfig`：`preset / baseUrl / apiKey / model`，含 `isConfigured`、`isReasoningModel`、
  `displayLabel`、`redacted()`、`maskSecret()`（前 3 后 4 掩码）。

### 3.2 API Key 加密存储 `SecureSecretStore`

- AndroidKeyStore AES/GCM/NoPadding，256 位，**不设 `setUserAuthenticationRequired`**（否则锁屏后读不到）。
- 存储格式 `Base64(iv):Base64(cipher)` 写入 `smart_ledger_ai` prefs。
- **解密失败一律优雅降级**（换机/恢复备份/密钥吊销必然发生）：返回 null + 清脏密文，
  UI 提示重填 Key，保留 baseUrl/model，绝不崩溃、不打印 Key。

### 3.3 配置仓库与线程纪律

- `AiSettingsRepository`：持 `StateFlow<AiConfig>`，三个入口（设置/统计/记账）共享同一状态。
- `load / save / clear` 全部 `suspend + withContext(Dispatchers.IO)`——Keystore 加解密在 StrongBox
  设备上可达几十毫秒，禁止在主线程同步等待（本次修复已消除 ViewModel 侧的双读与主线程解密）。
- 非敏感项（preset/baseUrl/model/开关）走 `AiPreferences`（SharedPreferences 范式，不引 DataStore）。

### 3.4 AiClient 与 HttpEngine

- `HttpEngine` 接口（`post` / `postStreaming`）+ `UrlConnectionHttpEngine` 生产实现；
  单测注入 `FakeHttpEngine`，不引 MockWebServer。
- `AiClientImpl`：`testConnection` / `chat`（非流式，记账解析）/ `streamChat`（SSE，消费体检）。
  - 请求体一律用 `org.json` 构造（用户备注/商户名里的引号换行不会破坏 JSON）；
  - 显式 `Accept-Encoding: identity` 禁 gzip，否则透明解压会破坏 SSE 逐行到达；
  - 流式墙钟超时用自定义异常与「用户取消」区分，`CancellationException` 原样上抛；
  - 协程取消时 `disconnect()` 底层连接 + 每行 `ensureActive()`；
  - 推理模型（content 为空、正文在 reasoning_content）做兜底，UI 标注建议改用 chat 模型。
- 错误分类 `AiError`：每个子类自带中文文案；`Unknown` 的 detail 先经 `SecretRedactor.redact` 脱敏；
  `IOException`（Connection reset 等）归 `Network`，不把英文技术细节怼给用户。
- `BaseUrlNormalizer`：补协议、去尾斜杠、剥用户误贴的 `/chat/completions`，
  自动为缺版本段的 base 补 `/v1`；`isCleartext` 拦明文（不放开全局 usesCleartextTraffic）。

### 3.5 设置页 `AiSettingsScreen`

- 掩码输入框：未改动时 `form.apiKey` 存掩码串，真明文只在 `saved.apiKey`；
  `keyDirty` 标记区分「改过/没改过」，避免把掩码串当新 Key 存进去毁掉配置；
  点眼睛可见时展示内存里的真明文。
- 测试连接用当前输入框值（不必先保存）；切预设自动填、用户手改后再切预设二次确认；
  「清除配置与本地 AI 报告」二次确认后同时清 Key 与 `ai_reports`。

---

## 4. 本地聚合与脱敏（能力 B 的数据基础）

链路四层，②③④ 均为不接 Context 的纯函数、全部有单测：

```
① 取数(Room)  TransactionDao        SUM/COUNT + 轻量投影
② 聚合(纯Kotlin) SummaryAggregator   本地时区分桶 → FinancialSummary
③ 脱敏(纯Kotlin) MerchantAnonymizer  真实商户名 → "餐饮商户 #1"
④ 成文(纯Kotlin) SummaryFormatter    → Prompt 文本 / canonicalJson
```

- **金额用整数分（Long）累加**：既保证精度，又保证顺序无关性——DAO 投影无 ORDER BY，
  浮点加法不满足结合律会让 `dataHash` 抖动，导致缓存永不命中。
- **时段/星期分桶在 Kotlin 侧用设备本地时区**：SQLite `strftime` 默认按 UTC，
  北京时间 23:30 会被算成「下午」，夜间统计失真。
- **脱敏靠类型设计**：`FinancialSummary` 结构上不存在 note / 真实 merchant / notificationKey，
  `SummaryFormatter` 是唯一 Prompt 文本出口，其输入类型只能是 `FinancialSummary`。
- `MerchantAnonymizer.normalizeKey` 与 `SummaryAggregator` 共用同一归一化规则
  （否则会出现两行同名的「餐饮商户 #1」）；不做部分打码、不持久化映射表。
- `SummaryPeriods`：本月 / 近 3 月区间；环比用「上一周期**同期**」（月末溢出钳制），
  近 3 月的 current `[-2,0]` 与 previous `[-5,-3]` 相邻不重叠。
- `FinancialSummaryBuilder`：只做取数编排；近 3 月预算仅当**每月都设了**才求和（否则 null，
  避免用 3 个月支出除以 1 个月预算算出 300%）。

---

## 5. AI 消费体检（能力 B）

### 5.1 状态机 `AiReportUiState`

`NotConfigured / NoData / Idle(cached,dataChanged,expenseCount) / Preparing / Streaming(text) /
Success(report,truncated,fromReasoning) / Failed(error,partialText)`。

### 5.2 数据流与缓存

1. 本地聚合 → `SummaryFormatter.canonicalJson` → SHA-256 得 `dataHash`；
2. 非「重新生成」时先查 `findReusable`（周期+指纹+模型 一致且未过 TTL 6 小时）命中直接复用，省额度；
3. 未命中走 `streamChat` 流式，ViewModel 用 `StringBuilder` 累积 + 80ms 节流刷 UI；
4. 完成/中断都落库（`pruneOld(20)`），页面重建后从 `findLatest` 恢复。

### 5.3 关键正确性

- **只有点击才发请求**：进页面/切 Tab/新增账单/`onResume` 均 0 请求。
- `canonicalJson` 字段顺序硬编码、金额统一 `%.2f`+Locale.US、含 daysElapsed、不含 now/createdAt。
- 「查看报告」走独立 `viewCachedReport`（绝不走 `generateReport`，否则 TTL 过期会静默重烧额度）。
- 「停止生成」用生成时缓存的 `activeSummary/activeHash` 落库（不重新聚合，否则期间新账会污染 hash）。
- `SuggestedBudgetExtractor` 从正文提取「建议可用总额度」，**永不自动写预算**，只作采纳弹窗预填值。
- `MarkdownText`：自研极简渲染（只支持 `##`/`-`/`**`），容忍流式未闭合 `**`，表格/代码块降级为纯文本不崩。

---

## 6. RecordScreen 状态提升 + 日期（能力 C 的前置重构）

- `RecordUiState`：把原来 6 个 Composable 内部 `remember` 字段提升到 ViewModel，
  新增一等的 `transactionTime` 字段（base 无日期能力，界面日期是死值、保存硬编码 now）。
- `AmountKeypad`：把原 `handleKeyPress` 逻辑原样搬入并锁单测（退格/小数点/两位小数/前导 0），
  新增整数位上限 `MAX_INT_DIGITS=9`（base 可无限按数字键，超 Double 精度）。
- 日期选择：三个快捷项（今天/昨天/前天）+ 系统 `DatePickerDialog`（`maxDate=今天`，不许未来）；
  改日期**保留当前时分**（避免落进夜间桶），不允许未来日期时给 Toast。
- `save`：新增金额非法提示（base 是静默失败）；`source` 仍为 `"manual"`（AI 只是帮填表单）。

---

## 7. 自然语言 / 语音快捷记账（能力 C）

- 入口在记账页顶部，AI 未配置时整块隐藏（`NlParseState.Hidden`）。
- 语音走 `ACTION_RECOGNIZE_SPEECH` 系统面板（`SpeechInput`），**不需麦克风权限**，
  不可用设备自动隐藏图标不报错；语音与文字走**完全相同**的解析链路。
- `chat()` → `JsonExtractor.extractObject`（剥围栏 + 括号配对，容忍前后多余文字）
  → `AiTransactionParser.parse` → `TransactionDraft` → `applyDraft` 回填。
- `AiTransactionParser`（纯函数，逐字段校验）：
  - `type` 大小写不敏感归小写（兜住 AI 返回 `EXPENSE`）；
  - `amount` 接 Number/String/带货币符，先舍入两位再判正负（0.001 不漏为 0），NaN/Inf/超限/负数拒；
  - `category` 按 `(name, type)` 精确匹配本地分类（分类名跨 type 会重名，只按 name 会记错方向）；
  - `channel` 必须在 `PaymentMethods.PRESETS`；
  - `date` 严格 `yyyy-MM-dd`（isLenient=false）、未来钳到今天、早于 2000 视为错误；
  - `time` 缺失时：今天用当前时分、过去日期用正午 12:00（不能用 00:00 落进夜间桶）。
- **回填只覆盖非 null 字段**，解析失败零破坏用户已填内容；`applyDraft` 一次性 copy（先 type 后 category）。
- **AI 无入库路径**：`data/ai` 与 `applyDraft` 不 import TransactionRepository/DAO；
  保存成功且同时有 merchant+categoryId 时才回写 `SmartCategorizer`（AI 猜错不污染自动记账规则）。

---

## 8. 动态预算与超支预测（能力 D）

- `BudgetPredictor`（纯 object、可离线、全单测）：`predict(budget, currentExpense, daysElapsed, daysTotal)`
  → `Result(status, remainingBudget, remainingDays, dailyAvailable, predictedMonthExpense, projectedBudgetDay, ...)`。
  - 入参全 `coerceIn` 防脏值/除零；`NO_BUDGET`（budget≤0）/`SAFE`/`WARNING`/`DANGER` 四态；
  - 月初护栏：`daysElapsed < 5` 时 `showProjection=false`（避免 1 号房租算出「月末 9 万」）。
- `BudgetCard`（首页）：四形态（本月已设+过护栏 / 本月已设+月初 / 本月未设 / 历史月）；
  历史月未设预算整卡不渲染；配色复用 SAFE=income、WARNING=warning、DANGER=expense。
- 预算写入统一走 `BudgetRepository.setExpenseLimit`（UPSERT）；采纳 AI 建议写**下月**，
  必须二次确认且可改金额，改过金额记 MANUAL、原样采纳记 AI_SUGGESTED。
- `BudgetViewModel`：月份不再构造期写死（跟 `dateTick`，回前台刷新），修掉了 base 跨月不重启统计上月的问题；
  `saveBudget` 保留但 `@Deprecated`，行为改为只更新支出上限、不再清空收入目标。

---

## 9. UI / 前端重构（本次改动量最大的部分之一）

> 这是本次二开除 AI 之外投入最大的一块：**新建了一整套设计系统 + 24 个可复用组件 + 多页重写**。
> 修改的 UI 文件净增约 **3000 行**，另有多个全新文件（`Primitives.kt` 组件库 1047 行、
> `Palette.kt`、`AppTokens.kt`、`TransactionRow.kt`、`TransactionEditDialogs.kt`、流水页等）。
> 视觉基调从原来的「普通 Material 默认风」整体换成 **「学术纸张」视觉语言**
> （暖纸底、细边框、大留白、轻阴影、克制语义色，参考顶会海报/研究系统），
> 因此即使部分页面自身 diff 不大，也会因主题层整体切换而「变好看」。

### 9.1 设计语言总纲

- 固定品牌色，**不启用 Material 3 Dynamic Color**——动态取色会让每台设备颜色不同，
  破坏「数据仪表盘」的一致性与可信度。
- 层次不靠花哨字形与重阴影，而靠 **背景层级 + 1dp 细边框 + 留白 + 字重/字距** 组合。
- 语义色分工严格（避免同屏多个大面积强调色）：Ink=主文字/交互强调、Seal=支出/超支、
  Moss=收入/成功、Amber=待确认/预警、Blue=图表/信息、Violet=AI 专用点色。

### 9.2 色板系统 `ui/theme/Palette.kt`（新增）

- **中性纸墨**：Ink `#171714` / InkSoft / InkFaint；Paper `#F3EFE5`（页面底）/
  PaperStrong `#FFFDF7`（卡片）/ PaperDeep（次级分区）。
- **语义色 + 10% 浅底**：Seal / Moss / Amber / Blue / Violet 各配一个 Tint（用于 Pill、高亮块）。
- **边框统一** Ink 12%（BorderSoft）/ 16%（BorderStrong）/ 8%（InkTint 选中底）。
- **深色模式**：由亮色语义等比提亮、保持同一色相的完整暗色套（DarkPaper / DarkInk / DarkSeal…），
  边框改用暖白低透明度，避免纯黑边在暖底上发脏。
- **图表色板**：亮/暗各一套 8 色低饱和渐变（以 Blue/Moss/Amber 为主轴），覆盖统计页 `take(8)`。

### 9.3 主题机制 `ui/theme/Theme.kt`（重写，+444）

- `ExtendedColors`（`@Immutable`）：亮/暗两套语义令牌，经 `LocalExtendedColors` 下发。
  为平滑迁移**保留了一批兼容别名**（`expense/income/accent/surface/foreground…`），
  既有页面无需改名即可编译，逐步迁移到语义名。
- `SmartLedgerColors`：全局取色入口（`fg/bg/surface/expense/income/warning/info/ai/accent/border/chartColors/nav…`），
  页面统一从这里取色，**禁止页面写死 `Color(0xFF…)`**（否则深色失效）。
- `SmartLedgerTheme`：按 `ThemeManager`（SYSTEM/LIGHT/DARK）解析深浅色，
  固定套用 Light/DarkColorScheme（Material3 映射 primary=Ink、secondary=Blue、tertiary=Violet、error=Seal），
  并同步状态栏颜色与亮/暗图标（仅 Activity 场景，悬浮窗等非 Activity Context 安全跳过）。
- `LocalIsDarkTheme`：显式暴露当前深色态，供图表调整网格/轴线（含用户手动指定 LIGHT/DARK 的情况）。
- **关键取舍**：`accent` 映射 **Ink 而非 Seal**——Seal 已被「支出/超支」占用，
  若再做品牌强调色会出现「主按钮与亏损数字同色」的语义冲突；Violet 严格只出现在 AI 元素上。

### 9.4 排版 `ui/theme/Type.kt`（重写，+263）

- 字体优先系统 Sans（Android 上自动落到 MiSans / Noto Sans CJK），**不打包字体文件**，不增体积。
- `AppType` 语义化样式：`heroAmount`(40–48sp) / `pageTitle`(28–32sp) / `cardNumber` / `cardNumberSmall` /
  `sectionTitle` / `listPrimary` / `listSecondary` / `listAmount` / `body` / `aux` / `eyebrow` / `pill` / `button`。
- 数字样式开启**等宽数字**（`fontFeatureSettings="tnum"`），金额竖向对齐、跳变不抖动；
  大数字收紧字距（负 letterSpacing）显精确，英文 eyebrow 放大字距并大写形成研究标注感。
- **修掉深色 bug**：旧实现给 TextStyle 写死 `color = Foreground`（亮色墨），深色模式下未显式传色的文字
  变成深底深字不可读；现改为继承 MaterialTheme contentColor，随主题正确切换。

### 9.5 布局与动效令牌 `ui/theme/AppTokens.kt`（新增）

- `AppSpacing`（16dp 页边距 / 12dp 卡间距 / 24dp 区块距 / 语义 xs–xxl）、
  `AppRadius`（大卡 24dp、普通卡 18dp、输入框 14dp、pill 全圆角）、
  `AppSize`（按钮 44–48dp、最小触控 48dp、1dp 细边框、条厚 6dp、列表项 56dp）、
  `AppMotion`（150–300ms）。
- `MotionPreference` + `rememberReducedMotion()`：读系统 `ANIMATOR_DURATION_SCALE == 0`
  尊重「减弱动态效果」无障碍开关，命中时非必要动效时长归零。

### 9.6 通用组件库 `ui/components/Primitives.kt`（新增，1047 行）

24 个全 App 复用组件，是「统一好看」的载体：
- 容器：`SectionCard`（大/普通卡、可选边框）、`SubPanel`（次级分区底）、`Hairline`（1dp 分隔）、`AppTopBar`。
- 文本：`Eyebrow`（英文标注）、`SectionHeader`、`AuxText`、`StatusPill`（POSITIVE/WARNING/DANGER/AI/NEUTRAL 语气）。
- 按钮：`PrimaryButton` / `SecondaryButton`（含 AI 语气）/ `QuietButton` / `IconButtonQuiet`。
- 选择：`FilterChipItem`、`SegmentedTabs`（分段切换）。
- 数据可视化：`ThinProgressBar`（进度钳制 0–1 + 减弱动效）、`ThinDonut`（权重归一、total=0 防护）、
  `MetricCell`（指标块）、`AnimatedMoney`（金额插值动画）。
- 输入与空态：`AppTextField`、`EmptyState`。
- 格式化：`formatMoney`（NaN/Inf 防护、整数免小数、千分位）、`formatPercent`。
- 另有 `MarkdownText`（AI 报告渲染，见第 5 节）、`CategoryIcons.categoryIcon()`（分类图标统一映射）、
  `TransactionRow`（账单行，总览/流水共用）、`TransactionEditDialogs`（操作菜单/改分类/编辑/删除弹窗）。

### 9.7 页面级重写

| 页面 | 变化 | 规模 |
|---|---|---|
| `StatisticsScreen` | 大改：页面标题 + 周期切换 + **AI 体检卡** + 细环形图（含图例/结余）+ 分类横向条形排行 | 440 行（diff +553/−…） |
| `RecordScreen` | 大改：顶部自然语言/语音栏、Hero 金额、行内表单卡、日期选择、自绘键盘、分类网格 | 730 行（diff +903/−…） |
| `ProfileScreen`（设置入口页） | 重排为卡片式分组，「我的」并入设置 | 355 行（diff +405/−…） |
| `SettingsScreen` | 新增「AI 财务顾问」分区入口（副标题显示当前状态） | 708 行 |
| `HomeScreen`（总览） | 插入预算卡（`BudgetCard`），其余随主题层整体焕新 | 1006 行（diff +16） |
| `TransactionsScreen`（**全新流水页**） | 按日期分组、商户为主/分类·渠道为次、金额右对齐 Seal/Moss、轻量收支 Chip 筛选 | 372 行 |
| `BudgetScreen` | 适配新 UPSERT 与新组件 | 366 行 |
| `SmartLedgerDialog` | 统一弹窗重构（纸面底 + 1dp 细边 + 24dp 圆角 + 极轻阴影），新增 `eyebrow` 等参数，**向后兼容**（新增参数皆有默认值） | diff +265 |

### 9.8 导航调整 `ui/navigation/Screen.kt`

- 底部导航从旧结构改为 **总览 / 流水 / 统计 / 设置** 四项；
- **移除「记账」Tab**，改由总览与流水页的 FAB 进入（记账是「动作」而非「视图」）；
- 「我的」并入「设置」；**不新增 AI 独立 Tab**（AI 作为增强层挂在统计页顶部与记账页顶部）；
- 设置 Tab 的 route 沿用旧的 `"profile"`，避免 MainActivity 多处 `popUpTo`/深链依赖被破坏。

### 9.9 本次修复的既有 UI 缺陷

- `Type.kt` 深色模式深底深字不可读（见 9.4）。
- `PaymentChannelPicker`：点「自定义」立刻 `onSelected("")` 把渠道写成空串、chip 全失选 → 改为仅切输入模式、有内容才回写。
- `EditTransactionDialog`：新增改日期/时刻能力 + 金额非法提示（原静默失败）；局部变量刻意避开与 `Calendar.time` 同名的遮蔽陷阱。

---

## 10. 测试

12 个纯 JVM 测试类、**325 个用例**：`AiClientImplTest(51)`、`AiTransactionParserTest(48)`、
`SummaryAggregatorTest(34)`、`BudgetPredictorTest(26)`、`SseParserTest(26)`、`SummaryFormatterTest(24)`、
`SummaryPeriodsTest(23)`、`AmountKeypadTest(22)`、`MarkdownTextParserTest(20)`、
`BaseUrlNormalizerTest(18)`、`SuggestedBudgetExtractorTest(17)`、`MerchantAnonymizerTest(16)`。
另有 `DeepSeekIntegrationTest` 真链路（默认排除）。

---

## 11. 本轮次要项修复（本次新增）

在两轮代码审查后，修复了 3 个由本次二开引入或被其放大的次要项，均已编译+全量测试验证：

| # | 问题 | 修复 | 涉及文件 |
|---|---|---|---|
| F1 | `bumpParseCounter` 双计数：一次「解析+保存」把 total/accepted 各 +2，接受率失真 | 拆成 `recordParseAttempt`（每次解析记一次）与 `recordParseAccepted`（AI 回填账单被保存时记一次），两个计数各自独立 | `AiPreferences`、`AiSettingsRepository`、`RecordViewModel` |
| F2 | `loadIdleState` 为数一个支出笔数把整周期投影（近 3 月可达千行）全拉进内存 `count{}` | 新增 DAO 查询 `getExpenseCountInRange`（`COUNT(*) WHERE type='expense' AND amount>0`），口径与聚合层一致 | `TransactionDao`、`TransactionRepository`、`StatisticsViewModel` |
| F3 | `DateUtil` 共享 `SimpleDateFormat`（非线程安全）：本次新增的 IO 聚合与 UI Flow 并发调用放大了错乱风险 | 改用 `ThreadLocal` 每线程各持一份 formatter，加非空访问扩展 `fmt` 消除可空告警；输出格式与口径完全不变 | `DateUtil` |

> 未处理项：`SuggestedBudgetExtractor` 对 AI 违反标准千分位格式（如 `¥12,34`）会截断——
> 属极低频畸形输入，且被「采纳弹窗二次确认可改金额」兜住，判定为可接受，保持不动。

---

## 12. 新增 / 修改文件清单

**新增（主要）**
```
data/db/entity/AiReportEntity.kt, BudgetSource.kt
data/db/dao/AiReportDao.kt
data/ai/  AiClient.kt, AiClientImpl.kt, BaseUrlNormalizer.kt, SseParser.kt,
          JsonExtractor.kt, SuggestedBudgetExtractor.kt, SecretRedactor.kt, AiTransactionParser.kt
data/ai/model/  AiConfig.kt, AiMessage.kt, AiError.kt, TransactionDraft.kt
data/ai/http/   HttpEngine.kt, UrlConnectionHttpEngine.kt
data/security/SecureSecretStore.kt
data/preferences/AiPreferences.kt
data/repository/AiSettingsRepository.kt, AiAdvisorRepository.kt, AiPromptBuilder.kt
data/analytics/ SummaryPeriods.kt, SummaryAggregator.kt, MerchantAnonymizer.kt,
                SummaryFormatter.kt, FinancialSummaryBuilder.kt
data/analytics/model/ TxPoint.kt, FinancialSummary.kt
util/BudgetPredictor.kt
ui/record/ RecordUiState.kt, AmountKeypad.kt, SpeechInput.kt
ui/settings/ AiSettingsScreen.kt, AiSettingsViewModel.kt
ui/statistics/ AiReportCard.kt, AiReportUiState.kt
ui/home/BudgetCard.kt
ui/transactions/ TransactionsScreen.kt, TransactionsViewModel.kt
ui/theme/ Palette.kt, AppTokens.kt
ui/components/ Primitives.kt, MarkdownText.kt, CategoryIcons.kt, TransactionRow.kt, TransactionEditDialogs.kt
res/xml/ backup_rules.xml, data_extraction_rules.xml
app/schemas/…/2.json、tools/verify_migration.py、app/src/test/**（12 个测试类）
```

**修改（主要）**：`build.gradle.kts`、`proguard-rules.pro`、`AndroidManifest.xml`、`MainActivity.kt`、
`SmartLedgerApp.kt`、`AppDatabase.kt`、`TransactionDao/BudgetDao`、`Budget.kt`、
`TransactionRepository/BudgetRepository`、`BudgetViewModel`、`PaymentChannelPicker`、`SmartLedgerDialog`、
`HomeScreen/HomeViewModel`、`Screen.kt`、`ProfileScreen`、`RecordScreen/RecordViewModel`、
`PrivacyPolicyScreen`、`SettingsScreen`、`StatisticsScreen/StatisticsViewModel`、`Theme.kt`、`Type.kt`、
`DateUtil.kt`（本轮线程安全修复）。

**零改动**：`service/` 全部自动记账链路文件。

---

## 13. 发版前必做（代码审查替代不了）

1. **真机覆盖安装**：v1.0.28 → 新版，断言账单/分类/预算零丢失、`budgets` 同月唯一、
   老预算行 source 为 NULL 读作 MANUAL、App 不崩。
2. **release 包（R8 minify）** 冷启动 + AI 全链路真机走查（含深色模式、低端机流式帧率）。
3. **降级验证**：完全不配置 AI → 全 App 正常无入口报错；配了 AI 但飞行模式 →
   自动记账/手工记账/统计/预算/导出全部正常，AI 入口给网络错误提示。
4. **脱敏抽查**：dump 一次真实请求体，人工确认不含通知原文/卡号/手机号/真实商户名/备注原文。
