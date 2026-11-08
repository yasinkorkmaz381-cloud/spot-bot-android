package com.spotbot.android.ui.markets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.R
import com.spotbot.android.data.market.SimulatedMarketRepository
import com.spotbot.android.domain.model.MarketTicker
import com.spotbot.android.ui.AppViewModelProvider
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.common.formatPrice
import com.spotbot.android.ui.theme.SpotBotTheme
import com.spotbot.android.ui.theme.gainLossColor
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun MarketsScreen(
    modifier: Modifier = Modifier,
    viewModel: MarketsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val tickers by viewModel.tickers.collectAsStateWithLifecycle()
    MarketsContent(
        query = viewModel.query,
        tickers = tickers,
        onQueryChange = viewModel::onQueryChange,
        modifier = modifier,
    )
}

@Composable
fun MarketsContent(
    query: String,
    tickers: List<MarketTicker>,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.markets_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.markets_simulated_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (tickers.isEmpty()) {
            Text(
                text = stringResource(R.string.markets_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(tickers, key = { it.pair.symbol }) { ticker ->
                    TickerRow(ticker)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TickerRow(ticker: MarketTicker) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
            Text(ticker.pair.base, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "/${ticker.pair.quote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatPrice(ticker.lastPrice),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 12.dp),
        )
        ChangeChip(ticker.changePercent)
    }
}

@Composable
private fun ChangeChip(changePercent: Double) {
    val signum = BigDecimal.valueOf(changePercent).setScale(2, RoundingMode.HALF_UP).signum()
    val color = gainLossColor(signum)
    Surface(
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.widthIn(min = 80.dp),
    ) {
        Text(
            text = formatPercent(changePercent, signed = true, minFractionDigits = 2),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MarketsPreview() {
    SpotBotTheme {
        MarketsContent(
            query = "",
            tickers = SimulatedMarketRepository.DEFAULT_TICKERS.mapIndexed { index, ticker ->
                val factor = BigDecimal.valueOf(1 + (index - 3) * 0.004)
                ticker.copy(lastPrice = ticker.lastPrice.multiply(factor))
            },
            onQueryChange = {},
        )
    }
}
