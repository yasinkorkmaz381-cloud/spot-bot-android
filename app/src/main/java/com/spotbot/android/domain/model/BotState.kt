package com.spotbot.android.domain.model

import java.math.BigDecimal

/** Botun tüm durumu: çalışma bilgisi, strateji, hesap ve işlem geçmişi (en yenisi başta). */
data class BotState(
    val isRunning: Boolean,
    val config: StrategyConfig,
    val account: PaperAccount,
    val trades: List<Trade>,
) {
    companion object {
        fun initial(initialBalance: BigDecimal, config: StrategyConfig = StrategyConfig.DEFAULT) = BotState(
            isRunning = false,
            config = config,
            account = PaperAccount.create(initialBalance),
            trades = emptyList(),
        )
    }
}
