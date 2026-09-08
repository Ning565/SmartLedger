# SmartLedger 二次开发技术方案（增量版 v2）

> 目标：基于现有 SmartLedger 做小步迭代，不改动已经稳定的通知自动记账主链路。
> 本阶段只新增三类能力：**AI 财务顾问、自然语言/语音快捷记账、动态预算预测**。
> AI 只在用户主动触发时调用：
> 1. 用户点击「AI 消费体检」；
> 2. 用户主动使用自然语言/语音快捷记账。
>
> **通知自动记账、普通手工记账、预算计算本身均不依赖 AI。**

---

# 0. 方案评审结论（v2 新增）

## 0.1 总体结论

**思路可行，可以按这个方向做，但 v1 方案不能直接照着写代码。**

v1 的产品切分、"AI 只做解释不做事实计算"、"AI 只在两个主动入口出现"、"AI 不直接入库"这几条核心边界是正确的，属于这类二开里风险最低的设计，保留。

问题集中在两处：

1. **v1 的伪代码/SQL/字段名与仓库真实代码不一致**，直接照抄有 12 处会编译失败或运行期错位；
2. **v1 缺了几项"不做就上不了线"的前置工程改造**（Room 迁移、RecordScreen 状态提升、隐私政策更新、测试脚手架），这些不是可选项。

因此 v2 在原 6 个任务前面插入 **任务 0（工程基线改造）**，并把 v1 的"任务 4 自然语言记账"拆成 **任务 4（RecordScreen 状态提升，纯重构不含 AI）** 与 **任务 5（AI 解析）** 两步，保证每一步都能独立验证、独立回滚。

## 0.2 v1 与现有代码的冲突清单（必须修正）

| # | v1 的写法 | 仓库真实情况 | 后果 |
|---|---|---|---|
| C1 | SQL `WHERE timestamp BETWEEN` | 字段名是 `transactionTime` | Room 编译期报错 |
| C2 | SQL `GROUP BY category` | 字段名是 `categoryId`（`Long?`，可空，外键 `SET NULL`） | Room 编译期报错 |
| C3 | SQL `type = 'EXPENSE'` | 实际存的是小写 `'expense'` / `'income'` | 查询恒为空，统计全 0 |
| C4 | Prompt 要求 AI 返回 `"type": "EXPENSE"` | 入库必须写 `expense` | 不做映射则脏数据入库 |
| C5 | 示例分类含「居住与通讯」 | 默认分类是「居住」「通讯」两个独立分类；另有医疗/教育/日用/其他 | Prompt 里给错分类清单，AI 返回的分类匹配不上 |
| C6 | 假设分类名全局唯一 | 分类**按 type 分组**，`其他` 在 expense 和 income 下各有一条 | 只按 name 查 categoryId 会命中错误 type 的分类 |
| C7 | 「AI 解析后回填现有 RecordScreen」 | `RecordScreen` 的 6 个字段全是 Composable 内部 `remember`，外部无法写入 | 无法回填，必须先做状态提升 |
| C8 | AI 返回 `date` 字段用于「昨天/前天」 | `RecordScreen` 没有日期选择器（日期是 Composable 里直接 `Calendar.getInstance()` 显示的死值）；`RecordViewModel.saveTransaction()` 没有时间参数，硬编码 `System.currentTimeMillis()` | 「昨天」解析出来也存不进去 |
| C9 | 新增 `AiReportEntity` 建表 | `AppDatabase` 当前 `version = 1`，**没有任何 Migration**，也没有 `fallbackToDestructiveMigration` | 加实体不升版本 → 运行期崩溃；用 destructive → **清空用户全部账单** |
| C10 | 「优先复用 Budget 表」 | `Budget` 只有 `yearMonth / totalIncomeTarget / totalExpenseLimit`，无来源字段；`yearMonth` **没有唯一索引**，`insertBudget` 的 `OnConflictStrategy.REPLACE` 实际按主键 `id` 判冲突，同月可插多行 | 「采纳 AI 建议」路径容易插出重复月份行 |
| C11 | `BudgetViewModel` 复用即可 | `BudgetViewModel` 把 `currentYearMonth` 写死为当月，只能读写当月预算 | 「采纳为**下月**预算」无法实现 |
| C12 | 依赖 OkHttp + kotlinx.serialization + MockWebServer | 仓库**没有** OkHttp、没有 kotlinx.serialization（连 Gradle plugin 都没加）、没有 DataStore、没有 security-crypto、**没有任何 testImplementation，也没有 test/ 源码目录**；现有网络代码（`UpdateChecker`）用的是 `HttpURLConnection` + `org.json` | 按 v1 写要新引 4~5 个依赖 + 从零搭测试工程，还要为 R8（`isMinifyEnabled = true`）补 keep 规则，release 包炸的风险明显上升 |

## 0.3 v1 缺失的关键设计（v2 补齐）

| # | 缺口 | v2 处理 |
|---|---|---|
| M1 | Room 数据迁移策略 | 任务 0.1：`version 1 → 2`，手写 `MIGRATION_1_2`，禁用 destructive |
| M2 | API Key 换机/恢复后无法解密 | `allowBackup="true"` 但 AndroidKeyStore 密钥不参与备份 → 任务 1.4 定义"解密失败即清空 + 提示重填"，并把 AI 配置排除自动备份 |
| M3 | `SpeechRecognizer` 在国产 ROM 大量不可用；Android 11+ 需要 `<queries>` 声明才能探测到识别服务 | 任务 5.2 改为 `ACTION_RECOGNIZE_SPEECH` Intent 优先（**不需要 RECORD_AUDIO 权限**），不可用则降级纯文字 |
| M4 | 时区：`22:00~02:00 夜间`、工作日/周末在 SQLite 里用 `strftime` 默认按 **UTC** 算 | 任务 2.4：需要时区的分桶一律在 Kotlin 侧用 `Calendar` 做，SQLite 只做 SUM/GROUP BY categoryId |
| M5 | Markdown 渲染没有库 | 任务 3.6：自研 ~120 行 `MarkdownText`（AnnotatedString），不引依赖 |
| M6 | 隐私政策仍宣称数据不出本机 | 任务 0.4：更新 `PrivacyPolicyScreen` + 首次启用 AI 的一次性明确告知弹窗 |
| M7 | Streaming 的超时 / 取消 / 切页面丢失 | 任务 3.2 状态机 + 3.8 生命周期，报告落库而非只存内存 |
| M8 | 当月 summary 每来一笔就变，`dataHash` 缓存等于永不命中 | 任务 3.4：`dataHash` + **TTL（默认 6 小时）** 双条件，并定义保留策略（最多 20 条） |
| M9 | AI 返回 JSON 常带 ``` 围栏和多余解释 | 任务 5.4：定义 JSON 提取容错流程 |
| M10 | Gemini 被列入 "OpenAI Compatible" 预设 | Gemini 原生 API 不兼容；任务 1.2 改用其 OpenAI 兼容端点并标注为「实验」 |

---

# 1. 现状基线（Code Facts）

> 以下是本方案所有设计的事实依据，开发时以此为准，不要以 v1 文档的伪代码为准。

## 1.1 数据层

**`transactions` 表**（`data/db/entity/Transaction.kt`）

```kotlin
@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"], childColumns = ["categoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("categoryId"), Index("transactionTime"), Index("notificationKey")]
)
data class Transaction(
    val id: Long = 0,
    val amount: Double,           // 正数；方向靠 type 区分
    val type: String,             // "expense" / "income"  ← 小写
    val categoryId: Long? = null, // 可空
    val merchant: String? = null,
    val paymentMethod: String? = null,
    val note: String? = null,
    val source: String,           // "manual" / "auto" / "sms"
    val notificationKey: String? = null,
    val transactionTime: Long,    // ← 不是 timestamp
    val createdAt: Long = System.currentTimeMillis()
)
```

**`categories` 表**：`id / name / icon / color / type / sortOrder / isDefault`。
默认数据：
- expense：餐饮、交通、购物、娱乐、居住、通讯、医疗、教育、日用、其他
- income：工资、理财、红包、转账、其他

→ **`name` 不唯一，必须 `(name, type)` 联合定位**。

**`budgets` 表**：`id / yearMonth("yyyy-MM") / totalIncomeTarget / totalExpenseLimit / createdAt`。
`yearMonth` 无唯一索引。**支出上限是 `totalExpenseLimit`**，不要用 `totalIncomeTarget`。

**`AppDatabase`**：`version = 1`，`exportSchema = false`，实体 4 个，无 Migration，`onCreate` 回调灌默认分类。

## 1.2 UI / ViewModel 层

- **无 DI 框架**。全部 `AndroidViewModel`，依赖从 `(application as SmartLedgerApp).xxxRepository` 拿。新增仓库照此在 `SmartLedgerApp` 里 `by lazy` 挂上。
- `RecordScreen`：`transactionType / amountText / selectedCategory / merchant / note / paymentMethod` **全部是 Composable 内的 `remember`**；无日期选择；金额靠自绘数字键盘。
- `RecordViewModel.saveTransaction(amount, type, categoryId, merchant, paymentMethod, note, onSuccess)` —— 无时间参数。
- `HomeScreen`（991 行）：无任何预算相关 UI。支持通过 `selectedYearMonth` **回看历史月份**（`previousMonth/nextMonth`，不允许未来月）。
- `StatisticsScreen`：周期 tab 是 **日/周/月/年** 四档，与 v1 假设的「本月/近 3 月」不同。
- `StatisticsViewModel` 用默认 `viewModel()` 作用域；`MainActivity` 的 NavHost 底 tab 切换带 `popUpTo(saveState = true)`，**切走再切回 ViewModel 可能已被回收** → 长任务结果必须落库。
- `BudgetViewModel`：`currentYearMonth` 为构造期固定值，只能读写当月。
- 主题色统一走 `SmartLedgerColors`（`bg/surface/surfaceHover/border/fg/fgSecondary/accent/accentDim/expense/income/chartColors`），新 UI 必须复用，不要引 MaterialTheme 默认色。
- 弹窗统一用 `ui/components/SmartLedgerDialog.kt` 的 `SmartLedgerDialog` / `SmartLedgerInputDialog`。

## 1.3 依赖与构建

- `minSdk 26`，`targetSdk/compileSdk 35`，Kotlin 2.0.21，Compose BOM 2024.12.01，Room 2.6.1（KSP）。
- **release 开了 `isMinifyEnabled = true` + `isShrinkResources = true`**，`proguard-rules.pro` 只 keep 了 entity 和几个 service。
- 依赖里**没有**：任何 HTTP 客户端库、任何 JSON 序列化库、DataStore、security-crypto、Markdown 库、**任何测试库**。
- 现有网络实现范式：`HttpURLConnection` + `org.json.JSONObject`（见 `util/UpdateChecker.kt`，含重定向跟随、Range 续传、超时设置，可直接借鉴风格）。
- Manifest 已有 `INTERNET`；**没有** `RECORD_AUDIO`，**没有** `<queries>`。
- `android:allowBackup="true"`，未配置 `dataExtractionRules` / `fullBackupContent`。
- 备份走 **CSV**（`BackupStorage` + `CsvExporter`/`CsvImporter`），不是复制 db 文件 → DB 升版本不影响备份恢复链路。

## 1.4 由基线得出的硬约束

1. **不新增网络/序列化依赖**：`AiClient` 用 `HttpURLConnection` + `org.json` 实现（含 SSE）。理由：与现有代码风格一致、零 R8 风险、APK 不增重、`org.json` 在 Android 平台自带。
2. **不新增 DI**：所有新组件挂到 `SmartLedgerApp`。
3. **不新增 DataStore**：AI 的非敏感配置沿用现有 `SharedPreferences("smart_ledger")` 范式即可（项目里 nickname / theme_mode / initial_balance 都是这么存的），只有 API Key 单独走加密存储。
4. **测试只做纯 JVM 单测**：新增唯一测试依赖 `junit:junit:4.13.2`。所有需要断言的逻辑都设计成**不依赖 Android 框架的纯函数**。
5. **任何 DB 结构变更必须写 Migration**，禁止 destructive。

---

# 2. 总体技术结构

```text
Room Transaction 数据
        │
        ├──────────────→ 原有自动记账 / 手工记账（本次不碰）
        │
        ├──→ FinancialSummaryBuilder
        │       ├── DAO 只做 SUM / GROUP BY categoryId
        │       ├── Kotlin 侧按本地时区分桶（小时 / 工作日周末 / 夜间）
        │       └── MerchantAnonymizer 脱敏
        │               └── 用户点击「AI 消费体检」
        │                       └── AiAdvisorRepository 组 Prompt
        │                               └── AiClient.streamChat()（SSE）
        │                                       └── AiReportEntity 落库 + Markdown 渲染
        │
        └──→ BudgetPredictor（纯函数，离线）
                └── HomeScreen 预算卡 / 超支预测

用户文本 / 语音
        │
        ├── ACTION_RECOGNIZE_SPEECH（语音，系统弹窗，无需麦克风权限）
        │
        └── 用户主动点击「AI 解析」
                └── AiClient.chat()（非流式）
                        └── AiTransactionParser 提取 + 校验 + 映射
                                └── TransactionDraft → RecordUiState 回填
                                        └── 用户确认后走原有 TransactionRepository 入库
```

## 本阶段明确不做

```text
AI 自动后台分析
AI 自动修改分类
AI 自动调整预算
AI 直接读取原始支付通知
AI 自动记账入库
金额类型从 Double 改 Long/BigDecimal（独立任务）
多账本
投资建议
复杂 Agent / 工具调用
服务端中转
Function Calling / JSON Schema 强约束（各家兼容度不一，第一版靠 Prompt + 本地容错）
```

---

# 任务 0：工程基线改造（v2 新增，必须最先做）

> 这个任务**不含任何 AI 功能**，目的是把后面 6 个任务的地基铺平，并且单独可验证：做完之后 App 行为应当与现在**完全一致**。

## 0.1 Room 迁移到 version 2

`data/db/AppDatabase.kt` 改动：

```kotlin
@Database(
    entities = [
        Transaction::class, Category::class, Budget::class, CategoryBudget::class,
        AiReportEntity::class            // 新增
    ],
    version = 2,                          // 1 → 2
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun aiReportDao(): AiReportDao       // 新增

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ai_reports` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `periodType` TEXT NOT NULL,
                        `periodStart` INTEGER NOT NULL,
                        `periodEnd` INTEGER NOT NULL,
                        `periodLabel` TEXT NOT NULL,
                        `dataHash` TEXT NOT NULL,
                        `model` TEXT NOT NULL,
                        `markdown` TEXT NOT NULL,
                        `suggestedBudget` REAL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_reports_periodStart_periodEnd` " +
                    "ON `ai_reports` (`periodStart`, `periodEnd`)"
                )

                // Budget 增加来源标记（任务 6 需要），默认 MANUAL 保证老数据语义不变
                db.execSQL(
                    "ALTER TABLE `budgets` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'"
                )

                // 修掉 C10：同月去重后建唯一索引
                db.execSQL("""
                    DELETE FROM `budgets`
                    WHERE `id` NOT IN (SELECT MAX(`id`) FROM `budgets` GROUP BY `yearMonth`)
                """.trimIndent())
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_yearMonth` " +
                    "ON `budgets` (`yearMonth`)"
                )
            }
        }
    }
}
```

`Room.databaseBuilder(...)` 追加 `.addMigrations(MIGRATION_1_2)`，**不要**加 `fallbackToDestructiveMigration()`。

`Budget` 实体同步改为：

```kotlin
@Entity(tableName = "budgets", indices = [Index(value = ["yearMonth"], unique = true)])
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val yearMonth: String,
    val totalIncomeTarget: Double? = null,
    val totalExpenseLimit: Double? = null,
    val source: String = BudgetSource.MANUAL.name,   // 新增
    val createdAt: Long = System.currentTimeMillis()
)
```

`BudgetDao.insertBudget` 的 `OnConflictStrategy.REPLACE` 建唯一索引后才真正按 `yearMonth` 生效，可保留。

**迁移验证方式（必须做）**：
1. 装 v1.0.28 正式包，记 3 笔账 + 设一次预算；
2. 直接覆盖安装新包（不卸载）；
3. 断言：账单条数不变、预算值不变、`budgets.source` 全为 `MANUAL`、App 不崩。

因为 `exportSchema = false`，无法用 Room 的 `MigrationTestHelper` 自动校验，这一步以**手工回归**为准，写进发版 checklist。

## 0.2 不新增网络 / 序列化依赖（决策记录）

| 候选 | 结论 | 理由 |
|---|---|---|
| OkHttp + kotlinx.serialization | ❌ 不采用 | 引 2 个库 + 1 个 Gradle plugin；R8 开启下 kotlinx.serialization 需额外 keep 规则，release 包易出问题；项目现有网络代码全是 `HttpURLConnection`，混两套范式增加维护成本 |
| Retrofit | ❌ 不采用 | 同上，且 SSE 还要再加 adapter |
| `HttpURLConnection` + `org.json` | ✅ 采用 | 零新增依赖；`UpdateChecker` 已有完整范例（超时/错误流/重定向）；SSE 只需 `BufferedReader.readLine()` 逐行解析，代码量约 80 行 |

`build.gradle.kts` 本阶段**只增加**：

```kotlin
testImplementation("junit:junit:4.13.2")
```

`proguard-rules.pro` 追加（新增 entity 已被 `data.db.entity.**` 覆盖，这里补 AI 相关纯数据类，防止将来改成反射序列化时踩坑）：

```proguard
-keep class com.smartledger.data.ai.model.** { *; }
```

## 0.3 测试脚手架

新建 `app/src/test/java/com/smartledger/`。第一版只放**不依赖 Android**的纯函数测试：

```text
app/src/test/java/com/smartledger/
├── ai/AiTransactionParserTest.kt
├── ai/JsonExtractorTest.kt
├── ai/SuggestedBudgetExtractorTest.kt
├── ai/SseParserTest.kt
├── ai/BaseUrlNormalizerTest.kt
├── analytics/MerchantAnonymizerTest.kt
├── analytics/FinancialSummaryBuilderTest.kt   // 用内存构造的 Transaction 列表，不碰 Room
└── budget/BudgetPredictorTest.kt
```

为此，**设计上强制要求**：

- `BudgetPredictor`、`AiTransactionParser`、`MerchantAnonymizer`、`SseParser`、`JsonExtractor`、`SuggestedBudgetExtractor`、`BaseUrlNormalizer` 一律为 `object` / 纯函数，**不接 `Context`**。
- `FinancialSummaryBuilder` 拆成两层：
  - `FinancialSummaryBuilder`（接 DAO，负责取数）
  - `SummaryAggregator`（纯函数：`List<TxPoint> + 参数 → FinancialSummary`）→ 被单测覆盖
- `AiClient` 的 HTTP 收发抽象成 `HttpEngine` 接口，测试用 `FakeHttpEngine` 注入，**不引 MockWebServer**。

## 0.4 隐私政策与首次告知（合规必做）

现状：`ui/settings/PrivacyPolicyScreen.kt` 的文案是按"纯本地应用"写的。启用 AI 后数据会出网，**必须先改文案再上功能**，否则是明确的隐私声明不实。

改动内容：

1. `PrivacyPolicyScreen` 增加一节「AI 财务顾问（可选功能）」，明确写：
   - 该功能默认关闭，需用户自行填写第三方模型服务商的 API Key 才可用；
   - 请求由**用户设备直连用户配置的服务商**，SmartLedger 不设中转服务器、不收集任何数据；
   - 发送内容仅限：周期标签、金额合计、分类名与金额、脱敏后的商户序号、时段/星期分布、笔数、预算数值；
   - **不发送**：原始支付通知全文、银行卡号、手机号、订单号、支付流水号、真实商户名称、用户备注原文、联系人；
   - 语音功能使用系统语音识别，**录音不由 SmartLedger 上传**，识别行为受系统语音服务提供方的隐私政策约束；
   - 用户可随时清空 API Key 与本地 AI 报告缓存。
2. 首次在设置里保存 AI 配置时，弹一次 `SmartLedgerDialog` 明确告知（同上要点精简版 + 「我已了解」），用 `SharedPreferences` 记 `ai_privacy_ack = true`，不重复弹。
3. `AndroidManifest.xml` 增加备份排除，避免 API Key 密文被带到新设备后成为无法解密的垃圾数据：

```xml
<application
    android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    ... >
```

`res/xml/backup_rules.xml`：

```xml
<full-backup-content>
    <exclude domain="sharedpref" path="smart_ledger_ai.xml" />
</full-backup-content>
```

`res/xml/data_extraction_rules.xml`：

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="smart_ledger_ai.xml" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="smart_ledger_ai.xml" />
    </device-transfer>
</data-extraction-rules>
```

## 0.5 任务 0 验收

```text
□ 覆盖安装（1 → 2）后账单/分类/预算数据完整，无崩溃
□ budgets 表存在 yearMonth 唯一索引，且历史重复月份已合并
□ ai_reports 表已创建，含 (periodStart, periodEnd) 索引
□ ./gradlew testDebugUnitTest 能跑通（此时可以只有 1 个占位测试）
□ ./gradlew assembleRelease 成功，release 包冷启动正常
□ 隐私政策页已包含「AI 财务顾问」章节
□ 除上述内容外，App 的 UI 与行为与 v1.0.28 完全一致
```

---

# 任务 1：统一 AI 顾问配置

## 1.1 目标与入口

在设置页新增统一 AI 配置，供「AI 消费体检」和「自然语言/语音快捷记账」共用。

```text
设置
└── AI 财务顾问                        ← 新增 SectionTitle
    └── AI 服务配置  [未配置 / DeepSeek·deepseek-chat]
            ↓ 进入 AiSettingsScreen
            ├── 服务商预设（下拉）
            ├── Base URL
            ├── API Key（默认掩码，可见性切换）
            ├── Model
            ├── [测试连接]
            └── [清除配置]
```

放在 `SettingsScreen` 的「自动记账」与「关于」之间，用现有 `SectionTitle` + `Card` + `MenuSettingItem` 组件，副标题显示当前状态（`未配置` / `DeepSeek · deepseek-chat`）。

导航：`MainActivity` 的 NavHost 增加 `composable("ai_settings")`，`SettingsScreen` 增加 `onNavigateToAiSettings` 回调（与现有 `onNavigateToFeedback` 同风格）。

## 1.2 数据模型与 Provider 预设

```kotlin
// data/ai/model/AiConfig.kt
enum class AiProviderPreset(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val apiKeyHint: String,
    val experimental: Boolean = false
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "sk-..."),
    KIMI("Kimi（月之暗面）", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "sk-..."),
    QWEN("通义千问（百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "sk-..."),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "sk-..."),
    GEMINI(
        "Gemini（OpenAI 兼容端点）",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-2.0-flash",
        "AIza...",
        experimental = true
    ),
    CUSTOM("自定义 OpenAI Compatible", "", "", "自定义 Key");
}

data class AiConfig(
    val preset: AiProviderPreset,
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}
```

> **关于 Gemini（修正 v1 的错误）**：Gemini 原生 API 是 `POST /v1beta/models/{model}:generateContent`，请求体是 `contents/parts` 结构，**与 OpenAI Chat Completions 不兼容**，v1 把它列进"OpenAI Compatible 预设"是错的。这里改用 Google 提供的 OpenAI 兼容端点，但其流式与参数支持是 OpenAI 的子集，因此在 UI 上标注「实验性」，测试连接失败时提示用户改用其他服务商。
>
> **表中所有 baseUrl / model 在开发时必须对照各家官方文档再核对一遍**（各家会调整），预设值只是省去用户手输，用户始终可改。

选择预设后自动填入 `defaultBaseUrl` / `defaultModel`；用户手改过任一字段后，再切预设需二次确认（避免误覆盖）。选 `CUSTOM` 时不自动填。

## 1.3 Base URL 规范化（避免最常见的配置错误）

用户会填出各种形态：`https://api.deepseek.com`、`.../v1`、`.../v1/`、`.../v1/chat/completions`。统一处理：

```kotlin
// data/ai/BaseUrlNormalizer.kt  —— 纯函数，有单测
object BaseUrlNormalizer {

    /** 归一化为不带尾斜杠、不带 /chat/completions 的 base */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        s = s.trimEnd('/')
        // 用户把完整 endpoint 贴进来时自动剥掉
        listOf("/chat/completions", "/completions").forEach { suffix ->
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trimEnd('/')
        }
        return s
    }

    /** 拼出最终请求地址；base 没有版本段时补 /v1 */
    fun chatCompletionsUrl(baseUrl: String): String {
        val base = normalize(baseUrl)
        val needsVersion = !Regex("/v\\d+([a-zA-Z0-9-]*)?$").containsMatchIn(base) &&
                !base.endsWith("/compatible-mode/v1") &&
                !base.endsWith("/openai")
        return if (needsVersion) "$base/v1/chat/completions" else "$base/chat/completions"
    }

    fun isCleartext(raw: String): Boolean = normalize(raw).startsWith("http://")
}
```

**明文 HTTP**：Android 9+ 默认禁止 cleartext，用户填 `http://` 自建地址会直接失败。处理方式：
- UI 层在保存/测试时检测到 `http://` 就提示「Android 系统默认禁止明文 HTTP 请求，请使用 https 地址」；
- **不**为此放开全局 `usesCleartextTraffic`（会削弱整个 App 的网络安全）。

## 1.4 SecureSecretStore（API Key 加密存储）

不引 `security-crypto`（已 deprecated）。`minSdk 26` 可直接用 AndroidKeyStore AES/GCM：

```kotlin
// data/security/SecureSecretStore.kt
object SecureSecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "smartledger_ai_secret_v1"
    private const val PREFS = "smart_ledger_ai"     // 已在备份规则中排除
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 加密写入；value 为空表示清除 */
    fun put(context: Context, key: String, value: String?)

    /**
     * 读取解密。以下情况一律返回 null（并顺手清除脏密文）：
     *  - 无记录
     *  - Keystore 密钥不存在（换机 / 恢复备份 / 清除凭据）
     *  - AEADBadTagException（密文损坏）
     */
    fun get(context: Context, key: String): String?

    fun clear(context: Context, key: String)
}
```

要点：

1. 密钥用 `KeyGenParameterSpec(KEY_ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)`，`BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE`，**不设 `setUserAuthenticationRequired`**（否则锁屏后读不到）。
2. 存储格式：`Base64(iv) + ":" + Base64(cipherText)`，写进 `SharedPreferences("smart_ledger_ai")`。
3. **解密失败必须优雅降级**（对应 M2）：AndroidKeyStore 密钥不参与云备份/换机迁移，恢复到新设备后旧密文一定解不开。此时：
   - `get()` 返回 `null` 并删除该条密文；
   - UI 显示「AI 配置需要重新填写 API Key」，其余配置（baseUrl/model/preset）保留；
   - **不崩溃、不弹异常堆栈**。
4. **日志纪律**：全局禁止把 Key 或含 Key 的 header/请求体打进 `Log`。约定：
   - `AiClient` 内部日志只打 `方法 + URL host + 状态码 + 耗时`；
   - 任何异常信息在往 UI 传之前经过 `redact()`，把形如 `sk-[A-Za-z0-9_\-]{8,}` 的片段替换为 `sk-***`；
   - Review 时 grep 检查：`Log.*apiKey`、`Log.*Authorization` 必须为 0 命中。

非敏感项存普通 prefs：

```kotlin
// data/preferences/AiPreferences.kt
object AiPreferences {
    // SharedPreferences("smart_ledger_ai")
    // ai_preset / ai_base_url / ai_model / ai_privacy_ack / ai_report_ttl_hours
    fun load(context: Context): AiConfig      // apiKey 从 SecureSecretStore 取
    fun save(context: Context, config: AiConfig)
    fun clear(context: Context)
    fun observeConfigured(context: Context): StateFlow<Boolean>  // 供 UI 显示入口状态
}
```

## 1.5 AiClient 与 HttpEngine

```kotlin
// data/ai/AiMessage.kt
data class AiMessage(val role: String, val content: String)   // role: system / user / assistant

// data/ai/AiError.kt
sealed class AiError(val userMessage: String) {
    data object NotConfigured   : AiError("请先在设置中配置 AI 服务")
    data object InvalidApiKey   : AiError("API Key 无效或已过期")
    data object ModelNotFound   : AiError("模型不存在，请检查 Model 名称")
    data object RateLimited     : AiError("请求过于频繁或额度不足，请稍后重试")
    data object ServerError     : AiError("AI 服务暂时不可用，请稍后重试")
    data object Timeout         : AiError("请求超时，请检查网络后重试")
    data object Network         : AiError("网络连接失败，请检查网络")
    data object Cleartext       : AiError("系统禁止明文 HTTP 请求，请使用 https 地址")
    data object Incompatible    : AiError("接口返回格式不兼容，请确认是 OpenAI 兼容接口")
    data object Cancelled       : AiError("已停止生成")
    data class  Unknown(val detail: String) : AiError("请求失败：$detail")
}

// data/ai/AiClient.kt
interface AiClient {
    suspend fun testConnection(config: AiConfig): Result<String>       // 成功返回模型回显内容
    suspend fun chat(config: AiConfig, messages: List<AiMessage>,
                     temperature: Double = 0.2, maxTokens: Int = 512): Result<String>
    fun streamChat(config: AiConfig, messages: List<AiMessage>,
                   temperature: Double = 0.7, maxTokens: Int = 2048): Flow<AiChunk>
}

sealed interface AiChunk {
    data class Delta(val text: String) : AiChunk
    data object Done : AiChunk
}
```

用途分工：

```text
chat()       → 自然语言记账字段抽取（temperature 0.2，短输出，要稳定）
streamChat() → AI 消费体检（temperature 0.7，长输出，要实时观感）
```

**HttpEngine 抽象**（为了可单测，且不引 MockWebServer）：

```kotlin
// data/ai/http/HttpEngine.kt
interface HttpEngine {
    /** 一次性请求，返回 (statusCode, body) */
    suspend fun post(url: String, headers: Map<String, String>, body: String,
                     connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse
    /** 流式请求，逐行回调；返回值为最终状态码 */
    suspend fun postStreaming(url: String, headers: Map<String, String>, body: String,
                              connectTimeoutMs: Int, readTimeoutMs: Int,
                              onLine: suspend (String) -> Unit): Int
}

data class HttpResponse(val code: Int, val body: String)

// data/ai/http/UrlConnectionHttpEngine.kt  —— HttpURLConnection 实现
// app/src/test/.../FakeHttpEngine.kt       —— 测试用，按脚本返回状态码/行序列
```

`AiClientImpl(private val engine: HttpEngine)`，`SmartLedgerApp` 里 `by lazy { AiClientImpl(UrlConnectionHttpEngine()) }`。

## 1.6 请求 / 响应格式

请求（`POST {base}/chat/completions`）：

```http
Content-Type: application/json
Authorization: Bearer {apiKey}
Accept: application/json            # 流式时为 text/event-stream
User-Agent: SmartLedger-Android/{versionName}
```

```json
{
  "model": "deepseek-chat",
  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
  "temperature": 0.7,
  "max_tokens": 2048,
  "stream": true
}
```

用 `org.json.JSONObject` / `JSONArray` 手工构造，**必须用 JSON API 拼装而不是字符串模板**，否则用户备注里的引号/换行会破坏请求体。

非流式响应解析：`choices[0].message.content`。缺失该路径 → `AiError.Incompatible`。

**超时参数**：

| 场景 | connectTimeout | readTimeout |
|---|---|---|
| 测试连接 | 8s | 15s |
| `chat()` 记账解析 | 8s | 30s |
| `streamChat()` 体检 | 8s | 60s（每次 readLine 的间隔上限） |

另加**整体墙钟上限**：体检 120s、解析 45s，由调用方 `withTimeout` 控制（超时归类为 `AiError.Timeout`）。

## 1.7 SSE 解析

```kotlin
// data/ai/SseParser.kt  —— 纯函数，有单测
object SseParser {
    /**
     * 输入一行原始 SSE 文本，输出解析结果。
     * 需要处理：
     *  - 空行（心跳/分隔）→ Ignore
     *  - ": ping" 之类注释行 → Ignore
     *  - "data: [DONE]" / "data:[DONE]" → Done
     *  - "data: {json}" → 取 choices[0].delta.content（可能不存在，如只带 role 的首包）
     *  - 非法 JSON → Ignore（不要因为一行坏包中断整个流）
     *  - 不以 "data:" 开头的行（event:/id:/retry:）→ Ignore
     */
    fun parseLine(line: String): SseEvent
}

sealed interface SseEvent {
    data class Content(val text: String) : SseEvent
    data object Done : SseEvent
    data object Ignore : SseEvent
}
```

`streamChat` 用 `callbackFlow` / `flow` 包装 `engine.postStreaming`，`flowOn(Dispatchers.IO)`；协程取消时关闭连接（`conn.disconnect()` 放 `finally`）。

**流中断处理**：若已经产出过内容但连接异常断开 → 不抛错清空，而是发 `AiChunk.Done` 并在 UI 上标注「生成被中断，内容可能不完整」，已生成部分**仍然落库**。

## 1.8 错误分类（HTTP → AiError）

| 状态码 / 异常 | 映射 | 备注 |
|---|---|---|
| 200 且能解出 content | 成功 | |
| 200 但结构不符 | `Incompatible` | 常见于填错成非 OpenAI 兼容端点 |
| 400 | `Unknown(取 error.message 前 120 字)` | 参数错，通常是 model 名或 max_tokens |
| 401 / 403 | `InvalidApiKey` | |
| 404 | `ModelNotFound` | 也可能是 baseUrl 路径错，文案里带上「或 Base URL 路径不正确」 |
| 429 | `RateLimited` | |
| 5xx | `ServerError` | |
| `SocketTimeoutException` | `Timeout` | |
| `UnknownHostException` / `ConnectException` / `SSLException` | `Network` | |
| 明文 http:// | `Cleartext` | 在发请求前就拦下 |
| `CancellationException` | `Cancelled` | 必须重新抛出，不吞 |

错误体解析：优先取 `error.message`，其次 `message`，再兜底截断原始 body（**截断前先跑 `redact()`**）。

## 1.9 测试连接

请求内容固定极小，避免浪费额度：

```json
{
  "model": "{model}",
  "messages": [
    {"role":"system","content":"You are a connectivity probe. Reply with exactly: OK"},
    {"role":"user","content":"ping"}
  ],
  "temperature": 0,
  "max_tokens": 8,
  "stream": false
}
```

UI 状态：

```text
正在测试…              （按钮转 loading，禁用）
✓ 连接成功（模型回复：OK，耗时 1.2s）
✗ API Key 无效或已过期
✗ 模型不存在，请检查 Model 名称
✗ 请求超时，请检查网络后重试
✗ 网络连接失败，请检查网络
✗ 接口返回格式不兼容，请确认是 OpenAI 兼容接口
✗ 系统禁止明文 HTTP 请求，请使用 https 地址
```

测试连接**不要求先保存**，用当前输入框内容试；成功后提示「已保存」。

## 1.10 UI 规格（AiSettingsScreen）

`ui/settings/AiSettingsScreen.kt`，沿用项目现有页面骨架（顶部返回 + `LazyColumn` + `Card` + `SmartLedgerColors`）：

```text
← AI 财务顾问

  服务商
  ┌──────────────────────────────────────┐
  │ DeepSeek                          ▾ │
  └──────────────────────────────────────┘

  Base URL
  ┌──────────────────────────────────────┐
  │ https://api.deepseek.com/v1          │
  └──────────────────────────────────────┘

  API Key
  ┌──────────────────────────────────────┐
  │ sk-••••••••••••••••••••••1a2b     👁 │
  └──────────────────────────────────────┘
  仅保存在本机（加密存储），不会上传到 SmartLedger

  Model
  ┌──────────────────────────────────────┐
  │ deepseek-chat                        │
  └──────────────────────────────────────┘

  [ 测试连接 ]        [ 保存 ]

  ─────────────────────────────────────
  AI 会收到什么数据？                  ›   ← 展开说明（与隐私政策同一份文案）
  清除配置与本地 AI 报告                   ← 二次确认后清空 Key + ai_reports
```

细节：
- 已保存的 Key 回显为 `sk-••••••1a2b`（前 3 后 4），点 👁 才显示明文；**用户不修改就不重新写入**（避免掩码串被当成新 Key 存进去）——实现上用 `isKeyDirty` 标记。
- `TextField` 的 `keyboardOptions` 关闭自动纠错/联想（`KeyboardType.Password` 或 `autoCorrect = false`），避免中文输入法把 Key 改了。
- 页面不做「立即生效」的隐式保存，保存动作显式。

## 1.11 任务 1 验收

```text
□ DeepSeek / Kimi / 通义千问 / OpenAI / 自定义 至少 2 家实测可用
□ Gemini 兼容端点已实测，可用则保留，不可用则在 UI 标注并给出提示
□ 切换预设自动填 Base URL / Model；用户改过之后切预设有二次确认
□ Base URL 填 "api.deepseek.com" / ".../v1/" / ".../v1/chat/completions" 三种形态都能请求成功
□ 填 http:// 地址时给出明确提示，且未放开全局 cleartext
□ API Key 在 Logcat / 错误提示 / 崩溃堆栈中均不出现（grep 校验）
□ 掩码状态下点保存不会把掩码串写成新 Key
□ 清除配置后 ai_reports 与 API Key 均被清空
□ 首次保存配置弹一次隐私告知，第二次不再弹
□ AI 未配置时，SmartLedger 其余功能完全正常（自动记账 / 手工记账 / 统计 / 导出 / 备份）
□ 飞行模式下点测试连接，提示网络错误，App 不崩
```


---

# 任务 2：本地财务数据聚合与脱敏

## 2.1 目标与分层

AI 不直接读取原始流水，也不逐笔上传。链路固定为四层，每层职责单一：

```text
① 取数（碰 Room）      TransactionDao          → 只做 SUM / COUNT / GROUP BY categoryId
                                                 + 一个轻量投影 List<TxPoint>
② 聚合（纯 Kotlin）    SummaryAggregator       → 按本地时区分桶，产出 FinancialSummary
③ 脱敏（纯 Kotlin）    MerchantAnonymizer      → 真实商户名 → "餐饮商户 #1"
④ 成文（纯 Kotlin）    SummaryFormatter        → FinancialSummary → Prompt 用的纯文本片段
```

② ③ ④ 全部是不接 `Context` 的纯函数，**全部有单测**。

## 2.2 数据类定义（v1 只引用未定义，这里补全）

```kotlin
// data/analytics/model/TxPoint.kt
/** DAO 轻量投影：只取聚合需要的列，避免把整行读进内存 */
data class TxPoint(
    val amount: Double,
    val type: String,             // "expense" / "income"
    val categoryId: Long?,
    val merchant: String?,
    val transactionTime: Long
)

// data/analytics/model/FinancialSummary.kt
data class CategoryStat(
    val categoryName: String,     // 已解析成中文名；未分类 → "未分类"
    val amount: Double,
    val percent: Double,          // 占总支出，0~100，保留 1 位
    val count: Int
)

data class MerchantStat(
    val anonymizedName: String,   // "餐饮商户 #1"
    val amount: Double,
    val count: Int
)

/** 时段桶，取代 v1 的 24 条 hourlyStats，压缩 prompt 体积 */
enum class TimeBucket(val label: String, val startHour: Int, val endHourExclusive: Int) {
    MORNING("早间 06-11", 6, 11),
    NOON("午间 11-14", 11, 14),
    AFTERNOON("下午 14-18", 14, 18),
    EVENING("晚间 18-22", 18, 22),
    NIGHT("夜间 22-06", 22, 6)      // 跨日
}

data class TimeBucketStat(
    val bucket: TimeBucket,
    val amount: Double,
    val count: Int
)

enum class SummaryPeriod(val label: String) {
    THIS_MONTH("本月"),
    LAST_3_MONTHS("近 3 个月")
}

data class FinancialSummary(
    val period: SummaryPeriod,
    val periodLabel: String,          // "2026 年 9 月" / "2026 年 7 月 ~ 9 月"
    val periodStart: Long,
    val periodEnd: Long,

    val totalIncome: Double,
    val totalExpense: Double,
    val transactionCount: Int,

    val budget: Double?,              // 来自 Budget.totalExpenseLimit（注意不是 totalIncomeTarget）
    val budgetUsedPercent: Double?,   // totalExpense / budget * 100

    val previousPeriodExpense: Double?,
    val expenseChangePercent: Double?, // (cur - prev) / prev * 100；prev<=0 时为 null

    val categoryStats: List<CategoryStat>,        // 全量，按金额降序
    val topMerchantStats: List<MerchantStat>,     // 脱敏后 Top 10

    val weekdayExpense: Double,
    val weekendExpense: Double,
    val weekdayDailyAverage: Double,
    val weekendDailyAverage: Double,

    val timeBucketStats: List<TimeBucketStat>,
    val nightTransactionCount: Int,    // 22:00~次日 06:00
    val nightExpense: Double,

    val daysElapsed: Int,             // 周期内已过天数（当月周期 = 今天几号）
    val daysTotal: Int                // 周期总天数
)
```

> **金额类型**：本阶段不为了 AI 重构原项目的 `Double`。所有对外展示与进 Prompt 的金额统一经 `CurrencyUtil` / `String.format("%.2f")` 格式化，聚合时用 `Double` 累加即可（月级几百笔，误差在 1e-9 量级，不影响结论）。精度改造列为后续独立任务。

## 2.3 TransactionDao 新增查询（按真实字段，修正 C1~C3）

```kotlin
// 追加到 data/db/dao/TransactionDao.kt

/** 周期内总支出（一次性，供聚合用；已有的 getExpenseSum 是 Flow） */
@Query("""
    SELECT COALESCE(SUM(amount), 0) FROM transactions
    WHERE type = 'expense' AND transactionTime BETWEEN :start AND :end
""")
suspend fun getExpenseSumOnce(start: Long, end: Long): Double

@Query("""
    SELECT COALESCE(SUM(amount), 0) FROM transactions
    WHERE type = 'income' AND transactionTime BETWEEN :start AND :end
""")
suspend fun getIncomeSumOnce(start: Long, end: Long): Double

@Query("""
    SELECT COUNT(*) FROM transactions
    WHERE transactionTime BETWEEN :start AND :end
""")
suspend fun getCountInRange(start: Long, end: Long): Int

/** 分类聚合：注意是 categoryId 而不是 category，type 是小写 'expense' */
@Query("""
    SELECT categoryId AS categoryId,
           COALESCE(SUM(amount), 0) AS total,
           COUNT(*) AS count
    FROM transactions
    WHERE type = 'expense' AND transactionTime BETWEEN :start AND :end
    GROUP BY categoryId
    ORDER BY total DESC
""")
suspend fun getExpenseByCategoryOnce(start: Long, end: Long): List<CategoryAggRow>

/** 商户聚合（真实商户名，脱敏在 Kotlin 侧做） */
@Query("""
    SELECT merchant AS merchant,
           COALESCE(SUM(amount), 0) AS total,
           COUNT(*) AS count
    FROM transactions
    WHERE type = 'expense'
      AND transactionTime BETWEEN :start AND :end
      AND merchant IS NOT NULL AND TRIM(merchant) <> ''
    GROUP BY merchant
    ORDER BY total DESC
    LIMIT :limit
""")
suspend fun getExpenseByMerchantOnce(start: Long, end: Long, limit: Int): List<MerchantAggRow>

/** 时段 / 星期分桶用的轻量投影：不取 note / notificationKey 等敏感或无用列 */
@Query("""
    SELECT amount, type, categoryId, merchant, transactionTime
    FROM transactions
    WHERE transactionTime BETWEEN :start AND :end
""")
suspend fun getPointsInRange(start: Long, end: Long): List<TxPoint>

data class CategoryAggRow(val categoryId: Long?, val total: Double, val count: Int)
data class MerchantAggRow(val merchant: String, val total: Double, val count: Int)
```

## 2.4 为什么时段/星期分桶放在 Kotlin 侧（修正 M4）

v1 说"优先让 SQLite 聚合，不把几千条流水读入 Kotlin"。这条原则在**金额合计**上对，在**时间分桶**上会踩坑：

`transactionTime` 是毫秒时间戳，SQLite 里要分小时得写 `strftime('%H', transactionTime/1000, 'unixepoch')`，**这个结果是 UTC 小时**。北京时间 23:30 的一笔消费，UTC 是 15:30，会被算进"下午"而不是"夜间"；`strftime('%w', ...)` 判周末同理会在周五/周一边界错位。要修就得写 `'localtime'` 修饰符，而它依赖设备时区数据、可读性差、也无法用纯 JVM 单测覆盖。

因此定死分工：

| 指标 | 计算位置 | 理由 |
|---|---|---|
| 总收支、笔数、分类聚合、商户聚合 | **SQLite** | 与时区无关，SUM/GROUP BY 效率最高 |
| 时段桶、夜间消费、工作日/周末、日均 | **Kotlin（`Calendar`，设备默认时区）** | 时区正确、可单测 |

`getPointsInRange` 一次读一个月的投影（典型 50~300 行、每行 5 个字段），在 `Dispatchers.IO` 上执行，成本可忽略。"近 3 个月"最多千行量级，同样没有性能问题。

**工作日/周末日均的分母**（v1 未定义，容易算错）：

```text
weekdayDaysElapsed = 周期内已过的日期中，周一~周五的天数
weekendDaysElapsed = 周期内已过的日期中，周六/周日的天数
weekdayDailyAverage = weekdayExpense / max(weekdayDaysElapsed, 1)
weekendDailyAverage = weekendExpense / max(weekendDaysElapsed, 1)
```

"已过"以 `min(periodEnd, now)` 为界，否则当月月初算出来的日均会被未来天数摊薄。

**夜间（22:00~次日 06:00）判定**：按该笔交易的本地小时 `h`，`h >= 22 || h < 6` 即计入。这是同一个自然日内的判定，不涉及跨日归属，实现简单且口径可解释；写进 Prompt 时明确说明是"22 点后或凌晨 6 点前发生的消费"，避免 AI 误解。

## 2.5 MerchantAnonymizer（商户脱敏）

```kotlin
// data/analytics/MerchantAnonymizer.kt —— 纯函数，有单测
object MerchantAnonymizer {
    /**
     * 输入：按金额降序的 (真实商户名, 金额, 笔数, 所属分类名) 列表
     * 输出：同序的 MerchantStat，名称形如 "餐饮商户 #1"
     *
     * 规则：
     *  1. 编号在同一次调用内按「分类 + 出现顺序」递增，保证同一份 Summary 内稳定；
     *  2. 同一真实商户名映射到同一编号（用 map 缓存）；
     *  3. 分类为空时统一用 "其他商户 #n"；
     *  4. 绝不输出真实商户名的任何片段（不做「瑞*咖啡」这类部分打码）。
     */
    fun anonymize(rows: List<MerchantRow>): List<MerchantStat>

    data class MerchantRow(
        val merchant: String,
        val amount: Double,
        val count: Int,
        val categoryName: String?
    )
}
```

举例：

| 本地真实数据 | 上传给 AI |
|---|---|
| 瑞幸咖啡科技园店 | 餐饮商户 #1 |
| 麦当劳南山店 | 餐饮商户 #2 |
| 淘宝 XX 数码旗舰店 | 购物商户 #1 |
| 京东自营 | 购物商户 #2 |
| （merchant 为空） | 不进入商户榜 |

进 Prompt 的形态：

```text
餐饮商户 #1：12 笔，共 326.00 元
购物商户 #1：4 笔，共 860.00 元
```

**编号只在单次报告内有意义**，不持久化映射表（避免"AI 报告里的 #1 到底是谁"这种反向关联需求把真实商户名再存一份）。UI 上在报告底部加一行小字说明：「报告中的商户已匿名编号，编号仅在本次报告内有效」。

## 2.6 脱敏边界（必须遵守）

**允许发送**：

```text
周期标签（如 2026 年 9 月）
金额合计（收入 / 支出 / 预算）
分类名（本地分类表里的中文名，如「餐饮」）+ 金额 + 笔数 + 占比
脱敏商户编号 + 金额 + 笔数
时段桶 / 工作日周末 / 夜间 的金额与笔数
交易总笔数、日均、环比百分比
```

**禁止发送**：

```text
原始支付通知全文 / 标题 / 包名
银行卡号、卡尾号
手机号
订单号、支付流水号、notificationKey
真实商户名称（含任何片段）
用户备注（note）原文
联系人姓名
transactionTime 精确到秒的时间戳序列（只发聚合后的桶）
```

**工程化保障**（v1 只写了"默认不上传"，没有强制手段）：

1. `FinancialSummary` 里**不存在**任何可承载敏感信息的字段（没有 `note`、没有 `rawMerchant`、没有 `notificationKey`）—— 靠类型设计而不是靠自觉；
2. `SummaryFormatter` 是唯一能产出 Prompt 文本的地方，它的输入类型只能是 `FinancialSummary`；
3. `AiAdvisorRepository` 只接受 `FinancialSummary`，不接受 `List<Transaction>`；
4. 加一个 DEBUG-only 的自检：组好 Prompt 后用正则扫一遍，命中 `\d{11}`（手机号）、`\d{16,19}`（卡号）、`\d{15,}`（长订单号）就 `Log.e` + 在 debug 包里直接抛异常，release 包只拦不抛。

## 2.7 SummaryFormatter（进 Prompt 的文本）

```kotlin
// data/analytics/SummaryFormatter.kt —— 纯函数，有单测
object SummaryFormatter {
    fun categorySection(s: FinancialSummary): String
    fun merchantSection(s: FinancialSummary): String
    fun weekdayWeekendSection(s: FinancialSummary): String
    fun nightSection(s: FinancialSummary): String
    fun historySection(s: FinancialSummary): String
    /** 用于计算 dataHash 的规范化序列化：字段顺序固定、金额统一 %.2f */
    fun canonicalJson(s: FinancialSummary): String
}
```

输出样例（这就是真正会发给 AI 的内容）：

```text
周期：2026 年 9 月（1 日 ~ 7 日，已过 7 天 / 共 30 天）
预算（支出上限）：5000.00 元
实际支出：4280.00 元（预算已用 85.6%）
收入：12000.00 元
消费笔数：62 笔

主要支出分类：
餐饮 1797.00 元 / 42.0% / 28 笔
购物 1070.00 元 / 25.0% / 9 笔
交通 642.00 元 / 15.0% / 14 笔
娱乐 513.00 元 / 12.0% / 6 笔
居住 172.00 元 / 4.0% / 1 笔
通讯 86.00 元 / 2.0% / 1 笔

高频消费商户（已匿名）：
餐饮商户 #1：12 笔，共 326.00 元
购物商户 #1：4 笔，共 860.00 元
交通商户 #1：9 笔，共 214.00 元

工作日 / 周末：
工作日支出 1926.00 元（5 天，日均 385.20 元）
周末支出 2354.00 元（2 天，日均 1177.00 元）
周末支出占比 55.0%

时段分布：
早间 06-11：214.00 元 / 8 笔
午间 11-14：986.00 元 / 20 笔
下午 14-18：520.00 元 / 11 笔
晚间 18-22：1840.00 元 / 17 笔
夜间 22-06：720.00 元 / 6 笔

历史对比：
上一周期（2026 年 8 月同期）支出 3627.00 元
本周期较上一周期增加 18.0%
```

**注意**：环比用的是"上月同期"（上月 1 日 ~ 上月同一天）而不是"上月整月"，否则月初对比恒为大幅下降，结论毫无意义。这一点 v1 没写清，容易实现成整月对比。`LAST_3_MONTHS` 周期的环比取"前 3 个月"同长度区间。

## 2.8 FinancialSummaryBuilder

```kotlin
// data/analytics/FinancialSummaryBuilder.kt
class FinancialSummaryBuilder(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val budgetDao: BudgetDao
) {
    suspend fun build(period: SummaryPeriod, now: Long = System.currentTimeMillis()): FinancialSummary
}

// data/analytics/SummaryAggregator.kt —— 纯函数，有单测
object SummaryAggregator {
    fun aggregate(
        period: SummaryPeriod,
        periodStart: Long, periodEnd: Long, now: Long,
        points: List<TxPoint>,
        categoryNameById: Map<Long, String>,
        budget: Double?,
        previousPeriodExpense: Double?
    ): FinancialSummary
}
```

`build()` 只负责：算区间（用现有 `DateUtil`，`LAST_3_MONTHS` = `shiftYearMonth(cur, -2)` 的月初 到 当月月末）、拉 DAO、拉分类名映射、拉 `Budget.totalExpenseLimit`，然后交给 `SummaryAggregator`。挂到 `SmartLedgerApp`：

```kotlin
val financialSummaryBuilder by lazy {
    FinancialSummaryBuilder(database.transactionDao(), database.categoryDao(), database.budgetDao())
}
```

**空数据/边界**：
- 周期内 0 笔 → 返回全 0 的 Summary，`categoryStats` 为空。UI 层拦住不让发 AI，提示「本月还没有记账数据」。
- 笔数 < 5 → 允许生成但在 Prompt 里加一句「样本量较小，请谨慎给出结论」，避免 AI 拿 2 笔账编出"消费画像"。
- `budget == null` → Prompt 里写「用户未设置预算」，并要求 AI 在第 3 节说明"因未设置预算，无法判断超支风险"。
- `previousPeriodExpense == null 或 <= 0` → 历史对比段落输出「无可比历史数据」。

## 2.9 任务 2 验收

```text
□ 组装出的 Prompt 中不含原始通知、银行卡号、手机号、订单号、真实商户名、note 原文
□ DEBUG 包的敏感信息自检正则无命中
□ Top 商户全部为「XX商户 #n」形态
□ 所有金额、占比、环比均由本地计算（改一笔账后数字随之变化）
□ 本月 / 近 3 月两个周期都能生成 Summary
□ 未配置 AI 时，Summary 也能正常构建（可用 debug 入口打印验证）
□ 把设备时区改为 UTC+0 / UTC+8 分别验证：夜间笔数、周末占比结论一致（因为用的是设备本地时区）
□ 环比用的是「上一周期同期」而非整月
□ 单测覆盖：分类金额与占比、Top 商户、周末占比与日均、夜间金额、环比、预算达成率、0 笔、1 笔、跨月边界
```

---

# 任务 3：AI 周报 / 月报消费体检

## 3.1 目标与周期口径

在 `ui/statistics/StatisticsScreen.kt` 顶部（周期 tab 之下、环形图之上）插入一张 AI 体检卡。

**AI 只在用户点击后调用**，不在页面打开、`onResume`、新增交易、后台任务时调用。

**周期口径（修正 v1 与现有 UI 的冲突）**：`StatisticsScreen` 的 tab 是 日/周/月/年，而 AI 体检只支持"本月 / 近 3 月"。定死规则：

- AI 体检卡**不跟随** tab，自己带一个二选一切换：`本月` / `近 3 月`；
- 默认 `本月`；
- 卡片在**任何 tab 下都显示**（避免用户在"日"tab 找不到入口），但文案固定说明分析周期，不产生歧义。

## 3.2 状态机

```kotlin
// ui/statistics/AiReportUiState.kt
sealed interface AiReportUiState {
    data object NotConfigured : AiReportUiState                 // 未配置 AI
    data object NoData : AiReportUiState                        // 周期内无交易
    data class Idle(val cached: AiReportEntity?) : AiReportUiState
    data object Preparing : AiReportUiState                     // 本地聚合中
    data class Streaming(val text: String) : AiReportUiState     // 已收到部分内容
    data class Success(val report: AiReportEntity, val truncated: Boolean) : AiReportUiState
    data class Failed(val error: AiError, val partialText: String?) : AiReportUiState
}
```

状态迁移：

```text
NotConfigured ──(去设置配置)──> Idle
Idle ──[生成报告]──> Preparing ──> Streaming ──> Success
                        │            │
                        │            ├─[用户点停止]──> Success(truncated=true)（已生成部分落库）
                        │            └─[流中断]──────> Success(truncated=true)
                        └─[聚合/网络失败]──> Failed
Success ──[重新生成]──> Preparing
Failed  ──[重试]──────> Preparing
```

## 3.3 UI 规格

未配置：

```text
┌────────────────────────────────────────┐
│ ✨ AI 消费体检                          │
│                                        │
│ 配置 AI 服务后，可基于本地统计数据      │
│ 生成消费诊断与节流建议                  │
│                                        │
│ [去设置]                                │
└────────────────────────────────────────┘
```

Idle（无缓存）：

```text
┌────────────────────────────────────────┐
│ ✨ AI 消费体检          [本月|近3月]    │
│                                        │
│ 将基于你本月 62 笔消费的本地统计生成    │
│ 分析（不上传商户名与备注）              │
│                                        │
│ [生成本月报告]                          │
└────────────────────────────────────────┘
```

Idle（有缓存）：

```text
│ 上次分析：9 月 7 日 18:30 · deepseek-chat │
│ [查看报告]        [重新生成]              │
```

Streaming：

```text
│ 正在分析你的消费结构…        [停止生成]  │
│                                        │
│ ## 1. 消费结构诊断                      │
│ 本月餐饮支出占总支出的 42%，其中高频     │
│ 小额消费较为明显……                      │
```

Success：报告全文 Markdown 渲染，底部：

```text
│ 建议可用总额度：¥4,800                  │
│ [采纳为下月预算]                         │
│                                        │
│ 报告中商户已匿名编号，编号仅本次有效     │
│ AI 生成内容仅供参考，不构成投资建议      │
│ [重新生成]                              │
```

Failed：

```text
│ ⚠ API Key 无效或已过期                  │
│ [重试]  [去设置]                        │
```

报告默认折叠显示前 6 行 + 「展开全文」，避免一张 AI 报告把统计页整体挤下去。

## 3.4 数据流

```text
用户点击「生成本月报告」
  ↓
StatisticsViewModel.generateAiReport(period)
  ↓ (viewModelScope + Dispatchers.IO)
FinancialSummaryBuilder.build(period)                → FinancialSummary
  ↓
SummaryFormatter.canonicalJson() → SHA-256           → dataHash
  ↓
AiReportDao.findReusable(periodType, dataHash, model, minCreatedAt)
  ├── 命中 → 直接 Success（不发网络请求）
  └── 未命中 ↓
AiAdvisorRepository.buildMessages(summary)           → List<AiMessage>
  ↓
AiClient.streamChat()                                → Flow<AiChunk>
  ↓ collect 累积（UI 每 ~80ms 或每收到换行时刷新一次，避免逐字重组大字符串）
StatisticsViewModel._aiState = Streaming(text)
  ↓ Done
SuggestedBudgetExtractor.extract(text)               → Double?
  ↓
AiReportDao.insert(AiReportEntity)  +  pruneOld(keep = 20)
  ↓
_aiState = Success(report)
```

**Streaming 的重组性能**：不要在每个 delta 上做 `text + delta` 然后整串重新 Markdown 解析。做法：
- ViewModel 侧用 `StringBuilder` 累积；
- 用 `MutableStateFlow<String>` 配合 `sample(80.milliseconds)` 节流后再暴露给 UI；
- Markdown 渲染在 `remember(text)` 里做，节流后重算频率约 12 次/秒，可接受。

## 3.5 Prompt（修正版）

由 `data/repository/AiAdvisorRepository.kt` 统一构造，拆成 system + user 两条消息（比单条 user 更稳定）。

**system**：

```text
你是一位精通个人财务规划与行为心理学的专属财务顾问，服务于一款名为 SmartLedger 的个人记账应用。

工作原则：
1. 你收到的所有数字都已由客户端本地计算完成，必须直接引用，不得自行重算或修改。
2. 不得虚构输入中不存在的数据；输入中没有的信息，直接说明"数据不足"。
3. 商户名称已匿名化为编号，不要试图猜测或还原真实商户。
4. 不提供股票、基金、保险、借贷、虚拟货币等投资或金融产品建议。
5. 语言风格：克制、专业、客观、有建设性；不使用夸张情绪化表达，不说教。
6. 使用简洁的 Markdown 排版，只使用二级标题（##）、无序列表（-）和加粗（**），不要使用表格、代码块、引用块和图片。
7. 全文控制在 700 字以内。
```

**user**：

```text
请根据以下用户近期消费数据进行诊断，并按指定结构输出。

【用户财务数据】
{summary_text}          ← 即 2.7 节 SummaryFormatter 的完整输出

{low_sample_note}       ← 笔数 < 5 时追加："注意：本周期样本量较小（仅 N 笔），请谨慎下结论。"
{no_budget_note}        ← 未设预算时追加："注意：用户尚未设置本周期预算上限。"

【输出要求】

## 1. 消费结构诊断
分析刚需支出与弹性/享乐支出的比例是否合理，指出当前最核心的 1~2 个"漏财点"。必须引用上面的具体分类与金额。

## 2. 行为习惯画像
根据时段分布、工作日/周末差异、消费频次，提炼 2~3 个最明显的消费行为特征。

## 3. 动态预警与预测
根据预算、已发生支出和当前消费速度，判断本周期是否存在超支风险。若用户未设置预算，直接说明无法判断超支风险，并给出建议的预算区间。

## 4. 下周行动建议
只给出 3 条未来 7 天可直接执行的具体行动，每条必须包含可量化的目标（金额或次数）。禁止"少花钱""理性消费""记好每一笔"这类空泛表述。

## 5. 建议可用总额度
结合近期消费水平、当前收入与预算执行情况，给出下一个预算周期的建议可用总额度，并用一句话说明理由。

全文最后必须单独成行输出（不要放在列表或加粗中）：

建议可用总额度：¥数字

例如：
建议可用总额度：¥4800
```

分工再强调一次：

```text
AI 负责：解释、总结、行为诊断、建议、可读性
本地负责：所有金额、占比、日均、环比、预算达成率、超支日期
```

**参数**：`temperature = 0.7`，`max_tokens = 2048`，`stream = true`。

## 3.6 缓存（AiReportEntity）

```kotlin
// data/db/entity/AiReportEntity.kt
@Entity(
    tableName = "ai_reports",
    indices = [Index(value = ["periodStart", "periodEnd"])]
)
data class AiReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodType: String,        // SummaryPeriod.name
    val periodStart: Long,
    val periodEnd: Long,
    val periodLabel: String,
    val dataHash: String,          // SHA-256(SummaryFormatter.canonicalJson(summary))
    val model: String,
    val markdown: String,
    val suggestedBudget: Double?,
    val createdAt: Long
)

// data/db/dao/AiReportDao.kt
@Dao
interface AiReportDao {
    @Insert suspend fun insert(entity: AiReportEntity): Long

    /** 缓存命中：周期 + 数据指纹 + 模型 三者相同，且未过 TTL */
    @Query("""
        SELECT * FROM ai_reports
        WHERE periodType = :periodType AND dataHash = :dataHash
          AND model = :model AND createdAt >= :minCreatedAt
        ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun findReusable(periodType: String, dataHash: String,
                             model: String, minCreatedAt: Long): AiReportEntity?

    /** 用于 Idle 态展示"上次分析" */
    @Query("""
        SELECT * FROM ai_reports WHERE periodType = :periodType
        ORDER BY createdAt DESC LIMIT 1
    """)
    suspend fun findLatest(periodType: String): AiReportEntity?

    @Query("""
        DELETE FROM ai_reports WHERE id NOT IN
            (SELECT id FROM ai_reports ORDER BY createdAt DESC LIMIT :keep)
    """)
    suspend fun pruneOld(keep: Int)

    @Query("DELETE FROM ai_reports") suspend fun clearAll()
}
```

**关于 v1 缓存设计的问题（M8）**：v1 说"周期 + dataHash + model 相同则用缓存"。但**当月的 Summary 每记一笔账就会变**，`dataHash` 必然变化，缓存等于永不命中——用户每次点开都要重新烧一次 token。

v2 的缓存判定：

```text
命中条件（任一路径）：
  A. dataHash 完全相同 且 createdAt 在 TTL 内  → 直接复用（数据没变，没必要重跑）
  B. dataHash 不同                              → 不自动复用，但在 Idle 态显示
                                                  "上次分析：9月7日 18:30（数据已更新）"
                                                  + [查看上次报告] / [重新生成]

TTL 默认 6 小时，存在 ai_report_ttl_hours（预留，不做 UI）
「重新生成」永远绕过缓存
```

这样既避免重复烧 token，也不会给用户看过期结论。

`canonicalJson` 的稳定性要求（v1 没提，会导致 hash 抖动）：
- 字段顺序在代码里硬编码固定，**不要用反射或 Map 遍历**；
- 所有 `Double` 统一 `String.format(Locale.US, "%.2f", v)`（避免 `4280.0` 与 `4280.00` 产生不同 hash，也避免中文 Locale 下的分隔符差异）；
- 不包含 `now`、`createdAt` 等随调用变化的字段；
- `daysElapsed` **要**包含（同样的金额、不同的已过天数，结论应该不同）。

**清除**：`AiSettingsScreen` 的「清除配置与本地 AI 报告」调用 `clearAll()`。

## 3.7 建议额度提取

Prompt 要求最后一行固定格式，但模型实际输出会有偏差（全角 ￥、千分位、"约 4800 元"、加粗包裹）。Regex 要放宽：

```kotlin
// data/ai/SuggestedBudgetExtractor.kt —— 纯函数，有单测
object SuggestedBudgetExtractor {
    private val REGEX = Regex(
        """建议可用总额度\s*[：:]\s*\*{0,2}\s*[¥￥]?\s*约?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s*(?:元)?""",
        RegexOption.MULTILINE
    )

    /** 取最后一次匹配（AI 可能在正文里也提到过） */
    fun extract(markdown: String): Double? =
        REGEX.findAll(markdown).lastOrNull()
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            ?.takeIf { it > 0 && it <= 1_000_000 }
}
```

必须覆盖的单测输入：

```text
"建议可用总额度：¥4800"                    → 4800.0
"建议可用总额度：￥4,800"                   → 4800.0
"建议可用总额度: 4800 元"                   → 4800.0
"**建议可用总额度：¥4800.50**"              → 4800.5
"建议可用总额度：约 5000元"                 → 5000.0
"...正文提到建议可用总额度：¥5000...\n建议可用总额度：¥4800"  → 4800.0（取最后）
"没有这一行的报告"                          → null
"建议可用总额度：¥0"                        → null
"建议可用总额度：¥99999999"                 → null（超上限）
```

提取值写入 `AiReportEntity.suggestedBudget`。**该值只是建议，任何情况下都不自动写入 `budgets` 表**（见任务 6.4）。

## 3.8 Markdown 渲染（自研，不引依赖）

```kotlin
// ui/components/MarkdownText.kt
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseColor: Color = SmartLedgerColors.fg
)
```

支持的语法（**只支持这些，与 Prompt 里的排版约束严格对应**）：

| 语法 | 渲染 |
|---|---|
| `## 标题` | `titleMedium` + `SemiBold` + 上下留白 |
| `### 标题` | `titleSmall` + `Medium` |
| `**加粗**` | `FontWeight.Bold` |
| `- 列表项` / `* 列表项` | `• ` 前缀 + 缩进 |
| `1. 列表项` | 保留原编号 + 缩进 |
| 空行 | 段落间距 |
| 其他（表格/代码块/图片/链接） | 按纯文本原样输出，不崩 |

实现要点：按行切分 → 每行判类型 → 行内 `**` 用 `buildAnnotatedString` + `SpanStyle`。约 120 行。流式渲染时最后一行可能是 `**未闭合`，解析器必须容忍未闭合标记（当作普通文本）。

理由：引 Markdown 库（如 `compose-markdown` / `Markwon`）会带入 commonmark + 传递依赖，APK 增重且需要额外 R8 keep；而我们的输出格式是自己 Prompt 约束的，可控子集完全够用。项目里已有 `UpdateChecker.stripMarkdown()` 这种自己处理 Markdown 的先例。

## 3.9 生命周期、取消与超时

**问题（M7）**：`StatisticsViewModel` 走默认 `viewModel()` 作用域，底 tab 切换带 `saveState = true`，切走再切回 ViewModel 可能已重建，内存里的流式文本会丢。

处理：

1. **成功/中断的报告一律落库**，UI 重建后从 `AiReportDao.findLatest()` 恢复展示 —— 这是最关键的一条；
2. 生成任务跑在 `viewModelScope`，ViewModel 被清理时协程自动取消（不再浪费 token）；
3. 提供显式「停止生成」：`job?.cancel()`，把已生成部分按 `truncated = true` 落库；
4. 整体超时：`withTimeout(120_000)` 包住整个流式收集，超时也按"已生成部分落库 + 标注不完整"处理；
5. **不使用 WorkManager 做后台生成**（违背"AI 只在用户主动触发时调用"的边界，也会带来后台联网的合规问题）。

## 3.10 任务 3 验收

```text
□ 只有点击按钮才发起网络请求（用抓包或在 AiClient 打点确认，进入/切换统计页 0 请求）
□ Streaming 文字逐段出现，滚动跟随，界面不卡顿（低端机实测）
□ Markdown 的 ## / **粗体** / - 列表 渲染正确，未闭合 ** 不崩
□ 生成中切到首页再切回统计页：不崩，报告内容可从缓存恢复
□ 「停止生成」立即停止，已生成部分保留并落库，标注"内容可能不完整"
□ 相同数据 6 小时内再点「生成」不发网络请求（命中缓存）
□ 记一笔新账后，Idle 态提示"数据已更新"，点「重新生成」发新请求
□ AI 请求体只包含 FinancialSummary 派生文本（打点 dump 请求体人工核对一次）
□ 能从报告中识别出「建议可用总额度」并展示 [采纳为下月预算]
□ 周期内无交易时不允许生成，提示"本月还没有记账数据"
□ 未设预算时 AI 报告第 3 节明确说明无法判断超支风险
□ API 401 / 429 / 超时 / 断网 四种失败下，统计页环形图与分类排行完全正常
□ ai_reports 超过 20 条后自动清理最旧记录
```

---

# 任务 4：RecordScreen 状态提升（v2 新增的前置重构，不含 AI）

## 4.1 为什么必须单独做这一步

v1 写"AI 解析后回填现有 RecordScreen"，但现有 `RecordScreen` 的 6 个字段全是 Composable 内部的 `remember`（`transactionType / amountText / selectedCategory / merchant / note / paymentMethod`），**外部没有任何写入口**；而且**根本没有日期字段**——界面上那个"9月7日"是在 Composable 里直接 `Calendar.getInstance()` 渲染出来的死值，`RecordViewModel.saveTransaction()` 也没有时间参数，硬编码 `System.currentTimeMillis()`。

所以"回填"实际上包含两件事：状态提升 + 新增日期能力。这两件事会改动**用户最高频使用的手工记账页**，风险独立于 AI，因此拆成独立一步：**先重构、先回归，再叠 AI**。做完这一步时，记账页的行为应当与现在完全一致（多出一个可选的日期选择）。

## 4.2 RecordUiState 与状态提升

```kotlin
// ui/record/RecordUiState.kt
data class RecordUiState(
    val transactionType: String = "expense",   // "expense" / "income"
    val amountText: String = "0",
    val selectedCategoryId: Long? = null,
    val merchant: String = "",
    val note: String = "",
    val paymentMethod: String = "微信",
    /** 交易发生时间；默认今天，AI 解析或用户手选可改 */
    val transactionTime: Long = System.currentTimeMillis()
)
```

`RecordViewModel` 增加：

```kotlin
private val _uiState = MutableStateFlow(RecordUiState())
val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

fun setType(type: String)                    // 切类型时清空 selectedCategoryId（保持现有行为）
fun setAmountKey(key: String)                // 复用现有 handleKeyPress 逻辑，移入 ViewModel
fun setAmountText(text: String)              // 供 AI 回填直接写数值
fun setCategoryId(id: Long?)
fun setMerchant(v: String)
fun setNote(v: String)
fun setPaymentMethod(v: String)
fun setTransactionTime(ms: Long)
fun resetAfterSaved()                        // 清空除 paymentMethod 外的字段（保持现有"保留上次渠道"行为）
```

`RecordScreen` 改为 `val state by viewModel.uiState.collectAsState()`，所有 `remember` 删除，事件回调改调 ViewModel。

**`handleKeyPress` 迁移**：现在是 `RecordScreen.kt` 里的 private 顶层函数，逻辑（`⌫` 回退、小数点唯一、最多 2 位小数、前导 0 替换）**必须原样搬进 ViewModel，不要重写**，并补一个单测锁住行为，防重构回归。

**`getCategoryIcon` 保留在 UI 层不动。**

## 4.3 日期选择

`RecordScreen` 现有的日期显示行（`Icons.Outlined.Info` + "9月7日"）改成可点击：

```text
📅 9月7日  ›        ← 点击弹出选择
```

弹出层用一个轻量的 `SmartLedgerDialog` + 三个快捷项 + 系统日期选择器：

```text
选择日期
  今天（9月7日）
  昨天（9月6日）
  前天（9月5日）
  选择其他日期…    → android.app.DatePickerDialog
[取消]
```

理由：不引 Material3 的 `DatePicker`（Compose M3 的 DatePicker 需要 `@OptIn(ExperimentalMaterial3Api)` 且样式与本项目的自定义灰阶主题不协调），用系统 `DatePickerDialog` 与项目现有风格（大量使用系统 Intent/Dialog）一致，且 90% 场景靠三个快捷项就够了。

**时间部分的处理规则**（v1 完全没提，直接影响"夜间消费"统计准确性）：

```text
选择"今天"        → transactionTime = System.currentTimeMillis()（保留当前时刻）
选择"昨天/前天/其他" → 保留用户当前的时刻（时/分），只替换年月日
                     即 transactionTime = 该日期 + 当前的 HH:mm
```

这样"昨天晚上吃饭"记出来的时间落在昨天的晚间桶，而不是昨天 00:00。AI 解析若能给出时间（如"昨晚"），可进一步覆盖（见 5.5）。

**约束**：不允许选择未来日期（与 `HomeViewModel.nextMonth()` 不允许翻未来月的现有约定一致），`DatePickerDialog` 设 `maxDate = 今天`。

## 4.4 saveTransaction 签名变更

```kotlin
fun save(onSuccess: () -> Unit, onInvalid: (String) -> Unit) {
    val s = _uiState.value
    val amount = s.amountText.toDoubleOrNull()
    when {
        amount == null || amount <= 0 -> return onInvalid("请输入有效金额")
        amount > 1_000_000 -> return onInvalid("金额过大，请检查")
        else -> viewModelScope.launch {
            transactionRepo.insert(
                Transaction(
                    amount = amount,
                    type = s.transactionType,
                    categoryId = s.selectedCategoryId,
                    merchant = s.merchant.ifBlank { null },
                    paymentMethod = s.paymentMethod.trim().ifBlank { "其他" },
                    note = s.note.ifBlank { null },
                    source = "manual",                    // ← AI 辅助填写仍算 manual，见下
                    transactionTime = s.transactionTime   // ← 新增
                )
            )
            resetAfterSaved()
            onSuccess()
        }
    }
}
```

**`source` 取值决策**：AI 只是帮用户填表单，最终由用户确认并点击保存，**语义上仍是手工记账**。但为了后续能评估 AI 解析质量，需要区分。方案：

- `source` 保持 `"manual"`（**不新增枚举值**）——因为 `CsvExporter` 里写着 `if (t.source == "auto") "自动" else "手动"`，`CsvImporter` 和统计逻辑也依赖这个二元判断，新增值会污染导出/导入/统计；
- AI 辅助的痕迹记在 `note` 之外的地方：在 `SharedPreferences` 里累加两个计数器 `ai_parse_total` / `ai_parse_accepted`，仅用于自查，不入库、不展示。

> 现有 `onInvalid` 场景当前是静默失败（`if (amount != null && amount > 0)` 不满足就什么都不做）。这次顺手补上 Toast 提示，属于合理的小改进，但要写进回归清单。

## 4.5 任务 4 验收（纯回归）

```text
□ 支出/收入切换、分类选择、数字键盘（含 ⌫ / 小数点 / 两位小数上限）行为与改造前完全一致
□ 保存后金额归零、分类清空、商户/备注清空、支付渠道保留（与现有行为一致）
□ 保存成功后跳回首页，首页金额立即更新
□ 金额为 0 或非法时给出提示（新增），不再静默失败
□ 日期默认今天；选昨天后保存，首页归到昨天分组，且时刻不是 00:00
□ 无法选择未来日期
□ 连续记 3 笔，每笔日期/金额/分类互不串台（状态提升后无残留）
□ handleKeyPress 单测通过（12 个按键序列用例）
□ 横竖屏切换 / 后台回前台 后表单内容不丢失（状态在 ViewModel，此为附带收益）
```

---

# 任务 5：自然语言 / 语音快捷记账

## 5.1 入口与 UI

入口在 `RecordScreen` 顶部（类型切换之下、金额之上），**不新增一级页面**。

未配置 AI 时**不显示**这个入口（避免给用户一个点了就报错的按钮）。

```text
┌─────────────────────────────────────────┐
│ 🎙  说一句或输入一句话快速填写           │
│    例如：昨天老乡鸡32元微信              │
└─────────────────────────────────────────┘
                              [ AI 解析 ]
```

交互细节：

- 输入框单行、`imeAction = Done`，回车等同点「AI 解析」；
- 输入为空时「AI 解析」禁用；
- 解析中：按钮变 `解析中…` loading，输入框禁用，可点「取消」；
- 解析成功：输入框**保留原文**（方便用户对照检查/改词重试），下方出现一行反馈：
  ```
  ✓ 已填入：昨天 · 餐饮 · 老乡鸡 · ¥32 · 微信
  ```
- 解析部分成功：
  ```
  ⚠ 部分信息未识别（未识别：支付渠道），请手工补充
  ```
- 解析失败：
  ```
  ✗ 无法识别，请手工填写   [重试]
  ```
- **失败时绝不清空用户已填的任何内容**。

## 5.2 语音方案（修正 v1 的 M3）

v1 写"Android SpeechRecognizer + RECORD_AUDIO 权限"。实际问题：

1. Android 11+ 的包可见性限制下，不在 Manifest 声明 `<queries>` 时 `SpeechRecognizer.isRecognitionAvailable()` **恒返回 false**；
2. 大量国产 ROM 没有预装 `RecognitionService`（或需要 Google 服务），直接持有 `SpeechRecognizer` 会静默失败；
3. 申请 `RECORD_AUDIO` 会触发麦克风权限弹窗，对一个记账 App 来说是相当高的用户心理成本，且各应用市场对麦克风权限审核趋严。

**v2 方案：`ACTION_RECOGNIZE_SPEECH` Intent 优先，降级到纯文字。**

```kotlin
// ui/record/SpeechInput.kt
object SpeechInput {
    fun isAvailable(context: Context): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出这笔消费，例如：昨天老乡鸡32元微信")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
}
```

Compose 侧用 `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())`，从 `RecognizerIntent.EXTRA_RESULTS` 取第一条填入输入框。

优势：

- **不需要 `RECORD_AUDIO` 权限**（录音由系统语音应用完成）；
- 不需要处理录音状态、音量回调、超时；
- 用户看到的是熟悉的系统语音面板。

代价：多一次 Activity 跳转；无法做"边说边显示"。对"说一句话记一笔"的场景完全够用。

Manifest 需要新增（用于 `queryIntentActivities` 探测，Android 11+ 必须）：

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
    <intent>
        <action android:name="android.speech.action.RECOGNIZE_SPEECH" />
    </intent>
</queries>
```

**不新增 `RECORD_AUDIO` 权限。**

降级策略：`isAvailable() == false` → 隐藏 🎙 图标，只保留文字输入，不弹任何错误。

`ActivityNotFoundException` / 用户取消 → 静默返回，不提示错误。

**录音文件绝不上传**：SmartLedger 全程拿不到音频，只拿到系统返回的文本。这一点写进隐私政策。

## 5.3 AI 解析 Prompt（修正版）

调用 `AiClient.chat()`，`temperature = 0.2`，`max_tokens = 300`，`stream = false`。

**system**：

```text
你是 SmartLedger 的记账字段解析器。你只输出一个 JSON 对象，不输出任何其他内容。
不要输出 Markdown 代码块标记，不要输出解释、前言或后记。
```

**user**：

```text
从用户输入中提取一笔交易信息。

当前日期时间：{current_datetime}      例：2026-09-07 14:30（周一）
时区：Asia/Shanghai

可用分类（必须原样从下面选一个，不得自造）：
- 支出分类：{expense_categories}      例：餐饮、交通、购物、娱乐、居住、通讯、医疗、教育、日用、其他
- 收入分类：{income_categories}       例：工资、理财、红包、转账、其他

可用支付渠道（必须原样从下面选一个）：
微信、支付宝、云闪付、现金、银行卡、抖音、京东

用户输入：
{user_text}

严格按以下 JSON 格式输出：

{
  "amount": 32.0,
  "type": "expense",
  "category": "餐饮",
  "channel": "微信",
  "merchant": "老乡鸡",
  "note": "同事AA",
  "date": "2026-09-06",
  "time": "12:30"
}

规则：
1. 无法确定的字段一律输出 null，禁止猜测。
2. type 只能是 "expense" 或 "income"（小写）。
3. category 必须来自上面对应 type 的分类列表；若都不匹配，输出 null（不要输出"其他"来凑）。
4. channel 必须来自上面的渠道列表；未提及则输出 null。
5. amount 为正数，单位元；"32块""32元""三十二"都解析为 32.0。
6. "今天/昨天/前天/上周三/9月5日"等按当前日期换算为 date（yyyy-MM-dd）；未提及日期则输出今天。
7. "上午/中午/下午/晚上/昨晚/凌晨"等换算为 time（HH:mm，取该时段中点：上午09:00、中午12:00、下午15:00、晚上20:00、凌晨02:00）；未提及则输出 null。
8. merchant 只填商家/店名，不要把"和同事""AA"等写进 merchant。
9. note 填补充信息（如同行人、事由），没有则 null。
10. 只输出 JSON。
```

**变化点（相对 v1）**：

| v1 | v2 | 原因 |
|---|---|---|
| `"type": "EXPENSE"` | `"type": "expense"` | 与 DB 实际值一致，省掉一层易错映射（修正 C4） |
| 单一 `{categories}` | 按 type 分开给两份 | 分类 name 在 expense/income 下会重名（修正 C6） |
| `"remark"` | `"note"` | 与 `Transaction.note` 字段名对齐，减少心智负担 |
| 无 `time` | 新增 `time` | 支撑"昨晚"这类输入，且直接影响夜间消费统计 |
| 分类不匹配时行为未定义 | 明确输出 null | 避免 AI 用"其他"糊过去，掩盖识别失败 |
| 渠道列表 6 项（含"其他"） | 用 `PaymentMethods.PRESETS` 的真实 7 项 | 与 `PaymentChannelPicker` 一致 |

分类列表**从数据库动态读取**（`categoryDao.getAllOnce()` 按 type 分组），不要在 Prompt 里硬编码——用户可以在「分类管理」里增删分类。

## 5.4 JSON 提取容错（M9）

模型经常返回 ` ```json {...} ``` ` 或前后带一句话。必须先做健壮提取：

```kotlin
// data/ai/JsonExtractor.kt —— 纯函数，有单测
object JsonExtractor {
    /**
     * 从模型原始输出中抽出第一个完整 JSON 对象。
     * 处理顺序：
     *  1. 去掉 ```json / ``` 围栏（含大小写、含 ```JSON）
     *  2. 定位第一个 '{'，用括号配对找到与之匹配的 '}'（需跳过字符串字面量内的花括号与转义）
     *  3. 返回该子串；找不到返回 null
     */
    fun extractObject(raw: String): String?
}
```

必须覆盖的单测输入：

```text
"{\"amount\":32}"                                  → 提取成功
"```json\n{\"amount\":32}\n```"                     → 提取成功
"```\n{\"amount\":32}\n```"                          → 提取成功
"好的，解析结果如下：\n{\"amount\":32}\n希望有帮助"   → 提取成功
"{\"note\":\"备注里有个 } 括号\"}"                    → 提取成功（字符串内花括号不误判）
"{\"note\":\"含转义\\\"引号\"}"                       → 提取成功
"抱歉我无法解析"                                     → null
"{\"amount\":32"                                    → null（不完整）
```

## 5.5 AiTransactionParser（提取 → 校验 → 映射）

```kotlin
// data/ai/model/TransactionDraft.kt
data class TransactionDraft(
    val amount: Double?,
    val type: String?,            // "expense" / "income"
    val categoryId: Long?,        // 已映射为本地 id
    val categoryName: String?,
    val channel: String?,
    val merchant: String?,
    val note: String?,
    val transactionTime: Long?,   // date + time 合成
    /** 未能识别的字段名，用于 UI 提示 */
    val missingFields: List<String>
) {
    val isUsable: Boolean get() = amount != null && amount > 0
}

// data/ai/AiTransactionParser.kt —— 纯函数（除了传入的分类表），有单测
object AiTransactionParser {

    sealed interface Result {
        data class Ok(val draft: TransactionDraft) : Result
        data object NotJson : Result          // 提不出 JSON
        data object NoAmount : Result         // 提出来了但没有可用金额
    }

    fun parse(
        rawModelOutput: String,
        categories: List<Category>,     // 全量本地分类
        now: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Result
}
```

**逐字段校验规则（v1 只列了 5 条，这里给完整判定表）**：

| 字段 | 校验 | 不通过时 |
|---|---|---|
| `amount` | `Double` 或可转数字的 `String`；`> 0`；`<= 1_000_000`；非 NaN/Infinity；四舍五入到 2 位小数 | 置 null → 返回 `NoAmount` |
| `type` | 只接受 `"expense"` / `"income"`（**大小写不敏感地归一到小写**，兜住 AI 返回 `EXPENSE`） | 默认 `"expense"`，加入 `missingFields` |
| `category` | 在 `categories.filter { it.type == resolvedType }` 里按 `name` **精确匹配** → 取 `id` | 置 null，加入 `missingFields`（**不要模糊匹配，避免"餐饮"匹配到"餐饮外卖"这类用户自建分类时选错**） |
| `channel` | 在 `PaymentMethods.PRESETS` 里精确匹配 | 置 null，UI 保持当前渠道（不覆盖用户上次选择），加入 `missingFields` |
| `merchant` | 去首尾空白；长度截断到 30 字符；剔除纯标点 | 置 null（不算 missing，商户是可选项） |
| `note` | 去首尾空白；长度截断到 50 字符 | 置 null（不算 missing） |
| `date` | `yyyy-MM-dd` 严格解析（`isLenient = false`）；**不允许未来日期**（超过今天则钳到今天）；不早于 2000-01-01 | 用今天 |
| `time` | `HH:mm` 严格解析，`0<=H<=23`，`0<=M<=59` | 用"当前时刻"（若 date 是今天）或 `12:00`（若 date 是过去某天，避免默认 00:00 落进夜间桶） |
| `transactionTime` | `date + time` 按本地时区合成毫秒 | — |

**`missingFields` 的用途**：UI 上生成"未识别：支付渠道、分类"这样的提示，并且**把光标/焦点引导到第一个缺失项**（如缺分类则不自动跳转，只在分类区域高亮）。

**回填规则（重要）**：

```text
只覆盖解析出非 null 的字段，null 字段保持表单现值。
特别地：
  - channel 为 null 时保留用户上次使用的渠道（现有"连续记账保留渠道"行为）
  - type 影响分类列表，必须先 setType 再 setCategoryId
  - amount 用 setAmountText（不是逐键模拟），并格式化为最多 2 位小数、去掉末尾 .0
```

## 5.6 最终保存流程

```text
用户输入 / 语音转文字
    ↓
点击「AI 解析」
    ↓
AiClient.chat()                     ← 唯一的网络调用
    ↓
JsonExtractor.extractObject()
    ↓
AiTransactionParser.parse()         → TransactionDraft
    ↓
RecordViewModel.applyDraft(draft)   → 写入 RecordUiState
    ↓
用户检查 / 修改任意字段              ← 表单完全可编辑
    ↓
点击「记一笔」
    ↓
RecordViewModel.save()  →  TransactionRepository.insert()
```

**AI 绝不能直接保存 Transaction**：`applyDraft` 只写 `_uiState`，物理上没有调用 `insert` 的路径。Code Review 检查点：`AiTransactionParser` 与 `applyDraft` 所在文件不得 import `TransactionRepository` / `TransactionDao`。

## 5.7 与 SmartCategorizer 的联动

现有 `SmartCategorizer.saveMerchantCategory(context, merchant, categoryId)` 会记住"商户 → 分类"映射，供自动记账使用（`HomeViewModel.updateTransactionCategory` 里已在用）。

AI 快捷记账保存成功后，若同时有 `merchant` 和 `categoryId`，**也调用一次** `saveMerchantCategory`。这样用户用一次自然语言记账，之后同名商户的自动记账也能正确归类——一处小改动带来明显的复利收益。

**注意**：只在用户**确认保存后**才写映射，不在 AI 解析出结果时写（AI 可能猜错，不能污染自动记账的分类规则）。

## 5.8 任务 5 验收

必测输入（覆盖各类边界）：

| 输入 | 期望解析 |
|---|---|
| `今天麦当劳28元` | 28 / expense / 餐饮 / 商户=麦当劳 / 渠道=null（保留原值）/ 今天 |
| `昨天滴滴35块微信` | 35 / expense / 交通 / 滴滴 / 微信 / 昨天 |
| `前天淘宝219支付宝` | 219 / expense / 购物 / 淘宝 / 支付宝 / 前天 |
| `今天工资到账12000` | 12000 / **income** / 工资 / 今天 |
| `上午瑞幸18.5` | 18.5 / expense / 餐饮 / 瑞幸 / 今天 09:00 |
| `昨晚和朋友吃饭AA我付68` | 68 / expense / 餐饮 / merchant=null / note 含"AA"或"朋友" / 昨天 20:00 |
| `昨天中午跟同事在老乡鸡吃快餐AA花了32微信付款` | 32 / expense / 餐饮 / 老乡鸡 / 微信 / 昨天 12:00 |
| `买了个东西` | `NoAmount` → 提示无法识别，表单不变 |
| `充话费50` | 50 / expense / 通讯 / 今天 |
| `明天预付房租3000` | 3000 / expense / 居住 / **日期钳到今天**（不允许未来） |

验收清单：

```text
□ AI 未配置时不显示自然语言入口
□ 语音不可用的设备上不显示 🎙，且无报错
□ 语音识别结果正确填入输入框，用户仍需点「AI 解析」（不自动解析、不自动保存）
□ 语音与文字走完全相同的解析链路（同一个函数）
□ 解析后表单被正确回填，用户可修改任意字段
□ 分类只能命中本地已有分类；AI 返回不存在的分类时不填、并提示"未识别：分类"
□ AI 返回 "EXPENSE" 大写时能正确归一到 expense
□ AI 返回带 ```json 围栏时能正确解析
□ AI 返回完全非法 JSON 时提示失败，表单内容零丢失
□ AI 返回未来日期时钳到今天
□ 解析中点「取消」立即中止，无残留 loading
□ AI 请求失败（401/超时/断网）后，普通手工记账链路完全可用
□ 保存后 SmartCategorizer 记住了商户→分类映射（再记同商户时分类自动带出）
□ 全部 10 条必测输入的实际表现记录在 PR 描述里（含失败的 case 和原因）
```

---

# 任务 6：动态预算与超支预测

## 6.1 目标

**完全本地计算，不调用 AI，可离线。**

月度可用总额度的来源只有两种：

```text
1. 用户手工设置（现有「预算」页 / 首页预算卡）
2. 用户主动采纳 AI 消费体检中的「建议可用总额度」（必须二次确认）
```

**AI 不能自动改预算。**

## 6.2 Budget 扩展（修正 C10 / C11）

任务 0.1 的迁移已加好 `source` 字段与 `yearMonth` 唯一索引。

```kotlin
// data/db/entity/BudgetSource.kt
enum class BudgetSource { MANUAL, AI_SUGGESTED }
```

`BudgetDao` 追加：

```kotlin
@Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth LIMIT 1")
fun observeBudget(yearMonth: String): Flow<Budget?>       // 已有 observeBudgetByMonth，复用即可

@Query("""
    INSERT INTO budgets (yearMonth, totalIncomeTarget, totalExpenseLimit, source, createdAt)
    VALUES (:yearMonth, NULL, :limit, :source, :createdAt)
    ON CONFLICT(yearMonth) DO UPDATE SET
        totalExpenseLimit = :limit,
        source = :source
""")
suspend fun upsertExpenseLimit(yearMonth: String, limit: Double, source: String, createdAt: Long)
```

> 用 SQLite 的 `ON CONFLICT ... DO UPDATE`（3.24+，Android 8.0/API 26 起可用，与 `minSdk 26` 匹配）一次写完，避免"先查再插/改"的竞态。注意 `DO UPDATE` 里不要动 `totalIncomeTarget`，否则会把用户设的收入目标清掉。

`BudgetRepository` 暴露：

```kotlin
fun observeBudget(yearMonth: String): Flow<Budget?>
suspend fun setExpenseLimit(yearMonth: String, limit: Double, source: BudgetSource)
```

**`BudgetViewModel` 的当月硬编码（C11）**：把 `currentYearMonth` 从构造期常量改为构造参数/可变状态，并新增 `saveExpenseLimitFor(yearMonth, limit, source)`。这样"采纳为下月预算"可以写 `DateUtil.shiftYearMonth(current, +1)`。

## 6.3 BudgetPredictor（纯函数，全部有单测）

```kotlin
// util/BudgetPredictor.kt —— object，不接 Context，可纯 JVM 单测
object BudgetPredictor {

    enum class Status { NO_BUDGET, SAFE, WARNING, DANGER }

    data class Result(
        val status: Status,
        val budget: Double?,
        val currentExpense: Double,
        /** 剩余额度，负数钳到 0 */
        val remainingBudget: Double,
        /** 含今天的剩余可消费天数 */
        val remainingDays: Int,
        /** 今日建议可用；无预算时为 null */
        val dailyAvailable: Double?,
        /** 已过天数（当月=今天几号） */
        val daysElapsed: Int,
        val daysTotal: Int,
        val averageDailyExpense: Double,
        val predictedMonthExpense: Double,
        /** 预计达到预算上限的「日」；无风险或无预算时 null；已超支时 <= daysElapsed */
        val projectedBudgetDay: Int?,
        val usedPercent: Double?
    )

    fun predict(
        budget: Double?,
        currentExpense: Double,
        daysElapsed: Int,          // 1..daysTotal
        daysTotal: Int
    ): Result
}
```

**公式与边界（v1 的公式在几处会除零或给出荒谬结果，这里补齐）**：

```text
remainingDays   = (daysTotal - daysElapsed + 1)            // 今天计入
remainingBudget = max(budget - currentExpense, 0)
dailyAvailable  = remainingBudget / remainingDays          // remainingDays >= 1 恒成立

averageDailyExpense   = currentExpense / max(daysElapsed, 1)
predictedMonthExpense = averageDailyExpense * daysTotal

projectedBudgetDay:
    averageDailyExpense <= 0        → null（当月没花钱，无法预测）
    predicted <= budget             → null（无超支风险，不显示日期）
    else                            → ceil(budget / averageDailyExpense).toInt()
                                       .coerceIn(1, daysTotal)

status:
    budget == null || budget <= 0        → NO_BUDGET
    currentExpense > budget             → DANGER
    predicted > budget                  → WARNING
    else                                → SAFE
```

**边界用例表（单测必须逐条覆盖）**：

| 场景 | budget | currentExpense | daysElapsed / daysTotal | 期望 |
|---|---|---|---|---|
| 未设预算 | null | 1000 | 7/30 | `NO_BUDGET`，`dailyAvailable = null` |
| 预算为 0 | 0 | 100 | 7/30 | `NO_BUDGET`（0 视为未设置） |
| 当月无支出 | 5000 | 0 | 7/30 | `SAFE`，`averageDaily = 0`，`projectedBudgetDay = null`，`dailyAvailable = 5000/24` |
| 月初 1 号大额固定支出 | 5000 | 3000 | 1/30 | `DANGER`? → 不，`currentExpense(3000) <= budget` 且 `predicted = 90000 > 5000` → `WARNING`，`projectedBudgetDay = ceil(5000/3000) = 2` |
| 月中正常 | 5000 | 2400 | 15/30 | `predicted = 4800 <= 5000` → `SAFE` |
| 月中偏快 | 5000 | 2800 | 15/30 | `predicted = 5600 > 5000` → `WARNING`，`projectedBudgetDay = ceil(5000/186.67) = 27` |
| 刚好达到 | 5000 | 5000 | 30/30 | `currentExpense > budget` 为假 → `predicted = 5000` → `SAFE`，`remainingBudget = 0`，`dailyAvailable = 0` |
| 已超支 | 5000 | 5600 | 20/30 | `DANGER`，`remainingBudget = 0`，`dailyAvailable = 0`，`projectedBudgetDay = ceil(5000/280) = 18 <= 20` |
| 月末最后一天 | 5000 | 4900 | 30/30 | `SAFE`，`remainingDays = 1`，`dailyAvailable = 100` |
| 2 月 28 天 | 3000 | 1500 | 14/28 | `predicted = 3000` → `SAFE` |

**`daysElapsed` / `daysTotal` 的来源**（由调用方算，`BudgetPredictor` 只做纯计算）：

```text
当月：
  daysTotal   = Calendar.getActualMaximum(DAY_OF_MONTH)
  daysElapsed = Calendar.get(DAY_OF_MONTH)      // 今天几号

历史月份（首页翻到过去的月）：
  daysTotal = daysElapsed = 该月总天数          → 预测退化为"实际值"，不显示预测/超支日期
```

**"月初大额固定支出干扰预测"**：v1 说"真实使用一段时间后再做第二版算法"。这个判断是对的，第一版**不做加权**。但上表第 4 行说明，1 号交房租会直接算出"预计月末 90000"这种荒谬数字。所以第一版加一条**展示层护栏**（不改算法）：

```text
daysElapsed < 5 时：
  - 不显示「预计月底 ¥X」
  - 不显示超支日期
  - 只显示「本月已用 ¥3,000 / ¥5,000」+「今日建议可用 ¥69」
  - 附一行小字：「月初数据较少，预测将在 5 日后显示」
```

这条护栏成本极低，能避免第一版上线就被用户当成 bug。

## 6.4 采纳 AI 建议额度

AI 报告底部：

```text
建议可用总额度：¥4,800

[采纳为下月预算]
```

点击后弹 `SmartLedgerInputDialog`（**允许用户在确认前改金额**，v1 已提到，这里明确用哪个组件）：

```text
设置下月可用额度

  2026 年 10 月支出上限
  ┌────────────────────┐
  │ 4800               │      ← 预填 AI 建议值，可改
  └────────────────────┘
  来自 AI 建议，你可以修改后再确认

[取消]  [确认]
```

确认后：

```kotlin
budgetRepository.setExpenseLimit(
    yearMonth = DateUtil.shiftYearMonth(DateUtil.getCurrentYearMonth(), 1),
    limit = userEditedValue,
    source = if (userEditedValue == suggested) BudgetSource.AI_SUGGESTED else BudgetSource.MANUAL
)
```

> 用户改过金额就记 `MANUAL`——因为那已经是用户自己的决定了，`AI_SUGGESTED` 只标记"原样采纳"，这样 `source` 字段将来才有分析价值。

采纳成功后 Toast「已设置 2026 年 10 月可用额度 ¥4,800」，并在报告卡里把按钮变成「已采纳为 10 月预算」（不可重复点，除非重新生成报告）。

**校验**：`0 < limit <= 1_000_000`，两位小数。

## 6.5 HomeScreen 预算卡

插入位置：`MonthSwitcher` 与 `BalanceSection` 之间（预算是"这个月能花多少"，紧跟月份切换最自然）。

**当月 + 已设预算 + daysElapsed >= 5**：

```text
┌──────────────────────────────────────────┐
│ 本月可用额度                    9月 7日  │
│                                          │
│ ¥4,280 / ¥5,000                          │
│ ████████████████████░░░░  85.6%          │
│                                          │
│ 剩余额度          今日建议可用            │
│ ¥720              ¥30.00                 │
│                                          │
│ 预计月底 ¥5,136                           │
│ ⚠ 按当前节奏，预计 9 月 27 日达到上限     │
└──────────────────────────────────────────┘
```

**当月 + 已设预算 + daysElapsed < 5**：

```text
│ ¥3,000 / ¥5,000    ████████████░░░░ 60%  │
│ 剩余额度 ¥2,000    今日建议可用 ¥76.92    │
│ 月初数据较少，支出预测将在 5 日后显示     │
```

**当月 + 未设预算**：

```text
┌──────────────────────────────────────────┐
│ 还没有设置本月可用额度                    │
│ 设置后可查看每日建议额度与超支预测        │
│ [设置本月可用额度]                        │
└──────────────────────────────────────────┘
```

**历史月份（v1 未定义，M 补）**：翻到过去的月份时，"今日建议可用""预计月底""超支日期"全部无意义。此时只显示：

```text
┌──────────────────────────────────────────┐
│ 2026 年 8 月预算执行                      │
│ ¥4,850 / ¥5,000   ███████████████░ 97.0% │
│ ✓ 未超支（结余 ¥150）                     │
└──────────────────────────────────────────┘
```

历史月份未设预算时，整张卡**不显示**（不要给用户一个"给 3 个月前补设预算"的入口，无意义且容易误操作）。

**状态配色**（复用 `SmartLedgerColors`，不引新色）：

| Status | 进度条颜色 | 说明文字颜色 |
|---|---|---|
| `SAFE` | `income` | `fgSecondary` |
| `WARNING` | `accent` | `accent` |
| `DANGER` | `expense` | `expense` |
| `NO_BUDGET` | — | `fgSecondary` |

**HomeViewModel 改动**：

```kotlin
private val budgetRepo = (application as SmartLedgerApp).budgetRepository

/** 跟随 selectedYearMonth 的预算 */
private val selectedBudget: StateFlow<Budget?> = _selectedYearMonth
    .flatMapLatest { budgetRepo.observeBudget(it) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

val budgetState: StateFlow<BudgetPredictor.Result?> = combine(
    dateTick, _selectedYearMonth, selectedBudget, monthExpense
) { _, ym, budget, expense ->
    val isCurrent = ym == DateUtil.getCurrentYearMonth()
    val cal = Calendar.getInstance().apply { time = /* ym 的某一天 */ }
    val daysTotal = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val daysElapsed = if (isCurrent) Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else daysTotal
    BudgetPredictor.predict(budget?.totalExpenseLimit, expense, daysElapsed, daysTotal)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

fun setMonthlyExpenseLimit(limit: Double) { /* 写当前 selectedYearMonth */ }
```

复用现有的 `UI_DEBOUNCE_MS` 去抖模式，避免记账后首页多次重组。

`dateTick` 已存在（`refreshDateRange()` 在 `onResume` 调用），跨日回到前台时 `daysElapsed` 会自动更新，这条现有机制正好能用。

## 6.6 任务 6 验收

```text
□ 未设置预算时首页显示「设置本月可用额度」，点击可设置并立即生效
□ 手工额度可反复修改，修改后进度条/剩余/今日建议同步更新
□ AI 建议额度必须经二次确认才生效，且确认前可改金额
□ 采纳后写入的是「下月」的 budgets 行，当月预算不受影响
□ budgets 表同一 yearMonth 只有一行（唯一索引生效）
□ 记一笔账后首页预算卡数字立即更新（不需要重启）
□ 每日可用额度 = (预算 - 已支出) / 含今天的剩余天数，手算核对 3 个日期
□ 月末最后一天：remainingDays = 1，今日建议可用 = 剩余额度
□ 已超支时显示 DANGER 配色 + 「本月预算已超支」
□ 月初 1~4 号不显示月末预测与超支日期，显示提示文案
□ 翻到历史月份显示「执行情况」形态，无今日/预测字样
□ 历史月份未设预算时不显示预算卡
□ 2 月（28/29 天）计算正确
□ 飞行模式下预算卡功能完全正常（纯本地）
□ BudgetPredictor 单测覆盖 6.3 节全部 10 个边界用例
```

---

# 任务 7：测试与回归

## 7.1 测试策略

项目当前**零测试基础设施**。第一版不追求覆盖率，只做**成本最低、防回归收益最高**的一层：**纯 JVM 单测**（`app/src/test`，仅依赖 `junit:junit:4.13.2`）。

不做的：
- ❌ MockWebServer（改用 `FakeHttpEngine`）
- ❌ Robolectric（所有被测逻辑不接 `Context`）
- ❌ Room `MigrationTestHelper`（`exportSchema = false`，改为手工回归）
- ❌ Compose UI 测试（本阶段人工回归）

这是任务 0.3 里那些"纯函数"设计约束的**目的**：让核心逻辑可以零成本测试。

## 7.2 单测清单

| 被测对象 | 用例数 | 关键用例 |
|---|---|---|
| `BaseUrlNormalizer` | ~8 | 无协议头、尾斜杠、贴了完整 endpoint、`compatible-mode/v1`、`/openai`、明文检测 |
| `SseParser` | ~10 | 正常 delta、首包只有 role、`[DONE]`、`data:` 无空格、心跳空行、注释行、非法 JSON、`event:` 行、多字节字符被切断 |
| `JsonExtractor` | ~8 | 见 5.4 节列表 |
| `SuggestedBudgetExtractor` | ~9 | 见 3.7 节列表 |
| `MerchantAnonymizer` | ~6 | 同名商户同编号、跨分类编号独立、分类为空、空列表、真实名不泄漏（断言输出不含输入子串） |
| `SummaryAggregator` | ~14 | 分类金额/占比/笔数、Top 商户、工作日周末金额与日均（分母正确）、时段桶归属、夜间 22/23/00/05/06 边界、环比（上期为 0/null）、预算达成率、0 笔、1 笔、跨月、时区（显式传 `TimeZone`） |
| `AiTransactionParser` | ~16 | 字段完整、缺商户、缺渠道、缺分类、相对日期（今天/昨天/前天）、时段词、金额带小数、金额为字符串 `"32"`、金额为 0/负数/超大、`type` 大写、非法分类、非法渠道、未来日期钳制、`time` 非法、note/merchant 超长截断 |
| `RecordAmountKeypad`（`handleKeyPress`） | ~12 | 前导 0 替换、重复小数点、两位小数上限、`⌫` 到最后一位、`⌫` 空串保护 |
| `BudgetPredictor` | ~10 | 见 6.3 节边界表 |
| `AiClientImpl`（配 `FakeHttpEngine`） | ~10 | 200 正常、200 结构不符、200 流式、401、404、429、500、超时异常、非法 JSON、流中断（已产出部分不清空） |

合计约 100 个用例，全部纯 JVM，`./gradlew testDebugUnitTest` 秒级完成。

`FakeHttpEngine` 示例接口：

```kotlin
class FakeHttpEngine(
    private val response: HttpResponse? = null,
    private val streamLines: List<String> = emptyList(),
    private val streamCode: Int = 200,
    private val throwOnCall: Exception? = null
) : HttpEngine
```

## 7.3 手工回归清单（每次发版必跑）

```text
【数据安全 — 最高优先级】
□ 从 v1.0.28 覆盖安装，账单/分类/预算数据零丢失
□ CSV 导出 → 卸载重装 → CSV 导入，数据完整
□ release 包（minify + shrinkResources）冷启动、各页面无 R8 相关崩溃

【原有主链路不回归】
□ 微信/支付宝收付款通知 → 自动记账成功
□ 模糊通知 → 确认弹窗 → 可改金额入库
□ 短信兜底通道记账成功
□ 通知监听断开后能自动重绑（KeepAlive / Watchdog）
□ 手工记账全流程（含任务 4 改造后的日期选择）
□ 统计页四个 tab、首页月份切换、搜索、分类管理、备份、导出

【AI 功能】
□ 见任务 1 / 3 / 5 各自验收清单

【降级】
□ 完全不配置 AI：全 App 正常，无任何 AI 相关入口报错
□ 配置了 AI 但飞行模式：自动记账、手工记账、统计、预算全部正常，AI 入口给出网络错误提示
□ 配置了错误的 API Key：同上
```

---

# 8. 开发顺序与里程碑

严格按下面顺序，**每一步单独提交、单独可回归、单独可回滚**：

| 步骤 | 内容 | 可独立验证的产出 | 风险 |
|---|---|---|---|
| **M0** | 任务 0：Room 1→2 迁移、Budget 加 source + 唯一索引、junit 依赖、备份规则、隐私政策 | 覆盖安装数据不丢，行为与现在一致 | **高**（碰数据库） |
| **M1** | 任务 1：`AiSettingsScreen` + `SecureSecretStore` + `AiClient` + `HttpEngine` | 能测通至少 2 家服务商 | 中 |
| **M2** | 任务 2：DAO 聚合 + `SummaryAggregator` + 脱敏 + `SummaryFormatter` | 单测全绿；debug 入口能打印 Prompt 供人工核对脱敏 | 低（纯新增） |
| **M3** | 任务 3：统计页 AI 体检卡 + Streaming + `MarkdownText` + `ai_reports` 缓存 | 能生成并缓存报告 | 中 |
| **M4** | 任务 4：`RecordScreen` 状态提升 + 日期选择（**不含 AI**） | 手工记账全回归通过 | **高**（碰高频页面） |
| **M5** | 任务 5：自然语言解析 + `ACTION_RECOGNIZE_SPEECH` 语音 | 10 条必测输入通过 | 中 |
| **M6** | 任务 6：`BudgetPredictor` + 首页预算卡 + 采纳 AI 建议 | 10 个边界用例通过 | 低 |
| **M7** | 任务 7：补齐单测、跑完整回归、更新 README / 版本号 | 可发版 | 低 |

**顺序调整说明（相对 v1）**：

1. 插入 M0：v1 没有迁移步骤，直接加实体会崩或清库。
2. v1 的第 5 步"SpeechRecognizer"独立成步没必要——语音只是给同一个输入框喂文本，合并进 M5。
3. 拆出 M4：v1 把状态提升隐含在"回填"里，实际是碰核心页面的重构，必须独立回归。
4. M2 放在 M3 前面不变（v1 这点是对的），因为 M3 依赖 Summary。

**并行可能性**：M6 与 M1~M5 无依赖（纯本地计算），如需并行可以先做。但"采纳 AI 建议"这个入口依赖 M3，所以 M6 可以先做主体、最后接入采纳按钮。

**不要同时修改**：`PaymentNotificationListener`、`NotificationParser`、`SmsReceiver`、`DedupHelper`、`ConfirmPaymentActivity`、`KeepAliveService`、`ListenerStatus/Watchdog/RebindScheduler`。这些是已经稳定的自动记账链路，本阶段**零改动**。唯一的例外是 M5 结束后向 `SmartCategorizer` 写商户映射（只调用现有 public 方法，不改其内部逻辑）。

---

# 9. 风险登记与回滚

| # | 风险 | 影响 | 缓解 | 回滚方式 |
|---|---|---|---|---|
| R1 | Room 迁移写错，用户数据损坏 | **极高**（不可逆） | 迁移只做 `CREATE TABLE` / `ADD COLUMN` / 建索引，不做 `DROP`、不重建表；上线前用真机覆盖安装验证；禁用 destructive migration | 迁移不可回滚，因此 M0 必须单独发一个内测版本验证后再继续 |
| R2 | API Key 泄漏（日志/崩溃上报/错误提示） | 高（用户资产） | 日志纪律 + `redact()` + Review grep 检查；加密存储；排除备份 | 立即发版移除相关日志，并在设置页提示用户重置 Key |
| R3 | 隐私声明不实（未更新政策就上 AI） | 高（合规/下架） | M0 就把隐私政策改完，先于任何 AI 功能上线 | 关闭 AI 入口（配置为空即隐藏，天然降级） |
| R4 | 脱敏遗漏，真实商户名/备注被上传 | 高 | 类型层面隔离（`FinancialSummary` 无敏感字段）+ DEBUG 正则自检 + Review 检查 `SummaryFormatter` 唯一出口 | 同上，AI 入口可整体隐藏 |
| R5 | R8 混淆导致 release 包 AI 功能失效 | 中 | 零新增序列化依赖（用 `org.json`，不依赖反射/泛型签名）；补 keep 规则；**每个里程碑都跑一次 `assembleRelease` 并实机验证** | 单个功能开关（AI 未配置即隐藏） |
| R6 | M4 状态提升引入手工记账回归 | 中高（高频功能） | `handleKeyPress` 原样搬迁 + 单测锁行为；M4 独立提交；完整回归清单 | 单独 revert M4 提交 |
| R7 | 各家服务商接口差异（尤其 Gemini） | 中 | 只用 OpenAI 兼容子集；Gemini 标注实验性；`Incompatible` 错误给明确指引 | 从预设列表移除该服务商 |
| R8 | AI 输出不稳定导致解析成功率低 | 中 | `temperature = 0.2` + JSON 提取容错 + 字段级校验 + 失败零破坏（表单不变） | 无需回滚，失败即降级为手工记账 |
| R9 | 长报告 Streaming 在低端机卡顿 | 低 | 80ms 节流 + `StringBuilder` 累积 + 折叠显示 + `max_tokens = 2048` 限长 | 降级为非流式 `chat()`（同一 Repository 换个方法即可） |
| R10 | 用户误以为 AI 会自动记账/自动改预算 | 低（口碑） | 所有 AI 入口都是显式按钮；采纳预算必须二次确认；报告底部固定免责说明 | — |
| R11 | AI 额度被意外消耗 | 低 | 全部主动触发；6 小时缓存；`max_tokens` 限长；无任何后台/定时调用 | — |

---

# 10. 本阶段最终验收标准

```text
【数据与兼容 — 一票否决项】
□ 从 v1.0.28 覆盖安装，账单 / 分类 / 预算数据零丢失
□ budgets 表同月唯一，历史重复行已合并
□ release 包（minify 开启）全功能可用
□ 原有通知自动记账 / 短信兜底 / 确认弹窗 / 保活重绑 行为完全不变

【AI 配置】
□ 设置页可配置统一 AI Provider（Base URL / API Key / Model / 测试连接）
□ API Key 加密存储，不出现在任何日志与提示中
□ 换机/恢复备份后无法解密时优雅提示重填，不崩溃
□ AI 仅在用户主动操作时请求网络（进页面、后台、新增交易均 0 请求）

【AI 消费体检】
□ 统计页可生成「本月」/「近 3 月」AI 消费体检
□ AI 输入数据 100% 来自本地 FinancialSummary
□ 原始通知、银行卡号、手机号、订单号、真实商户名、备注原文均不上传
□ 支持 Streaming 实时显示、支持停止生成
□ 支持本地缓存（相同数据 6 小时内不重复请求）
□ Markdown 正常渲染，报告落库，切页面回来不丢
□ 能提取「建议可用总额度」，用户可选择是否采纳
□ AI 不能自动修改预算

【AI 快捷记账】
□ RecordScreen 支持自然语言快捷填写
□ 支持系统语音识别转文字（无需麦克风权限；不可用设备自动隐藏入口）
□ AI 只回填表单，不直接保存账目（代码层面无 insert 路径）
□ 分类只能使用本地已有分类，且按 type 正确匹配
□ 支持相对日期（今天/昨天/前天）并正确入库
□ 解析失败时用户已填内容零丢失

【动态预算】
□ HomeScreen 展示本月可用额度、剩余额度、今日建议可用
□ 展示预计月末支出（月初 1~4 号除外）
□ 存在超支风险时展示预计超支日期
□ 已超支时展示明确警示
□ 历史月份展示「执行情况」形态
□ 本功能完全离线可用

【隐私与合规】
□ 隐私政策已包含 AI 章节，与实际行为一致
□ 首次启用 AI 有一次性明确告知
□ AI 配置已排除自动备份
□ 报告页有「AI 生成内容仅供参考，不构成投资建议」说明

【故障降级】
□ AI API 不可用（401 / 429 / 5xx / 超时 / 断网）时：
   自动记账正常
   手工记账正常
   统计正常
   预算正常
   导出 / 备份正常
□ AI 完全未配置时：所有 AI 入口隐藏，无任何报错

【工程质量】
□ ./gradlew testDebugUnitTest 全绿（约 100 个用例）
□ 未新增网络 / 序列化 / DI / Markdown 依赖（仅新增 junit 测试依赖）
□ 未修改 service/ 目录下任何自动记账相关文件（SmartCategorizer 的 public 调用除外）
```

---

# 11. 本次二开的最终范围

```text
SmartLedger VNext

0. 工程基线改造（Room 迁移 / 预算表约束 / 测试脚手架 / 隐私合规）  ← v2 新增
1. AI 顾问统一配置（含加密存储、错误分类、连通性测试）
2. 本地财务统计聚合 + 商户脱敏
3. AI 周报 / 月报消费体检（Streaming + 缓存 + Markdown）
4. RecordScreen 状态提升 + 日期选择（纯重构）                     ← v2 拆出
5. 自然语言 / 语音快捷填写记账
6. 动态预算 + 超支预测
7. 单元测试与完整回归
```

核心边界（保持 v1 的设计，这是本方案最有价值的部分）：

```text
AI 消费体检：
用户主动点击 → 本地聚合 → 脱敏 → Prompt → AI → Markdown 报告 → 落库
                                              ↑
                                        AI 只做解释与建议
                                        所有数字由本地计算

AI 快捷记账：
用户主动输入 / 说话 → AI 提取字段 → 本地校验与映射 → 回填表单
                                                      ↓
                                              用户确认 → 原有 Repository 保存
                                              （AI 无入库路径）

普通自动记账：
支付通知 → SmartLedger 原有逻辑 → Room        （本阶段零改动）

动态预算：
Room + 用户预算 → BudgetPredictor（纯函数，离线）→ HomeScreen
```

这样改动范围可控，AI 只存在于两个明确的主动入口，不侵入 SmartLedger 原有自动记账主链路，同时已经能形成完整的「自动记账 + AI 消费顾问 + 智能预算」体验。

---

## 附：v2 相对 v1 的改动索引

| v1 位置 | v2 处理 | 类型 |
|---|---|---|
| DAO 示例 SQL（`timestamp` / `category` / `'EXPENSE'`） | 2.3 全部按真实字段重写 | 修错 |
| 「回填现有 RecordScreen」 | 拆出任务 4，先做状态提升 + 日期能力 | 补设计 |
| `AiReportEntity` 新增 | 任务 0.1 补 Room `MIGRATION_1_2` | 补关键缺口 |
| Budget 复用 | 任务 0.1 加 `source` + `yearMonth` 唯一索引；6.2 改 upsert | 修错 + 补设计 |
| OkHttp + kotlinx.serialization + MockWebServer | 1.5 改 `HttpURLConnection` + `org.json` + `FakeHttpEngine` | 换方案 |
| Gemini 归入 OpenAI Compatible | 1.2 改用兼容端点并标注实验性 | 修错 |
| Prompt `"type": "EXPENSE"` | 5.3 改小写；并按 type 分开给分类列表 | 修错 |
| 24 条 `hourlyStats` | 2.2 改 5 个时段桶 | 优化 |
| SQLite 做小时/星期聚合 | 2.4 改 Kotlin 侧分桶（时区正确） | 修错 |
| `dataHash` 缓存 | 3.6 补 TTL 双条件 + 保留策略 + `canonicalJson` 稳定性要求 | 补设计 |
| 建议额度 Regex | 3.7 放宽（全角/千分位/加粗/"约"/"元"） | 补设计 |
| 「Compose 实时渲染 Markdown」 | 3.8 自研 `MarkdownText`，不引依赖 | 补设计 |
| `SpeechRecognizer` + `RECORD_AUDIO` | 5.2 改 `ACTION_RECOGNIZE_SPEECH` Intent，无需权限 | 换方案 |
| 「本月 / 近 3 月」与统计页四 tab 冲突 | 3.1 定死 AI 卡自带周期切换，不跟随 tab | 补设计 |
| 预算公式 | 6.3 补除零保护、`NO_BUDGET`、月初护栏、10 个边界用例 | 补设计 |
| 首页预算卡 | 6.5 补历史月份形态、配色映射、插入位置 | 补设计 |
| 隐私政策 | 0.4 新增（v1 完全未提） | 补关键缺口 |
| 测试方案 | 7.1 改纯 JVM 单测，并反推出"纯函数"设计约束 | 换方案 |
| 开发顺序 6 步 | 8. 改 8 个里程碑，每步独立回归/回滚 | 补设计 |
| （无） | 9. 风险登记与回滚 | 新增 |


---

# 12. 实现记录（开发完成后回写）

> 本节记录**实际落地情况**，与上文方案对照。凡与方案有出入的地方都写明了原因。

## 12.1 验证结果汇总

| 验证项 | 手段 | 结果 |
|---|---|---|
| Room `MIGRATION_1_2` | `tools/verify_migration.py`：sqlite3 造真实 v1 库（含重复月份预算 + 依赖外键的分类预算行）→ 执行从 `AppDatabase.kt` **源码里解析出来的**迁移 SQL → 与 Room 导出的 `schemas/2.json` 逐列比对 | ✅ 20 笔账单/3 分类/收入目标/外键全完好；`category_budgets` 未被连带删除；同月预算去重且保留最后写入值；5 张表列名/类型/notNull/defaultValue/索引全一致；唯一索引与 UPSERT 生效 |
| 纯逻辑单测 | `./gradlew testDebugUnitTest` | ✅ **308 个用例全绿**（11 个测试类） |
| AI 真链路 | `./gradlew testDebugUnitTest -PaiIntegration=true` | ✅ **11 个用例全绿**，直连 DeepSeek |
| Debug 构建 | `./gradlew assembleDebug` | ✅ app-debug.apk |
| Release + R8 | `./gradlew assembleRelease` | ✅ `minifyReleaseWithR8` 通过，1.89 MB（debug 18.3 MB） |
| 枚举名抗混淆 | 解包 release dex 查字符串 | ✅ `THIS_MONTH` / `AI_SUGGESTED` / `MANUAL` / `DEEPSEEK` 等均存活（这些值会写进 DB 与 prefs，被混淆就会导致升级后读不出来） |
| API Key 泄漏 | grep `Log.*` × `apiKey/Authorization` | ✅ 仅 4 处日志，全部只打异常类名 |
| AI 无入库路径 | grep `data/ai`+`data/analytics` 里的 `insert/Repository` | ✅ 只有 `aiReportDao.insert`（报告缓存），无任何 Transaction 写入 |
| AI 无自动触发 | grep UI 层 AI 调用点 | ✅ 全部在按钮回调内 |

单测分布：

```text
AiClientImplTest            51    AiTransactionParserTest   48
SummaryAggregatorTest       34    BudgetPredictorTest       26
SseParserTest               26    SummaryFormatterTest      24
SummaryPeriodsTest          23    AmountKeypadTest          22
MarkdownTextParserTest      20    BaseUrlNormalizerTest     18
MerchantAnonymizerTest      16
                          ────
                            308
```

## 12.2 开发过程中发现并修掉的真实 bug

这些都不是方案里预见到的，是**写测试和跑真链路时暴露出来的**，逐条记录以免回归：

| # | Bug | 发现方式 | 后果（若不修） |
|---|---|---|---|
| B1 | **`deepseek-v4-flash` / `pro` 返回的 `content` 是空字符串**，正文全在 `reasoning_content`；`max_tokens` 小时被推理过程吃光（`finish_reason=length`） | 开工前用 curl 实测三个模型 | AI 报告页一片空白、记账解析必然失败，而用户只会以为"功能坏了" |
| B2 | `streamChat` 用 `flow { emit(...) }`，而 `postStreaming` 内部 `withContext(Dispatchers.IO)` → **Flow exception transparency is violated** | 真链路集成测试（`FakeHttpEngine` 当时没切线程，51 个单测全绿也没发现） | AI 消费体检在真机上**必崩** |
| B3 | 通用 `IOException`（`Connection reset by peer` / broken pipe）落到 `Unknown` | 单测 | 流中断时把英文技术细节直接怼到用户脸上：「请求失败：Connection reset by peer」 |
| B4 | `SummaryAggregator` 与 `MerchantAnonymizer` 的商户名归一化规则不一致（一个折叠空白、一个删除空白） | 写单测时发现 | Prompt 里出现**两行同名的「餐饮商户 #1」**，AI 据此得出错误结论 |
| B5 | 用 Double 累加金额 → 浮点加法不满足结合律，而 DAO 投影查询无 `ORDER BY` | 设计评审 + 乱序单测 | `canonicalJson` 抖动 → `dataHash` 每次不同 → **缓存永不命中，每次点开都重烧 token** |
| B6 | Room 期望 `budgets.source TEXT NOT NULL` 且**无 defaultValue**，而 `ALTER TABLE ADD COLUMN ... NOT NULL DEFAULT 'MANUAL'` 会在库里留下默认值 | 导出 `schemas/2.json` 逐列比对 | `IllegalStateException: Migration didn't properly handle budgets` → **所有升级用户一启动就崩** |
| B7 | 常规解法「建新表→拷数据→DROP→RENAME」在本项目不可用：`category_budgets` 对 `budgets(id)` 有 `ON DELETE CASCADE` | 读 schema JSON 时发现外键 | 迁移会**连带删光所有分类预算** |
| B8 | 「查看报告」按钮走 `generateReport(force=false)`，TTL 过期或数据变过就会真的重发请求 | 代码走查 | 用户点"查看旧报告"却在背后静默烧一次额度 |
| B9 | `BudgetViewModel.saveBudget(null, limit)` 走 `copy(totalIncomeTarget = null)` | 代码走查 | 改一次支出上限就把用户的**收入目标抹掉** |
| B10 | `BudgetViewModel` 把 `currentYearMonth/monthStart/monthEnd` 在构造期算成常量 | 代码走查 | App 跨月不重启会一直统计**上个月** |
| B11 | API Key 输入框双重掩码：`form.apiKey` 存的是掩码串，又叠了 `PasswordVisualTransformation` | 代码走查 | 点👁眼睛切换只看到同一堆圆点，用户永远看不到自己存的 Key |
| B12 | `PaymentChannelPicker` 点「自定义」时立刻 `onSelected("")` | 代码走查 | 渠道被写成空串，所有 chip 失去选中高亮 |
| B13 | `AmountKeypad` 原实现整数位无上限 | 补单测时 | 能连按 20 次数字键并存进数据库（超出 Double 精确表示范围） |
| B14 | 日期缺省/过去日期无时刻时若落到 `00:00` | 设计评审 | 白天补记的账被算进「夜间 22-06」桶，**AI 行为画像直接失真** |
| B15 | `Typography` 里写死 `color = Foreground`（亮色墨色） | 重写主题时 | 深色模式下未显式传色的文字变成深底深字，不可读 |
| B16 | `EditTransactionDialog` 里状态变量名 `time` 与 `Calendar.time` 同名，在 `Calendar.apply{}` 内使用 | 代码走查 | 当前靠"局部变量优先于隐式接收者成员"侥幸正确，一旦有人挪动作用域语义会静默改变 |

B2 是最有价值的一条：**51 个单测全绿，只有真链路测试能抓到**。这也是为什么 `FakeHttpEngine` 事后被改成同样 `withContext(Dispatchers.IO)`——让这类错误以后在单测阶段就被拦住。

## 12.3 与方案的偏差（均已实测确认）

| 方案原文 | 实际实现 | 原因 |
|---|---|---|
| Provider 预设含 Gemini | **移除 Gemini**，保留 DeepSeek / Kimi / 通义千问 / OpenAI / 自定义 | 用户明确「默认 DeepSeek，其他先不开发」；自定义项已能覆盖任意 OpenAI 兼容服务 |
| 默认 model `deepseek-chat` | 保持 `deepseek-chat` | 实测 v4 系列有 B1 的空正文问题；`deepseek-chat` 是它的非推理别名，输出干净、额度最省。同时保留 reasoning 兜底，用户手选 v4 也不至于空白 |
| `Budget.source` 为 `String NOT NULL DEFAULT 'MANUAL'` | 改为**可空** `String?`，Kotlin 侧默认 `MANUAL` | 见 B6/B7：既避开 defaultValue 不一致，又不用 DROP 表触发外键级联删除。老行为 NULL，`budgetSource` 读作 MANUAL，语义正确 |
| DAO 做分类/商户/小时聚合 | 只保留 `getPointsInRange` 一个投影，分组全在 Kotlin 侧 | 时段与星期分桶必须用本地时区（SQLite `strftime` 按 UTC，见方案 M4）；点数据既然已经加载，其余分组顺带做完，少 4 个 DAO 方法、少 4 次往返，且**全部可纯 JVM 单测** |
| `hourlyStats` 24 条 | 5 个 `TimeBucket` | 方案已定，落地一致 |
| 「查看报告」= 命中缓存 | 独立 `viewCachedReport()` | 见 B8 |
| 单测约 100 例 | **308 例** + 11 例真链路 | 边界比预期多，尤其日期口径与脱敏红线值得逐条钉死 |

## 12.4 新增/改动文件清单

**新增（33 项）**

```text
设计系统      ui/theme/Palette.kt, AppTokens.kt
              ui/components/Primitives.kt, MarkdownText.kt, CategoryIcons.kt,
                            TransactionRow.kt, TransactionEditDialogs.kt
AI 纯逻辑     data/ai/BaseUrlNormalizer.kt, SseParser.kt, JsonExtractor.kt,
                     SuggestedBudgetExtractor.kt, SecretRedactor.kt,
                     AiTransactionParser.kt, AiClient.kt, AiClientImpl.kt
              data/ai/model/AiConfig.kt, AiMessage.kt, AiError.kt, TransactionDraft.kt
              data/ai/http/HttpEngine.kt, UrlConnectionHttpEngine.kt
AI 配置       data/security/SecureSecretStore.kt
              data/preferences/AiPreferences.kt
              data/repository/AiSettingsRepository.kt, AiAdvisorRepository.kt, AiPromptBuilder.kt
聚合脱敏      data/analytics/SummaryPeriods.kt, SummaryAggregator.kt,
                         MerchantAnonymizer.kt, SummaryFormatter.kt, FinancialSummaryBuilder.kt
              data/analytics/model/TxPoint.kt, FinancialSummary.kt
数据层        data/db/entity/AiReportEntity.kt, BudgetSource.kt
              data/db/dao/AiReportDao.kt
              app/schemas/…/2.json（Room 导出，用于校验迁移）
预算          util/BudgetPredictor.kt
记账          ui/record/RecordUiState.kt, AmountKeypad.kt, SpeechInput.kt
页面          ui/settings/AiSettingsScreen.kt, AiSettingsViewModel.kt
              ui/statistics/AiReportCard.kt, AiReportUiState.kt
              ui/home/BudgetCard.kt
              ui/transactions/TransactionsScreen.kt, TransactionsViewModel.kt
配置          res/xml/backup_rules.xml, data_extraction_rules.xml
测试          app/src/test/…（12 个测试类，308 例）
工具          tools/verify_migration.py
```

**修改（26 项）**：`build.gradle.kts`、`proguard-rules.pro`、`AndroidManifest.xml`、`MainActivity.kt`、`SmartLedgerApp.kt`、`AppDatabase.kt`、`TransactionDao/BudgetDao`、`Budget`、`TransactionRepository/BudgetRepository`、`BudgetViewModel`、`PaymentChannelPicker`、`SmartLedgerDialog`、`HomeScreen/HomeViewModel`、`Screen.kt`、`ProfileScreen`、`RecordScreen/RecordViewModel`、`PrivacyPolicyScreen`、`SettingsScreen`、`StatisticsScreen/StatisticsViewModel`、`Theme.kt`、`Type.kt`

**零改动**：`service/` 目录下全部自动记账链路文件（`PaymentNotificationListener`、`NotificationParser`、`SmsReceiver`、`DedupHelper`、`ConfirmPaymentActivity`、`KeepAliveService`、`ListenerStatus`/`Watchdog`/`RebindScheduler`、`FloatingWindowService`）——符合方案「不侵入已稳定主链路」的边界。

## 12.5 UI 重构落地说明

固定品牌色、不启用 Dynamic Color，全部按规范落到 `Palette.kt`：

```text
Ink #171714    主文字 / 交互强调（primary）
InkSoft #44443D 次文字        Paper #F3EFE5      页面背景
PaperStrong #FFFDF7 卡片      PaperDeep #E7DFCE  次级分区
Seal #C9482F   支出/超支      Moss #375A45       收入/成功
Amber #C68A2F  待确认/预警    Blue #315D6F       图表/信息（secondary）
Violet #6257C8 AI 点色（tertiary）  Lavender #EFECFF AI 浅底
边框统一 Ink 12%（BorderSoft）/ 16%（BorderStrong）
```

关键取舍：**`SmartLedgerColors.accent` 映射为 Ink 而不是 Seal**。Seal 已被"支出/超支"占用，若同时作为品牌强调色，会出现"主按钮和亏损数字同色"的语义冲突；用 Ink 做交互强调、Seal 只做支出语义，符合"同一屏不大面积使用多个强调色"。Violet 严格只出现在 AI 相关元素上。

排版：新增 `AppType` 语义化样式（`heroAmount` 44sp / `pageTitle` 30sp / `cardNumber` 26sp / `sectionTitle` 19sp / `body` 15sp / `aux` 13sp / `eyebrow` 11sp+1.1sp 字距），页面统一从这里取，不逐页拼字号。数字样式带 `fontFeatureSettings = "tnum"`（等宽数字），金额跳变不抖动。字体用 `FontFamily.SansSerif`，在小米设备自动落到 MiSans、其余落到 Noto Sans CJK，不打包字体文件。

令牌：16dp 页边距、12dp 卡片间距、24dp/18dp 圆角、pill chip、48dp 按钮与最小触控区、1dp 细边框、阴影仅 1dp。动效 150–300ms，并通过 `MotionPreference` 读 `Settings.Global.ANIMATOR_DURATION_SCALE == 0` 尊重系统"减弱动态效果"。

底部导航改为 **总览 / 流水 / 统计 / 设置**：新增流水页（按日期分组、商户为主信息、分类·渠道为次信息、金额右对齐 Seal/Moss、轻量 Chip 筛选）；记账 Tab 移除，改由总览与流水页的 FAB 进入；「我的」并入「设置」。**未新增 AI 独立 Tab**，AI 作为增强层挂在统计页顶部与记账页顶部。

## 12.6 额度控制措施

```text
· 连通性探测固定 max_tokens = 8、temperature = 0，请求体仅 "ping"
· 记账解析 max_tokens = 400、temperature = 0.2（实测单次约 558 tokens）
· 消费体检 max_tokens = 2048、temperature = 0.7（实测单次约 872 字 / 587 增量）
· 报告缓存 = dataHash + 6 小时 TTL 双条件，「查看报告」走 viewCachedReport 绝不重发
· 无任何后台/定时/页面打开触发的 AI 调用
· 推理模型在配置页给出额度警示
· 集成测试默认排除，需显式 -PaiIntegration=true 才跑
```

## 12.7 遗留事项（建议后续处理）

1. **真机 UI 走查未做**：本机无模拟器/设备，Compose 布局、深色模式、低端机流式帧率只能靠代码审查保证。发版前需按方案 7.3 节的手工回归清单在真机跑一遍。
2. **覆盖安装实测未做**：迁移已用 sqlite3 逐列验证，但仍建议按方案 0.1 节做一次 v1.0.28 → 新版的真机覆盖安装。
3. `ui/components/Charts.kt`（BarChart/LineChart/PieChart）是 0 引用的死代码，R8 会剥离，本次未删除以免超出范围。
4. `CsvExporter` 把 `source == "sms"` 的账单也归为「手动」，属于既有的小口径问题，未改动。
5. 分类图标映射目前在 `components/categoryIcon()` 与 `RecordScreen`/`BudgetScreen` 各存一份，建议后续统一到前者。

## 12.8 Code Review 后修复记录（2026-09-08）

独立 review 确认后修复 3 项，验证 325 单测全绿（308 + 新增 17）、assembleDebug 通过：

1. **建议额度正则截断（严重，必修）**：`SuggestedBudgetExtractor` 交替分支的千分位组 `(?:,[0-9]{3})*` 允许零个逗号组，Java 正则「左优先」语义让无千分位的 4 位以上金额只取前 3 位（`¥4800` → 480、`¥12000` → 120）。该值经「采纳为下月预算」弹窗可直达 budgets 表，错 10 倍的预算会被用户一键确认。修复：`*` → `+`（千分位分支要求至少一个 `,xxx`，无千分位数字落到第二分支贪婪匹配完整数字）。**根因**：实现时照抄了方案 3.7 节的原型正则（原型本身带此缺陷），且未按 7.2 节要求补测试。已补 `SuggestedBudgetExtractorTest` 17 例（3.7 节约定的 9 形态 + 截断回归 + 输入防御），并做过反证：临时回退 `*` 时 9 例失败 —— 其中「超上限拦截」也被绕过（`¥99999999` 截成 999 后溜进 1,000,000 上限以内），千分位用例全部通过（修复不伤千分位分支）。
2. **Keystore 操作在主线程（中风险）**：`AiSettingsViewModel` 的 reload/save 在 Main dispatcher 里同步做 AES/GCM 加解密 + prefs 磁盘 IO（StrongBox/低端机会掉帧，极端 ANR），且同一密文被解密两遍。修复：repository 的 load/save/clear 全部 suspend + `Dispatchers.IO`；`load()` 返回结果消除双读。
3. **删除埋雷的死方法**：`BudgetDao.insertBudget/updateBudget`（REPLACE 策略）已无调用点，但 `yearMonth` 唯一索引 + `category_budgets` 的 ON DELETE CASCADE 意味着将来一旦被误用，REPLACE 会删旧行并**静默删光该月分类预算**。已删除，并在 DAO 类头写明「为什么不提供整行 insert」。

review 判定的 8 个低风险项（stopGeneration 双落库竞态、bumpParseCounter 双计数、统计页 onResume 不重估缓存新鲜度、loadIdleState 全量拉点数笔数、迁移去重连带删除、30x 重定向文案、编辑弹窗静默拒绝）按报告建议延后；另 `JsonExtractorTest` 仍缺失（实现经 review 确认正确），可后续补。
