package com.cchharness.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun statusColor(status: String): Color = when (status) {
    "idle" -> Harness.colors.ok
    "busy" -> Harness.colors.brand
    "awaiting_permission" -> Harness.colors.warn
    else -> MaterialTheme.colorScheme.outline
}

/** A filled dot; [pulse] makes it breathe, used for work in progress. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulse: Boolean = false) {
    val a = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        val v by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        v
    } else 1f
    androidx.compose.foundation.layout.Box(modifier.size(size).alpha(a).background(color, CircleShape))
}

/** Status dot + label, e.g. "● 运行中". */
@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(statusColor(status), pulse = status == "busy" || status == "awaiting_permission")
        Text(statusLabel(status), style = MaterialTheme.typography.labelMedium, color = statusColor(status))
    }
}

/** The ✻ mark followed by the product name in the serif heading face. */
@Composable
fun BrandTitle(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✻", color = Harness.colors.brand, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("CC Harness", style = MaterialTheme.typography.titleLarge)
    }
}

val Mono = FontFamily.Monospace
