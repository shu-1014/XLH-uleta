package com.iki.xlh.ui.component.themeimport

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.iki.xlh.R
import com.iki.xlh.ui.screen.settings.ThemePackageOptions
import com.iki.xlh.ui.util.CustomTextGroup

@Composable
fun ThemeExportDialog(
    show: Boolean,
    hasBackgroundImage: Boolean,
    hasCardImage: Boolean,
    hasStickers: Boolean,
    hasAppIcon: Boolean,
    availableCustomTextGroups: Set<CustomTextGroup>,
    onDismissRequest: () -> Unit,
    onConfirm: (ThemePackageOptions) -> Unit,
    adaptive: Boolean = false,
    hasCustomColors: Boolean = false,
    hasImageEffects: Boolean = false,
    immersiveHome: Boolean = false,
    hasBottomBarIcons: Boolean = false,
    onUpload: ((ThemePackageOptions) -> Unit)? = null,
) {
    ThemeOptionsDialog(
        show = show,
        title = stringResource(R.string.theme_export),
        message = stringResource(R.string.theme_export_dialog_message),
        hasBackgroundImage = hasBackgroundImage,
        hasCardImage = hasCardImage,
        hasStickers = hasStickers,
        hasAppIcon = hasAppIcon,
        availableCustomTextGroups = availableCustomTextGroups,
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
        formatVersion = when {
            immersiveHome || hasBottomBarIcons ->
                com.iki.xlh.ui.util.ThemePackageStorage.HOME_STATUS_VERSION

            hasCustomColors || hasImageEffects ->
                com.iki.xlh.ui.util.ThemePackageStorage.APPEARANCE_VERSION

            adaptive -> com.iki.xlh.ui.util.ThemePackageStorage.ADAPTIVE_VERSION
            else -> com.iki.xlh.ui.util.ThemePackageStorage.BASE_VERSION
        },
        adaptive = adaptive,
        adaptiveNotice = R.string.theme_adaptive_export_notice,
        hasCustomColors = hasCustomColors,
        hasImageEffects = hasImageEffects,
        hasBottomBarIcons = hasBottomBarIcons,
        onUpload = onUpload,
    )
}
