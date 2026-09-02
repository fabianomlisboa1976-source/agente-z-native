package dev.mindmax.v4.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mindmax.v4.data.entity.MessageEntity
import dev.mindmax.v4.data.entity.SenderType
import dev.mindmax.v4.ui.theme.MindMaxColors
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * One message in the chat LazyColumn. User messages align right with the
 * primary tint; agent messages align left with the surface tint; system
 * messages span full width with muted text. Each bubble shows a name prefix
 * and a short timestamp.
 *
 * **Markdown is currently rendered as plain text.** Code blocks, links, and
 * lists come through literal. Upgrade path: pipe `message.content` through a
 * `AnnotatedString` builder (e.g. `androidx.compose.ui.text.markdown` or a
 * third-party library) and emit the styled string here. Keep the `bodyMedium`
 * style as the base — agents' pt-BR replies usually start with prose.
 */
@Composable
fun MessageBubble(
    message: MessageEntity,
    modifier: Modifier = Modifier,
) {
    val isUser = message.senderType == SenderType.USER
    val isSystem = message.senderType == SenderType.SYSTEM

    val rowAlignment = when {
        isSystem -> androidx.compose.ui.Alignment.CenterHorizontally
        isUser -> androidx.compose.ui.Alignment.End
        else -> androidx.compose.ui.Alignment.Start
    }

    val bubbleColor = when {
        isSystem -> Color.Transparent
        isUser -> MindMaxColors.SlatePrimary
        else -> MindMaxColors.SlateSurface
    }
    val textColor = when {
        isSystem -> MindMaxColors.SlateOnSurfaceMuted
        else -> MindMaxColors.SlateOnSurface
    }
    val textWeight = if (isUser || message.senderType == SenderType.SYSTEM) FontWeight.Normal else FontWeight.Normal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isSystem) {
            Column(horizontalAlignment = rowAlignment) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatTime(message.timestamp),
                    color = MindMaxColors.SlateOnSurfaceMuted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            return
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = if (isUser) Modifier else Modifier.padding(end = 32.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!isUser) {
                    val name = message.agentName ?: "Agente"
                    Text(
                        text = name,
                        color = MindMaxColors.SlateOnSurfaceMuted,
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(0.dp))
                }
                Text(
                    text = message.content,
                    color = textColor,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    fontWeight = textWeight,
                )
                Text(
                    text = formatTime(message.timestamp),
                    color = if (isUser) MindMaxColors.SlateOnSurface else MindMaxColors.SlateOnSurfaceMuted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(date: java.util.Date): String =
    timeFormatter.format(date)
