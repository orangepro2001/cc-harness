package com.cchharness.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Dialogs span the window and size themselves (Material limits them to 280-560dp), keeping a
 * margin to the screen edge. Platform-default-width dialogs re-layout endlessly under
 * Robolectric when they contain text fields, so this also keeps the UI tests deterministic.
 */
val HarnessDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

val HarnessDialogModifier: Modifier = Modifier.padding(horizontal = 24.dp)
