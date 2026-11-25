
package com.denzel.firelight

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.random.Random
import java.util.UUID

class MainActivity : Activity() {

    private val serviceUUID =
        UUID.fromString("7b7a0001-6d2a-4f9b-9c4e-3d4b2a110001")
    private val writeUUID =
        UUID.fromString("7b7a0002-6d2a-4f9b-9c4e-3d4b2a110001")
    private val statusUUID =
        UUID.fromString("7b7a0003-6d2a-4f9b-9c4e-3d4b2a110001")

    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    // One physical GATT connection to the Arduino; the strip sessions below
    // remain logical targets routed through its RX characteristic.
    private var bridgeGatt: BluetoothGatt? = null
    private var bridgeRxCharacteristic: BluetoothGattCharacteristic? = null
    private var bridgeStatusCharacteristic: BluetoothGattCharacteristic? = null
    private val bridgeWriteQueue = java.util.ArrayDeque<ByteArray>()
    private var bridgeWriteInFlight = false
    private var bridgeWriteReady = false
    // Enabled only when the bridge advertises WRITE_WITHOUT_RESPONSE.
    private var bridgeWriteWithoutResponse = false
    private var bridgeWriteRunner: Runnable? = null
    private var bridgeWriteScheduled = false
    private var bridgeNextWriteAt = 0L
    private var bridgeClosing = false
    private var bridgeCloseTimeout: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private enum class BleCatalogStyle { JUMP, FADE, STROBE, ALTERNATE, TAIL, CURTAIN, RUN, FLUSH, ENERGY }
    private data class BleCatalogSpec(val hardwareMode: Int, val palette: IntArray, val style: BleCatalogStyle)

    private data class DeviceSession(
        val address: String, val isMelk: Boolean, val targetId: Int,
        var gatt: BluetoothGatt? = null, var characteristic: BluetoothGattCharacteristic? = null,
        var notifyCharacteristic: BluetoothGattCharacteristic? = null,
        var connected: Boolean = false, var stripOn: Boolean = false, var stripStateKnown: Boolean = false,
        var mode: EffectMode = EffectMode.NONE,
        var micEnabled: Boolean = false, var micEffect: Int = 1, var micSensitivity: Int = 50,
        var red: Int = 255, var green: Int = 92, var blue: Int = 12, var brightness: Int = 100,
        var speed: Int = 15, var spread: Int = 0, var rainbowSpeed: Int = 15,
        var fillSpeed: Int = 50, var fillSpeedRunner: Runnable? = null,
        var catalogRunner: Runnable? = null, var catalogEffectId: Int = -1, var catalogHardwareMode: Int = -1,
        var catalogPalette: IntArray = intArrayOf(), var catalogStyle: BleCatalogStyle = BleCatalogStyle.JUMP,
        var catalogSpeed: Int = 50,
        var bleWriteQueue: java.util.ArrayDeque<ByteArray> = java.util.ArrayDeque(),
        var bleWriteRunner: Runnable? = null, var bleWriteScheduled: Boolean = false,
        var bleWriteLastAt: Long = 0L,
        var pending: ByteArray? = null, var flushQueued: Boolean = false,
        var lastWrite: Long = 0L, var flushRunner: Runnable? = null
    )
    private val sessions = linkedMapOf<String, DeviceSession>()
    private val managedDeviceAddresses = linkedSetOf<String>()
    private data class DiscoveredBle(val device: BluetoothDevice, val name: String, var rssi: Int)
    private val discoveredDevices = linkedMapOf<String, DiscoveredBle>()
    private var discoverAllScanning = false
    private var refreshDiscoveryDialog: (() -> Unit)? = null
    private val selectedDeviceAddresses = linkedSetOf<String>()

    private var connected = false
    private var fireEnabled = false
    private enum class EffectMode { NONE, FIRE, RAINBOW, FILL, MIC, CATALOG }
    private enum class DeviceLayout { SIMULTANEOUS, SEQUENTIAL }
    private var activeMode = EffectMode.NONE
    private var deviceLayout = DeviceLayout.SIMULTANEOUS
    private var rainbowSpeed = 15
    private var fillSpeed = 50
    private var rainbowPhase = 0f
    private var rainbowLastTickAt = 0L
    private lateinit var rainbowButton: Button
    private var scanning = false
    private lateinit var deviceField: TextView
    private var devicePopup: PopupWindow? = null
    private var refreshDevicePopup: (() -> Unit)? = null
    private var screenRoot: FrameLayout? = null
    private var refreshSelectionDisplay: (() -> Unit)? = null
    private var drawerOverlay: FrameLayout? = null
    private var drawerPanel: LinearLayout? = null
    private val deviceNames = listOf("ELK-BLEDOB", "MELK-OA21 2C")
    private var selectedDeviceAddress = ""

    private var red = 255
    private var green = 92
    private var blue = 12
    private var brightness = 100
    private var speed = 15
    private var fireColorSpread = 0
    private var stripOn = true
    private var stripStateKnown = false
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var bulbButton: BulbToggleView

    private var fireFrom = intArrayOf(255, 95, 8)
    private var fireTo = intArrayOf(235, 55, 3)
    private var fireTransitionStart = 0L
    private var fireTransitionDuration = 600L
    private var fireLevel = 0.76f
    private var fireLevelTarget = 0.76f
    private var fireHueShift = 0f
    private var fireHueTarget = 0f
    private var fireSaturation = 0.94f
    private var fireSaturationTarget = 0.94f
    private var fireNextChangeAt = 0L
    private var fireLastTickAt = 0L
    private var fireFlare = 0f
    private var fireSmoothingMs = 78f

    private lateinit var status: TextView
    private lateinit var fireButton: Button
    private lateinit var preview: TextView
    private var pendingPacket: ByteArray? = null
    private var writeScheduled = false
    private var lastWriteAt = 0L
    private val targetAddress = "BE:27:8A:00:73:DE"
    private val melkAddress = "BE:28:96:00:42:2C"
    private val thirdAddress = "BE:27:8A:00:01:D0"
    private val fourthAddress = "BE:27:8A:00:45:28"
    private var isMelkDevice = false
    private val powerNotifyUUID = UUID.fromString("7b7a0003-6d2a-4f9b-9c4e-3d4b2a110001")
    private val cccdUUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            val found = discoveredDevices.size
            status.text = if (discoverAllScanning) "Знайдено BLE-пристроїв: $found"
                else if (found > 0) "Знайдено пристроїв: $found" else "Додані пристрої не знайдено"
            refreshDevicePicker()
            refreshDiscoveryDialog?.invoke()
        }
    }
    private val powerStateTimeout = Runnable {
        if (connected && !stripStateKnown) {
            status.text = "Підключено · контролер не передав стан стрічки"
        }
    }
    // A named callback lets power-off cancel every queued RGB flush reliably.

    // FIRE and RAINBOW are generated by the ESP32 now. Android only keeps
    // the shared color-flush scheduler used by the remaining software effects.
    private fun ensureRunners(d: DeviceSession) {
        if (d.flushRunner == null) d.flushRunner = Runnable { flushPendingColor(d) }
    }

    // The selected wheel color is the fire's base hue. Flicker changes brightness
    // and saturation, not the color family (so blue stays blue, green stays green, etc.).
    private fun nextFireColor(): IntArray {
        val hsv = FloatArray(3)
        Color.RGBToHSV(red, green, blue, hsv)
        val hue = (hsv[0] + Random.nextFloat() * 12f - 6f + 360f) % 360f
        val saturation = (hsv[1] * Random.nextFloat().let { 0.88f + it * 0.12f })
            .coerceIn(0.15f, 1f)
        val value = Random.nextFloat().let { 0.38f + it * 0.62f }
        val rgb = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        return intArrayOf(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    // 1 = slow, 20 = fast. Both the flicker interval and transition duration
    // respond to the slider; the BLE writer still coalesces packets safely.
    private fun nextFireTick(): Long = (132L - (speed.coerceIn(11, 20) - 11) * 8L).coerceIn(52L, 132L)

    private fun nextTransitionDuration(): Long {
        val base = 2600L - speed * 115L
        return Random.nextLong((base * 0.70f).toLong().coerceAtLeast(180L),
            (base * 1.30f).toLong().coerceAtLeast(260L))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
        managedDeviceAddresses.add(targetAddress.uppercase())
        managedDeviceAddresses.add(melkAddress.uppercase())
        managedDeviceAddresses.add(thirdAddress.uppercase())
        managedDeviceAddresses.add(fourthAddress.uppercase())
        getSharedPreferences("firelight_devices", MODE_PRIVATE).getStringSet("addresses", null)?.let { saved ->
            managedDeviceAddresses.clear()
            managedDeviceAddresses.addAll(saved.map { it.uppercase() })
            if (managedDeviceAddresses.isEmpty()) managedDeviceAddresses.add(targetAddress.uppercase())
            managedDeviceAddresses.add(thirdAddress.uppercase())
            managedDeviceAddresses.add(fourthAddress.uppercase())
        }
        selectedDeviceAddresses.clear()
        selectedDeviceAddress = ""
        requestBluetoothPermissions()
        stripStateKnown = true
        stripOn = true
        if (hasBluetoothPermissions()) {
            startKeepAliveService()
            startScan()
        }
    }

    private fun createInterface() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(color: Int, radius: Int = 22, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (stroke != null) setStroke(dp(1), stroke)
            }

        val background = Color.rgb(10, 12, 18)
        val panel = Color.rgb(22, 26, 36)
        val muted = Color.rgb(151, 160, 178)
        val accent = Color.rgb(255, 111, 66)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(42), dp(12), dp(8))
            setBackgroundColor(background)
        }

        fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
            }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackground(rounded(panel, 24, Color.rgb(39, 45, 59)))
        }

        fun addGap(parent: LinearLayout, height: Int) {
            parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
        }

        fun actionButton(title: String, primary: Boolean = false, action: () -> Unit): Button =
            Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 15f
                setTextColor(if (primary) Color.WHITE else Color.rgb(222, 228, 239))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setBackground(rounded(if (primary) accent else Color.rgb(37, 43, 57), 17,
                    if (primary) null else Color.rgb(55, 63, 80)))
                stateListAnimator = null
                elevation = 0f
                setPadding(dp(16), 0, dp(16), 0)
                minHeight = dp(54)
                setOnClickListener { action() }
            }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("FIRELIGHT", 25f, Color.WHITE, true))
        addGap(brand, 4)
        brand.addView(text("SMART LIGHT CONTROL", 10f, muted, true))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 23f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(255, 142, 105))
            setBackground(rounded(Color.rgb(43, 35, 36), 14, Color.rgb(91, 59, 54)))
            isClickable = true
            isFocusable = true
            setOnClickListener { showAppMenu(this) }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(42)))
        root.addView(header)
        addGap(root, 8)

        val connectionCard = card()
        connectionCard.addView(text("ПІДКЛЮЧЕННЯ", 11f, muted, true))
        addGap(connectionCard, 8)
        deviceField = TextView(this).apply {
            text = "Пристрої · ${selectedDeviceAddresses.size} обрано  ▾"
            textSize = 14f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0); minHeight = dp(42)
            setBackground(rounded(Color.rgb(29, 34, 46), 13, Color.rgb(61, 70, 88)))
            isClickable = true; isFocusable = true
            setOnClickListener { showDevicePicker() }
        }
        connectionCard.addView(deviceField, LinearLayout.LayoutParams(-1, dp(42)))
        addGap(connectionCard, 8)
        status = text("Шукаю ELK-BLEDOB…", 14f, Color.WHITE, true)
        connectionCard.addView(status)
        addGap(connectionCard, 10)
        val connectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val connectButton = actionButton("Підключити", true) { startScan() }
        val disconnectButton = actionButton("Від'єднати") { disconnect() }
        connectionActions.addView(connectButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            marginEnd = dp(8)
        })
        connectionActions.addView(disconnectButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            marginStart = dp(8)
        })
        connectionCard.addView(connectionActions)
        root.addView(connectionCard)
        addGap(root, 8)

        val pageHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val firePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        firePage.setPadding(0, dp(2), 0, dp(8))
        pageHost.addView(firePage, FrameLayout.LayoutParams(-1, -1))
        val noActiveDeviceView = text("НЕМАЄ АКТИВНОГО ПРИСТРОЮ", 24f, muted, true).apply {
            gravity = android.view.Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            letterSpacing = 0.04f
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val colorCard = card().apply {
            setPadding(dp(12), dp(0), dp(12), dp(0))
            clipChildren = false
            clipToPadding = false
        }
        val colorHeader = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val colorTitle = text("КОЛІР СВІТІННЯ", 11f, muted, true).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        }
        colorHeader.addView(colorTitle, FrameLayout.LayoutParams(-1, dp(40), android.view.Gravity.TOP or android.view.Gravity.START))
        bulbButton = BulbToggleView(this) { toggleStrip() }.apply {
            setLit(stripOn)
        }
        // The power control is moved between the header and brightness row per tab.
        colorCard.addView(colorHeader, LinearLayout.LayoutParams(-1, dp(52)))
        val colorHeaderBottomGap = View(this)
        colorCard.addView(colorHeaderBottomGap, LinearLayout.LayoutParams(1, dp(0)))
        val pickerLabel = text("Обери свій відтінок", 17f, Color.WHITE, true)
        colorCard.addView(pickerLabel)
        addGap(colorCard, 8)

        val wheelFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(176))
        }
        val colorWheel = ColorWheelView(this) { selectedColor ->
            red = Color.red(selectedColor)
            green = Color.green(selectedColor)
            blue = Color.blue(selectedColor)
            updatePreview()
            selectedSessions().forEach { d ->
                d.red=red; d.green=green; d.blue=blue
                if (d.mode == EffectMode.CATALOG || d.mode == EffectMode.MIC) {
                    stopActiveEffect(d)
                    sendRGBTo(d, red, green, blue)
                } else if (d.mode == EffectMode.FIRE) {
                    sendEffectCommand(d, EffectMode.FIRE, true)
                } else if (d.mode == EffectMode.NONE) {
                    sendRGBTo(d, red, green, blue)
                }
            }
        }
        val wheelSize = dp(168)
        wheelFrame.addView(colorWheel, FrameLayout.LayoutParams(wheelSize, wheelSize, android.view.Gravity.CENTER))
        colorCard.addView(wheelFrame)

        preview = text("●  RGB  $red  /  $green  /  $blue", 13f, Color.rgb(red, green, blue), true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        colorCard.addView(preview)
        val tuningCard = card().apply {
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        tuningCard.addView(text("СВІТЛОВІ ЕФЕКТИ", 11f, muted, true))
        addGap(tuningCard, 7)

        var brightnessSliderRow: LinearLayout? = null
        var brightnessSlider: ModernSliderView? = null
        var brightnessValueView: TextView? = null
        var brightnessTitleView: TextView? = null
        var brightnessValueSpacer: View? = null
        var selectedTabIndex = 0

        fun addModernSlider(parent: LinearLayout, title: String, initial: Int, maxValue: Int, suffix: String, bottomGap: Int = 8, trailingView: View? = null, changed: (Int) -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val titleView = text(title, 13f, Color.rgb(225, 230, 240), true)
            val valueView = text("$initial$suffix", 13f, Color.WHITE, true)
            line.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(valueView)
            if (trailingView != null && title == "Яскравість") {
                brightnessValueSpacer = View(this)
                line.addView(brightnessValueSpacer, LinearLayout.LayoutParams(dp(50), 1))
            }
            if (title == "Яскравість") {
                brightnessTitleView = titleView
                brightnessValueView = valueView
            }
            parent.addView(line)
            addGap(parent, if (title == "Яскравість") 0 else 7)
            val slider = ModernSliderView(this, initial, maxValue) { value ->
                valueView.text = "$value$suffix"
                changed(value)
            }
            if (title == "Яскравість") brightnessSlider = slider
            if (trailingView == null) {
                parent.addView(slider, LinearLayout.LayoutParams(-1, dp(26)))
            } else {
                val sliderRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                sliderRow.addView(slider, LinearLayout.LayoutParams(0, dp(26), 1f))
                sliderRow.addView(trailingView, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(10)
                })
                if (title == "Яскравість") brightnessSliderRow = sliderRow
                parent.addView(sliderRow, LinearLayout.LayoutParams(-1, dp(if (title == "Яскравість") 40 else 52)))
            }
            addGap(parent, bottomGap)
        }

        addModernSlider(colorCard, "Яскравість", brightness, 100, "%", 0, bulbButton) {
            brightness = it
            if (selectedTabIndex == 4) return@addModernSlider
            selectedSessions().forEach { d ->
                d.brightness = brightness
                if (d.isMelk && d.mode != EffectMode.MIC) {
                    sendMelkBrightness(d, brightness)
                } else if (!d.isMelk && d.mode == EffectMode.CATALOG && d.catalogHardwareMode >= 0) {
                    sendBleBrightness(d, brightness)
                } else if (!d.isMelk && d.mode == EffectMode.NONE) {
                    sendRGBTo(d, d.red, d.green, d.blue)
                }
                if (d.mode == EffectMode.FIRE) sendEffectCommand(d, EffectMode.FIRE, true)
                if (d.mode == EffectMode.RAINBOW) sendEffectCommand(d, EffectMode.RAINBOW, true)
            }
        }
        root.addView(colorCard)
        addGap(root, 12)
        root.addView(pageHost)
        addModernSlider(tuningCard, "Швидкість вогню", speed - 10, 10, "/10") {
            speed = (it + 10).coerceIn(11, 20)
            selectedSessions().forEach { d ->
                d.speed = speed
                if (d.mode == EffectMode.FIRE) sendEffectCommand(d, EffectMode.FIRE, true)
            }
        }
        addModernSlider(tuningCard, "Розкид кольорів вогню", fireColorSpread, 100, "%", 2) {
            fireColorSpread = it.coerceIn(0, 100)
            selectedSessions().forEach { d ->
                d.spread = fireColorSpread
                if (d.mode == EffectMode.FIRE) sendEffectCommand(d, EffectMode.FIRE, true)
            }
        }
        addGap(tuningCard, 8)
        fireButton = actionButton("Увімкнути вогонь", true) { toggleFire() }
        tuningCard.addView(fireButton, LinearLayout.LayoutParams(-1, dp(40)))
        addGap(tuningCard, 4)
        firePage.addView(tuningCard)

        val rainbowPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val rainbowTuningCard = card().apply {
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        rainbowTuningCard.addView(text("СВІТЛОВІ ЕФЕКТИ", 11f, muted, true))
        addGap(rainbowTuningCard, 7)
        addModernSlider(rainbowTuningCard, "Швидкість", rainbowSpeed, 20, "/20", 8) {
            rainbowSpeed = it.coerceIn(1, 20)
            selectedSessions().forEach { d ->
                d.rainbowSpeed = rainbowSpeed
                if (d.mode == EffectMode.RAINBOW) sendEffectCommand(d, EffectMode.RAINBOW, true)
            }
        }
        rainbowButton = actionButton("Увімкнути веселку", true) { toggleRainbow() }
        rainbowTuningCard.addView(rainbowButton, LinearLayout.LayoutParams(-1, dp(40)))
        addGap(rainbowTuningCard, 4)
        rainbowPage.addView(rainbowTuningCard)

        // MELK catalogue: names, IDs and groups are taken directly from Magic Lantern 6.11.10.
        data class MelkEffect(val id: Int, val name: String, val group: String)
        val catalogEffects = listOf(
            MelkEffect(0, "Auto Play", "Basic"),
            MelkEffect(1, "Magic Forward", "Basic"),
            MelkEffect(2, "Magic Back", "Basic"),
            MelkEffect(3, "7-Color Trans", "Trans"),
            MelkEffect(4, "7-Color Trans Back", "Trans"),
            MelkEffect(5, "R-G-B Trans", "Trans"),
            MelkEffect(6, "R-G-B Trans Back", "Trans"),
            MelkEffect(7, "Y-C-P Trans", "Trans"),
            MelkEffect(8, "Y-C-P Trans Back", "Trans"),
            MelkEffect(9, "6-Color to Red", "Trans"),
            MelkEffect(10, "6-Color to Red Back", "Trans"),
            MelkEffect(11, "6-Color to Green", "Trans"),
            MelkEffect(12, "6-Color to Green Back", "Trans"),
            MelkEffect(13, "6-Color to Blue", "Trans"),
            MelkEffect(14, "6-Color to Blue Back", "Trans"),
            MelkEffect(15, "6-Color to Cyan", "Trans"),
            MelkEffect(16, "6-Color to Cyan Back", "Trans"),
            MelkEffect(17, "6-Color to Yellow", "Trans"),
            MelkEffect(18, "6-Color to Yellow Back", "Trans"),
            MelkEffect(19, "6-Color to Purple", "Trans"),
            MelkEffect(20, "6-Color to Purple Back", "Trans"),
            MelkEffect(21, "6-Color to White", "Trans"),
            MelkEffect(22, "6-Color to White Back", "Trans"),
            MelkEffect(23, "7-Color Tail", "Tail"),
            MelkEffect(24, "7-Color Tail Back", "Tail"),
            MelkEffect(25, "Red Tail", "Tail"),
            MelkEffect(26, "Red Tail Back", "Tail"),
            MelkEffect(27, "Green Tail", "Tail"),
            MelkEffect(28, "Green Tail Back", "Tail"),
            MelkEffect(29, "Blue Tail", "Tail"),
            MelkEffect(30, "Blue Tail Back", "Tail"),
            MelkEffect(31, "Yellow Tail", "Tail"),
            MelkEffect(32, "Yellow Tail Back", "Tail"),
            MelkEffect(33, "Cyan Tail", "Tail"),
            MelkEffect(34, "Cyan Tail Back", "Tail"),
            MelkEffect(35, "Purple Tail", "Tail"),
            MelkEffect(36, "Purple Tail Back", "Tail"),
            MelkEffect(37, "White Tail", "Tail"),
            MelkEffect(38, "White Tail Back", "Tail"),
            MelkEffect(39, "7-Color Water", "Water"),
            MelkEffect(40, "7-Color Water Back", "Water"),
            MelkEffect(41, "R-G-B Water", "Water"),
            MelkEffect(42, "R-G-B Water Back", "Water"),
            MelkEffect(43, "Y-C-P Water", "Water"),
            MelkEffect(44, "Y-C-P Water Back", "Water"),
            MelkEffect(45, "R-G Water", "Water"),
            MelkEffect(46, "R-G Water Back", "Water"),
            MelkEffect(47, "G-B Water", "Water"),
            MelkEffect(48, "G-B Water Back", "Water"),
            MelkEffect(49, "Y-B Water", "Water"),
            MelkEffect(50, "Y-B Water Back", "Water"),
            MelkEffect(51, "Y-C Water", "Water"),
            MelkEffect(52, "Y-C Water Back", "Water"),
            MelkEffect(53, "C-P Water", "Water"),
            MelkEffect(54, "C-P Water Back", "Water"),
            MelkEffect(55, "White Water", "Water"),
            MelkEffect(56, "White Water Back", "Water"),
            MelkEffect(57, "7-Color Close", "Curtain"),
            MelkEffect(58, "7-Color Open", "Curtain"),
            MelkEffect(59, "R-G-B Close", "Curtain"),
            MelkEffect(60, "R-G-B Open", "Curtain"),
            MelkEffect(61, "Y-C-P Close", "Curtain"),
            MelkEffect(62, "Y-C-P Open", "Curtain"),
            MelkEffect(63, "Red Close", "Curtain"),
            MelkEffect(64, "Red Open", "Curtain"),
            MelkEffect(65, "Green Close", "Curtain"),
            MelkEffect(66, "Green Open", "Curtain"),
            MelkEffect(67, "Blue Close", "Curtain"),
            MelkEffect(68, "Blue Open", "Curtain"),
            MelkEffect(69, "Yellow Close", "Curtain"),
            MelkEffect(70, "Yellow Open", "Curtain"),
            MelkEffect(71, "Cyan Close", "Curtain"),
            MelkEffect(72, "Cyan Open", "Curtain"),
            MelkEffect(73, "Purple Close", "Curtain"),
            MelkEffect(74, "Purple Open", "Curtain"),
            MelkEffect(75, "White Close", "Curtain"),
            MelkEffect(76, "White Open", "Curtain"),
            MelkEffect(77, "7-Color Race", "Basic"),
            MelkEffect(78, "7-Color Race Back", "Basic"),
            MelkEffect(79, "R-G-B Race", "Basic"),
            MelkEffect(80, "R-G-B Race Back", "Basic"),
            MelkEffect(81, "Y-C-P Race", "Basic"),
            MelkEffect(82, "Y-C-P Race Back", "Basic"),
            MelkEffect(83, "7-Color Wave", "Basic"),
            MelkEffect(84, "7-Color Wave Back", "Basic"),
            MelkEffect(85, "R-G-B Wave", "Basic"),
            MelkEffect(86, "R-G-B Wave Back", "Basic"),
            MelkEffect(87, "Y-C-P Wave", "Basic"),
            MelkEffect(88, "Y-C-P Wave Back", "Basic"),
            MelkEffect(89, "Red Running", "Run"),
            MelkEffect(90, "Red Run Back", "RunBack"),
            MelkEffect(91, "Green Running", "Run"),
            MelkEffect(92, "Green Run Back", "RunBack"),
            MelkEffect(93, "Blue Running", "Run"),
            MelkEffect(94, "Blue Run Back", "RunBack"),
            MelkEffect(95, "Yellow Running", "Run"),
            MelkEffect(96, "Yellow Run Back", "RunBack"),
            MelkEffect(97, "Cyan Running", "Run"),
            MelkEffect(98, "Cyan Run Back", "RunBack"),
            MelkEffect(99, "Purple Running", "Run"),
            MelkEffect(100, "Purple Run Back", "RunBack"),
            MelkEffect(101, "White Running", "Run"),
            MelkEffect(102, "White Run Back", "RunBack"),
            MelkEffect(103, "7-Color Running", "Run"),
            MelkEffect(104, "7-Color Run Back", "RunBack"),
            MelkEffect(105, "R-G-B Running", "Run"),
            MelkEffect(106, "R-G-B Run Back", "RunBack"),
            MelkEffect(107, "Y-C-P Running", "Run"),
            MelkEffect(108, "Y-C-P Run Back", "RunBack"),
            MelkEffect(109, "B-P-C-Y Running", "Run"),
            MelkEffect(110, "B-P-C-Y Run Back", "RunBack"),
            MelkEffect(111, "B-G-C-Y Running", "Run"),
            MelkEffect(112, "B-G-C-Y Run Back", "RunBack"),
            MelkEffect(113, "Red-Dot in White Running", "Run"),
            MelkEffect(114, "Red-Dot in White Run Back", "RunBack"),
            MelkEffect(115, "Green-Dot in Red Running", "Run"),
            MelkEffect(116, "Green-Dot in Red Run Back", "RunBack"),
            MelkEffect(117, "Blue-Dot in Green Running", "Run"),
            MelkEffect(118, "Blue-Dot in Green Run Back", "RunBack"),
            MelkEffect(119, "Yellow-Dot in Blue Running", "Run"),
            MelkEffect(120, "Yellow-Dot in Blue Run Back", "RunBack"),
            MelkEffect(121, "Cyan-Dot in Yellow Running", "Run"),
            MelkEffect(122, "Cyan-Dot in Yellow Run Back", "RunBack"),
            MelkEffect(123, "Purple-Dot in Cyan Running", "Run"),
            MelkEffect(124, "Purple-Dot in Cyan Run Back", "RunBack"),
            MelkEffect(125, "White-Dot in Purple Running", "Run"),
            MelkEffect(126, "White-Dot in Purple Run Back", "RunBack"),
            MelkEffect(127, "White-Dot in Red Running", "Run"),
            MelkEffect(128, "White-Dot in Red Run Back", "RunBack"),
            MelkEffect(129, "7-Color in Red Running", "Run"),
            MelkEffect(130, "7-Color in Red Run Back", "RunBack"),
            MelkEffect(131, "7-Color in Green Running", "Run"),
            MelkEffect(132, "7-Color in Green Run Back", "RunBack"),
            MelkEffect(133, "7-Color in Blue Running", "Run"),
            MelkEffect(134, "7-Color in Blue Run Back", "RunBack"),
            MelkEffect(135, "7-Color in Yellow Running", "Run"),
            MelkEffect(136, "7-Color in Yellow Run Back", "RunBack"),
            MelkEffect(137, "7-Color in Cyan Running", "Run"),
            MelkEffect(138, "7-Color in Cyan Run Back", "RunBack"),
            MelkEffect(139, "7-Color in Purple Running", "Run"),
            MelkEffect(140, "7-Color in Purple Run Back", "RunBack"),
            MelkEffect(141, "7-Color in White Running", "Run"),
            MelkEffect(142, "7-Color in White Run Back", "RunBack"),
            MelkEffect(143, "W-R-W Flow", "Flow"),
            MelkEffect(144, "W-R-W Flow Back", "Flow"),
            MelkEffect(145, "W-G-W Flow", "Flow"),
            MelkEffect(146, "W-G-W Flow Back", "Flow"),
            MelkEffect(147, "W-B-W Flow", "Flow"),
            MelkEffect(148, "W-B-W Flow Back", "Flow"),
            MelkEffect(149, "W-Y-W Flow", "Flow"),
            MelkEffect(150, "W-Y-W Flow Back", "Flow"),
            MelkEffect(151, "W-C-W Flow", "Flow"),
            MelkEffect(152, "W-C-W Flow Back", "Flow"),
            MelkEffect(153, "W-P-W Flow", "Flow"),
            MelkEffect(154, "W-P-W Flow Back", "Flow"),
            MelkEffect(155, "R-W-R Flow", "Flow"),
            MelkEffect(156, "R-W-R Flow Back", "Flow"),
            MelkEffect(157, "G-W-G Flow", "Flow"),
            MelkEffect(158, "G-W-G Flow Back", "Flow"),
            MelkEffect(159, "B-W-B Flow", "Flow"),
            MelkEffect(160, "B-W-B Flow Back", "Flow"),
            MelkEffect(161, "Y-W-Y Flow", "Flow"),
            MelkEffect(162, "Y-W-Y Flow Back", "Flow"),
            MelkEffect(163, "C-W-C Flow", "Flow"),
            MelkEffect(164, "C-W-C Flow Back", "Flow"),
            MelkEffect(165, "P-W-P Flow", "Flow"),
            MelkEffect(166, "P-W-P Flow Back", "Flow"),
            MelkEffect(167, "Green-Dot in Blue Running", "Run"),
            MelkEffect(168, "Green-Dot in Blue Run Back", "RunBack"),
            MelkEffect(169, "Green-Dot in Red Running", "Run"),
            MelkEffect(170, "Green-Dot in Red Run Back", "RunBack"),
            MelkEffect(171, "Red-Dot in Blue Running", "Run"),
            MelkEffect(172, "Red-Dot in Blue Run Back", "RunBack"),
            MelkEffect(173, "Cyan-Dot in Yellow Running", "Run"),
            MelkEffect(174, "Cyan-Dot in Yellow Run Back", "RunBack"),
            MelkEffect(175, "Yellow-Dot in Purple Running", "Run"),
            MelkEffect(176, "Yellow-Dot in Purple Run Back", "RunBack"),
            MelkEffect(177, "White-Dot in Yelllow Running", "Run"),
            MelkEffect(178, "White-Dot in Yelllow Run Back", "RunBack"),
            MelkEffect(179, "Yellow-Dot in White Running", "Run"),
            MelkEffect(180, "Yellow-Dot in White Run Back", "RunBack"),
            MelkEffect(181, "7-Color Flush", "Basic"),
            MelkEffect(182, "7-Color Flush Back", "Basic"),
            MelkEffect(183, "R-G-B Flush", "Basic"),
            MelkEffect(184, "R-G-B Flush Back", "Basic"),
            MelkEffect(185, "Y-C-P Flush", "Basic"),
            MelkEffect(186, "Y-C-P Flush Back", "Basic"),
            MelkEffect(187, "7-Color Flush Close", "Basic"),
            MelkEffect(188, "7-Color Flush Open", "Basic"),
            MelkEffect(189, "R-G-B Flush Close", "Basic"),
            MelkEffect(190, "R-G-B Flush Open", "Basic"),
            MelkEffect(191, "Y-C-P Flush Close", "Basic"),
            MelkEffect(192, "Y-C-P Flush Open", "Basic"),
            MelkEffect(193, "7-Color Jump", "Basic"),
            MelkEffect(194, "R-G-B Jump", "Basic"),
            MelkEffect(195, "Y-C-P Jump", "Basic"),
            MelkEffect(196, "7-Color Strobe", "Basic"),
            MelkEffect(197, "R-G-B Strobe", "Basic"),
            MelkEffect(198, "Y-C-P Strobe", "Basic"),
            MelkEffect(199, "7-Color Gradual", "Basic"),
            MelkEffect(200, "R-Y Gradual", "Basic"),
            MelkEffect(201, "R-P Gradual", "Basic"),
            MelkEffect(202, "G-C Gradual", "Basic"),
            MelkEffect(203, "G-Y Gradual", "Basic"),
            MelkEffect(204, "B-P Gradual", "Basic"),
            MelkEffect(205, "Red Marquee", "Basic"),
            MelkEffect(206, "Green Marquee", "Basic"),
            MelkEffect(207, "Blue Marquee", "Basic"),
            MelkEffect(208, "Yellow Marquee", "Basic"),
            MelkEffect(209, "Cyan Marquee", "Basic"),
            MelkEffect(210, "Purple Marquee", "Basic"),
            MelkEffect(211, "White Marquee", "Basic"),
            MelkEffect(212, "7-Color Energy", "Basic")
        )
        val effectCategories = listOf("Basic", "Curtain", "Trans", "Water", "Flow", "Tail", "Run", "RunBack")
        var selectedEffectCategory = "Basic"
        var effectSpeed = 50
        val effectsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val effectsCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryRow)
        effectsCard.addView(categoryScroll, LinearLayout.LayoutParams(-1, dp(42)))
        val selectedEffectTitle = text("Auto Play  ·  #0", 21f, Color.WHITE, true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
        }
        effectsCard.addView(selectedEffectTitle, LinearLayout.LayoutParams(-1, -2))
        val effectsScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
        }
        val effectsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        effectsScroll.addView(effectsList)
        effectsCard.addView(effectsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        effectsPage.addView(effectsCard, LinearLayout.LayoutParams(-1, 0, 1f))

        fun applyCatalogEffect(effect: MelkEffect) {
            val selected = selectedSessions()
            val targets = selected.filter { it.connected && it.characteristic != null }
            if (targets.isEmpty()) {
                Toast.makeText(this@MainActivity, "Спочатку підключи пристрій", Toast.LENGTH_SHORT).show()
                return
            }
            targets.forEach { d ->
                if (d.isMelk) {
                    stopActiveEffect(d)
                    d.mode = EffectMode.CATALOG
                    d.catalogEffectId = effect.id
                    if (!d.stripOn) {
                        d.stripOn = true
                        d.stripStateKnown = true
                        sendPowerCommand(d, true)
                    }
                    handler.postDelayed({
                        if (d.connected && d.stripOn && d.mode == EffectMode.CATALOG && d.catalogEffectId == effect.id) {
                            sendMelkMode(d, effect.id)
                            handler.postDelayed({
                                if (d.connected && d.stripOn && d.mode == EffectMode.CATALOG && d.catalogEffectId == effect.id)
                                    sendMelkModeSpeed(d, effectSpeed)
                            }, 100L)
                        }
                    }, 120L)
                } else {
                    startBleCatalogEffect(d, effect.id, effect.name, effectSpeed)
                }
            }
            selectedEffectTitle.text = "${effect.name}  ·  #${effect.id}"
            refreshEffectButtons()
            activateSelectedSession()
        }

        fun renderEffectCategory(category: String) {
            selectedEffectCategory = category
            categoryRow.removeAllViews()
            effectCategories.forEach { item ->
                val chip = text(item, 12f, if (item == category) Color.rgb(255, 142, 105) else Color.WHITE, true).apply {
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    setBackground(rounded(
                        if (item == category) Color.rgb(48, 39, 39) else Color.TRANSPARENT,
                        12,
                        if (item == category) Color.rgb(105, 62, 55) else Color.TRANSPARENT
                    ))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { renderEffectCategory(item) }
                }
                categoryRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply {
                    marginEnd = dp(6)
                })
            }
            effectsList.removeAllViews()
            val entries = catalogEffects.filter { it.group == category }
            entries.forEach { effect ->
                val item = text("${effect.name}    ·    #${effect.id}", 14f, Color.WHITE, false).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setBackground(rounded(Color.rgb(27, 31, 41), 10, Color.TRANSPARENT))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { applyCatalogEffect(effect) }
                }
                effectsList.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(5)
                })
            }
        }
        renderEffectCategory(selectedEffectCategory)
        addGap(effectsCard, 8)
        addModernSlider(effectsCard, "Швидкість", effectSpeed, 100, "%", 2) { value ->
            effectSpeed = value.coerceIn(0, 100)
            selectedSessions().filter { it.isMelk && it.connected && it.stripOn }.forEach { d ->
                sendMelkModeSpeed(d, effectSpeed)
            }
            selectedSessions().filter { !it.isMelk && it.connected && it.stripOn && it.mode == EffectMode.CATALOG }.forEach { d ->
                d.catalogSpeed = effectSpeed
                if (d.catalogHardwareMode >= 0) sendBleModeSpeed(d, effectSpeed)
            }
        }

        // Native sound-reactive modes of the controller microphone.
        data class MusicPreset(val id: Int, val name: String, val symbol: String)
        val musicPresets = listOf(
            MusicPreset(1, "Energetic", "✦"),
            MusicPreset(2, "Rhythm", "♫"),
            MusicPreset(3, "Spectrum", "◈"),
            MusicPreset(4, "Rolling", "≈"),
            MusicPreset(5, "Energetic II", "✧"),
            MusicPreset(6, "Rhythm II", "♪"),
            MusicPreset(7, "Spectrum II", "▥"),
            MusicPreset(8, "Rolling II", "◉")
        )
        var selectedMusicIndex = 0
        var musicSensitivity = 50
        val musicPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val musicCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(20))
            clipChildren = true
            clipToPadding = true
        }
        val musicHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        musicHeader.addView(
            text("МУЗИКА", 11f, muted, true).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(-2, dp(40))
        )
        val musicModeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = true
        }
        val musicModeIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        musicModeScroll.addView(musicModeIcons, android.view.ViewGroup.LayoutParams(-1, -2))
        musicHeader.addView(musicModeScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        musicCard.addView(musicHeader, LinearLayout.LayoutParams(-1, dp(40)))
        val musicContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 0)
            isClickable = true
            clipChildren = false
            clipToPadding = false
        }
        val musicSymbol = text(musicPresets[0].symbol, 60f, Color.rgb(255, 142, 105), true).apply {
            gravity = android.view.Gravity.CENTER
        }
        musicContent.addView(musicSymbol, LinearLayout.LayoutParams(-1, dp(76)))
        addGap(musicContent, 4)
        val musicName = text(musicPresets[0].name, 23f, Color.WHITE, true).apply {
            gravity = android.view.Gravity.CENTER
        }
        musicContent.addView(musicName, LinearLayout.LayoutParams(-1, -2))
        musicCard.addView(musicContent, LinearLayout.LayoutParams(-1, 0, 1f))
        musicPage.addView(musicCard, LinearLayout.LayoutParams(-1, 0, 1f))

        fun styleMusicIcon(view: TextView, active: Boolean) {
            view.setTextColor(if (active) Color.rgb(255, 142, 105) else Color.WHITE)
            view.background = rounded(
                if (active) Color.rgb(48, 39, 39) else Color.TRANSPARENT,
                10,
                if (active) Color.rgb(105, 62, 55) else Color.TRANSPARENT
            )
        }

        fun applyMusicProgram(index: Int) {
            selectedMusicIndex = (index + musicPresets.size) % musicPresets.size
            val preset = musicPresets[selectedMusicIndex]
            musicSymbol.text = preset.symbol
            musicName.text = preset.name
            for (i in 0 until musicModeIcons.childCount) {
                val icon = musicModeIcons.getChildAt(i) as? TextView ?: continue
                styleMusicIcon(icon, i == selectedMusicIndex)
            }
            val targets = selectedSessions().filter { it.isMelk && it.connected && it.characteristic != null }
            if (targets.isEmpty()) {
                status.text = "Для музичних режимів обери підключений пристрій"
                return
            }
            targets.forEach { d ->
                stopActiveEffect(d)
                d.mode = EffectMode.MIC
                if (!d.stripOn) {
                    d.stripOn = true
                    d.stripStateKnown = true
                    sendPowerCommand(d, true)
                }
                handler.postDelayed({
                    if (d.connected && d.mode == EffectMode.MIC) {
                        sendMelkMusicProgram(d, preset.id, musicSensitivity)
                    }
                }, 140L)
            }
            refreshEffectButtons()
            activateSelectedSession()
        }

        fun showMusic(nextIndex: Int, swipeLeft: Boolean) {
            selectedMusicIndex = (nextIndex + musicPresets.size) % musicPresets.size
            val selected = musicPresets[selectedMusicIndex]
            val exitX = if (swipeLeft) -dp(28).toFloat() else dp(28).toFloat()
            musicContent.animate().cancel()
            musicContent.animate().translationX(exitX).alpha(0f).setDuration(110L).withEndAction {
                musicSymbol.text = selected.symbol
                musicSymbol.textSize = 60f
                musicName.text = selected.name
                musicContent.translationX = -exitX
                musicContent.animate().translationX(0f).alpha(1f).setDuration(190L).start()
                for (i in 0 until musicModeIcons.childCount) {
                    val icon = musicModeIcons.getChildAt(i) as? TextView ?: continue
                    styleMusicIcon(icon, i == selectedMusicIndex)
                }
                applyMusicProgram(selectedMusicIndex)
            }.start()
        }

        musicPresets.forEachIndexed { index, preset ->
            val icon = text(preset.symbol, 18f, Color.WHITE, true).apply {
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (index != selectedMusicIndex) showMusic(index, index > selectedMusicIndex)
                }
            }
            styleMusicIcon(icon, index == selectedMusicIndex)
            musicModeIcons.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            })
        }

        var musicTouchStartX = 0f
        var musicTouchStartY = 0f
        musicContent.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    musicTouchStartX = event.x
                    musicTouchStartY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - musicTouchStartX
                    val deltaY = event.y - musicTouchStartY
                    if (kotlin.math.abs(deltaX) >= dp(45) &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.25f) {
                        if (deltaX < 0f) showMusic(selectedMusicIndex + 1, true)
                        else showMusic(selectedMusicIndex - 1, false)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        addGap(musicCard, 8)
        addModernSlider(musicCard, "Чутливість мікрофона", musicSensitivity, 100, "%", 2) { value ->
            musicSensitivity = value.coerceIn(0, 100)
            selectedSessions().filter { it.isMelk && it.connected && it.mode == EffectMode.MIC }.forEach { d ->
                sendMelkMusicSensitivity(d, musicSensitivity)
            }
        }

        // The bottom navigation stays visible; shared controls remain outside pageHost.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(3), dp(7), dp(3), dp(4))
            setBackground(rounded(Color.rgb(18, 21, 29), 20, Color.rgb(35, 41, 54)))
        }
        val navLabels = listOf("Вогонь", "Хвиля", "Веселка", "Режими", "Музика", "Конструктор")
        val navGlyphs = listOf("", "≋", "◉", "✦", "♪", "⌘")
        val navItems = mutableListOf<LinearLayout>()

        fun selectTab(index: Int) {
            selectedTabIndex = index
            val showColorPicker = index != 3 && index != 4
            val powerBesideSlider = index == 3 || index == 4
            colorCard.setPadding(dp(12), dp(if (showColorPicker) 0 else 12), dp(12), dp(if (showColorPicker) 2 else 12))
            colorHeader.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            colorHeaderBottomGap.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            colorTitle.translationY = 0f
            (colorHeader.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.bottomMargin = if (showColorPicker) -dp(12) else 0
                colorHeader.layoutParams = params
            }
            brightnessValueSpacer?.visibility = if (powerBesideSlider) View.VISIBLE else View.GONE
            (bulbButton.parent as? ViewGroup)?.removeView(bulbButton)
            if (powerBesideSlider) {
                bulbButton.translationY = 0f
                brightnessSliderRow?.addView(bulbButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(10)
                })
            } else {
                bulbButton.translationY = 0f
                colorHeader.addView(bulbButton, FrameLayout.LayoutParams(dp(40), dp(40), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                    topMargin = dp(12)
                })
            }
            val musicBrightnessLocked = index == 4
            brightnessSlider?.isEnabled = !musicBrightnessLocked
            brightnessSlider?.alpha = if (musicBrightnessLocked) 0.4f else 1f
            brightnessTitleView?.alpha = if (musicBrightnessLocked) 0.45f else 1f
            brightnessValueView?.alpha = if (musicBrightnessLocked) 0.45f else 1f
            colorTitle.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            pickerLabel.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            wheelFrame.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            preview.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            pageHost.removeAllViews()
            when (index) {
                0 -> pageHost.addView(firePage, FrameLayout.LayoutParams(-1, -1))
                2 -> pageHost.addView(rainbowPage, FrameLayout.LayoutParams(-1, -1))
                3 -> {
                    pageHost.addView(effectsPage, FrameLayout.LayoutParams(-1, -1))
                    renderEffectCategory(selectedEffectCategory)
                }
                4 -> {
                    pageHost.addView(musicPage, FrameLayout.LayoutParams(-1, -1))
                    applyMusicProgram(selectedMusicIndex)
                }
            }
            pageHost.addView(noActiveDeviceView, FrameLayout.LayoutParams(-1, -1))
            navItems.forEachIndexed { i, item ->
                item.setBackground(rounded(
                    if (i == index) Color.rgb(48, 39, 39) else Color.TRANSPARENT,
                    14,
                    if (i == index) Color.rgb(105, 62, 55) else Color.TRANSPARENT
                ))
                val label = item.getChildAt(1) as TextView
                label.setTextColor(if (i == index) Color.rgb(255, 142, 105) else muted)
                if (i == index) label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
                else label.setTypeface(label.typeface, android.graphics.Typeface.NORMAL)
            }
            refreshSelectionDisplay?.invoke()
        }

        navLabels.forEachIndexed { index, labelText ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(dp(2), dp(7), dp(2), dp(7))
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(index) }
            }
            if (index == 0) {
                item.addView(FlameGlyphView(this), LinearLayout.LayoutParams(dp(23), dp(23)))
            } else {
                item.addView(text(navGlyphs[index], 21f, Color.WHITE, true).apply {
                    gravity = android.view.Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, dp(23)))
            }
            item.addView(text(labelText, 9f, muted, false).apply {
                gravity = android.view.Gravity.CENTER
                maxLines = 1
            })
            nav.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            navItems.add(item)
        }
        root.addView(nav, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(7)
        })
        refreshSelectionDisplay = {
            val hasSelection = selectedDeviceAddresses.isNotEmpty()
            colorCard.visibility = if (hasSelection) View.VISIBLE else View.GONE
            nav.visibility = if (hasSelection) View.VISIBLE else View.GONE
            if (pageHost.childCount > 0 && pageHost.getChildAt(0) !== noActiveDeviceView) {
                pageHost.getChildAt(0).visibility = if (hasSelection) View.VISIBLE else View.GONE
            }
            noActiveDeviceView.visibility = if (hasSelection) View.GONE else View.VISIBLE
        }
        selectTab(0)
        refreshSelectionDisplay?.invoke()

        screenRoot = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(screenRoot)
    }

    private fun deviceDisplayName(address: String): String {
        val fallback = when {
            address.equals(melkAddress, true) -> deviceNames[1]
            address.equals(targetAddress, true) -> deviceNames[0]
            address.equals(thirdAddress, true) -> "STRIP3"
            address.equals(fourthAddress, true) -> "STRIP4"
            else -> discoveredDevices[address.uppercase()]?.name?.takeIf { it.isNotBlank() } ?: "BLE-пристрій"
        }
        return getSharedPreferences("firelight_device_names", MODE_PRIVATE)
            .getString(address.uppercase(), fallback)?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun showDevicePicker() {
        if (!::deviceField.isInitialized) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(27, 32, 43)); cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(61, 70, 88))
            }
        }
        val checks = linkedMapOf<String, TextView>()
        val labels = linkedMapOf<String, TextView>()
        val rowViews = linkedMapOf<String, View>()
        val addresses = managedDeviceAddresses.toList()
        fun refreshRows() {
            addresses.forEach { a ->
                val available = sessions[a]?.connected == true
                val selected = selectedDeviceAddresses.contains(a) && available
                checks[a]?.apply {
                    text = if (selected) "✓" else ""
                    setTextColor(if (available) Color.rgb(255, 142, 105) else Color.rgb(105, 113, 128))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (selected) Color.rgb(75, 48, 45) else Color.TRANSPARENT)
                        cornerRadius = dp(10).toFloat()
                    }
                }
                labels[a]?.apply {
                    text = deviceDisplayName(a)
                    setTextColor(if (available) Color.rgb(226, 231, 241) else Color.rgb(105, 113, 128))
                }
                rowViews[a]?.apply {
                    alpha = if (available) 1f else .58f
                    isEnabled = available
                    isClickable = available
                }
            }
            deviceField.text = "Пристрої · ${selectedDeviceAddresses.size} обрано  ▾"
        }
        addresses.forEach { a ->
            val check = TextView(this).apply {
                textSize = 15f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(255, 142, 105)); gravity = android.view.Gravity.CENTER
            }
            val label = TextView(this).apply {
                text = deviceDisplayName(a); textSize = 14f
                setTextColor(Color.rgb(226, 231, 241)); gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(14), 0); minimumHeight = dp(52)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (sessions[a]?.connected != true) return@setOnClickListener
                    if (!selectedDeviceAddresses.add(a)) {
                        selectedDeviceAddresses.remove(a)
                        if (selectedDeviceAddress.equals(a, true)) selectedDeviceAddress = selectedDeviceAddresses.firstOrNull() ?: ""
                    } else selectedDeviceAddress = a
                    activateSelectedSession(); refreshRows()
                }
                setOnLongClickListener {
                    if (sessions[a]?.connected != true) return@setOnLongClickListener true
                    selectedDeviceAddresses.clear(); selectedDeviceAddresses.add(a); selectedDeviceAddress = a
                    activateSelectedSession(); devicePopup?.dismiss(); true
                }
                addView(label, LinearLayout.LayoutParams(0, -1, 1f)); addView(check, LinearLayout.LayoutParams(dp(28), dp(28)))
            }
            labels[a] = label; checks[a] = check; rowViews[a] = row
            rows.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        }
        refreshDevicePopup = { refreshRows() }; refreshRows()
        val popup = PopupWindow(rows, deviceField.width.coerceAtLeast(dp(220)), -2, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true; elevation = dp(12).toFloat()
            setOnDismissListener { if (devicePopup === this) devicePopup = null }
        }
        devicePopup?.dismiss(); devicePopup = popup
        deviceField.post { if (deviceField.isAttachedToWindow && devicePopup === popup) popup.showAsDropDown(deviceField, 0, dp(4)) }
    }

    private fun refreshDevicePicker() {
        if (::deviceField.isInitialized) {
            deviceField.text = "Пристрої · ${selectedDeviceAddresses.size} обрано  ▾"
            refreshDevicePopup?.invoke()
        }
    }

    private fun showAppMenu(anchor: View) {
        val host = screenRoot ?: return
        if (drawerOverlay != null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val width = (resources.displayMetrics.widthPixels * .75f).toInt()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(166, 0, 0, 0)); alpha = 0f; setOnClickListener { closeAppMenu() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(54), dp(18), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(22, 26, 36))
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f, dp(22).toFloat(), dp(22).toFloat())
                setStroke(dp(1), Color.rgb(39, 45, 59))
            }
            isClickable = true; isFocusable = true; setOnClickListener { }
        }
        overlay.addView(panel, FrameLayout.LayoutParams(width, -1, android.view.Gravity.RIGHT or android.view.Gravity.TOP))
        panel.addView(TextView(this).apply {
            text = "МЕНЮ"; textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(226, 231, 241)); letterSpacing = .14f
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, dp(42)))
        fun item(title: String, symbol: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(15), 0, dp(14), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(37, 43, 57)); cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(55, 63, 80))
                }
                isClickable = true; isFocusable = true; setOnClickListener { closeAppMenu(); action() }
            }
            if (symbol == "power") {
                row.addView(PowerMenuGlyph(this), LinearLayout.LayoutParams(dp(34), dp(34)))
            } else {
                row.addView(TextView(this).apply {
                    text = symbol; textSize = 21f; setTextColor(Color.rgb(255, 142, 105)); gravity = android.view.Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(34), -1))
            }
            row.addView(TextView(this).apply {
                text = title; textSize = 16f; setTextColor(Color.rgb(222, 228, 239)); gravity = android.view.Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -1, 1f))
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        }
        item("Перейменувати девайси", "✎") { showRenameDevicesDialog() }
        item("Підключення пристроїв", "⌕") { showDeviceManagementDialog() }
        panel.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        item("Вимкнути все", "power") { turnOffAllConnectedDevices() }
        host.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        drawerOverlay = overlay; drawerPanel = panel
        overlay.animate().alpha(1f).setDuration(180L).start()
        panel.translationX = width.toFloat(); panel.animate().translationX(0f).setDuration(230L).start()
    }

    private fun closeAppMenu() {
        val overlay = drawerOverlay ?: return; val panel = drawerPanel
        val width = panel?.width?.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * .75f).toInt()
        overlay.animate().alpha(0f).setDuration(170L).start()
        panel?.animate()?.translationX(width.toFloat())?.setDuration(190L)?.withEndAction {
            (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
            if (drawerOverlay === overlay) { drawerOverlay = null; drawerPanel = null }
        }?.start() ?: run {
            (overlay.parent as? android.view.ViewGroup)?.removeView(overlay); drawerOverlay = null; drawerPanel = null
        }
    }

    private fun saveManagedDevices() {
        getSharedPreferences("firelight_devices", MODE_PRIVATE).edit()
            .putStringSet("addresses", managedDeviceAddresses.toSet()).apply()
    }

    private fun showDeviceManagementDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val hint = TextView(this).apply {
            text = "Знайдені Bluetooth-пристрої поблизу"
            textSize = 13f; setTextColor(Color.rgb(151, 160, 178))
        }
        content.addView(hint, LinearLayout.LayoutParams(-1, dp(34)))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(list) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val search = Button(this).apply {
            text = "Шукати пристрої"
            setOnClickListener {
                discoveredDevices.clear()
                hint.text = "Сканування... Знайдені пристрої з'являться нижче"
                startScan(true)
            }
        }
        val stop = Button(this).apply {
            text = "Зупинити"
            setOnClickListener { stopScan(); hint.text = "Сканування зупинено" }
        }
        actions.addView(search, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        actions.addView(stop, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        content.addView(actions)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Підключення пристроїв")
            .setView(content)
            .setNegativeButton("Закрити", null)
            .create()

        fun refreshRows() {
            list.removeAllViews()
            if (discoveredDevices.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = if (scanning) "Пошук пристроїв..." else "Поки нічого не знайдено. Натисни «Шукати пристрої»."
                    textSize = 14f; setTextColor(Color.rgb(183, 191, 207)); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(24))
                })
            }
            discoveredDevices.values.sortedBy { it.name.lowercase() }.forEach { found ->
                val address = found.device.address.uppercase()
                val added = bridgeGatt != null
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(8), dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(29, 34, 46)); cornerRadius = dp(12).toFloat()
                    }
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this).apply {
                    text = found.name; textSize = 14f
                    setTextColor(Color.rgb(226, 231, 241)); maxLines = 1
                })
                info.addView(TextView(this).apply {
                    text = "$address  ·  RSSI ${found.rssi} dBm"
                    textSize = 10f; setTextColor(Color.rgb(139, 149, 168)); maxLines = 1
                })
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                val toggle = TextView(this).apply {
                    text = if (added) "Додано ✓" else "Додати +"
                    textSize = 12f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(if (added) Color.rgb(255, 142, 105) else Color.rgb(130, 210, 164))
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        if (bridgeGatt != null) {
                            disconnect()
                        } else {
                            connect(found.device)
                        }
                        refreshDevicePicker()
                        refreshRows()
                    }
                }
                row.addView(toggle, LinearLayout.LayoutParams(dp(92), dp(42)))
                list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
            }
        }
        refreshDiscoveryDialog = { refreshRows() }
        dialog.setOnDismissListener {
            refreshDiscoveryDialog = null
            if (scanning && discoverAllScanning) stopScan()
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), dp(520))
        refreshRows()
        discoveredDevices.clear()
        hint.text = "Сканування почнеться автоматично. За потреби можна запустити знову."
        startScan(true)
    }

    private class PowerMenuGlyph(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 142, 105)
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.2f
            strokeCap = Paint.Cap.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * .31f
            canvas.drawArc(cx-r, cy-r, cx+r, cy+r, 135f, 270f, false, paint)
            canvas.drawLine(cx, cy-r*1.42f, cx, cy+r*.08f, paint)
        }
    }

    private fun showRenameDevicesDialog() {
        val connectedDevices = sessions.values.filter { it.connected && it.gatt != null && it.characteristic != null }
        if (connectedDevices.isEmpty()) {
            Toast.makeText(this, "Немає підключених пристроїв", Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val fields = linkedMapOf<String, EditText>()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        connectedDevices.forEachIndexed { index, device ->
            val defaultName = when (device.targetId) {
                0x01 -> deviceNames[1]
                0x02 -> deviceNames[0]
                0x03 -> "STRIP3"
                0x04 -> "STRIP4"
                else -> "STRIP"
            }
            content.addView(TextView(this).apply {
                text = defaultName
                textSize = 12f
                setTextColor(Color.rgb(151, 160, 178))
            })
            val field = EditText(this).apply {
                setSingleLine(true)
                textSize = 15f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.rgb(110, 119, 137))
                hint = defaultName
                setText(deviceDisplayName(device.address))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(29, 34, 46))
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), Color.rgb(61, 70, 88))
                }
            }
            content.addView(field, LinearLayout.LayoutParams(-1, dp(46)))
            fields[device.address.uppercase()] = field
            if (index != connectedDevices.lastIndex) content.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Перейменувати девайси")
            .setView(content)
            .setNegativeButton("Скасувати", null)
            .setPositiveButton("Зберегти", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prefs = getSharedPreferences("firelight_device_names", MODE_PRIVATE).edit()
                fields.forEach { (address, field) ->
                    val value = field.text.toString().trim()
                    if (value.isNotEmpty()) prefs.putString(address, value)
                }
                prefs.apply()
                refreshDevicePicker()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun turnOffAllConnectedDevices() {
        val targets = sessions.values.filter { it.connected && it.gatt != null && it.characteristic != null }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Немає підключених пристроїв", Toast.LENGTH_SHORT).show()
            return
        }
        targets.forEach { device ->
            pauseActiveEffectForPowerOff(device)
            device.stripOn = false
            device.stripStateKnown = true
            device.pending = null
            device.flushQueued = false
            device.flushRunner?.let { handler.removeCallbacks(it) }
            sendPowerCommand(device, false)
        }
        activateSelectedSession()
        Toast.makeText(this, "Усі підключені стрічки вимкнено", Toast.LENGTH_SHORT).show()
    }

    private fun startKeepAliveService() {
        try {
            val intent = android.content.Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {
            status.text = "Не вдалося запустити фоновий режим: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && hasBluetoothPermissions()) {
            startKeepAliveService()
            startScan()
        } else if (requestCode == 100) {
            status.text = "Потрібні дозволи Bluetooth для підключення"
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        }

        return checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startScan(discoverAll: Boolean = false) {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            Toast.makeText(this, "Надай дозволи Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Увімкни Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        if (!discoverAll && bridgeGatt != null) {
            status.text = "Firelight Bridge уже підключений"
            return
        }
        stopScan()
        discoverAllScanning = discoverAll
        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            status.text = "BLE-сканер недоступний"
            return
        }
        status.text = if (discoverAll) "Шукаю BLE-пристрої поблизу..." else "Шукаю додані пристрої..."
        scanning = true
        try {
            // Scan without a controller-side UUID filter: some Android BLE stacks
            // omit advertised service UUIDs from filtered scan results.
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            activeScanner.startScan(null, settings, scanCallback)
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, 12000L)
        } catch (e: SecurityException) {
            scanning = false
            status.text = "Немає дозволу Bluetooth"
        } catch (e: Exception) {
            scanning = false
            status.text = "Помилка пошуку: ${e.message}"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = try { result.device.address.uppercase() } catch (_: SecurityException) { "" }
            if (address.isBlank()) return
            // Identify the Arduino bridge by its stable BLE address; do not depend
            // on the advertised local name or service UUID being exposed by Android.
            if (!address.equals("20:6E:F1:2F:ED:C9", ignoreCase = true)) return
            runOnUiThread {
                discoveredDevices[address] = DiscoveredBle(result.device, "Firelight Bridge", result.rssi)
                refreshDiscoveryDialog?.invoke()
                if (!discoverAllScanning && bridgeGatt == null) {
                    status.text = "Знайшов Firelight Bridge · підключаю..."
                    connect(result.device)
                }
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread { status.text = "Помилка BLE-сканування: $errorCode" }
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        refreshDiscoveryDialog?.invoke()
    }

    private fun classifyDeviceLayout(device: BluetoothDevice): DeviceLayout {
        // MELK uses sequential layout; RGB targets (02 and 03) use simultaneous layout.
        return if (device.address.equals(melkAddress, ignoreCase = true)) {
            DeviceLayout.SEQUENTIAL
        } else {
            DeviceLayout.SIMULTANEOUS
        }
    }

    private fun selectedSessions(): List<DeviceSession> =
        selectedDeviceAddresses.mapNotNull { sessions[it] }.filter { it.connected && it.characteristic != null }

    private fun activateSelectedSession() {
        val connectedAddresses = sessions.values.filter { it.connected }.map { it.address }.toSet()
        selectedDeviceAddresses.retainAll(connectedAddresses)
        if (selectedDeviceAddress !in selectedDeviceAddresses) {
            selectedDeviceAddress = selectedDeviceAddresses.firstOrNull().orEmpty()
        }
        val d=sessions[selectedDeviceAddress.uppercase()]
        gatt=d?.gatt; characteristic=d?.characteristic; notifyCharacteristic=d?.notifyCharacteristic
        connected=d?.connected==true; isMelkDevice=d?.isMelk==true
        deviceLayout=if(isMelkDevice) DeviceLayout.SEQUENTIAL else DeviceLayout.SIMULTANEOUS
        if(d!=null){red=d.red;green=d.green;blue=d.blue;brightness=d.brightness;speed=d.speed;fireColorSpread=d.spread;rainbowSpeed=d.rainbowSpeed;fillSpeed=d.fillSpeed;if(::preview.isInitialized)updatePreview()}
        val chosen=selectedSessions()
        val powerStates=chosen.map{if(it.stripStateKnown) it.stripOn else null}.distinct()
        bulbButton.setLit(if(chosen.isEmpty() || powerStates.size!=1) null else powerStates.first())
        val modes=chosen.map{it.mode}.distinct()
        fireButton.text=when {chosen.isEmpty()||modes.size!=1->"Змішаний стан";modes.first()==EffectMode.FIRE->"Вимкнути вогонь";else->"Увімкнути вогонь"}
        rainbowButton.text=when {chosen.isEmpty()||modes.size!=1->"Змішаний стан";modes.first()==EffectMode.RAINBOW->"Вимкнути веселку";else->"Увімкнути веселку"}
        val one=chosen.firstOrNull()
        status.text=when {
            chosen.isEmpty()->"Обери підключений пристрій"
            chosen.size>1->"Обрано ${chosen.size} · керування групою"
            one==null || !one.stripStateKnown->"Підключено · стан стрічки невідомий"
            one.stripOn->"Підключено · стрічка увімкнена"
            else->"Підключено · стрічка вимкнена"
        }
        refreshDevicePicker()
        refreshSelectionDisplay?.invoke()
    }

    private fun connect(device: BluetoothDevice) {
        if (bridgeGatt != null) return
        sessions.getOrPut(targetAddress.uppercase()) {
            DeviceSession(targetAddress.uppercase(), false, 0x02)
        }
        sessions.getOrPut(melkAddress.uppercase()) {
            DeviceSession(melkAddress.uppercase(), true, 0x01)
        }
        sessions.getOrPut(thirdAddress.uppercase()) {
            DeviceSession(thirdAddress.uppercase(), false, 0x03)
        }
        sessions.getOrPut(fourthAddress.uppercase()) {
            DeviceSession(fourthAddress.uppercase(), false, 0x04)
        }
        status.text = "Підключення до Firelight Bridge…"
        try {
            bridgeGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (bridgeGatt == null) status.text = "Не вдалося відкрити BLE-з'єднання з мостом"
            refreshDevicePicker()
        } catch (_: SecurityException) {
            status.text = "Помилка дозволу Bluetooth"
            bridgeGatt = null
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (g !== bridgeGatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                if (bridgeClosing) {
                    try { g.disconnect() } catch (_: Exception) {}
                    return
                }
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                runOnUiThread { status.text = "Firelight Bridge підключено · шукаю сервіси…" }
                try { g.discoverServices() } catch (_: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasClosing = bridgeClosing
                bridgeCloseTimeout?.let { handler.removeCallbacks(it) }
                bridgeCloseTimeout = null
                bridgeClosing = false
                bridgeWriteQueue.clear()
                bridgeWriteInFlight = false
                bridgeWriteReady = false
                bridgeWriteWithoutResponse = false
                bridgeRxCharacteristic = null
                bridgeStatusCharacteristic = null
                sessions.values.forEach { session ->
                    session.connected = false
                    session.gatt = null
                    session.characteristic = null
                    session.notifyCharacteristic = null
                    // A BLE disconnect says nothing about the strip's power or active program.
                    // Keep the last known per-device state for the next bridge connection.
                }
                try { g.close() } catch (_: Exception) {}
                bridgeGatt = null
                if (!wasClosing && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        selectedDeviceAddresses.clear()
                        selectedDeviceAddress = ""
                        gatt = null
                        characteristic = null
                        connected = false
                        activateSelectedSession()
                        refreshDevicePicker()
                        status.text = "Firelight Bridge від'єднався"
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            if (g !== bridgeGatt) return
            val service = g.getService(serviceUUID)
            val rx = service?.getCharacteristic(writeUUID)
            val bridgeStatus = service?.getCharacteristic(statusUUID)
            if (statusCode != BluetoothGatt.GATT_SUCCESS || rx == null) {
                runOnUiThread { status.text = "Не знайдено сервіс Firelight Bridge або RX" }
                return
            }
            bridgeRxCharacteristic = rx
            bridgeStatusCharacteristic = bridgeStatus
            bridgeWriteWithoutResponse = (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            sessions.values.forEach { session ->
                session.gatt = g
                session.characteristic = rx
                session.notifyCharacteristic = bridgeStatus
                session.connected = true
                // Do not invent power state on reconnect; wait for the bridge's
                // per-target state report before marking it as known.
            }
            runOnUiThread {
                // Preserve the user's target selection across reconnects.
                if (selectedDeviceAddress.isBlank()) selectedDeviceAddress = targetAddress.uppercase()
                activateSelectedSession()
                status.text = "Firelight Bridge підключено · 4 цілі готові"
                refreshDevicePicker()
            }

            val canNotify = bridgeStatus != null &&
                (bridgeStatus.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
            bridgeWriteReady = true
            if (canNotify) {
                try {
                    g.setCharacteristicNotification(bridgeStatus, true)
                    val descriptor = bridgeStatus.getDescriptor(cccdUUID)
                    if (descriptor != null) {
                        val value = if ((bridgeStatus.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        bridgeWriteReady = false
                        val accepted = if (Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = value
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(descriptor)
                        }
                        if (!accepted) bridgeWriteReady = true
                    }
                } catch (_: Exception) {
                    bridgeWriteReady = true
                }
            }

            // The Arduino initializes MELK itself immediately after its
            // peripheral connection; these short 3-byte packets are not bridge frames.
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== bridgeGatt || characteristic.uuid != statusUUID) return
            handleBridgeStatus(characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (g !== bridgeGatt || characteristic.uuid != statusUUID) return
            handleBridgeStatus(value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== bridgeGatt || descriptor.uuid != cccdUUID) return
            bridgeWriteReady = true
            if (status == BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { this@MainActivity.status.text = "Firelight Bridge підключено · статус активний" }
            }
            handler.post { scheduleBridgeWrite() }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (g !== bridgeGatt || characteristic.uuid != writeUUID) return
            // Write Without Response is paced by the queue runner, not by this callback.
            if (bridgeWriteWithoutResponse) return
            bridgeWriteInFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { this@MainActivity.status.text = "Міст не прийняв BLE-команду (код $status)" }
            }
            handler.post { scheduleBridgeWrite() }
        }
    }

    private fun handleBridgeStatus(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        val message = String(value, Charsets.UTF_8).trim()
        if (message.isEmpty()) return
        if (message.startsWith("ERR:") || message.endsWith(":FAIL")) {
            runOnUiThread { status.text = "Firelight Bridge · $message" }
        } else if (message.endsWith(":OK")) {
            runOnUiThread {
                status.text = "Firelight Bridge · команду передано (${message.removePrefix("TX:").removeSuffix(":OK")})"
            }
        }
    }

    private fun updatePowerStateFromPacket(session: DeviceSession, packet: ByteArray?) {
        if(packet==null||packet.size<4||packet[0]!=0x7E.toByte()||packet[2]!=0x04.toByte())return
        val on=when(packet[3].toInt() and 255){0xF0,0xFF->true;0x00->false;else->return}
        session.stripOn=on;session.stripStateKnown=true
        if(!on) pauseActiveEffectForPowerOff(session)
        runOnUiThread { if(selectedDeviceAddresses.contains(session.address)) activateSelectedSession() }
    }

    private fun sendPowerCommand(session: DeviceSession,on:Boolean) {
        val gatt = session.gatt ?: return
        val characteristic = session.characteristic ?: return

        if (!session.isMelk) {
            if (!on) {
                // Prevent already queued RGB animation/color frames from
                // arriving after OFF and turning the strip back on.
                clearBleWriteQueue(session)
                session.pending = null
                session.flushQueued = false
                session.flushRunner?.let { handler.removeCallbacks(it) }
            }
            val frame = byteArrayOf(
                0xF2.toByte(), session.targetId.toByte(), 0x02, 0x01,
                if (on) 0x01 else 0x00
            )
            enqueueBridgeFrame(
                gatt, characteristic, frame,
                replaceTargetQueue = true
            )
            return
        }

        val packet = if (on) {
            byteArrayOf(0x7E,0x04,0x04,0xF0.toByte(),0,1,0xFF.toByte(),0,0xEF.toByte())
        } else {
            byteArrayOf(0x7E,0x04,0x04,0,0,0,0xFF.toByte(),0,0xEF.toByte())
        }
        writeBlePacketTo(gatt, characteristic, packet, session.targetId)
    }

    // BLE writes are serialized and paced. These controllers silently discard
    // overlapping write-without-response frames, so effect startup and RGB
    // animation packets must share one per-device queue.
    private fun enqueueBlePacket(session: DeviceSession, packet: ByteArray) {
        if (session.isMelk || !session.connected || session.gatt == null || session.characteristic == null) return

        // Brightness slider updates are replaceable. Do not let old values
        // accumulate in the per-device queue while the bridge is busy.
        val isRgbBrightness = packet.size == 9 &&
            packet[0] == 0x7E.toByte() &&
            packet[1] == 0x04.toByte() &&
            packet[2] == 0x01.toByte()
        if (isRgbBrightness) {
            val iterator = session.bleWriteQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.size == 9 &&
                    queued[0] == 0x7E.toByte() &&
                    queued[1] == 0x04.toByte() &&
                    queued[2] == 0x01.toByte()
                ) iterator.remove()
            }
        }

        session.bleWriteQueue.addLast(packet.copyOf())
        if (session.bleWriteScheduled) return
        val runner = session.bleWriteRunner ?: object : Runnable {
            override fun run() {
                if (!session.connected || session.gatt == null || session.characteristic == null) {
                    session.bleWriteQueue.clear()
                    session.bleWriteScheduled = false
                    return
                }
                val now = SystemClock.uptimeMillis()
                val wait = (50L - (now - session.bleWriteLastAt)).coerceAtLeast(0L)
                if (wait > 0L) {
                    handler.postDelayed(this, wait)
                    return
                }
                val next = session.bleWriteQueue.pollFirst()
                if (next == null) {
                    session.bleWriteScheduled = false
                    return
                }
                writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, next, session.targetId)
                session.bleWriteLastAt = SystemClock.uptimeMillis()
                if (session.bleWriteQueue.isNotEmpty()) {
                    handler.postDelayed(this, 50L)
                } else {
                    session.bleWriteScheduled = false
                }
            }
        }.also { session.bleWriteRunner = it }
        session.bleWriteScheduled = true
        handler.post(runner)
    }

    private fun clearBleWriteQueue(session: DeviceSession) {
        session.bleWriteRunner?.let { handler.removeCallbacks(it) }
        session.bleWriteQueue.clear()
        session.bleWriteScheduled = false
    }

    private fun sendBleMode(session: DeviceSession, modeIndex: Int) {
        if (session.isMelk || !session.connected) return
        val opcode = (0x80 + modeIndex.coerceIn(0, 28)).toByte()
        val packet = byteArrayOf(0x7E, 0x05, 0x03, opcode, 0x03, 0xFF.toByte(),
            0xFF.toByte(), 0x00, 0xEF.toByte())
        enqueueBlePacket(session, packet)
    }

    private fun sendBleModeSpeed(session: DeviceSession, value: Int) {
        if (session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x02, value.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        enqueueBlePacket(session, packet)
    }

    private fun sendBleBrightness(session: DeviceSession, value: Int) {
        if (session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x01, value.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        enqueueBlePacket(session, packet)
    }

    private fun bleColorIndex(name: String): Int = when {
        name.contains("Red", true) -> 0
        name.contains("Green", true) -> 1
        name.contains("Blue", true) -> 2
        name.contains("Yellow", true) || name.contains("Yelllow", true) -> 3
        name.contains("Cyan", true) -> 4
        name.contains("Purple", true) -> 5
        name.contains("White", true) -> 6
        else -> -1
    }

    private fun bleColor(name: String): Int = when (bleColorIndex(name)) {
        0 -> Color.rgb(255, 0, 0)
        1 -> Color.rgb(0, 255, 0)
        2 -> Color.rgb(0, 0, 255)
        3 -> Color.rgb(255, 255, 0)
        4 -> Color.rgb(0, 255, 255)
        5 -> Color.rgb(160, 0, 255)
        6 -> Color.WHITE
        else -> Color.BLACK
    }

    private fun mapBleCatalogEffect(name: String): BleCatalogSpec {
        val red = Color.rgb(255, 0, 0)
        val green = Color.rgb(0, 255, 0)
        val blue = Color.rgb(0, 0, 255)
        val yellow = Color.rgb(255, 255, 0)
        val cyan = Color.rgb(0, 255, 255)
        val purple = Color.rgb(160, 0, 255)
        val white = Color.WHITE
        val rainbow = intArrayOf(red, yellow, green, cyan, blue, purple, white)
        val rgb = intArrayOf(red, green, blue)
        val ycp = intArrayOf(yellow, cyan, purple)
        val sixColor = intArrayOf(red, yellow, green, cyan, blue, purple)

        val palette = when {
            name.contains("-Dot in") -> {
                val dot = bleColor(name.substringBefore("-Dot"))
                val background = bleColor(name.substringAfter(" in ")
                    .substringBefore(" Running").substringBefore(" Run Back"))
                intArrayOf(background, dot)
            }
            name.contains("7-Color in") -> {
                val background = bleColor(name.substringAfter(" in ")
                    .substringBefore(" Running").substringBefore(" Run Back"))
                intArrayOf(background) + rainbow
            }
            name.contains("B-P-C-Y") -> intArrayOf(blue, purple, cyan, yellow)
            name.contains("B-G-C-Y") -> intArrayOf(blue, green, cyan, yellow)
            name.contains("W-R-W") -> intArrayOf(white, red, white)
            name.contains("W-G-W") -> intArrayOf(white, green, white)
            name.contains("W-B-W") -> intArrayOf(white, blue, white)
            name.contains("W-Y-W") -> intArrayOf(white, yellow, white)
            name.contains("W-C-W") -> intArrayOf(white, cyan, white)
            name.contains("W-P-W") -> intArrayOf(white, purple, white)
            name.contains("R-W-R") -> intArrayOf(red, white, red)
            name.contains("G-W-G") -> intArrayOf(green, white, green)
            name.contains("B-W-B") -> intArrayOf(blue, white, blue)
            name.contains("Y-W-Y") -> intArrayOf(yellow, white, yellow)
            name.contains("C-W-C") -> intArrayOf(cyan, white, cyan)
            name.contains("P-W-P") -> intArrayOf(purple, white, purple)
            name.contains("R-G-B") -> rgb
            name.contains("Y-C-P") -> ycp
            name.contains("R-G Water") -> intArrayOf(red, green)
            name.contains("G-B Water") -> intArrayOf(green, blue)
            name.contains("Y-B Water") -> intArrayOf(yellow, blue)
            name.contains("Y-C Water") -> intArrayOf(yellow, cyan)
            name.contains("C-P Water") -> intArrayOf(cyan, purple)
            name.contains("R-Y") -> intArrayOf(red, yellow)
            name.contains("R-P") -> intArrayOf(red, purple)
            name.contains("G-C") -> intArrayOf(green, cyan)
            name.contains("G-Y") -> intArrayOf(green, yellow)
            name.contains("B-P") -> intArrayOf(blue, purple)
            name.contains("6-Color to") -> sixColor + bleColor(name.substringAfter(" to "))
            name.contains("7-Color") -> rainbow
            bleColorIndex(name) >= 0 -> intArrayOf(bleColor(name))
            else -> rainbow
        }

        val style = when {
            name.contains("Strobe") || name.contains("Marquee") -> BleCatalogStyle.STROBE
            name.contains("-Dot in") || name.contains("7-Color in") -> BleCatalogStyle.ALTERNATE
            name.contains("Tail") -> BleCatalogStyle.TAIL
            name.contains("Close") || name.contains("Open") -> BleCatalogStyle.CURTAIN
            name.contains("Running") || name.contains("Run Back") || name.contains("Race") -> BleCatalogStyle.RUN
            name.contains("Flush") -> BleCatalogStyle.FLUSH
            name.contains("Jump") || name.contains("Magic") || name.contains("Auto Play") -> BleCatalogStyle.JUMP
            name.contains("Energy") -> BleCatalogStyle.ENERGY
            else -> BleCatalogStyle.FADE
        }

        val reverse = name.contains("Back") || name.contains("Open")
        val finalPalette = if (reverse) palette.reversedArray() else palette

        // Only choose firmware modes where their colour sequence is a close
        // semantic match. Spatial MELK effects remain software analogues because
        // an ordinary RGB strip cannot move an individual pixel or segment.
        val colorIndex = bleColorIndex(name)
        val hardwareMode = when {
            name.contains("7-Color Strobe") -> 21
            name.contains("Strobe") && colorIndex >= 0 -> 22 + colorIndex
            name.contains("Marquee") && colorIndex >= 0 -> 22 + colorIndex
            name == "7-Color Trans" -> 10
            name == "R-G-B Trans" -> 9
            name == "7-Color Wave" || name == "7-Color Water" -> 10
            name == "R-G-B Wave" -> 9
            name == "R-G Water" -> 18
            name == "G-B Water" -> 20
            name == "7-Color Jump" || name == "7-Color Race" -> 8
            name == "R-G-B Jump" || name == "R-G-B Race" -> 7
            name == "7-Color Gradual" -> 10
            name.contains("Gradual") && colorIndex >= 0 -> 11 + colorIndex
            else -> -1
        }
        val resolvedHardwareMode = if (reverse) -1 else hardwareMode
        return BleCatalogSpec(resolvedHardwareMode, finalPalette, style)
    }

    private fun startBleCatalogEffect(session: DeviceSession, effectId: Int, effectName: String, speed: Int) {
        stopActiveEffect(session)
        session.mode = EffectMode.CATALOG
        session.catalogEffectId = effectId
        // Drop stale BLE animation frames before the newly selected mode starts.
        if (!session.isMelk) {
            session.pending = null
            session.flushQueued = false
            session.flushRunner?.let { handler.removeCallbacks(it) }
            clearBleWriteQueue(session)
        }
        val spec = mapBleCatalogEffect(effectName)
        session.catalogHardwareMode = spec.hardwareMode
        session.catalogPalette = spec.palette
        session.catalogStyle = spec.style
        session.catalogSpeed = speed.coerceIn(0, 100)
        if (!session.stripOn) {
            session.stripOn = true
            session.stripStateKnown = true
            sendPowerCommand(session, true)
        }
        handler.postDelayed({
            if (!session.connected || !session.stripOn || session.mode != EffectMode.CATALOG ||
                session.catalogEffectId != effectId) return@postDelayed
            if (session.catalogHardwareMode >= 0) {
                // Lotus Lantern applies speed to the next mode, so queue in the
                // documented order: brightness, speed, then animation opcode.
                sendBleBrightness(session, session.brightness)
                sendBleModeSpeed(session, session.catalogSpeed)
                sendBleMode(session, session.catalogHardwareMode)
            } else {
                startBleCatalogSoftware(session)
            }
        }, 160L)
    }

    private fun startBleCatalogSoftware(session: DeviceSession) {
        var paletteIndex = 0
        var frame = 0
        var fadeStep = 0
        val runner = object : Runnable {
            override fun run() {
                if (!session.connected || !session.stripOn || session.mode != EffectMode.CATALOG ||
                    session.catalogHardwareMode >= 0) return
                val colors = session.catalogPalette
                if (colors.isEmpty()) return
                val current = colors[paletteIndex % colors.size]
                val next = colors[(paletteIndex + 1) % colors.size]
                val speed = session.catalogSpeed.coerceIn(0, 100)
                val output = when (session.catalogStyle) {
                    BleCatalogStyle.JUMP -> {
                        paletteIndex++
                        current
                    }
                    BleCatalogStyle.FADE -> {
                        val t = fadeStep / 10f
                        val eased = (1f - kotlin.math.cos(t * Math.PI.toFloat())) * .5f
                        val color = Color.rgb(
                            (Color.red(current) + (Color.red(next) - Color.red(current)) * eased).toInt().coerceIn(0, 255),
                            (Color.green(current) + (Color.green(next) - Color.green(current)) * eased).toInt().coerceIn(0, 255),
                            (Color.blue(current) + (Color.blue(next) - Color.blue(current)) * eased).toInt().coerceIn(0, 255)
                        )
                        fadeStep++
                        if (fadeStep > 10) { fadeStep = 0; paletteIndex++ }
                        color
                    }
                    BleCatalogStyle.STROBE -> {
                        val color = if (frame % 2 == 0) current else Color.BLACK
                        frame++
                        if (frame % 2 == 0) paletteIndex++
                        color
                    }
                    BleCatalogStyle.ALTERNATE -> {
                        val alternateCount = (colors.size - 1).coerceAtLeast(1)
                        val alternate = colors[1 + (paletteIndex % alternateCount)]
                        val color = if (frame % 2 == 0) colors[0] else alternate
                        frame++
                        if (frame % 2 == 0) paletteIndex++
                        color
                    }
                    BleCatalogStyle.TAIL -> {
                        val t = (frame % 16) / 15f
                        val level = if (t < .5f) t * 2f else (1f - t) * 2f
                        frame++
                        if (frame % 16 == 0) paletteIndex++
                        val k = level.coerceIn(.06f, 1f)
                        Color.rgb((Color.red(current) * k).toInt(), (Color.green(current) * k).toInt(), (Color.blue(current) * k).toInt())
                    }
                    BleCatalogStyle.CURTAIN -> {
                        val step = frame % 8
                        val levels = floatArrayOf(.06f, .16f, .34f, .62f, 1f, .62f, .34f, .16f)
                        frame++
                        if (frame % 8 == 0) paletteIndex++
                        val k = levels[step]
                        Color.rgb((Color.red(current) * k).toInt(), (Color.green(current) * k).toInt(), (Color.blue(current) * k).toInt())
                    }
                    BleCatalogStyle.RUN -> {
                        val color = if (frame % 4 == 3) Color.BLACK else current
                        frame++
                        if (frame % 4 == 0) paletteIndex++
                        color
                    }
                    BleCatalogStyle.FLUSH -> {
                        val color = if (frame % 3 == 2) Color.BLACK else current
                        frame++
                        if (frame % 3 == 0) paletteIndex++
                        color
                    }
                    BleCatalogStyle.ENERGY -> {
                        val pulse = floatArrayOf(.22f, .38f, .72f, 1f, .58f, .28f)[frame % 6]
                        frame++
                        if (frame % 6 == 0) paletteIndex++
                        Color.rgb((Color.red(current) * pulse).toInt(), (Color.green(current) * pulse).toInt(), (Color.blue(current) * pulse).toInt())
                    }
                }
                sendRGBTo(session, Color.red(output), Color.green(output), Color.blue(output))
                val delay = (230f - speed * 1.65f).toLong().coerceIn(65L, 230L)
                handler.postDelayed(this, delay)
            }
        }
        session.catalogRunner = runner
        runner.run()
    }

    private fun sendMelkMode(session: DeviceSession, mode: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x05, 0x03, mode.toByte(), 0x06, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet, session.targetId)
    }

    private fun sendMelkModeSpeed(session: DeviceSession, value: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x02, value.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet, session.targetId)
    }

    private fun sendMelkBrightness(session: DeviceSession, value: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x01, value.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        // Coalesce slider updates and respect the MELK write interval so rapid
        // dragging cannot flood the BLE write-without-response queue.
        session.pending = packet
        ensureRunners(session)
        if (!session.flushQueued) {
            session.flushQueued = true
            handler.postDelayed(session.flushRunner!!,
                (100L - (SystemClock.uptimeMillis() - session.lastWrite)).coerceAtLeast(0L))
        }
    }

    private fun sendMelkMusicProgram(session: DeviceSession, effect: Int, sensitivity: Int) {
        if (!session.isMelk || !session.connected) return
        val gatt = session.gatt ?: return
        val characteristic = session.characteristic ?: return
        val frame = byteArrayOf(
            0xF2.toByte(), 0x01, 0x05,
            effect.coerceIn(1, 8).toByte(),
            sensitivity.coerceIn(0, 100).toByte()
        )
        enqueueBridgeFrame(gatt, characteristic, frame)
        session.micEnabled = true
        session.micEffect = effect.coerceIn(1, 8)
        session.micSensitivity = sensitivity.coerceIn(0, 100)
    }

    private fun sendMelkMusicSensitivity(session: DeviceSession, sensitivity: Int) {
        if (!session.isMelk || !session.connected) return
        val gatt = session.gatt ?: return
        val characteristic = session.characteristic ?: return
        val frame = byteArrayOf(
            0xF2.toByte(), 0x01, 0x06,
            sensitivity.coerceIn(0, 100).toByte()
        )
        enqueueBridgeFrame(gatt, characteristic, frame)
        session.micSensitivity = sensitivity.coerceIn(0, 100)
    }

    private fun sendMelkMicEnabled(session: DeviceSession, enabled: Boolean) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x07, if (enabled) 0x01 else 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet, session.targetId)
        session.micEnabled = enabled
    }

    private fun sendMelkMicEffect(session: DeviceSession, effect: Int) {
        if (!session.isMelk || !session.connected) return
        val encoded = (0x80 + (effect.coerceIn(1, 8) - 1)).toByte()
        val packet = byteArrayOf(0x7E, 0x05, 0x03, encoded, 0x04,
            0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet, session.targetId)
        session.micEffect = effect.coerceIn(1, 8)
    }

    private fun sendMelkMicSensitivity(session: DeviceSession, sensitivity: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x06, sensitivity.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet, session.targetId)
        session.micSensitivity = sensitivity.coerceIn(0, 100)
    }

    private fun scheduleMelkModeSpeed(session: DeviceSession) {
        if (!session.isMelk || session.mode != EffectMode.FILL) return
        session.fillSpeedRunner?.let { handler.removeCallbacks(it) }
        val runner = Runnable {
            if (session.connected && session.stripOn && session.mode == EffectMode.FILL) {
                sendMelkModeSpeed(session, session.fillSpeed)
            }
        }
        session.fillSpeedRunner = runner
        handler.postDelayed(runner, 120L)
    }

    private fun sendEffectCommand(d: DeviceSession, mode: EffectMode, start: Boolean) {
        val gatt = d.gatt ?: return
        val characteristic = d.characteristic ?: return
        val target = d.targetId
        val command = when (mode) {
            EffectMode.FIRE -> 0x03
            EffectMode.RAINBOW -> 0x04
            else -> return
        }
        val frame = if (!start) {
            byteArrayOf(0xF2.toByte(), target.toByte(), command.toByte(), 0x00)
        } else if (mode == EffectMode.FIRE) {
            byteArrayOf(
                0xF2.toByte(), target.toByte(), 0x03, 0x01,
                d.red.coerceIn(0, 255).toByte(),
                d.green.coerceIn(0, 255).toByte(),
                d.blue.coerceIn(0, 255).toByte(),
                d.brightness.coerceIn(0, 100).toByte(),
                d.speed.coerceIn(11, 20).toByte(),
                d.spread.coerceIn(0, 100).toByte()
            )
        } else {
            byteArrayOf(
                0xF2.toByte(), target.toByte(), 0x04, 0x01,
                d.rainbowSpeed.coerceIn(1, 20).toByte(),
                d.brightness.coerceIn(0, 100).toByte()
            )
        }

        // Coalesce only updates of the same effect/target. A stop command must
        // remain ordered before a newly selected, different effect.
        val iterator = bridgeWriteQueue.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.size >= 3 &&
                queued[0] == 0xF2.toByte() &&
                queued[1] == target.toByte() &&
                queued[2] == command.toByte()
            ) iterator.remove()
        }
        enqueueBridgeFrame(gatt, characteristic, frame)
    }

    private fun sendRGBTo(d:DeviceSession,r:Int,g:Int,b:Int) {
        if(!d.connected||d.characteristic==null||d.gatt==null)return

        // Manual RGB color + brightness are sent as one compact F2 command.
        // Software effects and MELK keep using the existing F1 strip packets.
        if (!d.isMelk && d.mode == EffectMode.NONE) {
            val gatt = d.gatt ?: return
            val characteristic = d.characteristic ?: return
            val frame = byteArrayOf(
                0xF2.toByte(), d.targetId.toByte(), 0x01, 0x04,
                r.coerceIn(0, 255).toByte(),
                g.coerceIn(0, 255).toByte(),
                b.coerceIn(0, 255).toByte(),
                d.brightness.coerceIn(0, 100).toByte()
            )
            enqueueBridgeFrame(gatt, characteristic, frame, coalesceColor = true)
            return
        }

        val factor=if (d.isMelk) 1.0 else d.brightness/100.0
        val rr=(r*factor).toInt().coerceIn(0,255);val gg=(g*factor).toInt().coerceIn(0,255);val bb=(b*factor).toInt().coerceIn(0,255)
        d.pending=if(d.isMelk) byteArrayOf(0x7E,0,5,3,rr.toByte(),gg.toByte(),bb.toByte(),0,0xEF.toByte())
            else byteArrayOf(0x7E,7,5,3,rr.toByte(),gg.toByte(),bb.toByte(),
                (if (d.mode == EffectMode.CATALOG) 0x20 else 0x10).toByte(),0xEF.toByte())
        ensureRunners(d)
        if(!d.flushQueued) {
            d.flushQueued=true
            val interval=if(d.isMelk && d.mode==EffectMode.RAINBOW)50L else if(d.isMelk)100L else if(d.mode==EffectMode.RAINBOW)30L else 50L
            handler.postDelayed(d.flushRunner!!,(interval-(SystemClock.uptimeMillis()-d.lastWrite)).coerceAtLeast(0L))
        }
    }

    private fun flushPendingColor(d:DeviceSession) {
        d.flushQueued=false
        val packet=d.pending ?: return;d.pending=null
        val g=d.gatt ?: return;val ch=d.characteristic ?: return
        // Color-wheel updates are replaceable: keep only the newest unsent
        // color for this strip instead of letting stale colors build up.
        writeBlePacketTo(g, ch, packet, d.targetId, coalesceColor = true)
        d.lastWrite=SystemClock.uptimeMillis()
        if(d.pending!=null&&!d.flushQueued){d.flushQueued=true;handler.postDelayed(d.flushRunner!!,if(d.isMelk && d.mode==EffectMode.RAINBOW)50L else if(d.isMelk)100L else 35L)}
    }

    private fun sendCurrentColor() { selectedSessions().forEach{sendRGBTo(it,it.red,it.green,it.blue)} }
    private fun sendRGB(r:Int,g:Int,b:Int) { selectedSessions().forEach{sendRGBTo(it,r,g,b)} }

    // All strip sessions share one physical GATT link. The Arduino frame is
    // F1 + target (01=MELK, 02-04=RGB) + the unchanged nine-byte packet.
    private fun writeBlePacketTo(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
        targetId: Int,
        coalesceColor: Boolean = false
    ) {
        if (g !== bridgeGatt || char.uuid != writeUUID || packet.size != 9) return
        val frame = ByteArray(11)
        frame[0] = 0xF1.toByte()
        frame[1] = targetId.toByte()
        System.arraycopy(packet, 0, frame, 2, 9)

        enqueueBridgeFrame(g, char, frame, coalesceColor)
    }

    // Shared serialized queue for both legacy F1 and compact F2 bridge frames.
    private fun enqueueBridgeFrame(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        frame: ByteArray,
        coalesceColor: Boolean = false,
        replaceTargetQueue: Boolean = false
    ) {
        if (g !== bridgeGatt || char.uuid != writeUUID) return

        if (replaceTargetQueue) {
            // Power-state changes supersede every queued command for this
            // target, including stale effect and color frames.
            val target = frame[1]
            val iterator = bridgeWriteQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.size >= 2 &&
                    (queued[0] == 0xF1.toByte() || queued[0] == 0xF2.toByte()) &&
                    queued[1] == target
                ) iterator.remove()
            }
        }

        // RGB brightness in hardware catalogue mode is a separate legacy F1
        // command. Keep only its newest unsent value, just like manual RGB.
        if (isBridgeRgbBrightnessFrame(frame)) {
            val target = frame[1]
            val iterator = bridgeWriteQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (isBridgeRgbBrightnessFrame(queued) && queued[1] == target) {
                    iterator.remove()
                }
            }
        }

        if (coalesceColor) {
            // Replace a pending color for this target IN ITS CURRENT QUEUE
            // POSITION. Removing it and appending it to the tail lets an
            // earlier target continuously jump ahead during color-wheel
            // dragging, starving later targets until ACTION_UP.
            val target = frame[1]
            val updatedQueue = java.util.ArrayDeque<ByteArray>()
            var replaced = false
            while (bridgeWriteQueue.isNotEmpty()) {
                val queued = bridgeWriteQueue.removeFirst()
                val legacyColor = queued.size == 11 &&
                    queued[0] == 0xF1.toByte() &&
                    queued[1] == target &&
                    queued[4] == 0x05.toByte() &&
                    queued[5] == 0x03.toByte()
                val compactColor = queued.size == 8 &&
                    queued[0] == 0xF2.toByte() &&
                    queued[1] == target &&
                    queued[2] == 0x01.toByte()
                if (legacyColor || compactColor) {
                    if (!replaced) {
                        updatedQueue.addLast(frame)
                        replaced = true
                    }
                } else {
                    updatedQueue.addLast(queued)
                }
            }
            bridgeWriteQueue.addAll(updatedQueue)
            if (!replaced) bridgeWriteQueue.addLast(frame)
        } else {
            bridgeWriteQueue.addLast(frame)
        }
        scheduleBridgeWrite()
    }

    private fun isBridgeRgbBrightnessFrame(frame: ByteArray): Boolean =
        frame.size == 11 &&
            frame[0] == 0xF1.toByte() &&
            (frame[1].toInt() and 0xFF) in 0x02..0x04 &&
            frame[2] == 0x7E.toByte() &&
            frame[3] == 0x04.toByte() &&
            frame[4] == 0x01.toByte()

    private fun isBridgeRgbColorFrame(frame: ByteArray): Boolean =
        frame.size == 8 &&
            frame[0] == 0xF2.toByte() &&
            (frame[1].toInt() and 0xFF) in 0x02..0x04 &&
            frame[2] == 0x01.toByte() &&
            frame[3] == 0x04.toByte()

    private fun isBridgeRgbRealtimeFrame(frame: ByteArray): Boolean =
        isBridgeRgbColorFrame(frame) || isBridgeRgbBrightnessFrame(frame)

    // Keep a single scheduled drain. New color-wheel events may replace the
    // queued color, but cannot pull the next BLE write forward and bypass pacing.
    private fun scheduleBridgeWrite(delayMs: Long = 0L) {
        if (bridgeWriteScheduled || bridgeWriteInFlight || bridgeWriteQueue.isEmpty()) return
        if (bridgeWriteRunner == null) {
            bridgeWriteRunner = Runnable {
                bridgeWriteScheduled = false
                drainBridgeWriteQueue()
            }
        }
        bridgeWriteScheduled = true
        handler.postDelayed(bridgeWriteRunner!!, delayMs.coerceAtLeast(0L))
    }

    private fun drainBridgeWriteQueue() {
        bridgeWriteScheduled = false
        val activeGatt = bridgeGatt ?: return
        val rx = bridgeRxCharacteristic ?: return
        if (!bridgeWriteReady || bridgeWriteInFlight || bridgeWriteQueue.isEmpty()) return

        val wait = (bridgeNextWriteAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (wait > 0L) {
            scheduleBridgeWrite(wait)
            return
        }

        val frame = bridgeWriteQueue.peekFirst() ?: return
        val writeType = if (bridgeWriteWithoutResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeCharacteristic(rx, frame, writeType)
            } else {
                @Suppress("DEPRECATION")
                rx.writeType = writeType
                @Suppress("DEPRECATION")
                rx.value = frame
                @Suppress("DEPRECATION")
                if (activeGatt.writeCharacteristic(rx)) BluetoothGatt.GATT_SUCCESS
                else BluetoothGatt.GATT_FAILURE
            }
            if (result == BluetoothGatt.GATT_SUCCESS) {
                bridgeWriteQueue.removeFirst()
                lastWriteAt = SystemClock.uptimeMillis()

                // Manual RGB color and hardware-mode brightness share one
                // 30 updates/second ceiling. Other commands keep the 10 ms gap.
                val pacingDelay = if (isBridgeRgbRealtimeFrame(frame)) 16L else 10L
                bridgeNextWriteAt = SystemClock.uptimeMillis() + pacingDelay

                if (bridgeWriteWithoutResponse) {
                    // WRITE_NO_RESPONSE has no completion callback: schedule the
                    // next packet explicitly, without allowing enqueue() to bypass it.
                    bridgeWriteInFlight = false
                    scheduleBridgeWrite(pacingDelay)
                } else {
                    bridgeWriteInFlight = true
                }
            } else {
                bridgeWriteQueue.removeFirst()
                runOnUiThread { status.text = "BLE-міст не прийняв запис (код $result)" }
                scheduleBridgeWrite(40L)
            }
        } catch (_: SecurityException) {
            bridgeWriteQueue.clear()
            runOnUiThread { status.text = "Помилка дозволу на запис Bluetooth" }
        } catch (e: Exception) {
            bridgeWriteQueue.removeFirst()
            runOnUiThread { status.text = "Помилка BLE-запису: ${e.message}" }
            scheduleBridgeWrite(40L)
        }
    }

    private fun pauseActiveEffectForPowerOff(d: DeviceSession) {
        if (d.mode == EffectMode.FIRE || d.mode == EffectMode.RAINBOW) {
            sendEffectCommand(d, d.mode, false)
        }
        d.fillSpeedRunner?.let { handler.removeCallbacks(it) }
        d.catalogRunner?.let { handler.removeCallbacks(it) }
        d.catalogRunner = null
        // Keep mode and its parameters so the same program can be restored on power-on.
    }

    private fun resumeActiveEffectAfterPowerOn(d: DeviceSession) {
        when (d.mode) {
            EffectMode.MIC -> {
                if (d.isMelk) handler.postDelayed({
                    if (d.connected && d.stripOn && d.mode == EffectMode.MIC) {
                        sendMelkMusicProgram(d, d.micEffect, d.micSensitivity)
                    }
                }, 140L)
            }
            EffectMode.CATALOG -> {
                handler.postDelayed({
                    if (!d.connected || !d.stripOn || d.mode != EffectMode.CATALOG) return@postDelayed
                    if (d.isMelk) {
                        if (d.catalogEffectId >= 0) {
                            sendMelkMode(d, d.catalogEffectId)
                            handler.postDelayed({
                                if (d.connected && d.stripOn && d.mode == EffectMode.CATALOG) {
                                    sendMelkModeSpeed(d, d.catalogSpeed)
                                }
                            }, 100L)
                        }
                    } else if (d.catalogHardwareMode >= 0) {
                        sendBleBrightness(d, d.brightness)
                        sendBleModeSpeed(d, d.catalogSpeed)
                        sendBleMode(d, d.catalogHardwareMode)
                    } else {
                        startBleCatalogSoftware(d)
                    }
                }, 160L)
            }
            EffectMode.FIRE, EffectMode.RAINBOW -> sendEffectCommand(d, d.mode, true)
            EffectMode.NONE, EffectMode.FILL -> sendRGBTo(d, d.red, d.green, d.blue)
        }
    }

    private fun stopActiveEffect(d:DeviceSession) {
        if (d.mode == EffectMode.FIRE || d.mode == EffectMode.RAINBOW) {
            sendEffectCommand(d, d.mode, false)
        }
        d.fillSpeedRunner?.let{handler.removeCallbacks(it)}
        d.catalogRunner?.let{handler.removeCallbacks(it)}
        d.catalogRunner = null
        d.catalogEffectId = -1
        d.catalogHardwareMode = -1
        d.catalogPalette = intArrayOf()
        // Music is stopped by the bridge when the next program/color command arrives.
        // Do not send a separate phone-side MELK microphone-off packet.
        d.micEnabled = false
        d.mode=EffectMode.NONE
    }
    private fun stopActiveEffect() { selectedSessions().forEach{stopActiveEffect(it)};refreshEffectButtons() }

    private fun toggleStrip() {
        val targets=selectedSessions()
        if(targets.isEmpty()){Toast.makeText(this,"Спочатку підключи пристрій",Toast.LENGTH_SHORT).show();return}
        val turnOff=targets.all{it.stripStateKnown&&it.stripOn}
        targets.forEach { d ->
            if (turnOff) {
                pauseActiveEffectForPowerOff(d)
                d.stripOn = false
                d.stripStateKnown = true
                d.pending = null
                d.flushQueued = false
                d.flushRunner?.let { handler.removeCallbacks(it) }
                sendPowerCommand(d, false)
            } else {
                d.stripOn = true
                d.stripStateKnown = true
                sendPowerCommand(d, true)
                handler.postDelayed({
                    if (d.connected && d.stripOn) resumeActiveEffectAfterPowerOn(d)
                }, 180L)
            }
        }
        activateSelectedSession()
    }

    private fun refreshEffectButtons() {
        val ds=selectedSessions();val modes=ds.map{it.mode}.distinct()
        if(::fireButton.isInitialized) fireButton.text=if(modes.size!=1||ds.isEmpty())"Змішаний стан" else if(modes[0]==EffectMode.FIRE)"Вимкнути вогонь" else "Увімкнути вогонь"
        if(::rainbowButton.isInitialized) rainbowButton.text=if(modes.size!=1||ds.isEmpty())"Змішаний стан" else if(modes[0]==EffectMode.RAINBOW)"Вимкнути веселку" else "Увімкнути веселку"
    }

    private fun toggleFire() {
        val ds=selectedSessions();if(ds.isEmpty()){Toast.makeText(this,"Спочатку підключи пристрій",Toast.LENGTH_SHORT).show();return}
        val stop=ds.all{it.mode==EffectMode.FIRE}
        ds.forEach{d->
            if(stop){stopActiveEffect(d);sendRGBTo(d,d.red,d.green,d.blue)}
            else{
                stopActiveEffect(d);d.mode=EffectMode.FIRE
                if(!d.stripOn){d.stripOn=true;d.stripStateKnown=true;sendPowerCommand(d,true)}
                sendEffectCommand(d, EffectMode.FIRE, true)
            }
        }
        refreshEffectButtons();activateSelectedSession()
    }

    private fun toggleRainbow() {
        val ds=selectedSessions();if(ds.isEmpty()){Toast.makeText(this,"Спочатку підключи пристрій",Toast.LENGTH_SHORT).show();return}
        val stop=ds.all{it.mode==EffectMode.RAINBOW}
        ds.forEach{d->
            if(stop){stopActiveEffect(d);sendRGBTo(d,d.red,d.green,d.blue)}
            else{
                stopActiveEffect(d);d.mode=EffectMode.RAINBOW
                if(!d.stripOn){d.stripOn=true;d.stripStateKnown=true;sendPowerCommand(d,true)}
                sendEffectCommand(d, EffectMode.RAINBOW, true)
            }
        }
        refreshEffectButtons();activateSelectedSession()
    }

    private fun stopFireForManual() {
        selectedSessions().filter { it.mode == EffectMode.FIRE }.forEach { stopActiveEffect(it) }
        refreshEffectButtons()
    }

    private fun updatePreview() {
        preview.setTextColor(Color.rgb(red, green, blue))
        preview.text = "● RGB: $red, $green, $blue"
    }

    private fun closeGattConnection() {
        handler.removeCallbacks(powerStateTimeout)
        sessions.values.forEach { d ->
            // Closing the phone-to-bridge link must not alter the remembered
            // state of the physical strip or clear its selected effect.
            d.gatt=null;d.characteristic=null;d.notifyCharacteristic=null;d.connected=false
            clearBleWriteQueue(d)
        }
        bridgeWriteQueue.clear(); bridgeWriteInFlight=false; bridgeWriteReady=false; bridgeWriteWithoutResponse=false
        bridgeWriteScheduled=false; bridgeNextWriteAt=0L
        bridgeWriteRunner?.let { handler.removeCallbacks(it) }
        bridgeWriteRunner=null
        bridgeRxCharacteristic=null
        bridgeStatusCharacteristic=null
        val oldBridge=bridgeGatt
        if (oldBridge != null) {
            bridgeClosing = true
            bridgeCloseTimeout?.let { handler.removeCallbacks(it) }
            val fallbackClose = Runnable {
                if (bridgeGatt === oldBridge) {
                    bridgeGatt = null
                    bridgeClosing = false
                    bridgeCloseTimeout = null
                    try { oldBridge.close() } catch (_: Exception) {}
                }
            }
            bridgeCloseTimeout = fallbackClose
            try {
                oldBridge.disconnect()
                // Android normally reports STATE_DISCONNECTED; this is a
                // fallback for devices that fail to deliver the callback.
                handler.postDelayed(fallbackClose, 1500L)
            } catch (_: Exception) {
                handler.removeCallbacks(fallbackClose)
                bridgeCloseTimeout = null
                bridgeGatt = null
                bridgeClosing = false
                try { oldBridge.close() } catch (_: Exception) {}
            }
        } else {
            bridgeClosing = false
            bridgeCloseTimeout?.let { handler.removeCallbacks(it) }
            bridgeCloseTimeout = null
        }
        gatt=null;connected=false;characteristic=null;notifyCharacteristic=null
    }

    private fun disconnect() {
        stopScan();closeGattConnection();selectedDeviceAddresses.clear();selectedDeviceAddress=""
        status.text="Статус: не підключено";refreshDevicePicker();refreshSelectionDisplay?.invoke();bulbButton.setLit(null)
        fireButton.text="Увімкнути вогонь";rainbowButton.text="Увімкнути веселку"
    }

    override fun onDestroy() {
        // Release the Arduino bridge when the Activity is closed.
        stopScan()
        handler.removeCallbacks(powerStateTimeout)
        closeGattConnection()
        try { stopService(android.content.Intent(this, KeepAliveService::class.java)) } catch (_: Exception) {}
        super.onDestroy()
    }
}

/** Square power toggle with a filled/outline bulb state. */
private class BulbToggleView(
    context: Context,
    private val onToggle: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lit: Boolean? = null
    private val accent = Color.rgb(255, 142, 105)
    private val dim = Color.rgb(151, 160, 178)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Увімкнути або вимкнути гірлянду"
        setOnClickListener { onToggle() }
    }

    fun setLit(value: Boolean?) {
        lit = value
        contentDescription = when (value) {
            true -> "Гірлянда увімкнена"
            false -> "Гірлянда вимкнена"
            null -> "Стан гірлянди невідомий"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val side = minOf(w, h)
        if (side <= 0f) return

        val left = (w - side) / 2f
        val top = (h - side) / 2f
        val radius = 12f * density
        paint.style = Paint.Style.FILL
        paint.color = when (lit) {
            true -> Color.rgb(55, 39, 39)
            false -> Color.rgb(29, 34, 46)
            null -> Color.rgb(34, 35, 43)
        }
        canvas.drawRoundRect(left, top, left + side, top + side, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = when (lit) {
            true -> Color.rgb(111, 68, 60)
            false -> Color.rgb(61, 70, 88)
            null -> Color.rgb(91, 83, 70)
        }
        canvas.drawRoundRect(left, top, left + side, top + side, radius, radius, paint)

        // Compact, symmetrical lamp glyph. All strokes stay inside a 24dp
        // square, leaving a safe margin so the lower base cannot be clipped.
        val cx = w / 2f
        val cy = h / 2f
        val d = density
        val iconColor = when (lit) {
            true -> accent
            false -> dim
            null -> Color.rgb(177, 157, 126)
        }
        paint.color = iconColor
        paint.strokeWidth = 1.8f * d
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val globe = android.graphics.Path().apply {
            moveTo(cx - 6.5f * d, cy - 1.5f * d)
            cubicTo(cx - 6.5f * d, cy - 6.2f * d, cx - 3.5f * d, cy - 9f * d, cx, cy - 9f * d)
            cubicTo(cx + 3.5f * d, cy - 9f * d, cx + 6.5f * d, cy - 6.2f * d, cx + 6.5f * d, cy - 1.5f * d)
            cubicTo(cx + 6.5f * d, cy + 1.6f * d, cx + 4f * d, cy + 3.5f * d, cx + 3f * d, cy + 5f * d)
            lineTo(cx - 3f * d, cy + 5f * d)
            cubicTo(cx - 4f * d, cy + 3.5f * d, cx - 6.5f * d, cy + 1.6f * d, cx - 6.5f * d, cy - 1.5f * d)
            close()
        }
        paint.style = if (lit == true) Paint.Style.FILL else Paint.Style.STROKE
        if (lit == true) {
            paint.setShadowLayer(3f * d, 0f, 0f, 0x55FF8E69)
            canvas.drawPath(globe, paint)
            paint.clearShadowLayer()
        } else {
            canvas.drawPath(globe, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * d
        canvas.drawLine(cx - 3f * d, cy + 7f * d, cx + 3f * d, cy + 7f * d, paint)
        canvas.drawLine(cx - 2f * d, cy + 9.5f * d, cx + 2f * d, cy + 9.5f * d, paint)
        canvas.drawLine(cx, cy - 12f * d, cx, cy - 10.5f * d, paint)
        canvas.drawLine(cx - 10f * d, cy - 4f * d, cx - 8.5f * d, cy - 3f * d, paint)
        canvas.drawLine(cx + 10f * d, cy - 4f * d, cx + 8.5f * d, cy - 3f * d, paint)
        paint.style = Paint.Style.FILL
    }
}

/** Minimal monochrome flame glyph used by the bottom navigation. */
private class FlameGlyphView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val save = canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(side / 24f, side / 24f)

        val outer = android.graphics.Path().apply {
            moveTo(12f, 1.5f)
            cubicTo(13.2f, 5.1f, 18.8f, 7.1f, 18.8f, 13.7f)
            cubicTo(18.8f, 18.8f, 15.8f, 22f, 11.8f, 22f)
            cubicTo(7.2f, 22f, 4.5f, 18.8f, 4.5f, 14.4f)
            cubicTo(4.5f, 10.7f, 6.5f, 8.1f, 8.5f, 6.2f)
            cubicTo(8.3f, 9.5f, 10.1f, 10.4f, 10.8f, 11.2f)
            cubicTo(12.2f, 8.2f, 12.8f, 5.4f, 12f, 1.5f)
            close()
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawPath(outer, paint)

        val inner = android.graphics.Path().apply {
            moveTo(12.1f, 12.1f)
            cubicTo(13.8f, 14.1f, 15.7f, 15.4f, 15.7f, 17.3f)
            cubicTo(15.7f, 19.4f, 14.1f, 20.7f, 12f, 20.7f)
            cubicTo(9.8f, 20.7f, 8.2f, 19.2f, 8.2f, 17.1f)
            cubicTo(8.2f, 15.5f, 9.2f, 14.2f, 10.4f, 13.1f)
            cubicTo(10.6f, 14.5f, 11.3f, 14.9f, 11.7f, 15.2f)
            cubicTo(12.2f, 14.2f, 12.3f, 13.1f, 12.1f, 12.1f)
            close()
        }
        paint.color = Color.rgb(48, 39, 39)
        canvas.drawPath(inner, paint)
        canvas.restoreToCount(save)
    }
}

/** Circular hue/saturation picker. Touch position directly determines the RGB value. */
private class ColorWheelView(
    context: Context,
    private val onColorSelected: (Int) -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wheelBitmap: Bitmap? = null
    private var selectedHue = 24f
    private var selectedAngle = 24f
    private var selectedSaturation = 0.95f
    private val density = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (280 * density).toInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val side = minOf(width, height)
        setMeasuredDimension(side, side)
    }

    private fun makeWheel(side: Int) {
        if (side <= 0) return
        val pixels = IntArray(side * side)
        val center = (side - 1) / 2f
        val radius = center
        val hsv = floatArrayOf(0f, 0f, 1f)
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dx = x - center
                val dy = center - y
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                val index = y * side + x
                if (distance <= radius) {
                    val angle = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
                    hsv[0] = angle
                    hsv[1] = (distance / radius).coerceIn(0f, 1f)
                    pixels[index] = Color.HSVToColor(hsv)
                } else {
                    pixels[index] = Color.TRANSPARENT
                }
            }
        }
        wheelBitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        if (wheelBitmap?.width != side.toInt()) makeWheel(side.toInt())
        wheelBitmap?.let {
            canvas.drawBitmap(it, null, android.graphics.RectF(0f, 0f, side, side), paint)
        }
        val radius = side / 2f
        val markerRadius = selectedSaturation * radius
        val markerAngle = Math.toRadians(selectedAngle.toDouble())
        val cx = radius + kotlin.math.cos(markerAngle).toFloat() * markerRadius
        val cy = radius - kotlin.math.sin(markerAngle).toFloat() * markerRadius
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.BLACK
        canvas.drawCircle(cx, cy, 10f * density, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 2f * density
        canvas.drawCircle(cx, cy, 7f * density, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateSelection(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun updateSelection(touchX: Float, touchY: Float) {
        val radius = minOf(width, height) / 2f
        if (radius <= 0f) return
        val dx = touchX - radius
        val dy = radius - touchY
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        selectedSaturation = (distance / radius).coerceIn(0f, 1f)
        val angle = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
        selectedAngle = angle
        selectedHue = angle
        onColorSelected(Color.HSVToColor(floatArrayOf(selectedHue, selectedSaturation, 1f)))
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}


/** Compact custom slider matching the dark Firelight interface. */
private class ModernSliderView(
    context: Context,
    initial: Int,
    private val maximum: Int,
    private val onChanged: (Int) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private var value = initial.coerceIn(0, maximum)
    private val thumbRadius = 8f * density

    init {
        isClickable = true
        contentDescription = "Slider"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = thumbRadius
        val right = width - thumbRadius
        val cy = height / 2f
        val fraction = if (maximum == 0) 0f else value.toFloat() / maximum
        val x = left + (right - left) * fraction
        track.color = Color.rgb(54, 62, 79)
        track.strokeWidth = 5f * density
        track.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(left, cy, right, cy, track)
        track.color = Color.rgb(255, 111, 66)
        canvas.drawLine(left, cy, x, cy, track)
        track.style = Paint.Style.FILL
        track.setShadowLayer(5f * density, 0f, 2f * density, 0x55300000)
        canvas.drawCircle(x, cy, thumbRadius, track)
        track.clearShadowLayer()
        track.color = Color.WHITE
        canvas.drawCircle(x, cy, 3f * density, track)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x)
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun updateFromTouch(touchX: Float) {
        val left = thumbRadius
        val right = (width - thumbRadius).coerceAtLeast(left + 1f)
        val fraction = ((touchX - left) / (right - left)).coerceIn(0f, 1f)
        val next = (fraction * maximum).toInt().coerceIn(0, maximum)
        if (next != value) {
            value = next
            onChanged(value)
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}