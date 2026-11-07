package com.example.crunchboost

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private data class Preset(val name: String, val crunchDb: Float)

    private val presets = listOf(
        Preset("Extra crunch", 9f),
        Preset("Crispy greens", 7f),
        Preset("Fizzy drink", 6f),
        Preset("Subtle", 4f),
    )

    private enum class Route { WIRED, BLUETOOTH, SPEAKER }

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var selectedPreset: Preset? = null

    private lateinit var routeText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var bypassSwitch: Switch
    private lateinit var paperSwitch: Switch
    private lateinit var crackleSwitch: Switch
    private val presetButtons = mutableListOf<Button>()

    private lateinit var crunchBoost: Param
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
            statusText.setTextColor(TEAL)
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
            setStatus("Microphone permission is needed to hear your food.", ERROR)
        }
    }

    // ---------------------------------------------------------------- engine control

    private fun onStartStopClicked() {
        if (running) {
            stopEngine()
            return
        }
        if (currentRoute() == Route.SPEAKER) {
            setStatus("Plug in headphones first — through the speaker it will howl with feedback.", ERROR)
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
            styleStartButton()
            setStatus("Starting…", TEXT_2)
            handler.postDelayed(latencyPoll, 700)
        } else {
            setStatus("Couldn't open the audio streams (see logcat, tag CrunchEngine).", ERROR)
        }
    }

    private fun stopEngine() {
        CrunchEngine.nativeStop()
        running = false
        handler.removeCallbacks(latencyPoll)
        styleStartButton()
        setStatus("Stopped", TEXT_2)
    }

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun pushParams() {
        if (!::volume.isInitialized || !::crackleSwitch.isInitialized) return
        CrunchEngine.nativeSetParams(
            LOW_CUT_HZ, CRUNCH_HZ, crunchBoost.value, AIR_DB,
            SNAP, GATE_DB, volume.value, bypassSwitch.isChecked,
            paperSwitch.isChecked, crackleSwitch.isChecked,
        )
    }

    private fun applyPreset(p: Preset) {
        crunchBoost.update(p.crunchDb)
        selectPreset(p)
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
                routeText.text = "✓  Wired headphones — lowest latency"
                routeText.setTextColor(TEAL)
            }
            Route.BLUETOOTH -> {
                routeText.text = "⚠  Bluetooth — expect 100–300 ms of delay (sounds like an echo). Wired is much better."
                routeText.setTextColor(WARN)
            }
            Route.SPEAKER -> {
                routeText.text = "✕  No headphones detected — plug some in"
                routeText.setTextColor(ERROR)
                if (running) {
                    stopEngine()
                    setStatus("Stopped: headphones were disconnected.", ERROR)
                }
            }
        }
    }

    // ---------------------------------------------------------------- styling helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(fill: Int, stroke: Int = Color.TRANSPARENT, strokeDp: Int = 0, radiusDp: Int = 28) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    private fun withRipple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(RIPPLE), content, null)

    private fun styleStartButton() {
        if (running) {
            startButton.text = "Stop"
            startButton.setTextColor(TEAL)
            startButton.background = withRipple(rounded(Color.BLACK, TEAL, 2))
        } else {
            startButton.text = "Start"
            startButton.setTextColor(Color.BLACK)
            startButton.background = withRipple(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BLUE, TEAL)).apply {
                    cornerRadius = dp(28).toFloat()
                },
            )
        }
    }

    private fun selectPreset(p: Preset?) {
        selectedPreset = p
        presetButtons.forEachIndexed { i, b ->
            val on = presets[i] == p
            b.setTextColor(if (on) TEAL else TEXT_1)
            b.background = withRipple(rounded(SURFACE, if (on) TEAL else OUTLINE, if (on) 2 else 1, 20))
        }
    }

    private fun styledSwitch(label: String) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(TEXT_1)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL, TEXT_2),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL_DIM, TRACK),
        )
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
        private val nameView = TextView(this@MainActivity).apply {
            text = label
            setTextColor(TEXT_1)
            textSize = 15f
        }
        private val valueView = TextView(this@MainActivity).apply {
            setTextColor(TEAL)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        }
        val bar = SeekBar(this@MainActivity).apply {
            max = STEPS
            progressTintList = ColorStateList.valueOf(TEAL)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            thumbTintList = ColorStateList.valueOf(TEAL)
        }
        var value: Float = initial
            private set

        init {
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value = fromT(progress / STEPS.toFloat())
                    refreshLabel()
                    if (this@Param !== volume) selectPreset(null)
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
            valueView.text = fmt(value)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val db: (Float) -> String = { "%+.0f dB".format(it) }

        crunchBoost = Param("Crunch boost", 0f, 15f, false, db, 9f)
        volume = Param("Volume", -12f, 30f, false, db, 6f)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(32))
        }
        fun text(s: String, size: Float, color: Int = TEXT_1, bold: Boolean = false, top: Int = 0, parent: LinearLayout = col) =
            TextView(this).apply {
                text = s
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(top), 0, 0)
            }.also { parent.addView(it) }
        fun card() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, OUTLINE, 1, 20)
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }.also {
            col.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        // Header: logo + wordmark
        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "CrunchBoost logo"
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130)).apply { topMargin = dp(8) })
        text("CrunchBoost", 30f, bold = true).apply { gravity = Gravity.CENTER_HORIZONTAL; letterSpacing = 0.02f }
        text("Your mic → crunch EQ → your ears, live.", 15f, TEXT_2, top = 2).gravity = Gravity.CENTER_HORIZONTAL

        routeText = text("", 14f, top = 20)

        startButton = Button(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            stateListAnimator = null
            setOnClickListener { onStartStopClicked() }
        }
        col.addView(startButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply {
            topMargin = dp(12)
        })
        styleStartButton()
        statusText = text("Stopped", 14f, TEXT_2, top = 8)

        val abCard = card()
        bypassSwitch = styledSwitch("A/B: hear the raw mic (bypass)").apply {
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, _ -> pushParams() }
        }
        abCard.addView(bypassSwitch)
        text(
            "Use sparingly — hearing the plain sound can weaken the illusion.",
            13f, TEXT_2, top = 2, parent = abCard,
        ).setPadding(0, dp(2), dp(56), 0)

        text("Presets", 18f, bold = true, top = 24)
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { p ->
            val b = Button(this).apply {
                text = p.name
                isAllCaps = false
                stateListAnimator = null
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { applyPreset(p) }
            }
            presetButtons += b
            presetRow.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                marginEnd = dp(8)
            })
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(10), 0, 0)
            addView(presetRow)
        })

        text("Effects", 18f, bold = true, top = 24)
        val fxCard = card()
        fun effectToggle(title: String, subtitle: String): Switch {
            val sw = styledSwitch(title).apply { setPadding(0, dp(10), 0, 0) }
            fxCard.addView(sw)
            text(subtitle, 13f, TEXT_2, parent = fxCard).setPadding(0, dp(2), dp(56), dp(4))
            return sw
        }
        paperSwitch = effectToggle(
            "Paper mode",
            "One broad boost above 2 kHz, like the original Pringles study. Uses the Crunch boost amount.",
        )
        crackleSwitch = effectToggle(
            "Crunch layer",
            "Adds a burst of synthetic crackle to every bite — even soft foods.",
        )
        paperSwitch.setOnCheckedChangeListener { _, _ -> pushParams() }
        crackleSwitch.setOnCheckedChangeListener { _, _ -> pushParams() }

        text("Fine-tune", 18f, bold = true, top = 24)
        val tuneCard = card()
        for (p in listOf(crunchBoost, volume)) {
            p.row.setPadding(0, dp(12), 0, dp(2))
            tuneCard.addView(p.row)
            tuneCard.addView(p.bar)
        }

        text(
            "Tips: wired earbuds with an inline mic work best — clip the mic near your jaw. " +
                "Start with the volume low and raise it slowly. Flip the bypass switch mid-bite to hear the difference.",
            13f, TEXT_3, top = 24,
        )

        return ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
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

        // Locked sound settings (tuned by ear on a real phone).
        private const val LOW_CUT_HZ = 99f   // removes jaw boom
        private const val CRUNCH_HZ = 4000f  // crunch peak (when Paper mode is off)
        private const val AIR_DB = 4f        // 9 kHz sparkle (when Paper mode is off)
        private const val SNAP = 0.82f       // attack emphasis on each bite
        private const val GATE_DB = -34f     // quiet between bites

        // AMOLED palette: true black base, brand teal/blue from the logo.
        private const val TEAL = 0xFF20D2C1.toInt()
        private const val TEAL_DIM = 0x8020D2C1.toInt()
        private const val BLUE = 0xFF0971D3.toInt()
        private const val SURFACE = 0xFF0A0A0C.toInt()
        private const val OUTLINE = 0xFF222228.toInt()
        private const val TRACK = 0xFF2A2A31.toInt()
        private const val RIPPLE = 0x4020D2C1
        private const val TEXT_1 = 0xFFF2F2F5.toInt()
        private const val TEXT_2 = 0xFF9A9AA5.toInt()
        private const val TEXT_3 = 0xFF6B6B75.toInt()
        private const val WARN = 0xFFFAC85A.toInt()
        private const val ERROR = 0xFFFA6E64.toInt()
    }
}
