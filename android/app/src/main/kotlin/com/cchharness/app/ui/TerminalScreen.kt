package com.cchharness.app.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.cchharness.core.TermStatus
import com.cchharness.core.TerminalController
import com.cchharness.core.term.TermKey
import com.cchharness.core.term.TermKeys

private val Bg = Color(TerminalPalette.BACKGROUND)
private val Fg = Color(TerminalPalette.FOREGROUND)
private val Dim = Color(0xFFA09D93)
private val Key = Color(0xFF30302E)
private val KeyOn = Color(TerminalPalette.CURSOR)

@Composable
fun TerminalScreen(
    title: String,
    controller: TerminalController,
    fontSizeSp: Int,
    onFontSize: (Int) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val status by controller.status.collectAsState()
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val clipboard = LocalClipboardManager.current
    val emu = controller.emulator

    // The screen is always dark: make the status-bar icons light while it is shown.
    val host = LocalView.current
    DisposableEffect(host) {
        val window = (host.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, host) }
        val before = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose { if (before != null) ctl.isAppearanceLightStatusBars = before }
    }
    LaunchedEffect(controller, view) { controller.frame.collect { view?.invalidate() } }
    LaunchedEffect(controller) {
        emu.onClipboard = { text -> host.post { clipboard.setText(AnnotatedString(text)) } } // OSC 52: the program copied something
    }

    fun sendKey(k: TermKey) {
        controller.send(TermKeys.key(k, appCursor = emu.appCursorKeys, alt = alt, ctrl = ctrl))
        if (ctrl || alt) { ctrl = false; alt = false }
        view?.scrollToBottom()
    }

    fun sendText(t: String) {
        controller.send(TermKeys.text(t, ctrl, alt))
        if (ctrl || alt) { ctrl = false; alt = false }
    }

    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("term-back")) { Text("‹", color = Fg, fontSize = 30.sp) }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "终端" }, color = Fg, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val (label, color) = when (status) {
                TermStatus.Live -> "已连接" to Color(0xFF6CBF88)
                TermStatus.Connecting -> "连接中…" to Color(0xFFE0A83C)
                is TermStatus.Reconnecting -> "重连中…" to Color(0xFFE0A83C)
                TermStatus.Ended -> "已结束" to Dim
                TermStatus.Unauthorized -> "登录已过期" to Color(0xFFE8756B)
                is TermStatus.Failed -> "不可用" to Color(0xFFE8756B)
            }
            Dot(color, pulse = status is TermStatus.Connecting || status is TermStatus.Reconnecting)
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp, end = 4.dp).testTag("term-status"))
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.testTag("term-menu")) { Text("⋮", color = Fg, fontSize = 20.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("粘贴") },
                        onClick = {
                            menu = false
                            clipboard.getText()?.text?.let { controller.send(TermKeys.paste(it, emu.bracketedPaste)) }
                        },
                        modifier = Modifier.testTag("term-paste"),
                    )
                    DropdownMenuItem(
                        text = { Text("选择 / 复制屏幕文字") },
                        onClick = {
                            menu = false
                            textDialog = emu.screenText(includeScrollback = true)
                        },
                        modifier = Modifier.testTag("term-copy"),
                    )
                    DropdownMenuItem(text = { Text("字体 +") }, onClick = { onFontSize(fontSizeSp + 1) }, modifier = Modifier.testTag("term-font-up"))
                    DropdownMenuItem(text = { Text("字体 −") }, onClick = { onFontSize(fontSizeSp - 1) }, modifier = Modifier.testTag("term-font-down"))
                    DropdownMenuItem(
                        text = { Text("结束终端(关闭 shell)") },
                        onClick = {
                            menu = false
                            confirmClose = true
                        },
                        modifier = Modifier.testTag("term-close"),
                    )
                }
            }
        }

        val banner = when (val s = status) {
            is TermStatus.Reconnecting -> "连接中断,正在重连…" + if (s.message.isNotBlank()) " (${s.message})" else ""
            TermStatus.Ended -> "终端已结束。返回列表可新建。"
            TermStatus.Unauthorized -> "登录已过期,请返回并重新登录"
            is TermStatus.Failed -> s.message
            else -> null
        }
        if (banner != null) {
            Text(
                banner,
                color = Color(0xFFF8E1DE),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF4A2220)).padding(horizontal = 16.dp, vertical = 6.dp).testTag("term-banner"),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx).also { v ->
                        v.fontSizeSp = fontSizeSp
                        v.emulator = emu
                        v.onInput = { controller.send(it) }
                        v.onResize = { c, r -> controller.resize(c, r) }
                        v.onFontSize = onFontSize
                        v.modifiers = { ctrl to alt }
                        v.onModifiersUsed = { ctrl = false; alt = false }
                        view = v
                        v.requestFocus()
                    }
                },
                update = {
                    it.fontSizeSp = fontSizeSp
                    it.modifiers = { ctrl to alt }
                    it.onModifiersUsed = { ctrl = false; alt = false }
                },
                modifier = Modifier.fillMaxSize().testTag("term-view"),
            )
        }

        ExtraKeys(
            ctrl = ctrl,
            alt = alt,
            onCtrl = { ctrl = !ctrl },
            onAlt = { alt = !alt },
            onKey = ::sendKey,
            onText = ::sendText,
            onKeyboard = { view?.let { v -> v.requestFocus(); v.showKeyboard() } },
        )
    }

    textDialog?.let { text ->
        AlertDialog(
            onDismissRequest = { textDialog = null },
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("屏幕文字") },
            text = {
                Surface(color = Bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(360.dp)) {
                    Box(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                        SelectionContainer {
                            Text(text, color = Fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.testTag("term-text"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); textDialog = null }, modifier = Modifier.testTag("term-copy-all")) { Text("复制全部") }
            },
            dismissButton = { TextButton(onClick = { textDialog = null }) { Text("关闭") } },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            properties = HarnessDialogProperties,
            modifier = HarnessDialogModifier,
            title = { Text("结束终端") },
            text = { Text("这会关闭其中的 shell 和正在运行的程序。只想离开的话,点左上角返回即可,终端会在服务器上继续运行。") },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; onClose() }, modifier = Modifier.testTag("term-close-confirm")) {
                    Text("结束", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("取消") } },
        )
    }
}

/** Keys a phone keyboard lacks. Ctrl and Alt are sticky: they apply to the next key and release. */
@Composable
private fun ExtraKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (TermKey) -> Unit,
    onText: (String) -> Unit,
    onKeyboard: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF262624)).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp).testTag("term-keys"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyButton("Esc", "esc") { onKey(TermKey.Escape) }
        KeyButton("Tab", "tab") { onKey(TermKey.Tab) }
        KeyButton("Ctrl", "ctrl", on = ctrl, onClick = onCtrl)
        KeyButton("Alt", "alt", on = alt, onClick = onAlt)
        KeyButton("←", "left") { onKey(TermKey.Left) }
        KeyButton("↓", "down") { onKey(TermKey.Down) }
        KeyButton("↑", "up") { onKey(TermKey.Up) }
        KeyButton("→", "right") { onKey(TermKey.Right) }
        KeyButton("Home", "home") { onKey(TermKey.Home) }
        KeyButton("End", "end") { onKey(TermKey.End) }
        KeyButton("PgUp", "pgup") { onKey(TermKey.PageUp) }
        KeyButton("PgDn", "pgdn") { onKey(TermKey.PageDown) }
        for (c in listOf("/", "-", "|", "~", "_", "\"", "'", "`", "$", "&", "*", "(", ")", "[", "]", "{", "}", "<", ">", "\\")) {
            KeyButton(c, "char-$c") { onText(c) }
        }
        KeyButton("⌨", "keyboard", onClick = onKeyboard)
    }
}

@Composable
private fun KeyButton(label: String, tag: String, on: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (on) KeyOn else Key,
        contentColor = if (on) Color.White else Fg,
        modifier = Modifier.widthIn(min = 40.dp).height(38.dp).testTag("term-key-$tag"),
    ) {
        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
    }
}
