package com.cchharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cchharness.core.Usage
import java.time.Duration
import java.time.Instant

/** "3 小时 20 分后重置" for an ISO-8601 instant; "" when it is unknown or already past. */
fun resetText(iso: String?, now: Instant = Instant.now()): String {
    val at = try {
        Instant.parse(iso ?: return "")
    } catch (e: Exception) {
        return ""
    }
    val minutes = Duration.between(now, at).toMinutes()
    return when {
        minutes <= 0 -> ""
        minutes < 60 -> "$minutes 分钟后重置"
        minutes < 48 * 60 -> "${minutes / 60} 小时 ${minutes % 60} 分后重置"
        else -> "${(minutes + 12 * 60) / (24 * 60)} 天后重置"
    }
}

/** The plan-usage bars of the server's Claude account; nothing until the first answer or error. */
@Composable
fun UsagePanel(usage: Usage?, error: String?, modifier: Modifier = Modifier) {
    if (usage == null && error == null) return
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (usage != null && usage.plan.isNotBlank()) "用量 · ${usage.plan}" else "用量",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (usage == null) {
            Text("用量不可用: $error", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("usage-error"))
            return@Column
        }
        for (l in usage.limits) {
            val pct = l.percent.coerceIn(0.0, 100.0)
            val color = when {
                pct >= 90 -> Harness.colors.bad
                pct >= 70 -> Harness.colors.warn
                else -> Harness.colors.brand
            }
            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("usage-${l.kind}-${l.label}")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(l.label, style = MaterialTheme.typography.bodySmall)
                    Text("${l.percent.toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(
                    progress = { (pct / 100).toFloat() },
                    color = color,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp),
                )
                val reset = resetText(l.resetsAt)
                if (reset.isNotEmpty()) Text(reset, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
