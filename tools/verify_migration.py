#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Room MIGRATION_1_2 的真实校验。

做法：用 sqlite3 造一个**真实的 v1 数据库**（含账单、分类、重复月份的预算、
以及依赖 budgets 外键的分类预算行），执行与 AppDatabase.MIGRATION_1_2
逐字一致的 SQL，再把结果 schema 与 Room 导出的 2.json 期望值逐列比对。

这一步是整个二开里唯一不可回滚的改动（迁移写错=用户账单丢失或全员启动崩溃），
因此不能靠人眼审查，必须实测。
"""
import json
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA_JSON = os.path.join(ROOT, "app/schemas/com.smartledger.data.db.AppDatabase/2.json")
DB = "/tmp/migration_test.db"
APP_DB_KT = os.path.join(ROOT, "app/src/main/java/com/smartledger/data/db/AppDatabase.kt")

# ─── v1 的真实建表语句（由 v1 实体反推，与 2.json 中未变更的表完全一致）───
V1_DDL = [
    """CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `color` INTEGER NOT NULL, `type` TEXT NOT NULL,
        `sortOrder` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `amount` REAL NOT NULL, `type` TEXT NOT NULL, `categoryId` INTEGER, `merchant` TEXT,
        `paymentMethod` TEXT, `note` TEXT, `source` TEXT NOT NULL, `notificationKey` TEXT,
        `transactionTime` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""",
    "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_transactionTime` ON `transactions` (`transactionTime`)",
    "CREATE INDEX IF NOT EXISTS `index_transactions_notificationKey` ON `transactions` (`notificationKey`)",
    # v1 的 budgets：没有 source 列，没有唯一索引
    """CREATE TABLE IF NOT EXISTS `budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `yearMonth` TEXT NOT NULL, `totalIncomeTarget` REAL, `totalExpenseLimit` REAL,
        `createdAt` INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS `category_budgets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `budgetId` INTEGER NOT NULL, `categoryId` INTEGER NOT NULL, `amountLimit` REAL NOT NULL,
        FOREIGN KEY(`budgetId`) REFERENCES `budgets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    "CREATE INDEX IF NOT EXISTS `index_category_budgets_budgetId` ON `category_budgets` (`budgetId`)",
    "CREATE INDEX IF NOT EXISTS `index_category_budgets_categoryId` ON `category_budgets` (`categoryId`)",
]


def extract_migration_sql():
    """
    从 AppDatabase.kt 里把 MIGRATION_1_2 的 SQL 原文抠出来，
    保证测的就是要发布的代码，而不是手抄一份。

    必须按源码**出现顺序**返回：迁移里「先去重再建唯一索引」的
    先后关系是有意义的，顺序错了测试就会假阳性。
    """
    src = open(APP_DB_KT, encoding="utf-8").read()
    start = src.index("val MIGRATION_1_2")
    end = src.index("fun getInstance", start)
    body = src[start:end]

    stmts = []
    i = 0
    while True:
        k = body.find("db.execSQL(", i)
        if k < 0:
            break
        parts, i = _parse_concat_expr(body, k + len("db.execSQL("))
        joined = "".join(parts)
        # Kotlin 的 trimIndent() 只影响空白；SQL 里把连续空白压成一个空格语义不变
        norm = re.sub(r"\s+", " ", joined).strip()
        if norm:
            stmts.append(norm)
    return stmts


def _parse_concat_expr(s, i):
    """
    从 `db.execSQL(` 之后开始，解析一串用 + 拼接的字符串字面量，
    返回 (字面量内容列表, 结束位置)。

    不能用 split(")") 之类的粗暴做法：SQL 字符串里本身就含括号，
    例如 "ON `ai_reports` (`periodStart`, `periodEnd`)"，
    按括号切会把语句截断成不完整的 SQL。
    这里在**字面量外部**跟踪括号深度，遇到深度 0 的 ) 才算 execSQL 结束。
    """
    parts = []
    depth = 0
    while i < len(s):
        c = s[i]
        if c == '"':
            if s.startswith('"""', i):
                close = s.index('"""', i + 3)
                parts.append(s[i + 3:close])
                i = close + 3
            else:
                j = i + 1
                buf = []
                while j < len(s):
                    if s[j] == "\\":
                        buf.append(_unescape(s[j:j + 2]))
                        j += 2
                        continue
                    if s[j] == '"':
                        break
                    buf.append(s[j])
                    j += 1
                parts.append("".join(buf))
                i = j + 1
            continue
        if c == "+":
            i += 1
            continue
        if c == "(":
            depth += 1
            i += 1
            continue
        if c == ")":
            if depth == 0:
                return parts, i + 1
            depth -= 1
            i += 1
            continue
        i += 1
    raise ValueError("db.execSQL( 括号不平衡")


def _unescape(seq):
    return {"\\n": "\n", "\\t": "\t", "\\\\": "\\",
            '\\"': '"', "\\$": "$"}.get(seq, seq)


def norm_cols(db):
    """按 Room 的口径归一化实际 schema：列名/类型/notNull/defaultValue"""
    result = {}
    for (tbl,) in db.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
        "AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'"
    ).fetchall():
        cols = []
        for row in db.execute(f"PRAGMA table_info(`{tbl}`)").fetchall():
            # cid, name, type, notnull, dflt_value, pk
            cols.append({
                "columnName": row[1],
                "affinity": row[2],
                "notNull": bool(row[3]),
                "defaultValue": row[4],
            })
        idx = []
        for row in db.execute(f"PRAGMA index_list(`{tbl}`)").fetchall():
            name, unique = row[1], bool(row[2])
            if name.startswith("sqlite_autoindex"):
                continue
            icols = [r[2] for r in db.execute(f"PRAGMA index_info(`{name}`)").fetchall()]
            idx.append({"name": name, "unique": unique, "columnNames": icols})
        result[tbl] = {"columns": cols, "indices": idx}
    return result


def main():
    if os.path.exists(DB):
        os.remove(DB)

    sqls = extract_migration_sql()
    print("从 AppDatabase.kt 提取到 %d 条迁移 SQL：" % len(sqls))
    for s in sqls:
        print("   •", " ".join(s.split())[:100] + ("…" if len(s) > 100 else ""))
    print()

    con = sqlite3.connect(DB, isolation_level=None)
    # Room 迁移运行在外键开启的环境下，这里同样开启，
    # 以便暴露「DROP 父表连带 CASCADE 删子表」这类真实风险。
    # isolation_level=None 才能在事务外设置该 pragma，并手动控制 BEGIN/COMMIT。
    con.execute("PRAGMA foreign_keys = ON")
    for ddl in V1_DDL:
        con.execute(ddl)

    # ── 造真实数据 ──
    con.execute("INSERT INTO categories (id,name,icon,color,type,sortOrder,isDefault) VALUES "
                "(1,'餐饮','Restaurant',4294923042,'expense',0,1),"
                "(2,'交通','DirectionsCar',4280391411,'expense',1,1),"
                "(11,'工资','Work',4283215696,'income',0,1)")
    for i in range(1, 21):
        con.execute(
            "INSERT INTO transactions (amount,type,categoryId,merchant,paymentMethod,note,source,"
            "notificationKey,transactionTime,createdAt) VALUES (?,?,?,?,?,?,?,?,?,?)",
            (10.0 + i, "expense", 1 + (i % 2), f"商户{i}", "微信", f"备注{i}", "auto",
             f"key{i}", 1788700000000 + i * 1000, 1788700000000 + i * 1000))

    # 关键：**同月重复的预算行**（v1 缺唯一索引时确实会产生）
    con.execute("INSERT INTO budgets (id,yearMonth,totalIncomeTarget,totalExpenseLimit,createdAt) "
                "VALUES (1,'2026-08',10000.0,4000.0,100)")
    con.execute("INSERT INTO budgets (id,yearMonth,totalIncomeTarget,totalExpenseLimit,createdAt) "
                "VALUES (2,'2026-08',10000.0,5000.0,200)")   # 同月第二条，额度不同
    con.execute("INSERT INTO budgets (id,yearMonth,totalIncomeTarget,totalExpenseLimit,createdAt) "
                "VALUES (3,'2026-09',12000.0,6000.0,300)")
    # 依赖 budgets 的外键子行：迁移若误用 DROP 会连带删光
    con.execute("INSERT INTO category_budgets (id,budgetId,categoryId,amountLimit) VALUES "
                "(1,2,1,1500.0),(2,2,2,800.0),(3,3,1,2000.0)")
    con.commit()

    before = {
        "transactions": con.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],
        "categories": con.execute("SELECT COUNT(*) FROM categories").fetchone()[0],
        "budgets": con.execute("SELECT COUNT(*) FROM budgets").fetchone()[0],
        "category_budgets": con.execute("SELECT COUNT(*) FROM category_budgets").fetchone()[0],
        "tx_sum": con.execute("SELECT SUM(amount) FROM transactions").fetchone()[0],
    }
    print("迁移前：", before)

    # ── 执行迁移（与 App 里完全相同的语句与顺序）──
    con.execute("BEGIN")
    for s in sqls:
        con.execute(s)
    con.commit()
    print("迁移执行成功\n")

    after = {
        "transactions": con.execute("SELECT COUNT(*) FROM transactions").fetchone()[0],
        "categories": con.execute("SELECT COUNT(*) FROM categories").fetchone()[0],
        "budgets": con.execute("SELECT COUNT(*) FROM budgets").fetchone()[0],
        "category_budgets": con.execute("SELECT COUNT(*) FROM category_budgets").fetchone()[0],
        "tx_sum": con.execute("SELECT SUM(amount) FROM transactions").fetchone()[0],
    }
    print("迁移后：", after)

    failures = []

    # ═══ 1. 数据完整性 ═══
    for k in ("transactions", "categories", "tx_sum"):
        if before[k] != after[k]:
            failures.append(f"数据丢失：{k} {before[k]} → {after[k]}")
    if after["category_budgets"] != before["category_budgets"]:
        failures.append(
            f"外键连带删除：category_budgets {before['category_budgets']} → {after['category_budgets']}")
    if after["budgets"] != 2:
        failures.append(f"同月预算未正确去重，期望 2 行，实际 {after['budgets']} 行")

    # 去重必须保留 id 最大的那条（最后一次写入的额度 5000）
    row = con.execute("SELECT id,totalExpenseLimit,source FROM budgets WHERE yearMonth='2026-08'").fetchone()
    if row is None:
        failures.append("2026-08 预算行丢失")
    else:
        if row[0] != 2 or row[1] != 5000.0:
            failures.append(f"去重保留错了行：id={row[0]} limit={row[1]}，期望 id=2 limit=5000.0")
        if row[2] is not None:
            failures.append(f"老预算行的 source 应为 NULL（读作 MANUAL），实际={row[2]!r}")

    # 收入目标不能被迁移影响
    ti = con.execute("SELECT totalIncomeTarget FROM budgets WHERE yearMonth='2026-09'").fetchone()[0]
    if ti != 12000.0:
        failures.append(f"totalIncomeTarget 被迁移破坏：{ti}")

    # 账单外键仍然有效
    fk_ok = con.execute(
        "SELECT COUNT(*) FROM transactions t LEFT JOIN categories c ON t.categoryId=c.id "
        "WHERE t.categoryId IS NOT NULL AND c.id IS NULL").fetchone()[0]
    if fk_ok != 0:
        failures.append(f"{fk_ok} 笔账单的分类外键失效")

    # ═══ 2. Schema 与 Room 期望逐列比对 ═══
    expected = json.load(open(SCHEMA_JSON, encoding="utf-8"))["database"]
    actual = norm_cols(con)

    if expected["version"] != 2:
        failures.append(f"schema JSON 版本不是 2：{expected['version']}")

    for ent in expected["entities"]:
        tbl = ent["tableName"]
        if tbl not in actual:
            failures.append(f"表 {tbl} 不存在")
            continue

        # 列：按名字比 affinity / notNull / defaultValue
        exp_cols = {c["columnName"]: c for c in ent["fields"]}
        act_cols = {c["columnName"]: c for c in actual[tbl]["columns"]}
        missing = set(exp_cols) - set(act_cols)
        extra = set(act_cols) - set(exp_cols)
        if missing:
            failures.append(f"{tbl} 缺少列 {sorted(missing)}")
        if extra:
            failures.append(f"{tbl} 多出列 {sorted(extra)}（Room 会报 schema 不匹配）")
        for name in sorted(set(exp_cols) & set(act_cols)):
            e, a = exp_cols[name], act_cols[name]
            if e["affinity"] != a["affinity"]:
                failures.append(f"{tbl}.{name} 类型不符：期望 {e['affinity']} 实际 {a['affinity']}")
            if e["notNull"] != a["notNull"]:
                failures.append(f"{tbl}.{name} notNull 不符：期望 {e['notNull']} 实际 {a['notNull']}")
            ed, ad = e.get("defaultValue"), a["defaultValue"]
            if ed != ad:
                failures.append(f"{tbl}.{name} defaultValue 不符：期望 {ed!r} 实际 {ad!r}")
        # 列顺序（Room 不校验顺序，但顺序错乱往往意味着拷贝语句写错）
        if [c["columnName"] for c in ent["fields"]] != [c["columnName"] for c in actual[tbl]["columns"]]:
            print(f"  ⚠ {tbl} 列顺序与实体不同（Room 按列名映射，不影响运行）：")
            print(f"      实体: {[c['columnName'] for c in ent['fields']]}")
            print(f"      实际: {[c['columnName'] for c in actual[tbl]['columns']]}")

        # 索引
        exp_idx = {i["name"]: i for i in ent.get("indices", [])}
        act_idx = {i["name"]: i for i in actual[tbl]["indices"]}
        for name, ei in exp_idx.items():
            if name not in act_idx:
                failures.append(f"{tbl} 缺少索引 {name}")
                continue
            ai = act_idx[name]
            if ai["unique"] != ei["unique"]:
                failures.append(f"索引 {name} unique 不符：期望 {ei['unique']} 实际 {ai['unique']}")
            if ai["columnNames"] != ei["columnNames"]:
                failures.append(f"索引 {name} 列不符：期望 {ei['columnNames']} 实际 {ai['columnNames']}")
        for name in set(act_idx) - set(exp_idx):
            failures.append(f"{tbl} 多出索引 {name}")

    # ═══ 3. 唯一索引真的生效 ═══
    try:
        con.execute("INSERT INTO budgets (yearMonth,totalExpenseLimit,createdAt) VALUES ('2026-09',1.0,1)")
        failures.append("yearMonth 唯一索引未生效：同月重复插入竟然成功了")
    except sqlite3.IntegrityError:
        pass

    # ═══ 4. UPSERT 语义（BudgetDao.upsertExpenseLimit 依赖）═══
    con.execute("""INSERT INTO budgets (yearMonth,totalIncomeTarget,totalExpenseLimit,source,createdAt)
                   VALUES ('2026-10',NULL,4800.0,'AI_SUGGESTED',1)
                   ON CONFLICT(yearMonth) DO UPDATE SET totalExpenseLimit=4800.0, source='AI_SUGGESTED'""")
    con.execute("""INSERT INTO budgets (yearMonth,totalIncomeTarget,totalExpenseLimit,source,createdAt)
                   VALUES ('2026-10',NULL,5200.0,'MANUAL',2)
                   ON CONFLICT(yearMonth) DO UPDATE SET totalExpenseLimit=5200.0, source='MANUAL'""")
    rows = con.execute("SELECT totalExpenseLimit,source,totalIncomeTarget FROM budgets WHERE yearMonth='2026-10'").fetchall()
    if len(rows) != 1:
        failures.append(f"UPSERT 未去重，2026-10 有 {len(rows)} 行")
    elif rows[0][0] != 5200.0 or rows[0][1] != "MANUAL":
        failures.append(f"UPSERT 更新结果错误：{rows[0]}")

    con.close()

    print("\n" + "=" * 72)
    if failures:
        print("❌ 迁移校验失败 %d 项：" % len(failures))
        for f in failures:
            print("   -", f)
        sys.exit(1)
    print("✅ 迁移校验全部通过")
    print("   • 20 笔账单、3 个分类、收入目标、分类外键全部完好")
    print("   • category_budgets 3 行未被外键连带删除")
    print("   • 同月重复预算已去重且保留了最后一次写入的额度")
    print("   • 5 张表的列名/类型/notNull/defaultValue/索引与 Room 2.json 逐列一致")
    print("   • yearMonth 唯一索引生效，UPSERT 语义正确")
    sys.exit(0)


if __name__ == "__main__":
    main()
