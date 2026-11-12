package com.spotbot.core.rs1

/**
 * Sürümlerin kural setleri, açıkça ve değişmez biçimde. Karşılaştırmalar varsayılanlara ([Rs1Config]) değil bu
 * sabitlere dayanır; varsayılanlar ileride değişse de eski sonuçlar birebir yeniden üretilebilir.
 */
object Rs1Versions {
    /** V5 (docs/RS1-ONKAYIT.md): 150 gün, iz süren stop, EMA50, uygunluk kaybı ve BTC çıkışı; EMA100 ve 30g şartı. */
    val V5 = Rs1Config(
        minHistoryDays = 150, entryEveryDay = false,
        exitTrailing = true, exitEma = true, exitIneligible = true, exitBtc = true,
        useBtcFilter = true, useTrendFilter = true, useReturn7 = true, useReturn30 = true, useRelativeStrength = true,
        useRangeFilter = true, useVolumeFilter = true,
    )

    /** V6 (commit 15271c7, docs/RS1-V6.md): haftalık giriş; çıkış koruyucu stop + BTC rejimi; EMA100 ve 30g şartı yok. */
    val V6 = Rs1Config(
        minHistoryDays = 250, entryEveryDay = false,
        exitTrailing = false, exitEma = false, exitIneligible = false, exitBtc = true,
        useBtcFilter = true, useTrendFilter = false, useReturn7 = true, useReturn30 = false, useRelativeStrength = true,
        useRangeFilter = true, useVolumeFilter = true,
    )
}
