package com.prism.music.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.prism.music.connect.yandex.StationConnectAccount
import com.prism.music.connect.yandex.YandexDeviceAuth
import com.prism.music.connect.yandex.YandexDeviceGrant
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.connect.CastState
import com.prism.music.connect.RendererKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun StationAccountRow(ink: Color) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val signedIn by StationConnectAccount.signedIn.collectAsState()
    val ru = java.util.Locale.getDefault().language == "ru"
    fun label(russian: String, english: String) = if (ru) russian else english
    var show by remember { mutableStateOf(false) }
    var grant by remember { mutableStateOf<YandexDeviceGrant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { StationConnectAccount.initialize(context.applicationContext) }
    }
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        grant = null
        error = null
        try {
            val auth = YandexDeviceAuth()
            val code = auth.requestCode()
            grant = code
            val token = auth.awaitToken(code)
            withContext(Dispatchers.IO) {
                StationConnectAccount.authorizePersistently(context.applicationContext, token)
            }
            show = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = label("Не удалось войти. Закройте окно и повторите попытку.", "Sign-in failed. Close and try again.")
        }
    }
    Row(
        Modifier.fillMaxWidth().testTag("account:yandex")
            .clickable { if (signedIn) confirmLogout = true else show = true }
            .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFFC3F1D)), contentAlignment = Alignment.Center) {
            Text("Я", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (signedIn) "Яндекс ID" else label("Войти в Яндекс ID", "Sign in to Yandex ID"),
                color = ink, style = MaterialTheme.typography.bodyMedium)
            Text(if (signedIn) label("Станции · выполнен вход", "Stations · signed in")
                else label("Подключение Яндекс Станций", "Connect Yandex Stations"),
                color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Icon(if (signedIn) PrismIcons.Logout else PrismIcons.ChevronRight, null, tint = ink)
    }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(label("Вход в Яндекс", "Yandex sign-in")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val code = grant
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    code == null -> CircularProgressIndicator()
                    else -> {
                        Text(label("Введите этот код на официальной странице Яндекса.", "Enter this code on the official Yandex page."))
                        Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code.userCode)) }) {
                            Text(label("Скопировать код", "Copy code"))
                        }
                        Text(label("После подтверждения вернитесь в Prism.", "Return to Prism after approval."))
                    }
                }
            }
        },
        confirmButton = {
            val code = grant
            if (code != null && error == null) TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl))) }
                catch (_: Exception) { error = label("Не удалось открыть браузер.", "Could not open browser.") }
            }) { Text(label("Открыть Яндекс", "Open Yandex")) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(label("Отмена", "Cancel")) } },
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(label("Выйти из Яндекс ID?", "Sign out of Yandex ID?")) },
        text = { Text(label("Подключение к Станции будет закрыто. YouTube Music не изменится.", "Station connection will close. YouTube Music is unaffected.")) },
        confirmButton = { TextButton(onClick = {
            confirmLogout = false
            val state = c.cast.state.value
            if ((state as? CastState.Casting)?.renderer?.kind == RendererKind.YANDEX_STATION ||
                (state as? CastState.Connecting)?.renderer?.kind == RendererKind.YANDEX_STATION) {
                c.cast.disconnect(keepPlaying = false)
            }
            StationConnectAccount.clear()
        }) { Text(label("Выйти", "Sign out")) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(label("Отмена", "Cancel")) } },
    )
}
