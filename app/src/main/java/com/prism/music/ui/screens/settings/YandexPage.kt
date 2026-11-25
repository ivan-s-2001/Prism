package com.prism.music.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.music.connect.yandex.StationConnectAccount
import com.prism.music.ui.screens.StationAccountRow
import com.prism.music.ui.screens.YandexTransferRow
import com.prism.music.ui.screens.ConnectAccountControls
import com.prism.music.ui.screens.SettingEntry
import com.prism.music.data.prefs.AppSettings

internal val yandexIndex: List<SettingEntry>
    get() = listOf(
        SettingEntry(SettingsPageId.YANDEX, "Яндекс ID", "Яндекс ID", "Yandex login account"),
        SettingEntry(SettingsPageId.YANDEX, "Яндекс Музыка", "Перенести музыку", "Yandex Music transfer import обновить библиотеку"),
    )

internal val yandexLookKeys: List<String> = emptyList()
internal val yandexOldGroups: Map<String, String> = emptyMap()
internal fun yandexValue(s: AppSettings, dark: Boolean): String =
    if (java.util.Locale.getDefault().language == "ru") "ID, музыка, Станции" else "ID, music, Stations"

@Composable
internal fun YandexPage(bottomPadding: Dp, find: String?, showBack: Boolean, onSearch: (() -> Unit)?) {
    val signedIn by StationConnectAccount.signedIn.collectAsState()
    val ink = MaterialTheme.colorScheme.onSurface
    SettingsPageScaffold("Яндекс", bottomPadding, showBack = showBack, onSearch = onSearch, find = find) {
        group("Яндекс ID") {
            StationAccountRow(ink)
        }
        group("Яндекс Музыка") {
            if (signedIn) YandexTransferRow(ink)
            else Text("Войдите в Яндекс ID, чтобы перенести медиатеку.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (signedIn) group("Яндекс Станции") {
            ConnectAccountControls(ink)
        }
    }
}
