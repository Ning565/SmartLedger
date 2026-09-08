package com.smartledger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smartledger.data.db.dao.AiReportDao
import com.smartledger.data.db.dao.BudgetDao
import com.smartledger.data.db.dao.CategoryDao
import com.smartledger.data.db.dao.TransactionDao
import com.smartledger.data.db.entity.AiReportEntity
import com.smartledger.data.db.entity.Budget
import com.smartledger.data.db.entity.Category
import com.smartledger.data.db.entity.CategoryBudget
import com.smartledger.data.db.entity.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Transaction::class,
        Category::class,
        Budget::class,
        CategoryBudget::class,
        AiReportEntity::class
    ],
    version = 2,
    // 打开 schema 导出：迁移 SQL 必须与 Room 期望的表结构逐列一致，
    // 靠人眼比对极易出错（列名大小写、NOT NULL、DEFAULT、索引名都会导致
    // 运行期 IllegalStateException）。导出 JSON 后可直接对照校验。
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun aiReportDao(): AiReportDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2。
         *
         * 变更内容（只做加法，绝不 DROP / 重建表，保证用户账单零风险）：
         *  1. 新建 `ai_reports` 表 + 复合索引；
         *  2. `budgets` 增加 `source` 列；
         *  3. `budgets` 合并同月重复行后建 `yearMonth` 唯一索引。
         *
         * 第 3 步的顺序很关键：**必须先去重再建唯一索引**，
         * 否则库里已有重复月份的用户升级时会直接迁移失败、App 起不来。
         * 去重保留 id 最大的一行（最后一次写入的额度），这与 v1 里
         * `getBudgetByMonth(... ORDER BY 无 LIMIT 1)` 的实际读取语义最接近。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 1. AI 报告缓存表 ──
                db.execSQL(
                    """
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
                        `fromReasoningFallback` INTEGER NOT NULL,
                        `truncated` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_reports_periodStart_periodEnd` " +
                            "ON `ai_reports` (`periodStart`, `periodEnd`)"
                )

                // ── 2. budgets 增加来源列 ──
                //
                // 刻意用**可空列且不带 DEFAULT**，理由见 Budget 类注释：
                //  - 带 NOT NULL 就必须带 DEFAULT，而 Room 导出的期望 schema 无 defaultValue，
                //    两者不一致会在打开数据库时抛 "Migration didn't properly handle budgets"；
                //  - 想避开 DEFAULT 就得重建表，但 category_budgets 对 budgets(id) 有
                //    ON DELETE CASCADE 外键，DROP 父表会连带删光分类预算。
                // 老行读到 NULL，Budget.budgetSource 会把它解释为 MANUAL，语义正确。
                db.execSQL("ALTER TABLE `budgets` ADD COLUMN `source` TEXT")

                // ── 3. 同月去重后建唯一索引 ──
                db.execSQL(
                    """
                    DELETE FROM `budgets`
                    WHERE `id` NOT IN (
                        SELECT MAX(`id`) FROM `budgets` GROUP BY `yearMonth`
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_budgets_yearMonth` " +
                            "ON `budgets` (`yearMonth`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_ledger.db"
                )
                    // 显式注册迁移。**不加 fallbackToDestructiveMigration**：
                    // 那会在迁移不匹配时静默清空用户全部账单，属于不可接受的损失。
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(DatabaseCallback())
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }

    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateDefaultCategories(database.categoryDao())
                }
            }
        }

        private suspend fun populateDefaultCategories(categoryDao: CategoryDao) {
            val defaultCategories = listOf(
                // 支出分类
                Category(name = "餐饮", icon = "Restaurant", color = 0xFFFF5722, type = "expense", sortOrder = 0, isDefault = true),
                Category(name = "交通", icon = "DirectionsCar", color = 0xFF2196F3, type = "expense", sortOrder = 1, isDefault = true),
                Category(name = "购物", icon = "ShoppingBag", color = 0xFFE91E63, type = "expense", sortOrder = 2, isDefault = true),
                Category(name = "娱乐", icon = "SportsEsports", color = 0xFF9C27B0, type = "expense", sortOrder = 3, isDefault = true),
                Category(name = "居住", icon = "Home", color = 0xFF795548, type = "expense", sortOrder = 4, isDefault = true),
                Category(name = "通讯", icon = "Phone", color = 0xFF00BCD4, type = "expense", sortOrder = 5, isDefault = true),
                Category(name = "医疗", icon = "LocalHospital", color = 0xFFF44336, type = "expense", sortOrder = 6, isDefault = true),
                Category(name = "教育", icon = "School", color = 0xFF3F51B5, type = "expense", sortOrder = 7, isDefault = true),
                Category(name = "日用", icon = "ShoppingCart", color = 0xFF4CAF50, type = "expense", sortOrder = 8, isDefault = true),
                Category(name = "其他", icon = "MoreHoriz", color = 0xFF607D8B, type = "expense", sortOrder = 9, isDefault = true),
                // 收入分类
                Category(name = "工资", icon = "Work", color = 0xFF4CAF50, type = "income", sortOrder = 0, isDefault = true),
                Category(name = "理财", icon = "TrendingUp", color = 0xFF00897B, type = "income", sortOrder = 1, isDefault = true),
                Category(name = "红包", icon = "CardGiftcard", color = 0xFFF44336, type = "income", sortOrder = 2, isDefault = true),
                Category(name = "转账", icon = "SwapHoriz", color = 0xFF2196F3, type = "income", sortOrder = 3, isDefault = true),
                Category(name = "其他", icon = "MoreHoriz", color = 0xFF607D8B, type = "income", sortOrder = 4, isDefault = true),
            )
            categoryDao.insertAll(defaultCategories)
        }
    }
}
