package com.spotbot.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spotbot.android.BuildConfig
import com.spotbot.android.R
import com.spotbot.android.ui.AppViewModelProvider
import com.spotbot.android.ui.common.formatMoney
import com.spotbot.android.ui.common.formatPercent
import com.spotbot.android.ui.theme.SpotBotTheme
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun SettingsScreen(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    SettingsContent(
        uiState = uiState,
        onResetClick = { showResetDialog = true },
        modifier = modifier,
    )

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.onResetSimulation()
                        scope.launch {
                            snackbarHostState.showSnackbar(resources.getString(R.string.settings_reset_done))
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingsSection(stringResource(R.string.settings_section_mode)) {
            SettingsItem(
                headline = stringResource(R.string.settings_mode_simulation),
                supporting = stringResource(R.string.settings_mode_simulation_desc),
            )
        }
        SettingsSection(stringResource(R.string.settings_section_exchange)) {
            SettingsItem(
                headline = stringResource(R.string.settings_exchange_not_connected),
                supporting = stringResource(R.string.settings_exchange_desc),
            )
        }
        SettingsSection(stringResource(R.string.settings_section_account)) {
            SettingsItem(
                headline = stringResource(R.string.settings_initial_balance),
                trailing = stringResource(
                    R.string.amount_with_asset,
                    formatMoney(uiState.initialBalance),
                    uiState.quoteAsset,
                ),
            )
            SettingsItem(
                headline = stringResource(R.string.settings_fee),
                trailing = formatPercent(uiState.feeRate.toDouble() * 100),
            )
            OutlinedButton(
                onClick = onResetClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                Text(stringResource(R.string.settings_reset))
            }
        }
        SettingsSection(stringResource(R.string.settings_section_about)) {
            SettingsItem(
                headline = stringResource(R.string.settings_version),
                trailing = BuildConfig.VERSION_NAME,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsItem(headline: String, supporting: String? = null, trailing: String? = null) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing?.let { { Text(it, style = MaterialTheme.typography.bodyLarge) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    SpotBotTheme {
        SettingsContent(
            uiState = SettingsUiState(initialBalance = BigDecimal("1000"), quoteAsset = "USDT"),
            onResetClick = {},
        )
    }
}
