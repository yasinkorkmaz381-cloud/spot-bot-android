package com.spotbot.android.ui.strategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.R
import com.spotbot.android.domain.model.StrategyConfig
import com.spotbot.android.domain.model.StrategyError
import com.spotbot.android.domain.model.TradingPair
import com.spotbot.android.domain.trading.PaperTrader
import com.spotbot.android.ui.AppViewModelProvider
import com.spotbot.android.ui.common.InfoBanner
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.theme.SpotBotTheme

@Composable
fun StrategyScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: StrategyViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    val isBotRunning by viewModel.isBotRunning.collectAsStateWithLifecycle()
    val resources = LocalResources.current

    LaunchedEffect(viewModel, snackbarHostState, resources) {
        viewModel.events.collect { event ->
            snackbarHostState.showSnackbar(resources.getString(event.messageRes))
        }
    }

    StrategyContent(
        form = viewModel.form,
        pairs = pairs,
        isBotRunning = isBotRunning,
        onPairSelected = viewModel::onPairSelected,
        onOrderAmountChange = viewModel::onOrderAmountChange,
        onTakeProfitChange = viewModel::onTakeProfitChange,
        onStopLossChange = viewModel::onStopLossChange,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
fun StrategyContent(
    form: StrategyFormState,
    pairs: List<TradingPair>,
    isBotRunning: Boolean,
    onPairSelected: (TradingPair) -> Unit,
    onOrderAmountChange: (String) -> Unit,
    onTakeProfitChange: (String) -> Unit,
    onStopLossChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !isBotRunning
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isBotRunning) {
            InfoBanner(
                text = stringResource(R.string.strategy_running_info),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.strategy_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(stringResource(R.string.strategy_pair), style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pairs, key = { it.symbol }) { pair ->
                FilterChip(
                    selected = pair == form.pair,
                    onClick = { onPairSelected(pair) },
                    label = { Text(pair.displayName) },
                    enabled = enabled,
                )
            }
        }

        DecimalField(
            value = form.orderAmount,
            onValueChange = onOrderAmountChange,
            label = stringResource(R.string.label_order_amount),
            suffix = form.pair.quote,
            errorText = if (StrategyError.ORDER_AMOUNT_TOO_LOW in form.errors) {
                stringResource(R.string.error_order_amount, formatMoney(StrategyConfig.MIN_ORDER_AMOUNT))
            } else {
                null
            },
            enabled = enabled,
        )
        DecimalField(
            value = form.takeProfitPercent,
            onValueChange = onTakeProfitChange,
            label = stringResource(R.string.label_take_profit),
            suffix = "%",
            errorText = if (StrategyError.TAKE_PROFIT_OUT_OF_RANGE in form.errors) {
                stringResource(R.string.error_take_profit)
            } else {
                null
            },
            enabled = enabled,
        )
        DecimalField(
            value = form.stopLossPercent,
            onValueChange = onStopLossChange,
            label = stringResource(R.string.label_stop_loss),
            suffix = "%",
            errorText = if (StrategyError.STOP_LOSS_OUT_OF_RANGE in form.errors) {
                stringResource(R.string.error_stop_loss)
            } else {
                null
            },
            enabled = enabled,
            imeAction = ImeAction.Done,
        )
        Text(
            text = stringResource(R.string.strategy_fee_note, formatPercent(PaperTrader.FEE_RATE.toDouble() * 100)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.strategy_save))
        }
    }
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    errorText: String?,
    enabled: Boolean,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(showBackground = true)
@Composable
private fun StrategyPreview() {
    SpotBotTheme {
        StrategyContent(
            form = StrategyFormState.from(StrategyConfig.DEFAULT).copy(
                stopLossPercent = "0",
                errors = setOf(StrategyError.STOP_LOSS_OUT_OF_RANGE),
            ),
            pairs = listOf(TradingPair("BTC", "USDT"), TradingPair("ETH", "USDT"), TradingPair("SOL", "USDT")),
            isBotRunning = false,
            onPairSelected = {},
            onOrderAmountChange = {},
            onTakeProfitChange = {},
            onStopLossChange = {},
            onSave = {},
        )
    }
}
