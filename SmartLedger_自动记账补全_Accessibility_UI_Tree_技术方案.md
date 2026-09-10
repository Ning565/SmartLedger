# SmartLedger 自动记账补全技术方案
> 范围：只补齐自动记账采集链路。现有 AI 顾问、AI 快捷记账、统计、预算等功能不在本方案内修改。  
> 当前目标：在保留现有 `NotificationListenerService + SMS` 的基础上，新增 **微信 / 支付宝 Accessibility UI Tree 识别**，解决微信无系统支付通知、支付宝转账通知缺金额、主动转账无通知等问题。  
> 本阶段**不做 OCR、不 Root、不修改微信/支付宝 APK**。

---

# 任务 1：抽取统一自动入账处理器

当前 `PaymentNotificationListener` 内部已经包含通知过滤、`NotificationParser.parse()`、模糊确认、`DedupHelper`、`SmartCategorizer`、Room 入库等逻辑。新增 Accessibility 后不要再复制一套保存逻辑，先把“解析完成后的处理”抽成公共组件。

## 1.1 新增文件

```text
app/src/main/java/com/smartledger/service/
├── AutoRecordProcessor.kt
├── AutoCaptureSource.kt
└── AccessibilityFingerprintCache.kt
```

### AutoCaptureSource.kt

```kotlin
package com.smartledger.service

enum class AutoCaptureSource {
    NOTIFICATION,
    ACCESSIBILITY,
    SMS
}
```

### AutoRecordProcessor.kt

职责：

```text
ParsedPayment
    ↓
是否需要确认
    ↓
DedupHelper
    ↓
SmartCategorizer
    ↓
TransactionDao.insert
    ↓
入账通知
```

接口：

```kotlin
class AutoRecordProcessor(
    private val context: Context
) {

    suspend fun process(
        parsed: ParsedPayment,
        transactionTime: Long,
        source: AutoCaptureSource
    )
}
```

把当前 `PaymentNotificationListener` 中这些逻辑移动进去：

```text
shouldAskConfirm()
askUserConfirm()
saveAutoTransaction()
DedupHelper.findDuplicate()
DedupHelper.mergeIfDuplicate()
SmartCategorizer.categorize()
Transaction(...)
notifyPaymentDetected()
```

Transaction 的现有字段先不改数据库结构：

```kotlin
source = "auto"
```

为了调试采集来源，使用 `notificationKey` 前缀区分：

```text
通知：
notif:<原 notificationKey/hash>

Accessibility：
a11y:<package>:<fingerprint>
```

如果当前 `NotificationParser` 已生成自己的 `notificationKey`，通知链路继续沿用；Accessibility 只需要生成 `a11y:` 前缀的 key。

## 1.2 修改 PaymentNotificationListener

现有：

```kotlin
if (shouldAskConfirm(parsed)) {
    askUserConfirm(parsed, postTime)
    return
}

scope.launch {
    saveAutoTransaction(parsed, postTime)
}
```

改为：

```kotlin
scope.launch {
    AutoRecordProcessor(applicationContext).process(
        parsed = parsed,
        transactionTime = postTime,
        source = AutoCaptureSource.NOTIFICATION
    )
}
```

通知解析部分、聚合通知补全、银行规则全部保持不变。

## 1.3 AccessibilityFingerprintCache

Accessibility 同一个支付页面会产生多次事件，所以增加 15 秒内存去重。

```kotlin
object AccessibilityFingerprintCache {

    private const val TTL = 15_000L

    private val cache = ConcurrentHashMap<String, Long>()

    fun shouldProcess(key: String): Boolean {
        val now = System.currentTimeMillis()

        cache.entries.removeIf {
            now - it.value > TTL
        }

        val last = cache[key]

        if (last != null && now - last < TTL) {
            return false
        }

        cache[key] = now
        return true
    }
}
```

fingerprint：

```text
package
+
type
+
amount cents
+
merchant normalized
+
页面状态关键词
```

---

# 任务 2：增加微信 / 支付宝 AccessibilityService

## 2.1 新增文件

```text
app/src/main/java/com/smartledger/service/accessibility/
├── PaymentAccessibilityService.kt
├── AccessibilityStatus.kt
├── UiTreeSnapshotExtractor.kt
├── PaymentSignalDetector.kt
├── WeChatAccessibilityParser.kt
└── AlipayAccessibilityParser.kt
```

资源：

```text
app/src/main/res/xml/payment_accessibility_service.xml
```

## 2.2 AndroidManifest.xml

在 `<application>` 内增加：

```xml
<service
    android:name=".service.accessibility.PaymentAccessibilityService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">

    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>

    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/payment_accessibility_service" />
</service>
```

不要新增：

```xml
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
```

`BIND_ACCESSIBILITY_SERVICE` 应声明在 Service 的 `android:permission` 上。

## 2.3 payment_accessibility_service.xml

```xml
<?xml version="1.0" encoding="utf-8"?>

<accessibility-service
    xmlns:android="http://schemas.android.com/apk/res/android"

    android:description="@string/accessibility_payment_description"

    android:accessibilityEventTypes="
        typeWindowStateChanged|
        typeWindowContentChanged"

    android:accessibilityFeedbackType="feedbackGeneric"

    android:packageNames="
        com.tencent.mm,
        com.eg.android.AlipayGphone"

    android:notificationTimeout="300"

    android:canRetrieveWindowContent="true"

    android:accessibilityFlags="flagReportViewIds" />
```

当前阶段只监听：

```text
com.tencent.mm
com.eg.android.AlipayGphone
```

不要使用：

```text
typeAllMask
```

不要监听所有 App。

## 2.4 设置页增加入口

增加：

```text
自动记账
────────────────────
通知识别              已开启
微信/支付宝页面辅助识别   已开启 / 去开启
```

点击：

```kotlin
startActivity(
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
)
```

判断是否已开启：

```kotlin
fun isPaymentAccessibilityEnabled(context: Context): Boolean {
    val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager

    return manager
        .getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        .any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
            it.resolveInfo.serviceInfo.name.endsWith(
                "PaymentAccessibilityService"
            )
        }
}
```

同时保留一个 App 内开关：

```text
accessibility_auto_record_enabled = true
```

即使系统无障碍权限已开启，用户也可以暂时停止 SmartLedger 解析支付页面。

---

# 任务 3：实现低耗电二级事件过滤

Accessibility Service 不是轮询，不定时扫描屏幕，不截图。

只在微信 / 支付宝产生指定 AccessibilityEvent 时触发。

处理流程：

```text
AccessibilityEvent
        ↓
包名过滤
        ↓
事件类型过滤
        ↓
300~500ms 防抖
        ↓
Level 1：浅层文本探测
        ↓
存在支付强特征？
       / \
     否   是
     ↓     ↓
   结束   Level 2：完整 UI Tree 提取
                ↓
              Parser
                ↓
          AutoRecordProcessor
```

## 3.1 PaymentAccessibilityService

建议实现：

```kotlin
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val WECHAT = "com.tencent.mm"
        private const val ALIPAY =
            "com.eg.android.AlipayGphone"

        private const val DEBOUNCE_MS = 450L

        private val ALLOWED_PACKAGES =
            setOf(WECHAT, ALIPAY)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var pendingRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        AccessibilityStatus.setConnected(
            applicationContext,
            true
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        event ?: return

        if (!isFeatureEnabled()) return

        val packageName =
            event.packageName?.toString() ?: return

        if (packageName !in ALLOWED_PACKAGES) {
            return
        }

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleFullScan(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (quickProbe(event)) {
                    scheduleFullScan(packageName)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityStatus.setConnected(
            applicationContext,
            false
        )
        super.onDestroy()
    }
}
```

## 3.2 TYPE_WINDOW_STATE_CHANGED

页面切换属于低频事件。

例如：

```text
微信支付
→ 支付成功页

支付宝
→ 转账成功页

微信
→ 红包详情页
```

该事件收到后直接：

```text
450ms debounce
→ full scan
```

原因：支付成功页刚出现时 UI 可能还没完全渲染，立即读取可能拿不到金额。

## 3.3 TYPE_WINDOW_CONTENT_CHANGED

聊天、列表刷新也会频繁产生该事件。

不能每次完整遍历 UI Tree。

先执行：

```text
quickProbe()
```

仅检查：

```text
event.text
event.contentDescription
event.source 当前节点
event.source 下最多 30~40 个浅层节点
```

只要没有支付强特征，立即结束。

## 3.4 quickProbe

```kotlin
private fun quickProbe(
    event: AccessibilityEvent
): Boolean {

    val parts = mutableListOf<String>()

    event.text
        .mapNotNull { it?.toString() }
        .forEach(parts::add)

    event.contentDescription
        ?.toString()
        ?.let(parts::add)

    event.source?.let { source ->
        parts += UiTreeSnapshotExtractor.extractShallow(
            source = source,
            maxNodes = 40,
            maxDepth = 4
        )
    }

    return PaymentSignalDetector
        .hasStrongSignal(
            parts.joinToString(" ")
        )
}
```

## 3.5 防抖

```kotlin
private fun scheduleFullScan(
    packageName: String
) {
    pendingRunnable?.let(
        handler::removeCallbacks
    )

    val runnable = Runnable {
        performFullScan(packageName)
    }

    pendingRunnable = runnable

    handler.postDelayed(
        runnable,
        DEBOUNCE_MS
    )
}
```

连续 20 次 UI 更新最终只执行一次完整扫描。

## 3.6 耗电限制

必须遵守：

```text
不使用 while / Timer 轮询
不使用 WakeLock
不新增前台 Service
不截图
不 OCR
不监听所有 App
不处理 TYPES_ALL_MASK
完整树最多遍历 250~350 节点
同一页面 fingerprint 15 秒内不重复处理
```

---

# 任务 4：实现 UI Tree 文本提取和微信 / 支付宝 Parser

## 4.1 UiTreeSnapshotExtractor

不要让 Parser 直接操作 `AccessibilityNodeInfo`。

先把页面转成纯文本快照：

```kotlin
data class UiTextNode(
    val text: String,
    val viewId: String?,
    val className: String?,
    val depth: Int,
    val index: Int
)

data class UiSnapshot(
    val packageName: String,
    val nodes: List<UiTextNode>,
    val capturedAt: Long
)
```

提取：

```kotlin
object UiTreeSnapshotExtractor {

    fun extract(
        root: AccessibilityNodeInfo,
        packageName: String,
        maxNodes: Int = 300
    ): UiSnapshot {

        val result = mutableListOf<UiTextNode>()
        val queue =
            ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()

        queue.add(root to 0)

        var index = 0

        while (
            queue.isNotEmpty() &&
            result.size < maxNodes
        ) {
            val (node, depth) =
                queue.removeFirst()

            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            )
                .map { normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()

            values.forEach { text ->
                result += UiTextNode(
                    text = text,
                    viewId =
                        node.viewIdResourceName,
                    className =
                        node.className?.toString(),
                    depth = depth,
                    index = index++
                )
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    queue.add(it to depth + 1)
                }
            }
        }

        return UiSnapshot(
            packageName = packageName,
            nodes = result,
            capturedAt =
                System.currentTimeMillis()
        )
    }
}
```

文本 normalize：

```text
去首尾空格
连续空格合一
￥ → ¥
全角冒号统一
换行合并
```

## 4.2 performFullScan

```kotlin
private fun performFullScan(
    packageName: String
) {
    val root =
        rootInActiveWindow ?: return

    val snapshot =
        UiTreeSnapshotExtractor.extract(
            root = root,
            packageName = packageName
        )

    if (!PaymentSignalDetector
            .hasStrongSignal(
                snapshot.nodes
                    .joinToString(" ") {
                        it.text
                    }
            )
    ) {
        return
    }

    val parsed =
        when (packageName) {

            WECHAT ->
                WeChatAccessibilityParser
                    .parse(snapshot)

            ALIPAY ->
                AlipayAccessibilityParser
                    .parse(snapshot)

            else -> null
        }
        ?: return

    val key =
        AccessibilityFingerprintBuilder
            .build(packageName, parsed)

    if (!AccessibilityFingerprintCache
            .shouldProcess(key)
    ) {
        return
    }

    CoroutineScope(Dispatchers.IO).launch {
        AutoRecordProcessor(
            applicationContext
        ).process(
            parsed = parsed,
            transactionTime =
                snapshot.capturedAt,
            source =
                AutoCaptureSource.ACCESSIBILITY
        )
    }
}
```

## 4.3 PaymentSignalDetector

必须要求：

```text
交易状态词
+
金额
```

不能只看金额。

金额：

```kotlin
private val AMOUNT_REGEX = Regex(
    """(?:¥\s*)?([0-9,]+(?:\.[0-9]{1,2})?)\s*元?"""
)
```

强交易词：

```kotlin
private val STRONG_WORDS = listOf(
    "支付成功",
    "付款成功",
    "已支付",
    "转账成功",
    "收款成功",
    "已收款",
    "已到账",
    "到账成功",
    "退款成功",
    "已退款",
    "红包已领取",
    "已领取",
    "已存入零钱"
)
```

实现：

```kotlin
fun hasStrongSignal(text: String): Boolean {
    val normalized = normalize(text)

    val hasStatus =
        STRONG_WORDS.any {
            normalized.contains(it)
        }

    val hasAmount =
        AMOUNT_REGEX.containsMatchIn(
            normalized
        )

    return hasStatus && hasAmount
}
```

注意：

```text
“转账”
“红包”
“¥500”
```

单独出现均不能自动入账。

---

# 任务 5：实现具体交易场景规则

第一版只支持“能明确确定金额 + 收支方向”的页面。

解析不确定时：

```text
返回 null
```

不要猜。

## 5.1 微信支出

### 场景 A：微信付款成功

页面包含：

```text
支付成功
¥32.00
老乡鸡
```

输出：

```kotlin
ParsedPayment(
    amount = 32.0,
    merchant = "老乡鸡",
    paymentMethod = "微信",
    notificationKey =
        "a11y:wechat:<hash>",
    type = "expense",
    confidence =
        ParseConfidence.HIGH
)
```

### 场景 B：主动转账成功

页面包含：

```text
转账成功
¥500.00
张三
```

输出：

```text
expense
500.00
微信
merchant = 张三
```

### 不支持自动记账

```text
转账
待收款
对方已收款
已退还
```

除非同时有明确金额和明确“本次由我转出”的成功语义。

避免打开历史聊天时重复产生支出。

---

## 5.2 微信收入

### 场景 C：收到转账并已收款

页面 / 转账详情包含：

```text
¥500.00
已收款
```

或：

```text
已收款
500.00元
已存入零钱
```

输出：

```text
income
500.00
微信
```

### 场景 D：红包详情

仅当页面有金额：

```text
12.88
已存入零钱
```

或：

```text
红包已领取
12.88元
```

输出：

```text
income
12.88
微信
```

以下情况不记：

```text
你领取了一个红包
红包已领取
已收款
```

但页面没有金额。

因为只知道发生了交易，不知道金额。

---

## 5.3 支付宝支出

### 场景 E：付款成功

```text
支付成功
32.00
商户名称
```

输出：

```text
expense
32.00
支付宝
```

### 场景 F：主动转账成功

```text
转账成功
500.00元
张三
```

输出：

```text
expense
500.00
支付宝
```

---

## 5.4 支付宝收入

### 场景 G：收到转账

系统通知可能只有：

```text
XX成功向你转了一笔钱
```

这条通知不强行生成账目。

当用户之后打开支付宝相关页面，如果 UI Tree 出现：

```text
收到转账
500.00
交易成功
```

或：

```text
收款成功
500.00
```

则：

```text
income
500.00
支付宝
```

### 场景 H：退款

```text
退款成功
68.00
```

沿用 SmartLedger 当前语义：

```text
income
68.00
支付宝
```

如果你后续单独增加 `refund` 类型，再统一调整；本阶段不改数据模型。

---

## 5.5 金额提取

金额提取不能简单取页面第一个数字。

建议顺序：

```text
1. ¥ / ￥ 前缀金额
2. “金额 / 实付 / 收款 / 转账金额”附近金额
3. 带 “元” 的金额
4. 页面唯一合法金额
```

过滤：

```text
时间：12:30
日期：2026-09-09
订单号
手机号
百分比
银行卡尾号
验证码
```

实现：

```kotlin
data class ScreenAmountHit(
    val amount: Double,
    val nodeIndex: Int,
    val score: Int
)
```

打分：

```text
+100  ¥32.00
+80   32.00元
+80   与“实付/金额/收款”距离 <= 2 nodes
+60   页面只有一个金额候选

-100  位于“订单号/交易号”附近
-100  8 位以上连续数字
-80   位于“时间/日期”附近
```

最高分低于阈值：

```text
return null
```

---

## 5.6 商户提取

商户不是自动入账的必填条件。

优先：

```text
金额附近 1~4 个文本节点
```

排除：

```text
支付成功
转账成功
交易成功
已收款
付款方式
零钱
银行卡
完成
返回
查看账单
交易详情
```

如果无法稳定识别：

```kotlin
merchant = null
```

仍可入账：

```text
微信支出 ¥32.00
```

后续 SmartLedger 可人工补商户。

---

## 5.7 Parser 接口

```kotlin
interface AccessibilityPaymentParser {
    fun parse(
        snapshot: UiSnapshot
    ): ParsedPayment?
}
```

微信：

```kotlin
object WeChatAccessibilityParser :
    AccessibilityPaymentParser
```

支付宝：

```kotlin
object AlipayAccessibilityParser :
    AccessibilityPaymentParser
```

禁止把两套规则全部塞进一个巨大 Parser。

---

# 任务 6：去重、诊断、测试和验收

## 6.1 三层去重

### 第一层：Accessibility 事件去重

```text
AccessibilityFingerprintCache
TTL = 15 秒
```

防止同一页面连续触发。

### 第二层：现有 DedupHelper

继续使用 SmartLedger 当前规则。

它已经能处理：

```text
相同金额
相同 type
时间窗口
商户匹配
跨支付渠道
```

典型：

```text
支付宝 Notification
¥32
        ↓
支付宝成功页 Accessibility
¥32
```

最终只保留一笔。

### 第三层：sourceKey

Accessibility：

```text
a11y:wechat:
a11y:alipay:
```

同一快照生成稳定 hash：

```text
SHA-256(
    package +
    amount +
    type +
    merchant +
    strongStatusText
)
```

---

## 6.2 不完整通知处理

本阶段不要为：

```text
“XX向你转了一笔钱”
```

创建金额为 0 的 Transaction。

规则：

```text
通知有完整金额
→ 原有 NotificationParser 正常处理

通知明确是交易但没有金额
→ 不正式入账

之后 Accessibility 获取到：
金额 + 强交易状态
→ 正常入账
```

如果一直没有打开显示金额的页面：

```text
这一笔仍然无法自动入账
```

不要为了“完整率”制造错误账目。

---

## 6.3 Debug 诊断

只在 Debug / 用户开启调试模式时显示：

```text
Accessibility 服务：Connected

最近事件：
微信 · 14:22:31

最近完整扫描：
微信 · 14:22:31

最近识别：
微信支出 ¥32.00
HIGH

今日：
Accessibility Event 382
Quick Probe 命中 8
Full Scan 7
成功识别 4
去重 2
```

Release 日志不要输出完整 UI 文本。

禁止：

```kotlin
Log.d(TAG, snapshot.toString())
```

只打印：

```text
package
eventType
nodeCount
matchedRule
amount
type
```

真实商户可选脱敏。

---

## 6.4 Parser 单元测试

测试 Parser 使用：

```text
UiSnapshot
```

不直接依赖 Android Accessibility API。

### 微信测试集

```text
支付成功 + ¥32
转账成功 + ¥500
已收款 + ¥500
红包已领取 + ¥12.88
已存入零钱 + 12.88
只有“红包已领取”无金额
只有“已收款”无金额
普通聊天“我给你500元”
普通聊天“这个东西¥32”
公众号文章“优惠价¥99”
历史聊天“对方已收款”
```

要求：

```text
前 5 个正确识别
后 5 个不得自动入账
```

### 支付宝测试集

```text
支付成功 + 金额
转账成功 + 金额
收款成功 + 金额
收到转账 + 交易成功 + 金额
退款成功 + 金额
只有“成功向你转了一笔钱”
营销“最高领取500元”
余额页面
账单列表同时出现多个金额
```

要求：

```text
明确单笔成功页面识别
模糊页面不自动记账
```

---

## 6.5 真机测试

必须在小米 HyperOS 真机完成。

测试场景：

```text
1. 微信扫码付款
2. 微信付款码付款
3. 微信给好友转账
4. 微信收到好友转账后打开聊天
5. 微信收到转账并点击进入详情
6. 微信收到红包并打开红包详情
7. 支付宝扫码付款
8. 支付宝给好友转账
9. 支付宝收到好友转账
10. 支付宝退款
11. 正常微信聊天 30 分钟
12. 微信群频繁滚动
13. 支付宝浏览首页/余额/账单
14. 锁屏再解锁
15. SmartLedger 被划出最近任务
```

每个场景记录：

```text
是否收到 AccessibilityEvent
quick probe 是否命中
full scan 节点数
解析结果
是否产生重复账
```

---

# 最终文件改动范围

```text
com.smartledger/

service/
├── PaymentNotificationListener.kt
│   └── 修改：保存逻辑交给 AutoRecordProcessor
│
├── AutoRecordProcessor.kt
├── AutoCaptureSource.kt
├── AccessibilityFingerprintCache.kt
│
└── accessibility/
    ├── PaymentAccessibilityService.kt
    ├── AccessibilityStatus.kt
    ├── UiTreeSnapshotExtractor.kt
    ├── PaymentSignalDetector.kt
    ├── AccessibilityFingerprintBuilder.kt
    ├── AccessibilityPaymentParser.kt
    ├── WeChatAccessibilityParser.kt
    └── AlipayAccessibilityParser.kt

res/xml/
└── payment_accessibility_service.xml

AndroidManifest.xml
└── 新增 PaymentAccessibilityService

设置页
└── 新增“微信/支付宝页面辅助识别”
```

本阶段不改：

```text
NotificationParser 现有通知规则
SmsReceiver
AI 顾问
AI 快捷记账
Statistics
Budget
Room schema
```

---

# 开发顺序

严格按以下顺序：

```text
1. 抽 AutoRecordProcessor
   → 确保原通知自动记账完全正常

2. 注册 PaymentAccessibilityService
   → 设置页可检测 Connected

3. 实现 UiTreeSnapshotExtractor
   → 真机调试能读取微信/支付宝可见 UI 文本

4. 实现二级过滤
   → 正常聊天不会频繁 full scan

5. 实现 WeChatAccessibilityParser
   → 微信支付/转账/收款/红包

6. 实现 AlipayAccessibilityParser
   → 支付/转账/收款/退款

7. 接 AutoRecordProcessor
   → Accessibility 自动形成正式账目

8. 验证 DedupHelper
   → 通知 + Accessibility 同笔交易只记一次

9. 跑 3~7 天真机数据
   → 根据真实漏记场景补规则

10. 只有 UI Tree 实测确实拿不到关键金额时
    → 再单独评估 OCR
```

---

# 完成验收标准

```text
□ 原有 Notification 自动记账行为无回归
□ 原有银行 / 支付宝通知识别不受影响

□ 无障碍只监听微信和支付宝
□ 不使用轮询
□ 不使用 OCR
□ 不截图
□ 不使用 WakeLock

□ 普通微信聊天不会生成账目
□ 普通支付宝浏览不会生成账目

□ 微信明确付款成功页面可自动记支出
□ 微信主动转账成功页面可自动记支出
□ 微信“已收款 + 明确金额”可自动记收入
□ 微信红包详情“明确金额 + 已领取/已存入零钱”可自动记收入

□ 支付宝付款成功页面可自动记支出
□ 支付宝主动转账成功页面可自动记支出
□ 支付宝收款/收到转账详情存在明确金额时可自动记收入
□ 支付宝退款成功页面可自动记收入

□ 只有“已收款/红包已领取/向你转了一笔钱”但没有金额时不误记

□ Accessibility 同页面连续事件不会重复入账
□ Notification + Accessibility 捕获同笔交易不会重复入账

□ 小米 HyperOS 连续使用 24~48 小时无明显异常耗电
□ 无障碍关闭后，SmartLedger 原有通知自动记账仍完全可用
```

完成这一阶段后，SmartLedger 的自动采集链路为：

```text
                     SmartLedger
                          │
          ┌───────────────┴───────────────┐
          │                               │
NotificationListener              AccessibilityService
          │                               │
支付宝/银行/云闪付等通知          微信/支付宝可见交易页面
          │                               │
 NotificationParser               UI Tree
          │                               │
          │                     二级过滤 + 专用 Parser
          │                               │
          └───────────────┬───────────────┘
                          ↓
                 AutoRecordProcessor
                          ↓
                      DedupHelper
                          ↓
                    SmartCategorizer
                          ↓
                         Room
```

下一阶段是否加入 OCR，只根据真机 3~7 天实测结果决定；如果关键支付页面的金额和状态已经能通过 Accessibility UI Tree 获取，则不增加 OCR。

---

# 附录 A：开发前评审结论与补充设计（对真实代码库逐条核对后回写）

## A.1 与真实代码核对无冲突的部分

- `ParsedPayment` 字段（amount/merchant/paymentMethod/notificationKey/type/confidence/uncertainReason/rawSnippet）与方案 5.x 输出一致；`ParseConfidence` 只有 HIGH/UNCERTAIN 两档。
- `DedupHelper.findDuplicate(dao, amountCents, type, merchant, paymentMethod, now)` / `mergeIfDuplicate(dao, existing, newMethod, newMerchant)` 签名一致，可直接复用。
- `SmartCategorizer.categorize(merchant, paymentMethod, note, categories, type)` 一致。
- 通知链路现在就写 `source = "auto"`，Accessibility 沿用，不改 schema。
- `shouldAskConfirm` 依赖 prefs（`smart_ledger`）的 `confirm_all_auto` / `confirm_uncertain`（默认 true）——抽入 AutoRecordProcessor 后语义不变。
- 设置页 `SettingsScreen` 已有「自动记账」分组（模糊确认/全部确认/通知使用权/悬浮窗/电池优化），新入口加在这里，不需要重复建「通知识别」项。
- `SmsReceiver` 是独立保存路径（source="sms"），本阶段不动，`AutoCaptureSource.SMS` 仅作枚举预留。

## A.2 需修正的设计缺陷（C1~C8）

| # | 问题 | 修正 |
|---|---|---|
| C1 | 3.4 的 `quickProbe` 调用了 `UiTreeSnapshotExtractor.extractShallow`，但 4.1 只定义了 `extract`，无实现 | 补齐：`extractShallow(source, maxNodes=40, maxDepth=4)` 返回 `List<String>`（DFS 深度≤4、最多 40 节点、只收 text/contentDescription、过 normalize） |
| C2 | 4.2 `CoroutineScope(Dispatchers.IO).launch` 是裸作用域，泄漏 | Service 持有 `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`，`onDestroy` 取消（与 PaymentNotificationListener 同模式） |
| C3 | **词包含陷阱**：“对方已收款”.contains("已收款")、"待收款".contains("收款") —— 直接 contains 会把付款方视角的历史聊天误判为收入 | Parser 内**排除词先于状态词匹配**（待收款/对方已收款/已退还等）；与 SuggestedBudgetExtractor 正则陷阱同类教训，单测锁定 |
| C4 | `AccessibilityStatus` 无设计 | 参照 `ListenerStatus`：prefs `smart_ledger` + 进程内 AtomicBoolean；UI 的「已开启/去开启」用系统查询 `isPaymentAccessibilityEnabled`，存活标志仅诊断 |
| C5 | 6.3 诊断无存储设计 | 新增 `AccessibilityDiagnostics`：内存 ring（最近 8 条事件摘要）+ prefs 按日重置计数（events/probeHits/fullScans/recognized/deduped）；仅 debug_toasts 或 BuildConfig.DEBUG 展示 |
| C6 | 4.2 full scan 的树遍历（300 节点 binder 调用）在主线程 runnable 里做，会掉帧 | 主线程只做 debounce + `rootInActiveWindow` 判空，`extract + parse + fingerprint + process` 全部切 IO（AccessibilityNodeInfo 可跨线程使用，Parcelable） |
| C7 | 4.3 探测正则与 5.5 提取逻辑混用风险 | 探测（SignalDetector 存在性判断）与提取（ScreenAmountExtractor 打分制）用两套正则，提取正则采用 SuggestedBudgetExtractor 修复后的形式（千分位分支 `+`） |
| C8 | C 方案里 fingerprint 字段描述两处不一致（1.3 与 6.1） | 统一为：`sha256("pkg|type|amountCents|merchantOrEmpty|statusWord")` 取前 16 hex，key = `a11y:<wechat|alipay>:<hash>`，同时作为 notificationKey 与 fingerprint cache 的 key |

## A.3 补齐的缺口（M1~M5）

- **M1 状态词-金额邻近性约束**（新增，防聊天页误入账）：历史聊天里可能同时存在「已收款」旧消息与新消息金额，SignalDetector 是整页文本判断拦不住。Parser 要求**状态词命中节点与金额候选节点的 index 距离 ≤ 4**，支付成功页天然满足，聊天记录里两者距离远。与 C3 排除词双保险。
- **M2 金额打分制阈值**：`MIN_SCORE = 60`。分值：¥/￥ 前缀 +100、带「元」 +80、邻近「实付/金额/收款/转账金额」≤2 节点 +80、全页唯一候选 +60、裸数字整数≥6位 −100、邻近「订单号/交易号/单号」−100、邻近「时间/日期」−80、值域 (0, 1000000] 外丢弃。红包页的「12.88」裸数字靠「唯一候选 +60」恰好过阈值。
- **M3 合规缺口**：隐私政策需补「页面辅助识别（可选功能）」章节（默认关闭/仅微信支付宝/只提取金额与商户与状态词/不保存页面全文/不上传）；`strings.xml` 补 `accessibility_payment_description` 文案。
- **M4 App 内开关**：prefs `smart_ledger` 的 `accessibility_auto_record_enabled`（默认 true）；Service 每次事件回调时读取（prefs 读是内存操作，成本可忽略）。
- **M5 单测规划**（全部纯 JVM，UiSnapshot/UiTextNode 不依赖 Android API）：

```text
UiTreeTextNormalizerTest     ~8   ￥→¥、全角冒号、空白归一、换行合并
PaymentSignalDetectorTest    ~10  状态词+金额双条件；仅金额/仅状态词/营销词不触发
ScreenAmountExtractorTest    ~12  打分制各分支、阈值拒绝、订单号/时间负分、千分位
MerchantPickerTest           ~6   金额邻近 1~4 节点、排除词表、不可识别返回 null
WeChatAccessibilityParserTest ~10  方案 6.4 微信集（5 正 5 负，含词包含陷阱用例）
AlipayAccessibilityParserTest ~8   方案 6.4 支付宝集（5 正 3 负）
AccessibilityFingerprintCacheTest ~4  TTL 内去重、过期重处理（now 注入可测）
AccessibilityFingerprintBuilderTest ~4  同输入同 hash、字段变化 hash 变、null merchant 稳定
```

合计约 60 例。`AccessibilityFingerprintCache.shouldProcess` 增加 `now` 默认参数以支持时钟注入。

---

# 附录 B：实现记录（开发完成后回写）

## B.1 验证结果汇总

| 验证项 | 手段 | 结果 |
|---|---|---|
| 纯逻辑单测 | `testDebugUnitTest` | ✅ **402 例全绿**（原 325 + 本次新增 77，共 11 个新增测试类，零失败零错误） |
| 新增测试覆盖 | 方案 6.4 微信 10 用例 + 支付宝 8 用例全部落地，另含归一化/信号/金额打分/商户/指纹缓存/指纹构建 | ✅ |
| Debug 构建 | `assembleDebug` | ✅ |
| Release + R8 | `minifyReleaseWithR8`（签名 keystore 属环境配置，不在代码仓库） | ✅ mapping 中 accessibility 包 423 条存活，`PaymentAccessibilityService` 类名保留（Manifest keep），无剥离 |
| 通知链路零回归 | 逐行比对平移：prefs key（confirm_all_auto/confirm_uncertain/debug_toasts）、PendingConfirmStore 参数、NotificationStyle 调用、Transaction 字段（source="auto"）全部一致；askUserConfirm 切回主线程执行 | ✅ |
| C9 终身去重覆盖确认分支 | 核对 ConfirmPaymentActivity L332 保留 pending.notificationKey 入库 | ✅ |
| 方案红线自查 | 不轮询/不 WakeLock/不前台服务/不截图/不 OCR/只监听两个包名/树上限 300 节点/15 秒指纹去重 | ✅ |

## B.2 实际落地文件

新增（14 个主代码 + 8 个测试）：

```text
service/AutoCaptureSource.kt                       采集来源枚举（SMS 预留）
service/AutoRecordProcessor.kt                     统一入账处理器（含 C9 终身去重）
service/AccessibilityFingerprintCache.kt           15s 事件去重（时钟可注入）
service/accessibility/AccessibilityStatus.kt       状态（系统查询 + 存活标志）
service/accessibility/AccessibilityDiagnostics.kt  诊断（ring + 按日计数）
service/accessibility/PaymentAccessibilityService.kt 主服务（二级过滤/防抖/IO 线程扫描）
service/accessibility/UiTreeSnapshotExtractor.kt  快照 + extractShallow + 归一化
service/accessibility/PaymentSignalDetector.kt     强信号探测（含 C10 补词）
service/accessibility/ScreenAmountExtractor.kt     金额打分制（MIN_SCORE=60）
service/accessibility/MerchantPicker.kt            商户提取（可空）
service/accessibility/AccessibilityPaymentParser.kt 接口 + 公共识别框架（排除词优先/邻近性/方向唯一）
service/accessibility/WeChatAccessibilityParser.kt
service/accessibility/AlipayAccessibilityParser.kt
res/xml/payment_accessibility_service.xml
+ test/…：UiTreeTextNormalizerTest(8) PaymentSignalDetectorTest(10)
  ScreenAmountExtractorTest(14) MerchantPickerTest(8)
  WeChatAccessibilityParserTest(15) AlipayAccessibilityParserTest(13)
  AccessibilityFingerprintCacheTest(5) AccessibilityFingerprintBuilderTest(4)
```

修改（6 个）：`PaymentNotificationListener`（-108 行，保存逻辑移交）、`TransactionDao`（+getByNotificationKeyOnce）、`AndroidManifest`（service 注册）、`strings.xml`、`SettingsScreen`（自动记账分组 +3 入口）、`PrivacyPolicyScreen`（无障碍章节 + 权利第 7 项 + 修订说明）。

## B.3 开发中发现并修掉的问题

1. **诊断计数重复累计**：`readCounts` 读时合并 prefs 与内存计数但未结算，每次打开诊断弹窗会把同一批进程内计数再加一遍。修复为「读取即结算」（并入 prefs 并清零内存）。
2. **诊断长文本溢出**：`SmartLedgerDialog.text` 无滚动限制，诊断文本（今日计数 + 最近动态）在小屏可能顶出弹窗。改用 content + `heightIn(max=360.dp)` + verticalScroll。
3. **编译期问题**：`AccessibilityServiceInfo` 的正确包名是 `android.accessibilityservice`（非 view.accessibility）；public object 继承 internal 基类的可见性冲突（基类改 public）；ScreenAmountExtractor 初版的无标签 return 调换为单节点提取函数。

## B.4 与方案的偏差

- 金额打分制的「-100 8 位以上连续数字」细化为「裸数字整数≥7 位」（带 ¥/元 前缀的金额不受限）；裸数字候选要求**整节点纯金额形态**（混合文本里的数字不收），进一步降低订单号/时间误报。
- 支付宝 income 词表**未加**「交易成功」（无方向词，账单列表会误触），场景 G 靠「收到转账」（信号层与 Parser 层同步补齐，C10）。
- `AutoCaptureSource.SMS` 为预留，SmsReceiver 未迁移（方案原文即如此）。
- 诊断 ring 的记录始终运行（开销纳秒级），展示受 debug_toasts 控制（方案要求「只在调试模式显示」，记录为展示的前提）。

## B.5 遗留事项（需真机，本机无设备）

1. 方案 6.5 的 15 个真机场景（微信扫码/付款码/转账/红包、支付宝支付/转账/退款、聊天 30 分钟、锁屏、划后台）与耗电观测，必须小米 HyperOS 实测。
2. 3~7 天真实数据回填：根据真实漏记页面补词形与规则。
3. WebView/H5 页面的金额可达性待实测（部分支付宝页面是 H5，无障碍树可能拿不到内容）；实测拿不到再评估 OCR（方案原文约定）。

---

# 附录 C：外部 Code Review 后修复记录（P0×3 + P1×2 + P2×4 全部修复）

> Review 结论：架构优于平均，但 3 个 P0 会导致核心目标落空，不建议发布。本次全部修复并补齐测试缺口，验证 429 例全绿。

## C.1 P0 修复

**P0-1 packageNames 前导空格（微信侧静默失效）**：aapt2 把 XML 多行缩进转成空格，AOSP `split("(\\s)*,(\\s)*")` 只吃逗号两侧空白不 trim 首尾，第一个包名带 9 个前导空格永不匹配。修复：单行化 + XML 注释写明陷阱；新增 `tools/verify_a11y_config.py`（aapt2 编译产物逐字符校验 + 源码常量交叉比对 + 事件 flag 校验），反证实验确认多行格式会被脚本拦住。**此后每次发版应跑一次该校验。**

**P0-2 终身去重吞掉同额新交易**：指纹不含时间，「每天同一家店 8 元早餐」第二天起永久漏记。修复：新增 `TransactionTimeExtractor`（页面交易时间文本优先，完整日期时间 > 月日时间 > 纯时间），作为指纹第 6 字段；无时间文本（红包页）时退化到 `capturedAt` 归一到天 —— 漏记窗口从永久压缩到一天，代价仅是隔天重开无时间页的误记可被用户发现并删。测试：两笔同额不同时间→不同 key；重开历史页→同 key；无时间隔天→不同 key。

**P0-3 积分/理财误记收入且静默入账**：三层修复——
1. 框架新增 `incomeExcludedWords`（积分/优惠券/理财/余额宝/签到/提现等），**只拦收入方向不拦支出**（支付成功页常带「本单获得 X 积分」，整页排除会误杀真实支出）；
2. 「已到账」从两层词表移除（信号层 + Parser 层同步，真实到账总有更具体的词形），只保留「到账成功」；
3. 设置页新增独立开关「页面识别需确认」（`confirm_accessibility`，默认关），让谨慎用户可逐笔确认。

## C.2 P1 / P2 修复

- **P1-4**：MerchantPicker 补齐支付页标签词（收款方/付款方/交易时间等 13 个）+ 新增「标签-值」结构识别（`收款方` 的下一个节点即值）+ 营销文案模式排除（满 X 元可用 / 满 X 减 Y / 券后）+ 操作按钮词排除。review 实测的「收款方被当商户」「满200元可用被当商户」均已用例锁定。
- **P1-5**：extractShallow 的 contentDescription 改为与本节点 text 比较（原与 texts.lastOrNull() 比较会跨节点串味，节点2 的 desc 与节点1 的 text 相同时被误丢）。
- **P2-6**：quickProbe 先做零成本的 event.text 判断，非空且无信号直接返回，只有文本为空才读 event.source 做树遍历（页面级变化已由 WINDOW_STATE_CHANGED 兑住）。
- **P2-7**：extract 新增视图节点访问上限 800（MAX_NODES 只限文本节点，无文本容器仍会产生 binder 调用）。
- **P2-8**：C9 终身去重改用 `source == ACCESSIBILITY` 枚举判断，`source` 参数名副其实。
- **P2-9**：AMOUNT_WITH_PREFIX / AMOUNT_WITH_UNIT 的 `[0-9,]*` 改为严格千分位形式（与 BARE_AMOUNT / SuggestedBudgetExtractor 统一），`¥12,34` 不再拼成 1234。

## C.3 测试与验证

- 新增 27 例（总 429 全绿）：TransactionTimeExtractorTest(7)、指纹时间区分(3)、微信营销负例(5)、支付宝营销负例(5)、MerchantPicker 真实页面结构(5)、畸形千分位(2) 等
- `verify_a11y_config.py` 反证：多行格式报红（检出 9 个空白）、单行格式全过
- assembleDebug / minifyReleaseWithR8 / accessibilityEventTypes flag(0x820) 全部通过
- review 指出的 4 类测试缺口（真实页面结构 / 两笔同额 / 营销误记 / XML 产物校验）全部补齐

## C.4 遗留

- 营销词表基于典型结构推断，真机拿到真实页面文本后需回填校准（review 建议的 B→C 路径）
- 「页面识别需确认」默认关闭；若真机误记率高于预期，可考虑改默认开
- 其余真机事项同附录 B.5

---

# 附录 C5：第二轮 Review 对抗性测试修复（N1/N2/N3 + 次要×2）

> 第二轮 review 逐项验证了 9 项修复（全部确认到位）后，用 4 个对抗场景发现 3 个「修复引入的新问题」：N1 重复记账、N2+N3 漏记（同根因两症状）。全部修复，445 例全绿。

## C5.1 N1：TIME_ONLY 兜底导致指纹随滚动漂移 → 同一笔红包重复记账

**问题**：孤立的 `14:22` 会被 TIME_ONLY 当作交易时间纳入指纹；微信聊天页的时间分隔符恰好就是「14:22」（当天）与「9月9日 14:22」（跨天）形态 —— 用户领红包后滚动聊天页，分隔符变化 → 同一笔红包指纹漂移 → 超过 2 分钟去重窗口后重复记账。

**修复（比 review 建议更进一步）**：review 建议直接删掉 TIME_ONLY 兜底；但发现微信**跨天**分隔符同样会撞上 MONTH_DAY_TIME，于是改为**分级信任**：
- FULL_DATETIME（含年份）：聊天分隔符不含年份，整页搜索即安全；
- MONTH_DAY_TIME / TIME_ONLY：必须**邻近时间标签**（≤2 节点，或同节点含标签如「交易时间 14:22」）才认定为交易时间；
- 都不满足 → 退化到按天（同 C.1 P0-2），指纹稳定。

用例锁定：滚动前后同 key、跨天分隔符变化不漂移、孤立时间/倒计时不采用、四种标签形态（独立标签/同节点/月日/带冒号）采用。

## C5.2 N2：营销词整页 contains 误杀带横幅的真实收款

**问题**：「收款成功 ¥100」页底部有「本单获得5积分」横幅 → 整页 contains「积分」命中 → 整笔 100 元真实收入被丢弃。P0-3 的分层解决了支出侧，收入侧的对称问题仍在。

**修复**：营销词命中节点必须**邻近状态词命中节点**（≤2 节点，`MARKETING_STATUS_MAX_DISTANCE`）才判定为营销页 —— 复用 M1 的邻近性思路。同节点的「积分已到账」（距离 0）被拦，页面底部横幅（距离 ≥3）不连坐。

## C5.3 N3：移除「已到账」是过度修复 → 真实到账漏记

**问题**：「已到账」是转账/工资/退款到账的高频词形，移除后「转账已到账 ¥500」漏记。N2 修好后这个移除就成了多余的（同一个根因两个症状）。

**修复**：把「已到账」加回两层词表（STRONG_WORDS + 两个 Parser 的 incomeWords），误记由 N2 邻近性判断拦住。双向用例锁定：「转账已到账 ¥500」记收入 /「积分已到账 ¥20」不记 /「余额宝收益已到账」不记 /「工资已到账 ¥8,000」记收入。

## C5.4 次要观察两项

- MerchantPicker 开头显式 `sortedBy { it.index }`：标签-值结构依赖「下一节点」语义，不再依赖调用方传入顺序的隐式契约；
- 通用词标签（商品/订单/备注等 6 个）从 EXCLUDED_WORDS（contains）移入 GENERIC_LABELS（**整节点相等**）：「商品街便利店」「订单来了餐厅」不再被误杀，整节点恰好是「商品」时仍被排除。

## C5.5 验证

- 445 例全绿（429 + 16 新增：N1×5、N2/N3×8、次要×3）；对抗场景全部转为准常驻用例
- assembleDebug / minifyReleaseWithR8 / verify_a11y_config.py 全部通过
- 第二轮 review 的 9 项修复确认清单全部复核无回归

---

# 附录 C6：真机校准（9/10 首轮真机测试三场景全失效）

## C6.1 现象与词形修复

用户真机实测（微信转出 0.02 / 微信被转 0.02 / 支付宝转出 0.01）全部未自动记账。用户提供的真实页面文本：

| 场景 | 真实页面文字 | 处理 |
|---|---|---|
| 微信转出（瞬间） | 支付成功 | expense ✅（原词表已有）|
| 微信转出（跳转后详情页，停留更久） | 待xxx 确认收款 / 你发起了一笔转账 | **expense 新增**（防抖后扫到的往往是此页；「待张三确认收款」不含连续「待收款/待确认」子串，不被排除词误拦）|
| 微信转出（对方收款后回看） | 已收款 / 你发起了一笔转账 | **视角排除新增**：income 拒绝（「我的转出被收到」非我的收入）|
| 微信被转 | 已被接收 / 已收款 | income（新增「已被接收」）|
| 支付宝转出 | 转账成功 / 金额 / 收款方：xxx / 交易方式：xxx | expense（已有）+ 标签-值取商户 + 「交易方式」等标签词排除 |

同步修改：STRONG_WORDS 补「你发起了一笔转账 / 已被接收 / 已转账 / 已收钱」；MerchantPicker 排除词补状态词与支付宝标签词形。

## C6.2 新设计：incomeViewExcludedWords（转出方视角排除）

微信转出方详情页在对方收款后状态变「已收款」—— income 词命中 + 金额在 + 邻近满足，六步全会通过而误记收入。新增框架字段 `incomeViewExcludedWords`（整页 contains，仅 income 方向）：页面含「你发起了一笔转账」时 income 一律拒绝。与 C3 强排除词的分工：强排除拦「交易未完成」，视角排除拦「转出方回看历史页」。

## C6.3 诊断增强：最近扫描文本样本（词形校准闭环）

AccessibilityDiagnostics 新增 `lastScanSample`：调试模式下保存最近一次完整扫描的前 30 条节点文本（每条截断 24 字，内存 only，重启即清，关开关不记录）。信号命中与否都记录 —— 词形不匹配的页面正是需要看到的。设置页「采集诊断」弹窗展示。隐私政策已同步补充。

## C6.4 待用户自查的链路断点（三场景词形基本已覆盖，全失效仍指向服务未运行）

用户实测三场景的状态词（支付成功/已收款/转账成功）原本就在词表内 —— 若服务在运行，至少部分场景应被识别。诊断弹窗读法：
- 「Accessibility 事件：0」→ 服务未连接（系统权限未开或被厂商省电策略杀掉）
- 「事件 > 0，完整扫描：0」→ 事件类型未匹配（不应发生）
- 「完整扫描 > 0，成功识别：0」+ 样本文本 → 词形问题（看样本校准）
- 样本为空且扫描 > 0 → 页面为 H5 或自绘，无障碍树拿不到文本（需评估 OCR）

## C6.5 验证

455 例全绿（445 + 10 新增真机词形用例）；assembleDebug / minifyReleaseWithR8 / verify_a11y_config.py 全部通过。

## C6.6 第二批真机反馈（同日）：「您已收款」页面 + 采集不到问题

用户提供收款页完整文本：「您已收款，资金已存入零钱 / 0.01 / 零钱余额 / 转账时间：xxx / 收款时间：xxx」。

1. **词形已覆盖**：「已收款」「已存入零钱」均在词表（contains 命中），无需新词；但暴露了**零钱余额数字干扰**风险 —— 新增打分规则「裸数字与状态词命中节点 ≤ 2 → +80」：转账金额紧贴状态词，余额数字不会，这是两者的关键区分器（无此规则时双裸数字候选均 0 分全部淘汰 → 漏记）。状态词索引由 Parser 传入 extractBest。
2. **撤销 P2-6 短路盲区（采集不到的主要嫌疑）**：P2-6 的「event.text 有内容但无信号 → 直接判负不读树」隐含假设「信号会出现在事件增量文本里」。真机微信页面是碎片化渲染：每次 CONTENT_CHANGED 只带一小块文本（单独的金额「0.01」或「零钱余额」），谁都不含完整强词 → 全部被拦；若页面又是 fragment 级切换（无 STATE_CHANGED 兑底）→ 一次扫描都不会发生。恢复「文本无信号也做浅层树探测」（保留「文本有信号立即命中」的快速路径）。主线程 40 节点浅层 DFS 是方案 3.4 原始设计，成本可接受。
3. **诊断盲点修复**：rootInActiveWindow 为 null 时原实现静默 return（连扫描计数都不涨）→ 新增 onRootUnavailable 进诊断 ring；「窗口不可用」条目出现说明事件已到达。
4. **诊断线程安全**：performFullScan 的 IO 协程一直在调 onRecognized/onDeduped，而 recentEvents(ArrayDeque)/memCounts(IntArray) 非线程安全 → 改 ConcurrentLinkedDeque + AtomicIntegerArray。

验证：456 例全绿（455 + 1 收款页用例）；新 APK 17:04 构建；R8/校验脚本通过。
