package com.example.crunchboost

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class Preset(
        val name: String,
        val lowCutHz: Float,
        val crunchHz: Float,
        val crunchDb: Float,
        val airDb: Float,
        val snap: Float,
        val gateDb: Float,
    )

    private val presets = listOf(
        Preset("Extra crunch", 200f, 4000f, 9f, 4f, 0.6f, -55f),
        Preset("Crispy greens", 250f, 6000f, 7f, 6f, 0.4f, -55f),
        Preset("Fizzy drink", 300f, 8000f, 6f, 9f, 0.2f, -60f),
        Preset("Subtle", 150f, 3500f, 4f, 2f, 0.25f, -65f),
    )

    private enum class Route { WIRED, BLUETOOTH, SPEAKER }

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private lateinit var routeText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var bypassSwitch: Switch
    private val presetButtons = mutableListOf<Button>()

    private lateinit var lowCut: Param
    private lateinit var crunchFreq: Param
    private lateinit var crunchBoost: Param
    private lateinit var air: Param
    private lateinit var snap: Param
    private lateinit var gate: Param
    private lateinit var volume: Param

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = updateRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = updateRoute()
    }

    private val latencyPoll = object : Runnable {
        override fun run() {
            if (!running) return
            val ms = CrunchEngine.nativeOutputLatencyMs()
            val sr = CrunchEngine.nativeSampleRate()
            statusText.text = if (ms > 0) {
                "● Live · output latency ≈ %.0f ms · %d Hz".format(ms, sr)
            } else {
                "● Live · %d Hz".format(sr)
            }
            handler.postDelayed(this, 2000)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())
        applyPreset(presets[0])
    }

    override fun onStart() {
        super.onStart()
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        updateRoute()
    }

    override fun onStop() {
        super.onStop()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        // Android silences the mic for background apps anyway, so stop cleanly.
        if (running) stopEngine()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startEngine()
        } else {
            statusText.text = "Microphone permission is needed to hear your food."
        }
    }

    // ---------------------------------------------------------------- engine control

    private fun onStartStopClicked() {
        if (running) {
            stopEngine()
            return
        }
        if (currentRoute() == Route.SPEAKER) {
            statusText.text = "Plug in headphones first — through the speaker it will howl with feedback."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startEngine()
    }

    private fun startEngine() {
        pushParams()
        if (CrunchEngine.nativeStart()) {
            running = true
            startButton.text = "Stop"
            statusText.text = "Starting…"
            handler.postDelayed(latencyPoll, 700)
        } else {
            statusText.text = "Couldn't open the audio streams (see logcat, tag CrunchEngine)."
        }
    }

    private fun stopEngine() {
        CrunchEngine.nativeStop()
        running = false
        handler.removeCallbacks(latencyPoll)
        startButton.text = "Start"
        statusText.text = "Stopped"
    }

    private fun pushParams() {
        if (!::volume.isInitialized || !::bypassSwitch.isInitialized) return
        CrunchEngine.nativeSetParams(
            lowCut.value, crunchFreq.value, crunchBoost.value, air.value,
            snap.value, gate.value, volume.value, bypassSwitch.isChecked,
        )
    }

    private fun applyPreset(p: Preset) {
        lowCut.update(p.lowCutHz)
        crunchFreq.update(p.crunchHz)
        crunchBoost.update(p.crunchDb)
        air.update(p.airDb)
        snap.update(p.snap)
        gate.update(p.gateDb)
        presetButtons.forEachIndexed { i, b -> b.alpha = if (presets[i] == p) 1f else 0.55f }
        pushParams()
    }

    // ---------------------------------------------------------------- routing

    private fun currentRoute(): Route {
        val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val wired = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )
        val bluetooth = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            TYPE_BLE_HEADSET,
        )
        return when {
            outs.any { it.type in wired } -> Route.WIRED
            outs.any { it.type in bluetooth } -> Route.BLUETOOTH
            else -> Route.SPEAKER
        }
    }

    private fun updateRoute() {
        if (!::routeText.isInitialized) return
        when (currentRoute()) {
            Route.WIRED -> {
                routeText.text = "✓ Wired headphones — lowest latency"
                routeText.setTextColor(Color.rgb(120, 220, 140))
            }
            Route.BLUETOOTH -> {
                routeText.text = "⚠ Bluetooth — expect 100–300 ms of delay (sounds like an echo). Wired is much better."
                routeText.setTextColor(Color.rgb(250, 200, 90))
            }
            Route.SPEAKER -> {
                routeText.text = "✕ No headphones detected — plug some in"
                routeText.setTextColor(Color.rgb(250, 110, 100))
                if (running) {
                    stopEngine()
                    statusText.text = "Stopped: headphones were disconnected."
                }
            }
        }
    }

    // ---------------------------------------------------------------- UI

    /** A labelled slider. Frequency sliders use a log scale. */
    private inner class Param(
        private val label: String,
        private val lo: Float,
        private val hi: Float,
        private val logScale: Boolean,
        private val fmt: (Float) -> String,
        initial: Float,
    ) {
        val labelView = TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val bar = SeekBar(this@MainActivity).apply { max = STEPS }
        var value: Float = initial
            private set

        init {
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value = fromT(progress / STEPS.toFloat())
                    refreshLabel()
                    presetButtons.forEach { it.alpha = 0.55f }
                    pushParams()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            update(initial)
        }

        fun update(v: Float) {
            value = v.coerceIn(lo, hi)
            bar.progress = (toT(value) * STEPS).roundToInt()
            refreshLabel()
        }

        private fun toT(v: Float) = if (logScale) ln(v / lo) / ln(hi / lo) else (v - lo) / (hi - lo)
        private fun fromT(t: Float) = if (logScale) lo * (hi / lo).pow(t) else lo + t * (hi - lo)
        private fun refreshLabel() {
            labelView.text = "$label  ·  ${fmt(value)}"
        }
    }

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).roundToInt()
        val hz: (Float) -> String = { if (it >= 1000f) "%.1f kHz".format(it / 1000f) else "%.0f Hz".format(it) }
        val db: (Float) -> String = { "%+.0f dB".format(it) }

        lowCut = Param("Low cut (removes jaw boom)", 40f, 800f, true, hz, 200f)
        crunchFreq = Param("Crunch frequency", 1500f, 10000f, true, hz, 4000f)
        crunchBoost = Param("Crunch boost", 0f, 15f, false, db, 9f)
        air = Param("Air / sparkle", 0f, 12f, false, db, 4f)
        snap = Param("Snap (attack of each bite)", 0f, 1f, false, { "${(it * 100).roundToInt()}%" }, 0.6f)
        gate = Param("Quiet between bites", -80f, -30f, false, { if (it <= -79.5f) "off" else "%.0f dB".format(it) }, -55f)
        volume = Param("Volume", -12f, 30f, false, db, 6f)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(32))
        }
        fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false, top: Int = 0) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, px(top), 0, 0)
            }.also { col.addView(it) }

        text("CrunchBoost", 30f, bold = true)
        text("Your mic → crunch EQ → your ears, live.", 15f, Color.rgb(180, 180, 190), top = 4)

        routeText = text("", 14f, top = 16)

        startButton = Button(this).apply {
            text = "Start"
            textSize = 18f
            setOnClickListener { onStartStopClicked() }
        }
        col.addView(startButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(64)).apply {
            topMargin = px(12)
        })
        statusText = text("Stopped", 14f, Color.rgb(180, 180, 190), top = 6)

        bypassSwitch = Switch(this).apply {
            text = "A/B: hear the raw mic (bypass)"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, px(16), 0, px(4))
            setOnCheckedChangeListener { _, _ -> pushParams() }
        }
        col.addView(bypassSwitch)

        text("Presets", 18f, bold = true, top = 20)
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { p ->
            val b = Button(this).apply {
                text = p.name
                isAllCaps = false
                setOnClickListener { applyPreset(p) }
            }
            presetButtons += b
            presetRow.addView(b)
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(presetRow)
        })

        text("Fine-tune", 18f, bold = true, top = 20)
        for (p in listOf(lowCut, crunchFreq, crunchBoost, air, snap, gate, volume)) {
            p.labelView.setPadding(0, px(12), 0, 0)
            col.addView(p.labelView)
            col.addView(p.bar)
        }

        text(
            "Tips: wired earbuds with an inline mic work best — clip the mic near your jaw. " +
                "Start with the volume low and raise it slowly. Flip the bypass switch mid-bite to hear the difference.",
            13f, Color.rgb(150, 150, 160), top = 24,
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 22))
            addView(col)
            // Android 15 draws edge-to-edge; keep content clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
                insets
            }
        }
    }

    companion object {
        private const val REQ_MIC = 1
        private const val STEPS = 1000
        private const val TYPE_BLE_HEADSET = 26 // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
    }
}
