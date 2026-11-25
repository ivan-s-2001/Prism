package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr

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
import kotlinx.coroutines.launch

@Composable
internal fun StationAccountRow(ink: Color) {
    val context = LocalContext.current
    val c = LocalContainer.current
    val signedIn by StationConnectAccount.signedIn.collectAsState()

    var show by remember { mutableStateOf(false) }
    var grant by remember { mutableStateOf<YandexDeviceGrant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val accountScope = rememberCoroutineScope()

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
            val token = auth.awaitCredentials(code)
            withContext(Dispatchers.IO) {
                StationConnectAccount.authorizePersistently(context.applicationContext, token)
            }
            show = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = tr(R.string.prism_yandex_sign_in_failed_close_and_try_again)
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
            Text(if (signedIn) "Яндекс ID" else tr(R.string.prism_yandex_sign_in_to_yandex_id),
                color = ink, style = MaterialTheme.typography.bodyMedium)
            Text(if (signedIn) tr(R.string.prism_yandex_stations_signed_in)
                else tr(R.string.prism_yandex_connect_yandex_stations),
                color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Icon(if (signedIn) PrismIcons.Logout else PrismIcons.ChevronRight, null, tint = ink)
    }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(tr(R.string.prism_yandex_yandex_sign_in)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val code = grant
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    code == null -> CircularProgressIndicator()
                    else -> {
                        Text(tr(R.string.prism_yandex_enter_this_code_on_the_official_yandex_page))
                        Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(code.userCode)) }) {
                            Text(tr(R.string.prism_yandex_copy_code))
                        }
                        Text(tr(R.string.prism_yandex_return_to_prism_after_approval))
                    }
                }
            }
        },
        confirmButton = {
            val code = grant
            if (code != null && error == null) TextButton(onClick = {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl))) }
                catch (_: Exception) { error = tr(R.string.prism_yandex_could_not_open_browser) }
            }) { Text(tr(R.string.prism_yandex_open_yandex)) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(tr(R.string.prism_yandex_cancel)) } },
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(tr(R.string.prism_yandex_sign_out_of_yandex_id)) },
        text = { Text(tr(R.string.prism_yandex_station_connection_will_close_youtube_music_is_unaffected)) },
        confirmButton = { TextButton(onClick = {
            confirmLogout = false
            val state = c.cast.state.value
            if ((state as? CastState.Casting)?.renderer?.kind == RendererKind.YANDEX_STATION ||
                (state as? CastState.Connecting)?.renderer?.kind == RendererKind.YANDEX_STATION) {
                c.cast.disconnect(keepPlaying = false)
            }
            accountScope.launch {
                try {
                    com.prism.music.connect.yandex.ConnectDeviceClient.revoke(context)
                    withContext(Dispatchers.IO) { StationConnectAccount.clear() }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    android.widget.Toast.makeText(context, tr(R.string.prism_yandex_wait_for_the_station_to_disconnect_and_try_again), android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }) { Text(tr(R.string.prism_yandex_sign_out)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(tr(R.string.prism_yandex_cancel)) } },
    )
}
