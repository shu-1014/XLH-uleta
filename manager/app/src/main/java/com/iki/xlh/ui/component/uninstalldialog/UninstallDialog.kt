package com.iki.xlh.ui.component.uninstalldialog

import androidx.compose.runtime.Composable
import com.iki.xlh.ui.LocalUiMode
import com.iki.xlh.ui.UiMode

@Composable
fun UninstallDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    when (LocalUiMode.current) {
        UiMode.Miuix -> UninstallDialogMiuix(show, onDismissRequest)
        UiMode.Material -> UninstallDialogMaterial(show, onDismissRequest)
    }
}
