package dev.mindmax.v4.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Text(
            text = "Configurações",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("provider") {
                ProviderSection(
                    selectedProviderId = state.providerId,
                    customBaseUrl = state.customBaseUrl,
                    onProviderChange = viewModel::onProviderChange,
                    onCustomChange = viewModel::onCustomBaseUrlChange,
                )
            }
            item("model") {
                ModelSection(
                    model = state.model,
                    onChange = viewModel::onModelChange,
                )
            }
            item("api-key") {
                ApiKeySection(
                    hasKey = state.hasApiKey,
                    input = state.apiKeyInput,
                    onInputChange = viewModel::onApiKeyInputChange,
                    onSave = viewModel::saveApiKey,
                    onClear = viewModel::clearApiKey,
                )
            }
            item("params") {
                ParametersSection(
                    temperature = state.temperature,
                    maxTokens = state.maxTokens,
                    topP = state.topP,
                    onTemperature = viewModel::onTemperatureChange,
                    onMaxTokens = viewModel::onMaxTokensChange,
                    onTopP = viewModel::onTopPChange,
                )
            }
            item("runtime") {
                RuntimeSection(
                    multiAgent = state.multiAgentEnabled,
                    crossAudit = state.crossAuditEnabled,
                    serviceEnabled = state.serviceEnabled,
                    autoStart = state.autoStart,
                    onMultiAgent = viewModel::onMultiAgentToggle,
                    onCrossAudit = viewModel::onCrossAuditToggle,
                    onService = viewModel::onServiceToggle,
                    onAutoStart = viewModel::onAutoStartToggle,
                )
            }
            item("danger") {
                DangerZone(onClearData = viewModel::clearAllData)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSection(
    selectedProviderId: String,
    customBaseUrl: String,
    onProviderChange: (String) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Text(
        text = "Provedor de IA",
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))

    var expanded by remember { mutableStateOf(false) }
    val providerLabel = remember(selectedProviderId) {
        mapOf(
            "groq" to "Groq",
            "openrouter" to "OpenRouter",
            "cloudflare" to "Cloudflare Workers AI",
            "github" to "GitHub Models",
            "openai" to "OpenAI",
            "custom" to "Endpoint customizado",
        )[selectedProviderId] ?: "Groq"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = providerLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Provedor") },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(
                "groq" to "Groq",
                "openrouter" to "OpenRouter",
                "cloudflare" to "Cloudflare Workers AI",
                "github" to "GitHub Models",
                "openai" to "OpenAI",
                "custom" to "Endpoint customizado",
            ).forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onProviderChange(id)
                        expanded = false
                    },
                )
            }
        }
    }

    if (selectedProviderId == "custom") {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customBaseUrl,
            onValueChange = onCustomChange,
            label = { Text("Base URL") },
            placeholder = { Text("https://seu-endpoint/v1/") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ModelSection(model: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = model,
        onValueChange = onChange,
        label = { Text("Modelo") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ApiKeySection(
    hasKey: Boolean,
    input: String,
    onInputChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Column {
        Text(
            text = "Chave de API",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(if (hasKey) "Substituir chave" else "Cole sua chave") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = onSave,
                label = { Text("Salvar") },
                enabled = input.isNotBlank(),
            )
            Spacer(Modifier.width(8.dp))
            if (hasKey) {
                AssistChip(
                    onClick = onClear,
                    label = { Text("Remover") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ParametersSection(
    temperature: Float,
    maxTokens: Int,
    topP: Float,
    onTemperature: (Float) -> Unit,
    onMaxTokens: (Int) -> Unit,
    onTopP: (Float) -> Unit,
) {
    Column {
        Text("Parâmetros", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Temperatura",
            value = temperature,
            range = 0f..1f,
            onChange = onTemperature,
            formatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "Top-P",
            value = topP,
            range = 0f..1f,
            onChange = onTopP,
            formatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "Max tokens",
            value = maxTokens.toFloat(),
            range = 64f..8192f,
            onChange = { onMaxTokens(it.toInt()) },
            formatter = { it.toInt().toString() },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    formatter: (Float) -> String,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label)
            Text(formatter(value))
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun RuntimeSection(
    multiAgent: Boolean,
    crossAudit: Boolean,
    serviceEnabled: Boolean,
    autoStart: Boolean,
    onMultiAgent: (Boolean) -> Unit,
    onCrossAudit: (Boolean) -> Unit,
    onService: (Boolean) -> Unit,
    onAutoStart: (Boolean) -> Unit,
) {
    Column {
        Text("Runtime", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Multi-agente", multiAgent, onMultiAgent)
        SwitchRow("Cross-auditoria", crossAudit, onCrossAudit)
        SwitchRow("Serviço em background", serviceEnabled, onService)
        SwitchRow("Iniciar automaticamente", autoStart, onAutoStart)
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun DangerZone(onClearData: () -> Unit) {
    Column {
        Spacer(Modifier.height(16.dp))
        Text("Danger Zone", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        AssistChip(
            onClick = onClearData,
            label = { Text("Limpar todos os dados") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        )
    }
}
