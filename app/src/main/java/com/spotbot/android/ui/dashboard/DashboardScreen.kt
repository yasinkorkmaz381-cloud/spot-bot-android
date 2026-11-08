package com.spotbot.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.R
import com.spotbot.android.domain.model.OrderSide
import com.spotbot.android.domain.model.Position
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.Trade
import com.spotbot.android.domain.model.TradeReason
import com.spotbot.android.ui.AppViewModelProvider
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.LabeledValue
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.common.formatQuantity
import com.spotbot.android.ui.common.formatSignedMoney
import com.spotbot.android.ui.common.formatTime
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import java.math.BigDecimal
import java.time.Instant

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(uiState = uiState, onToggleBot = viewModel::onToggleBot, modifier = modifier)
}

@Composable
fun DashboardContent(
    uiState: DashboardUiState,
    onToggleBot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { InfoBanner(stringResource(R.string.simulation_banner)) }
        item { BotStatusCard(uiState, onToggleBot) }
        item { PortfolioCard(uiState) }
        item {
            Text(
                text = stringResource(R.string.recent_trades),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (uiState.recentTrades.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.trades_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(uiState.recentTrades, key = { it.id }) { trade ->
                TradeRow(trade, uiState.quoteAsset)
            }
        }
    }
}

@Composable
private fun BotStatusCard(uiState: DashboardUiState, onToggleBot: () -> Unit) {
    val config = uiState.config
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = uiState.isRunning)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (uiState.isRunning) R.string.bot_status_running else R.string.bot_status_stopped,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = config.pair.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LabeledValue(
                label = stringResource(R.string.label_current_price),
                value = uiState.currentPrice
                    ?.let { stringResource(R.string.amount_with_asset, formatPrice(it), config.pair.quote) }
                    ?: stringResource(R.string.value_unknown),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledValue(
                    label = stringResource(R.string.label_order_amount),
                    value = stringResource(R.string.amount_with_asset, formatMoney(config.orderAmount), config.pair.quote),
                    modifier = Modifier.weight(1f),
                )
                LabeledValue(
                    label = stringResource(R.string.label_take_profit),
                    value = formatPercent(config.takeProfitPercent.toDouble()),
                    modifier = Modifier.weight(1f),
                )
                LabeledValue(
                    label = stringResource(R.string.label_stop_loss),
                    value = formatPercent(config.stopLossPercent.toDouble()),
                    modifier = Modifier.weight(1f),
                )
            }
            if (uiState.isRunning) {
                OutlinedButton(
                    onClick = onToggleBot,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.bot_stop))
                }
            } else {
                Button(onClick = onToggleBot, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bot_start))
                }
            }
        }
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    val color = if (active) SpotBotTheme.tradingColors.gain else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun PortfolioCard(uiState: DashboardUiState) {
    val quote = uiState.quoteAsset
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.portfolio_title), style = MaterialTheme.typography.titleMedium)
            Column {
                Text(
                    text = stringResource(R.string.portfolio_total_value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.amount_with_asset, formatMoney(uiState.totalValue), quote),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LabeledValue(
                label = stringResource(R.string.portfolio_pnl),
                value = stringResource(
                    R.string.pnl_format,
                    formatSignedMoney(uiState.pnl),
                    quote,
                    formatPercent(uiState.pnlPercent, signed = true, minFractionDigits = 2),
                ),
                valueColor = gainLossColor(uiState.pnl.signum()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledValue(
                    label = stringResource(R.string.portfolio_cash),
                    value = stringResource(R.string.amount_with_asset, formatMoney(uiState.quoteBalance), quote),
                    modifier = Modifier.weight(1f),
                )
                LabeledValue(
                    label = stringResource(R.string.portfolio_position),
                    value = uiState.position?.let { position ->
                        stringResource(
                            R.string.position_format,
                            formatQuantity(position.quantity),
                            position.pair.base,
                            formatPrice(position.entryPrice),
                        )
                    } ?: stringResource(R.string.portfolio_no_position),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TradeRow(trade: Trade, quoteAsset: String) {
    val reason = stringResource(
        when (trade.reason) {
            TradeReason.ENTRY -> R.string.trade_reason_entry
            TradeReason.TAKE_PROFIT -> R.string.trade_reason_take_profit
            TradeReason.STOP_LOSS -> R.string.trade_reason_stop_loss
        },
    )
    ListItem(
        leadingContent = { SideBadge(trade.side) },
        headlineContent = { Text("${trade.pair.displayName} · $reason") },
        supportingContent = {
            Text(
                stringResource(
                    R.string.trade_details,
                    formatQuantity(trade.quantity),
                    trade.pair.base,
                    formatPrice(trade.price),
                    formatTime(trade.timestamp),
                ),
            )
        },
        trailingContent = trade.realizedPnl?.let { pnl ->
            {
                Text(
                    text = stringResource(R.string.amount_with_asset, formatSignedMoney(pnl), quoteAsset),
                    style = MaterialTheme.typography.labelLarge,
                    color = gainLossColor(pnl.signum()),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SideBadge(side: OrderSide) {
    val colors = SpotBotTheme.tradingColors
    val color = if (side == OrderSide.BUY) colors.gain else colors.loss
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = MaterialTheme.shapes.small) {
        Text(
            text = stringResource(if (side == OrderSide.BUY) R.string.trade_side_buy else R.string.trade_side_sell),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DashboardPreview() {
    val config = StrategyConfig.DEFAULT
    val now = Instant.parse("2026-01-01T12:00:00Z")
    SpotBotTheme {
        DashboardContent(
            uiState = DashboardUiState(
                isRunning = true,
                config = config,
                quoteAsset = "USDT",
                currentPrice = BigDecimal("95321.12"),
                totalValue = BigDecimal("1003.42"),
                quoteBalance = BigDecimal("953.42"),
                position = Position(config.pair, BigDecimal("0.00052"), BigDecimal("95120.5"), BigDecimal("50")),
                pnl = BigDecimal("3.42"),
                pnlPercent = 0.342,
                recentTrades = listOf(
                    Trade(
                        2, config.pair, OrderSide.SELL, TradeReason.TAKE_PROFIT, BigDecimal("96500"),
                        BigDecimal("0.00052"), BigDecimal("50.13"), BigDecimal("0.05"), BigDecimal("0.13"), now,
                    ),
                    Trade(
                        1, config.pair, OrderSide.BUY, TradeReason.ENTRY, BigDecimal("95000"),
                        BigDecimal("0.00052"), BigDecimal("50"), BigDecimal("0.05"), null, now,
                    ),
                ),
            ),
            onToggleBot = {},
        )
    }
}
