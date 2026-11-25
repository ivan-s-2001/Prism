package com.prism.music.connect.yandex

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Internal hardware diagnostic only. Not in release APKs.
 * Login takes place in the external browser; OAuth tokens are held in memory only.
 */
class StationDiagnosticsActivity : ComponentActivity() {
    private val devices = linkedMapOf<String, YandexStation>()
    private lateinit var status: TextView
    private lateinit var chooser: Spinner
    private lateinit var adapter: ArrayAdapter<String>
    private var scan: Job? = null
    private var operation: Job? = null
    private var session: StationSmokeSession? = null
    private val group = StationSmokeGroup()
    private lateinit var groupRows: LinearLayout
    private var connectedStation: YandexStation? = null
    private var file: File? = null
    private var accessToken: String? = null
    private var loginJob: Job? = null
    private val frames = AtomicLong()
    private val delivered = AtomicLong()
    private var stage = "Idle"
    private var lastError = "None"

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runOperation("Importing MP3") {
            stage = "Import file"
            val imported = withContext(Dispatchers.IO) { importMp3(uri) }
            try {
                // Keep the verified connection when the first file is chosen.
                if (file != null) { session?.stop(); group.stop() }
            } catch (e: Throwable) {
                imported.delete()
                throw e
            }
            file?.delete()
            file = imported
            report("MP3 ready: ${imported.length()} bytes. ${if (session == null) "Connect Station first." else "Connected; ready to play."}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        status = TextView(this).apply {
            text = "Yandex Station diagnostics (experimental)\nConnect the phone and Station to the same Wi-Fi. Use a test account where possible."
            textSize = 16f
        }
        column.addView(status)
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        button("Sign in with Yandex device code") { showLogin() }
        button("Configure Alice voice bridge") { showVoiceBridge() }
        button("Disable Alice voice bridge") {
            VoiceBridgeClient.clear()
            report("Voice bridge disabled.")
        }
        button("Open normal Prism player") {
            if (operation?.isActive == true) return@button
            if (group.size > 0) { report("Stop and disconnect the test group first."); return@button }
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                session?.close()
                session = null
                scan?.cancel()
                startActivity(launch)
            } else report("Cannot open main Prism activity.")
        }
        button("Scan local network") { startScan() }
        chooser = Spinner(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>())
        chooser.adapter = adapter
        column.addView(chooser)
        button("Verify and connect selected Station") { connect() }
        button("Choose local MP3") { picker.launch(arrayOf("audio/mpeg")) }
        button("Play test MP3") {
            runOperation("Sending play command") {
                stage = "Check connection"
                val active = session ?: error("Not connected")
                stage = "Check selected file"
                val mp3 = file ?: error("No file")
                val station = connectedStation ?: error("Not connected")
                stage = "Find phone LAN address"
                val address = withContext(Dispatchers.IO) {
                    DatagramSocket().use {
                        it.connect(station.address, station.port)
                        it.localAddress as? Inet4Address ?: error("No IPv4 route")
                    }
                }
                stage = "Send Glagol / start HTTP server"
                delivered.set(0)
                withContext(Dispatchers.IO) {
                    active.play(address, mp3, "Prism audio test") { bytes -> delivered.addAndGet(bytes) }
                }
                report("Play command queued. Listen to the Station and check counters.")
            }
        }
        button("Volume 20%") { runOperation("Setting volume") { sessionOrThrow().volume(0.2); report("Volume command queued.") } }
        button("Stop") { runOperation("Stopping") { sessionOrThrow().stop(); report("Station reports playback stopped.") } }
        button("Refresh diagnostics") {
            report("Connected: ${session != null}\nMP3: ${file?.length() ?: 0} bytes\nState frames: ${frames.get()}\nMP3 bytes delivered: ${delivered.get()}\nLast error: $lastError\nOnly audible playback confirms success.")
        }
        button("Disconnect") {
            if (operation?.isActive == true) { report("Another operation is running."); return@button }
            session?.close()
            session = null
            report("Disconnected. Use Stop first to terminate remote playback.")
        }
        column.addView(TextView(this).apply {
            text = "Multiroom test · independent volume"
            textSize = 20f
        })
        button("Add selected Station to group") {
            runOperation("Add group member") {
                check(session == null) { "Disconnect the single Station first" }
                val station = selected()
                val api = QuasarStationApi(accessToken ?: error("Sign in with device code first"))
                group.add(station, api)
                renderGroup()
                report("Group: ${group.size} Station(s). Select another or play test MP3.")
            }
        }
        button("Remove selected Station from group") {
            runOperation("Remove group member") {
                group.remove(selected().deviceId)
                renderGroup()
                report("Group: ${group.size} Station(s).")
            }
        }
        button("Play MP3 on group") {
            runOperation("Play group") {
                check(group.size > 0) { "Add Stations first" }
                group.play(file ?: error("No file"))
                renderGroup()
                report("Group play commands sent. Check each Station and counters.")
            }
        }
        button("Stop group") {
            runOperation("Stop group") { group.stop(); renderGroup(); val failed = group.snapshot().count { it.error != null }; report(if (failed == 0) "All Stations report playback stopped." else "$failed Station(s): stop not confirmed. See group diagnostics.") }
        }
        button("Group volume 20%") {
            runOperation("Group volume") { group.volumeAll(20); renderGroup(); report("Group volume requested.") }
        }
        button("Refresh group diagnostics") { renderGroup() }
        button("Disconnect group") {
            runOperation("Disconnect group") {
                try { group.stop() } finally { group.close(); renderGroup() }
                report("Group disconnected.")
            }
        }
        groupRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(groupRows)
        val scroll = ScrollView(this).apply { addView(column) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
    }

    private fun selected(): YandexStation = devices.values.elementAtOrNull(chooser.selectedItemPosition)
        ?: error("Select a Station")

    private fun sessionOrThrow(): StationSmokeSession = session ?: error("Not connected")

    private fun startScan() {
        if (operation?.isActive == true) { report("Another operation is running."); return }
        scan?.cancel()
        session?.close()
        session = null
        devices.clear()
        adapter.clear()
        scan = lifecycleScope.launch {
            report("Scanning...")
            try {
                YandexStationDiscovery(this@StationDiagnosticsActivity).discover().collect { event ->
                    when (event) {
                        is StationDiscoveryEvent.Found -> devices[event.station.serviceName] = event.station
                        is StationDiscoveryEvent.Lost -> devices.remove(event.serviceName)
                    }
                    adapter.clear()
                    adapter.addAll(devices.values.map { "${it.serviceName} (${it.platform})" })
                    report("Found ${devices.size} candidate(s). Account ownership is not verified yet.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                report("Discovery failed. Check Wi-Fi and Android local network permissions.")
            }
        }
    }

    private fun connect() = runOperation("Verifying Station with Yandex") {
        check(group.size == 0) { "Disconnect the group first" }
        stage = "Select Station"
        val station = selected()
        scan?.cancelAndJoin()
        scan = null
        session?.close()
        session = null
        frames.set(0)
        val api = QuasarStationApi(accessToken ?: error("Sign in with device code first"))
        stage = "Quasar device list / certificate"
        val identity = api.verify(station)
        stage = "TLS WebSocket / device token / ping"
        val next = StationSmokeSession(station, identity, api) { frames.incrementAndGet() }
        try {
            next.ping()
            session = next
            connectedStation = station
            report("Account match and TLS connection established. Ping queued; check state frames.")
        } catch (e: Throwable) {
            next.close()
            throw e
        }
    }

    private fun showVoiceBridge() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }
        val url = android.widget.EditText(this).apply {
            hint = "https://your-domain/prism-voice/"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = android.widget.EditText(this).apply {
            hint = "PRISM_PHONE_TOKEN"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fields.addView(url)
        fields.addView(token)
        AlertDialog.Builder(this)
            .setTitle("Private Alice voice bridge")
            .setView(fields)
            .setPositiveButton("Enable") { _, _ ->
                try {
                    VoiceBridgeClient.configure(url.text.toString(), token.text.toString().trim())
                    report("Voice bridge configured for this process. Connect a Station in normal Prism.")
                } catch (_: Exception) {
                    report("Invalid HTTPS URL or phone token.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogin() {
        if (loginJob?.isActive == true) {
            report("Authorization is already in progress.")
            return
        }
        if (operation?.isActive == true) return
        if (group.size > 0) { report("Disconnect the group before signing in again."); return }
        scan?.cancel()
        session?.close()
        session = null
        accessToken = null
        StationConnectAccount.clear()
        loginJob = lifecycleScope.launch {
            report("Requesting Yandex device code...")
            try {
                val auth = YandexDeviceAuth()
                val grant = auth.requestCode()
                val dialog = AlertDialog.Builder(this@StationDiagnosticsActivity)
                    .setTitle("Yandex authorization")
                    .setMessage("Enter this code on the official Yandex page:\n\n${grant.userCode}\n\nConfirm access there, then return to Prism.")
                    .setPositiveButton("Open Yandex") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(grant.verificationUrl)))
                    }
                    .setNegativeButton("Cancel") { _, _ -> loginJob?.cancel() }
                    .setOnCancelListener { loginJob?.cancel() }
                    .create()
                report("Enter the displayed device code on Yandex.")
                dialog.show()
                try {
                    accessToken = auth.awaitToken(grant)
                    StationConnectAccount.authorize(requireNotNull(accessToken))
                    report("Yandex OAuth authorized. Scan and connect your Station.")
                } finally {
                    dialog.dismiss()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                report("Authorization canceled.")
                throw e
            } catch (e: Exception) {
                lastError = if (e is YandexAuthException) e.message.orEmpty() else e.javaClass.simpleName
                report("OAuth: $lastError")
                android.util.Log.w("PrismStationLab", "OAuth: $lastError")
            }
        }
    }

    private fun importMp3(uri: Uri): File {
        val target = File.createTempFile("station-test-", ".mp3", cacheDir)
        try {
            val input = contentResolver.openInputStream(uri) ?: error("Cannot open file")
            input.use { source ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(32768)
                    var size = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= 30L * 1024 * 1024)
                        out.write(buffer, 0, count)
                    }
                    require(size > 0)
                }
            }
            return target
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun runOperation(label: String, action: suspend () -> Unit) {
        if (operation?.isActive == true) { report("Another operation is running."); return }
        operation = lifecycleScope.launch {
            stage = label
            report(label)
            try { action() }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                lastError = "$stage: timeout"
                report(lastError)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val safeMessage = e.message.orEmpty().let { msg ->
                    when {
                        msg in setOf("Sign in with device code first", "Disconnect the single Station first", "Disconnect the group first", "Add Stations first", "Group limit: 8 Stations", "Not connected", "No file", "No IPv4 route", "Select a Station", "Station is not in this Yandex account", "No Station certificate in account metadata", "Yandex rejected the device token request", "Station certificate mismatch", "Station connection closed", "Station stop not confirmed", "Invalid Yandex device list") -> msg
                        Regex("Quasar HTTP [0-9]{3}").matches(msg) -> msg
                        else -> e.javaClass.simpleName
                    }
                }
                lastError = "$stage: $safeMessage"
                report(lastError)
                android.util.Log.w("PrismStationLab", lastError)
            }
        }
    }

    private fun renderGroup() {
        groupRows.removeAllViews()
        for (m in group.snapshot()) {
            groupRows.addView(TextView(this).apply {
                text = "${m.station.serviceName} (${m.station.platform})\nState frames: ${m.frames} · MP3 bytes: ${m.bytes}\nRequested volume: ${m.requestedVolume?.let { "$it%" } ?: "not set"} · ${m.error ?: "No command error"}"
            })
            val slider = SeekBar(this).apply {
                max = 100
                progress = m.requestedVolume ?: 20
                contentDescription = "Volume for ${m.station.serviceName}; drag to set"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val value = seekBar?.progress ?: return
                        runOperation("Station volume") {
                            group.volume(m.station.deviceId, value)
                            renderGroup()
                            report("Individual volume requested.")
                        }
                    }
                })
            }
            groupRows.addView(slider)
        }
    }

    private fun report(message: String) { status.text = message }

    override fun onDestroy() {
        loginJob?.cancel()
        accessToken = null
        scan?.cancel()
        operation?.cancel()
        group.close()
        session?.close()
        file?.delete()
        super.onDestroy()
    }

}
