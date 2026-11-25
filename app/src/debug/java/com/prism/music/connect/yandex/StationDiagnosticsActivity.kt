package com.prism.music.connect.yandex

import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Internal hardware diagnostic only. Not in release APKs.
 * Login is performed on Yandex's page. No passwords or session values are displayed,
 * exported, or written to Prism preferences. Android WebView manages its own cookies.
 */
class StationDiagnosticsActivity : ComponentActivity() {
    private val devices = linkedMapOf<String, YandexStation>()
    private lateinit var status: TextView
    private lateinit var chooser: Spinner
    private lateinit var adapter: ArrayAdapter<String>
    private var scan: Job? = null
    private var operation: Job? = null
    private var session: StationSmokeSession? = null
    private var file: File? = null
    private var login: Dialog? = null
    private val frames = AtomicLong()
    private val delivered = AtomicLong()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runOperation("Importing MP3") {
            session?.close()
            session = null
            val imported = withContext(Dispatchers.IO) { importMp3(uri) }
            file?.delete()
            file = imported
            report("MP3 ready: ${imported.length()} bytes. Use a short CBR MP3 test file.")
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
        button("Sign in on Yandex (experimental)") { showLogin() }
        button("Scan local network") { startScan() }
        chooser = Spinner(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf<String>())
        chooser.adapter = adapter
        column.addView(chooser)
        button("Verify and connect selected Station") { connect() }
        button("Choose local MP3") { picker.launch(arrayOf("audio/mpeg")) }
        button("Play test MP3") {
            runOperation("Sending play command") {
                val active = session ?: error("Not connected")
                val mp3 = file ?: error("No file")
                val station = selected()
                val address = withContext(Dispatchers.IO) {
                    DatagramSocket().use {
                        it.connect(station.address, station.port)
                        it.localAddress as? Inet4Address ?: error("No IPv4 route")
                    }
                }
                delivered.set(0)
                withContext(Dispatchers.IO) {
                    active.play(address, mp3, "Prism audio test") { bytes -> delivered.addAndGet(bytes) }
                }
                report("Play command queued. Listen to the Station and check counters.")
            }
        }
        button("Volume 20%") { runOperation("Setting volume") { sessionOrThrow().volume(0.2); report("Volume command queued.") } }
        button("Stop") { runOperation("Stopping") { sessionOrThrow().stop(); report("Stop command queued.") } }
        button("Refresh diagnostics") {
            report("State frames: ${frames.get()}\nMP3 bytes delivered: ${delivered.get()}\nOnly audible playback confirms success.")
        }
        button("Disconnect") {
            operation?.cancel()
            session?.close()
            session = null
            report("Disconnected. Use Stop first to terminate remote playback.")
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun selected(): YandexStation = devices.values.elementAtOrNull(chooser.selectedItemPosition)
        ?: error("Select a Station")

    private fun sessionOrThrow(): StationSmokeSession = session ?: error("Not connected")

    private fun startScan() {
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
        val station = selected()
        scan?.cancelAndJoin()
        scan = null
        session?.close()
        session = null
        frames.set(0)
        val api = QuasarStationApi(YandexWebCookieJar())
        val identity = api.verify(station)
        val next = StationSmokeSession(station, identity, api) { frames.incrementAndGet() }
        try {
            next.ping()
            session = next
            report("Account match and TLS connection established. Ping queued; check state frames.")
        } catch (e: Throwable) {
            next.close()
            throw e
        }
    }

    private fun showLogin() {
        if (login?.isShowing == true) return
        val dialog = Dialog(this)
        val web = WebView(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "Sign in directly on the Yandex page. If Yandex blocks embedded login, stop; do not paste credentials or cookies elsewhere."
        })
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Done"
            setOnClickListener { CookieManager.getInstance().flush(); dialog.dismiss() }
        })
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val host = uri.host.orEmpty().lowercase()
                return uri.scheme != "https" ||
                    !(host == "yandex.ru" || host.endsWith(".yandex.ru") ||
                      host == "ya.ru" || host == "yandex.com" || host.endsWith(".yandex.com"))
            }
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { web.stopLoading(); web.destroy(); login = null }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        login = dialog
        web.loadUrl("https://passport.yandex.ru/auth")
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
            report(label)
            try { action() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
                report("$label failed. Check account login, device selection, certificate and Wi-Fi; no secrets are logged.")
            }
        }
    }

    private fun report(message: String) { status.text = message }

    override fun onDestroy() {
        login?.dismiss()
        scan?.cancel()
        operation?.cancel()
        session?.close()
        file?.delete()
        super.onDestroy()
    }

    /** Only two Quasar HTTPS hosts may receive their own existing first-party cookies. */
    private class YandexWebCookieJar : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!url.isHttps || url.host !in setOf("quasar.iot.yandex.ru", "quasar.yandex.ru")) return emptyList()
            return CookieManager.getInstance().getCookie(url.toString()).orEmpty()
                .split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }
}
