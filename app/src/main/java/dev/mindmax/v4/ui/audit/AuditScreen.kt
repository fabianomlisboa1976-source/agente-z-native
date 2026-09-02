package dev.mindmax.v4.ui.audit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mindmax.v4.data.entity.AuditLogEntity
import dev.mindmax.v4.data.entity.AuditStatus
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AuditScreen(
    modifier: Modifier = Modifier,
    viewModel: AuditViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Text(
            text = "Auditoria",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            text = "Filtros por tipo viram nos chips abaixo. Entradas mais antigas aparecem primeiro.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.entries, key = { it.id }) { entry ->
                AuditRow(entry)
            }
        }
    }
}

@Composable
private fun AuditRow(entry: AuditLogEntity) {
    val rowColor = when (entry.status) {
        AuditStatus.ERROR -> MaterialTheme.colorScheme.error
        AuditStatus.WARNING -> MaterialTheme.colorScheme.tertiary
        AuditStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        AuditStatus.PENDING -> MaterialTheme.colorScheme.secondary
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
        androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 8.dp))
        AssistChip(
            onClick = {},
            label = { Text(entry.type.name) },
            colors = AssistChipDefaults.assistChipColors(containerColor = rowColor.copy(alpha = 0.18f)),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 6.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = entry.action,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${entry.agentName ?: entry.agentId ?: "system"} · ${format(entry.timestamp)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (!entry.details.isNullOrBlank()) {
                Text(
                    text = entry.details.take(200),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun format(date: java.util.Date): String = df.format(date)
