package com.prism.music

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runs Prism's in-app tests. Before Prism starts it puts [RecordedNetwork] in front of every request, so a test never
 * depends on YouTube or a lyrics site that day. `-Pandroid.testInstrumentationRunnerArguments.record=true` records the
 * answers the tests need instead (live), into /sdcard/prism-tests/recorded/ for tools/record.ps1 to pull into
 * src/androidTest/assets/recorded/.
 */
class PrismTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        // Android calls this before PrismApp.onCreate, which builds Prism's network client with it.
        TestHooks.network = RecordedNetwork(record = arguments?.getString("record") == "true")
        val args = arguments ?: Bundle()
        // Measurements (@Measurement) run only when asked for (`-e tvperf 1`): left out of every other run, rather than
        // each one skipped by an assumption, which the orchestrator reports as a failure.
        if (args.getString("tvperf") == null && args.getString("singBench") == null && args.getString("mixStutter") == null && args.getString("eqCheck") == null) {
            args.putString("filter", listOfNotNull(args.getString("filter"), NoMeasurements::class.java.name).joinToString(","))
        }
        whoTouchedCompose()
        super.onCreate(args)
    }

    /**
     * "Detected multithreaded access to SnapshotStateObserver: previousThreadId=47" (Google TV 14, 1.8.0) names the other
     * thread only by its number: when it happens, the log says which thread that was and what it was doing.
     */
    private fun whoTouchedCompose() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val id = Regex("previousThreadId=(\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toLong()
                if (id != null) {
                    val other = Thread.getAllStackTraces().entries.firstOrNull { it.key.id == id }
                    android.util.Log.e("PrismCompose", "previousThreadId=$id is ${other?.key?.name ?: "gone"}:\n" + other?.value?.joinToString("\n") { "  at $it" })
                }
            }
            before?.uncaughtException(thread, e)
        }
    }
}

/**
 * A measurement, not a pass-or-fail test: it runs only when asked for (`-e tvperf 1`, Sing's `-e singBench true`, or
 * Crossfade's `-e mixStutter 1`).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Measurement

/** Leaves measurements (@Measurement classes) out of a run. */
class NoMeasurements : org.junit.runner.manipulation.Filter() {
    override fun shouldRun(description: org.junit.runner.Description): Boolean =
        description.testClass?.isAnnotationPresent(Measurement::class.java) != true

    override fun describe() = "no measurements"
}

