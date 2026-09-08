# SmartLedger ProGuard Rules
-keep class com.smartledger.data.db.entity.** { *; }
-keep class com.smartledger.service.PaymentNotificationListener { *; }
-keep class com.smartledger.service.KeepAliveService { *; }
-keep class com.smartledger.service.FloatingWindowService { *; }
-keep class com.smartledger.service.BootReceiver { *; }
-keep class com.smartledger.service.PackageReplacedReceiver { *; }
-keep class com.smartledger.service.SmsReceiver { *; }
-keep class com.smartledger.util.ListenerRebindWorker { *; }

# ═══ AI 二开新增 ═══
#
# Room 的查询投影 POJO（TxPoint）与聚合模型。
# Room 生成的 DAO 实现是直接 new 出这些对象的，不走反射，
# 理论上不需要 keep；但保留成员名可以避免 R8 在极端优化下
# 改名字段后与 Cursor 列名对不上（这类问题只在 release 包出现，极难排查）。
#
# 注：本次聚合只在 DAO 里用了 `getPointsInRange` 一个投影，
# 分类/商户/时段的分组全部在 Kotlin 侧完成（时区正确性要求，
# 详见 TransactionDao 注释），因此没有额外的 Row POJO。
-keep class com.smartledger.data.analytics.model.** { *; }
-keep class com.smartledger.data.db.dao.CategoryTotal { *; }

# AI 请求 / 响应的数据类。同样是用 org.json 手工读写，不依赖反射，
# 但枚举的 name() 会被持久化到 prefs 与数据库（preset、periodType、source），
# 必须保证枚举成员名不被混淆。
-keepclassmembers enum com.smartledger.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# 网络层：HttpURLConnection 由平台提供，无需 keep。
# 明确不引入 OkHttp / kotlinx.serialization / Gson，因此这里没有对应的规则 ——
# 少一套反射式序列化，就少一类只在 release 包复现的混淆事故。
