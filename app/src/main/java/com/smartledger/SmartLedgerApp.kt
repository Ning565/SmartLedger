package com.smartledger

import android.app.Application
import com.smartledger.data.ai.AiClient
import com.smartledger.data.ai.AiClientImpl
import com.smartledger.data.ai.http.UrlConnectionHttpEngine
import com.smartledger.data.analytics.FinancialSummaryBuilder
import com.smartledger.data.db.AppDatabase
import com.smartledger.data.repository.AiAdvisorRepository
import com.smartledger.data.repository.AiSettingsRepository
import com.smartledger.data.repository.BudgetRepository
import com.smartledger.data.repository.CategoryRepository
import com.smartledger.data.repository.TransactionRepository
import com.smartledger.service.ListenerStatus
import com.smartledger.service.SmartCategorizer
import com.smartledger.util.ListenerRebindScheduler
import com.smartledger.util.ListenerWatchdog
import com.smartledger.util.NotificationStyle

class SmartLedgerApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val transactionRepository by lazy { TransactionRepository(database.transactionDao()) }
    val categoryRepository by lazy { CategoryRepository(database.categoryDao()) }
    val budgetRepository by lazy { BudgetRepository(database.budgetDao()) }

    // ═══ AI 相关（全部 by lazy）═══
    //
    // 刻意不在 onCreate 里初始化：AI 是可选功能，未配置的用户
    // 不应该为它付任何启动成本（Keystore 读取、prefs 加载）。
    // 更重要的是：**AI 链路任何一环失败都不能影响自动记账**，
    // 懒加载从结构上保证了这一点。

    val aiClient: AiClient by lazy { AiClientImpl(UrlConnectionHttpEngine()) }

    val aiSettingsRepository by lazy { AiSettingsRepository(this) }

    val financialSummaryBuilder by lazy {
        FinancialSummaryBuilder(
            transactionDao = database.transactionDao(),
            categoryDao = database.categoryDao(),
            budgetDao = database.budgetDao()
        )
    }

    val aiAdvisorRepository by lazy {
        AiAdvisorRepository(
            summaryBuilder = financialSummaryBuilder,
            aiReportDao = database.aiReportDao(),
            aiClient = aiClient,
            settings = aiSettingsRepository
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 主题与主界面一致，供确认弹窗等独立 Activity 使用
        val prefs = getSharedPreferences("smart_ledger", MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_mode", "SYSTEM")
        com.smartledger.ui.theme.ThemeManager.init(
            try {
                com.smartledger.ui.theme.ThemeMode.valueOf(savedTheme ?: "SYSTEM")
            } catch (_: Exception) {
                com.smartledger.ui.theme.ThemeMode.SYSTEM
            }
        )
        // 勿在冷启动时强行标「未连接」：若系统已连上但未再回调 onListenerConnected，
        // 会一直显示「监听已断开」。以 onListenerConnected / 收到通知为准更新状态。
        ListenerStatus.checkAppUpdated(this)
        SmartCategorizer.init(this)
        NotificationStyle.ensureChannels(this)
        ListenerRebindScheduler.schedule(this)
        ListenerWatchdog.schedule(this)
        if (ListenerStatus.isEnabledInSettings(this)) {
            com.smartledger.service.KeepAliveService.start(this)
            ListenerStatus.ensureListening(this)
        }
    }
}
