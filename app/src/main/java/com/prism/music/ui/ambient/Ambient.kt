package com.prism.music.ui.ambient

import android.os.BatteryManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.prism.music.AppContainer
import com.prism.music.playback.CarLyricsCard
import com.prism.music.ui.player.rememberLyricsState
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.sin

/**
 * When the listener last touched the screen or pressed a key ([touched] from the activities' onUserInteraction), for the
 * ambient screen to know the room has gone quiet.
 */
object Idle {
    @Volatile var lastInput: Long = System.currentTimeMillis()
        private set
    /** The ambient screen is showing: the next key only wakes it (it doesn't also press what's under it). */
    @Volatile var ambient: Boolean = false
        internal set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun touched() {
        lastInput = System.currentTimeMillis()
        listeners.forEach { it() }
    }

    /** Coming back to Prism counts as the remote in hand: the quiet starts over (the ambient screen waits again). */
    fun reset() { lastInput = System.currentTimeMillis() }

    /** Pages that keep the ambient screen away while they're open ([HoldsAmbient]): pairing, typing a search. */
    @Volatile var holds = 0
        private set

    @Synchronized internal fun hold(on: Boolean) {
        holds = (holds + if (on) 1 else -1).coerceAtLeast(0)
        // Let go: the quiet counts from now.
        if (!on) reset()
    }

    internal fun listen(l: () -> Unit) = listeners.add(l)
    internal fun stop(l: () -> Unit) = listeners.remove(l)
}

/**
 * Keeps the ambient screen away while this is shown (it'd take the page's place: a pairing page closed, a search being
 * typed on the TV's keyboard, whose keys don't come through Prism, lost).
 */
@Composable
fun HoldsAmbient() {
    androidx.compose.runtime.DisposableEffect(Unit) {
        Idle.hold(true)
        onDispose { Idle.hold(false) }
    }
}

/** How often the ambient screen checks the time since the last input. */
private const val CHECK_MS = 5_000L

/** Whether the ambient screen should show now: music playing, [allowed], and [minutes] since the last input (0: never). */
fun ambientDue(playing: Boolean, allowed: Boolean, minutes: Int, idleMs: Long): Boolean =
    playing && allowed && minutes > 0 && idleMs >= minutes * 60_000L

/** Desk mode may show only on a charging phone, never over a video or Sing. */
fun ambientAllowed(minutes: Int, deskMode: Boolean, charging: Boolean, video: Boolean, singing: Boolean): Boolean =
    minutes > 0 && deskMode && charging && !video && !singing

/** A desk-mode screen stays awake only while music is playing. */
fun keepScreenOn(playing: Boolean, allowed: Boolean): Boolean = playing && allowed

/** The slow drift that keeps the ambient screen burn-in safe: a few dp, one loop every several minutes. */
fun ambientDrift(nowMs: Long): DpOffset {
    val t = nowMs / 1000.0
    return DpOffset((14 * sin(t / 420.0 * 2 * Math.PI)).dp, (10 * sin(t / 300.0 * 2 * Math.PI)).dp)
}

/** Phone desk mode uses its own dialog over the current screen, dismissed by Back or a tap. */
@Composable
fun AmbientHost() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val view = LocalView.current
    val settings by c.settings.flow.collectAsState()
    val playing by c.player.isPlaying.collectAsState()
    val video by c.player.videoMode.collectAsState()
    val singing by c.sing.on.collectAsState()
    // A phone asks about charging only while desk mode is on (off, this costs nothing).
    val desk = settings.ambient.deskMode
    val charging by produceState(false, desk) {
        while (desk) { value = isCharging(context); delay(CHECK_MS * 6) }
    }
    val allowed = ambientAllowed(settings.ambient.ambientMinutes, desk, charging, video, singing)
    // The window's own flag (the player sets the view's for videos, #169): neither turns the other off.
    val window = (view.context as? android.app.Activity)?.window
    val keepOn = keepScreenOn(playing, allowed)
    androidx.compose.runtime.DisposableEffect(keepOn, window) {
        val on = keepOn
        if (on) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (on) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var shown by remember { mutableStateOf(false) }
    var input by remember { mutableLongStateOf(Idle.lastInput) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l: () -> Unit = { input = Idle.lastInput; shown = false }
        Idle.listen(l)
        onDispose { Idle.stop(l) }
    }
    // The quiet only counts while Prism is on screen: away from it (another app, the TV's home) and back, it starts over
    // (the release check: the ambient screen came up the moment Prism was back).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START) Idle.reset() }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    LaunchedEffect(allowed, playing, settings.ambient.ambientMinutes, input) {
        if (!allowed || !playing) { shown = false; return@LaunchedEffect }
        while (true) {
            val here = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (here && Idle.holds == 0 && ambientDue(true, true, settings.ambient.ambientMinutes, System.currentTimeMillis() - Idle.lastInput)) { shown = true; break }
            if (!here || Idle.holds > 0) Idle.reset()
            delay(CHECK_MS)
        }
    }
    androidx.compose.runtime.SideEffect { Idle.ambient = shown }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Idle.ambient = false } }
    if (shown) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { Idle.touched() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AmbientNow(c, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { Idle.touched() } })
        }
    }
}

/** What the ambient screen shows now: the song, the line being sung (when it has lyrics), the time, drifting. */
@Composable
fun AmbientNow(c: AppContainer, modifier: Modifier = Modifier) {
    val song by c.player.currentSong.collectAsState()
    val clock by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(CHECK_MS * 2) } }
    val time = remember(clock / 60_000) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(clock)) }
    val line = song?.let { s ->
        val lyrics = rememberLyricsState(s)
        val lines = lyrics.lines
        val at by produceState(-1, lines) { while (true) { value = CarLyricsCard.lineAt(lines, c.player.lyricPosition); delay(500) } }
        lines.getOrNull(at)?.text?.takeIf { it.isNotBlank() }
    }
    val n = song ?: return
    PhoneAmbientScreen(n, line, time, modifier, drift = ambientDrift(clock))
}

private fun isCharging(context: android.content.Context): Boolean =
    context.getSystemService(BatteryManager::class.java)?.isCharging == true
