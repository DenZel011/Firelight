
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
import android.widget.*
import kotlin.random.Random
import java.util.UUID

class MainActivity : Activity() {

    private val serviceUUID =
        UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    private val writeUUID =
        UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB")

    private val handler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private data class DeviceSession(
        val address: String, val isMelk: Boolean,
        var gatt: BluetoothGatt? = null, var characteristic: BluetoothGattCharacteristic? = null,
        var notifyCharacteristic: BluetoothGattCharacteristic? = null,
        var connected: Boolean = false, var stripOn: Boolean = false, var stripStateKnown: Boolean = false,
        var mode: EffectMode = EffectMode.NONE,
        var micEnabled: Boolean = false, var micEffect: Int = 1, var micSensitivity: Int = 50,
        var red: Int = 255, var green: Int = 92, var blue: Int = 12, var brightness: Int = 100,
        var speed: Int = 15, var spread: Int = 0, var rainbowSpeed: Int = 15,
        var fillSpeed: Int = 50, var fillSpeedRunner: Runnable? = null,
        var phase: Float = 0f, var lastTick: Long = 0L,
        var level: Float = .76f, var targetLevel: Float = .76f, var hueShift: Float = 0f,
        var targetHue: Float = 0f, var saturation: Float = .94f, var targetSaturation: Float = .94f,
        var nextChange: Long = 0L, var fireTick: Long = 0L, var flare: Float = 0f,
        var smoothing: Float = 78f, var pending: ByteArray? = null, var flushQueued: Boolean = false,
        var lastWrite: Long = 0L, var fireRunner: Runnable? = null, var rainbowRunner: Runnable? = null,
        var flushRunner: Runnable? = null
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
    private enum class EffectMode { NONE, FIRE, RAINBOW, FILL, SCENE, MIC }
    private var activeSceneId = 1
    private var activeSceneIsPreset = true
    private var sceneStartedAt = 0L
    private var sceneSpeedPercent = 50
    private var sceneRunner: Runnable? = null
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
    private var stripOn = false
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
    private var isMelkDevice = false
    private val powerNotifyUUID = UUID.fromString("0000FFF4-0000-1000-8000-00805F9B34FB")
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

    private fun resetFireMotion(session: DeviceSession) {
        session.level=.76f; session.targetLevel=.76f; session.hueShift=0f; session.targetHue=0f
        session.saturation=1f; session.targetSaturation=1f; session.nextChange=0L
        session.fireTick=0L; session.flare=0f; session.smoothing=78f
    }

    private fun ensureRunners(d: DeviceSession) {
        if (d.flushRunner == null) d.flushRunner = Runnable { flushPendingColor(d) }
        if (d.fireRunner == null) d.fireRunner = object : Runnable {
            override fun run() {
                if (d.mode != EffectMode.FIRE || !d.connected || !d.stripOn) return
                val now=SystemClock.uptimeMillis()
                val elapsed=if(d.fireTick==0L) 80L else (now-d.fireTick).coerceIn(1L,250L)
                d.fireTick=now
                if(now>=d.nextChange) {
                    val roll=Random.nextFloat()
                    d.targetLevel=if(d.isMelk) when { roll<.12f -> Random.nextFloat()*.34f+.66f; roll<.48f -> Random.nextFloat()*.24f+.08f; else -> Random.nextFloat()*.38f+.30f }
                        else when { roll<.13f -> Random.nextFloat()*.42f+.56f; roll<.34f -> Random.nextFloat()*.38f+.30f; else -> Random.nextFloat()*.38f+.48f }
                    val spread=d.spread.coerceIn(0,100)/100f
                    d.targetHue=if(spread==0f) 0f else Random.nextFloat()*360f*spread-180f*spread
                    d.targetSaturation=1f-Random.nextFloat()*.42f*spread
                    d.smoothing=if(d.isMelk) { if(Random.nextFloat()<.24f) Random.nextFloat()*140f+170f else Random.nextFloat()*42f+42f }
                        else if(Random.nextFloat()<.32f) Random.nextFloat()*250f+260f else Random.nextFloat()*48f+58f
                    if(roll<.13f) d.flare=if(d.isMelk) Random.nextFloat()*.30f+.16f else Random.nextFloat()*.24f+.12f
                    val interval=330L-(d.speed.coerceIn(11,20)-11)*28L
                    d.nextChange=now+Random.nextLong((interval*.55f).toLong().coerceAtLeast(45L),(interval*1.15f).toLong().coerceAtLeast(70L))
                }
                val blend=1f-kotlin.math.exp(-elapsed.toFloat()/d.smoothing)
                d.level+=(d.targetLevel-d.level)*blend; d.hueShift+=(d.targetHue-d.hueShift)*(blend*.9f)
                d.saturation+=(d.targetSaturation-d.saturation)*blend; d.flare*=kotlin.math.exp(-elapsed.toFloat()/150f)
                val raw=(d.level+kotlin.math.sin(now/510.0).toFloat()*.045f+kotlin.math.sin(now/91.0).toFloat()*.035f+d.flare)
                    .coerceIn(if(d.isMelk).24f else .22f,1f)
                val value=if(d.isMelk) Math.pow(raw.toDouble(),1.18).toFloat().coerceIn(.22f,1f) else raw
                val hsv=FloatArray(3); Color.RGBToHSV(d.red,d.green,d.blue,hsv)
                val rgb=Color.HSVToColor(floatArrayOf((hsv[0]+d.hueShift+360f)%360f,(hsv[1]*d.saturation).coerceIn(0f,1f),value))
                sendRGBTo(d,Color.red(rgb),Color.green(rgb),Color.blue(rgb))
                handler.postDelayed(this,(132L-(d.speed.coerceIn(11,20)-11)*8L).coerceIn(52L,132L))
            }
        }
        if (d.rainbowRunner == null) d.rainbowRunner = object : Runnable {
            override fun run() {
                if(d.mode!=EffectMode.RAINBOW || !d.connected || !d.stripOn) return
                val now=SystemClock.uptimeMillis()
                val elapsed=if(d.lastTick==0L) 50L else (now-d.lastTick).coerceIn(1L,180L); d.lastTick=now
                val sp=d.rainbowSpeed.coerceIn(1,20); val slow=1f+.30f*((20-sp)/19f)
                val cycle=((12500L-(sp-1)*520L).coerceAtLeast(1800L)*slow).toLong()
                val p=if(d.isMelk) floatArrayOf(0f,17.6470588f,60f,120f,180f,240f,300f)
                    else floatArrayOf(0f,2.3529413f,23.5294118f,120f,180f,240f,300f)
                val hold=(360f/7f)*.60f*(if(sp==20).75f else 1f)
                d.phase=(d.phase+elapsed.toFloat()/cycle*360f)%(360f+hold*3f)
                var rem=d.phase; var rgb=Color.HSVToColor(floatArrayOf(p[0],1f,1f))
                for(i in p.indices) {
                    val n=(i+1)%p.size; val delta=((p[n]-p[i]+540f)%360f)-180f; val seg=kotlin.math.abs(delta)
                    val a=Color.HSVToColor(floatArrayOf(p[i],1f,1f)); val b=Color.HSVToColor(floatArrayOf(p[n],1f,1f))
                    val h=if(n<=2) hold else 0f
                    if(rem<=seg) {
                        val t=(rem/seg.coerceAtLeast(.001f)).coerceIn(0f,1f); val q=(1f-kotlin.math.cos(t*Math.PI.toFloat()))*.5f
                        val r=Color.red(a)*(1-q)+Color.red(b)*q; val g=Color.green(a)*(1-q)+Color.green(b)*q; val bl=Color.blue(a)*(1-q)+Color.blue(b)*q
                        val k=255f/maxOf(r,g,bl).coerceAtLeast(1f)
                        rgb=Color.rgb((r*k).toInt().coerceIn(0,255),(g*k).toInt().coerceIn(0,255),(bl*k).toInt().coerceIn(0,255)); break
                    }
                    if(rem<=seg+h){rgb=b;break}; rem-=seg+h
                }
                sendRGBTo(d,Color.red(rgb),Color.green(rgb),Color.blue(rgb)); handler.postDelayed(this,20L)
            }
        }
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
        getSharedPreferences("firelight_devices", MODE_PRIVATE).getStringSet("addresses", null)?.let { saved ->
            managedDeviceAddresses.clear()
            managedDeviceAddresses.addAll(saved.map { it.uppercase() })
            if (managedDeviceAddresses.isEmpty()) managedDeviceAddresses.add(targetAddress.uppercase())
        }
        selectedDeviceAddresses.clear()
        selectedDeviceAddress = ""
        requestBluetoothPermissions()
        stripStateKnown = false
        stripOn = false
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
        val colorCard = card().apply {
            setPadding(dp(12), dp(2), dp(12), dp(6))
            clipChildren = false
            clipToPadding = false
        }
        val colorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val colorTitle = text("КОЛІР СВІТІННЯ", 11f, muted, true)
        colorHeader.addView(colorTitle, LinearLayout.LayoutParams(0, -2, 1f))
        bulbButton = BulbToggleView(this) { toggleStrip() }.apply {
            setLit(stripOn)
        }
        // Preserve the 40dp button and the header's total height while
        // restoring visual space above the bulb inside the existing layout.
        colorHeader.addView(bulbButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            topMargin = dp(8)
            bottomMargin = dp(-8)
            marginEnd = dp(8)
        })
        colorCard.addView(colorHeader)
        addGap(colorCard, 2)
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
            selectedSessions().forEach { it.red=red; it.green=green; it.blue=blue }
            selectedSessions().filter { it.mode == EffectMode.NONE }.forEach { sendRGBTo(it,red,green,blue) }
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

        fun addModernSlider(parent: LinearLayout, title: String, initial: Int, maxValue: Int, suffix: String, bottomGap: Int = 8, changed: (Int) -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val titleView = text(title, 13f, Color.rgb(225, 230, 240), true)
            val valueView = text("$initial$suffix", 13f, Color.WHITE, true)
            line.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(valueView)
            parent.addView(line)
            addGap(parent, 7)
            val slider = ModernSliderView(this, initial, maxValue) { value ->
                valueView.text = "$value$suffix"
                changed(value)
            }
            parent.addView(slider, LinearLayout.LayoutParams(-1, dp(26)))
            addGap(parent, bottomGap)
        }

        addModernSlider(colorCard, "Яскравість", brightness, 100, "%", 2) {
            brightness = it
            selectedSessions().forEach { d ->
                d.brightness = brightness
                if (d.isMelk) {
                    sendMelkBrightness(d, brightness)
                } else if (d.mode == EffectMode.NONE) {
                    sendRGBTo(d, d.red, d.green, d.blue)
                }
            }
        }
        root.addView(colorCard)
        addGap(root, 12)
        root.addView(pageHost)
        addModernSlider(tuningCard, "Швидкість вогню", speed - 10, 10, "/10") {
            speed = (it + 10).coerceIn(11, 20); selectedSessions().forEach { it.speed=speed }
        }
        addModernSlider(tuningCard, "Розкид кольорів вогню", fireColorSpread, 100, "%", 2) {
            fireColorSpread = it.coerceIn(0, 100); selectedSessions().forEach { d -> d.spread=fireColorSpread }
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
            rainbowSpeed = it.coerceIn(1, 20); selectedSessions().forEach { it.rainbowSpeed=rainbowSpeed }
        }
        rainbowButton = actionButton("Увімкнути веселку", true) { toggleRainbow() }
        rainbowTuningCard.addView(rainbowButton, LinearLayout.LayoutParams(-1, dp(40)))
        addGap(rainbowTuningCard, 4)
        rainbowPage.addView(rainbowTuningCard)

        val scenePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        data class ScenePreset(val id: Int, val name: String, val symbol: String, val isScene: Boolean)
        val scenePresets = listOf(
            ScenePreset(1, "Стрибки кольорів", "✦", false),
            ScenePreset(2, "Стробоскоп", "⚡", false),
            ScenePreset(3, "Дихання", "◉", false),
            ScenePreset(4, "Попередження", "⚠", false),
            ScenePreset(1, "Схід сонця", "☀", true),
            ScenePreset(2, "Захід сонця", "◒", true),
            ScenePreset(3, "День народження", "★", true),
            ScenePreset(4, "Свічка", "♨", true),
            ScenePreset(5, "Феєрверки", "✺", true),
            ScenePreset(6, "Вечірка", "♫", true),
            ScenePreset(7, "Побачення", "♥", true),
            ScenePreset(8, "Зоряне небо", "✧", true),
            ScenePreset(9, "Романтика", "♡", true),
            ScenePreset(10, "Диско", "✷", true),
            ScenePreset(11, "Веселка", "◈", true),
            ScenePreset(12, "Кіно", "▣", true),
            ScenePreset(13, "Різдво", "❄", true),
            ScenePreset(14, "Плин", "≈", true),
            ScenePreset(15, "Сон", "☾", true),
            ScenePreset(16, "Океан", "≋", true),
            ScenePreset(17, "Ліс", "♣", true),
            ScenePreset(18, "Читання", "▤", true),
            ScenePreset(19, "Робота", "⌘", true),
            ScenePreset(20, "Сяйво", "✹", true),
            ScenePreset(21, "Ніжність", "❋", true),
            ScenePreset(22, "Весілля", "♢", true),
            ScenePreset(23, "Сніг", "❅", true),
            ScenePreset(24, "Вогонь", "♨", true),
            ScenePreset(25, "Блискавка", "ϟ", true),
            ScenePreset(26, "День закоханих", "♥", true),
            ScenePreset(27, "Гелловін", "☠", true),
            ScenePreset(28, "Попередження", "⚠", true),
            ScenePreset(150, "Машина часу", "◷", true),
            ScenePreset(152, "Машина часу 2", "◴", true),
            ScenePreset(154, "Метеор", "☄", true),
            ScenePreset(155, "Метеор 2", "✦", true),
            ScenePreset(156, "Шоу феєрверків", "✺", true)
        )
        var selectedSceneIndex = 0
        var sceneSpeed = 50

        // Keep the original swipe carousel: fixed card, animated scene content,
        // and a compact horizontally scrollable row of scene shortcuts.
        val sceneCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(20))
            clipChildren = true
            clipToPadding = true
        }
        val sceneHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        sceneHeader.addView(
            text("СЦЕНИ", 11f, muted, true).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(-2, dp(40))
        )
        val sceneModeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        val sceneModeIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        sceneModeScroll.addView(sceneModeIcons)
        sceneHeader.addView(sceneModeScroll, LinearLayout.LayoutParams(0, dp(40), 1f))
        sceneCard.addView(sceneHeader, LinearLayout.LayoutParams(-1, dp(40)))

        val sceneContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            isClickable = true
            clipChildren = false
            clipToPadding = false
        }
        val sceneSymbol = text(scenePresets[0].symbol, 60f, Color.rgb(255, 142, 105), true).apply {
            gravity = android.view.Gravity.CENTER
        }
        sceneContent.addView(sceneSymbol, LinearLayout.LayoutParams(-1, dp(76)))
        addGap(sceneContent, 4)
        val sceneName = text(scenePresets[0].name, 23f, Color.WHITE, true).apply {
            gravity = android.view.Gravity.CENTER
        }
        sceneContent.addView(sceneName, LinearLayout.LayoutParams(-1, -2))
        sceneCard.addView(sceneContent, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = -dp(8) })
        scenePage.addView(sceneCard, LinearLayout.LayoutParams(-1, 0, 1f))

        addGap(sceneCard, 8)
        addModernSlider(sceneCard, "Швидкість", sceneSpeed, 100, "%", 2) { value ->
            sceneSpeed = value.coerceIn(0, 100)
            sceneSpeedPercent = sceneSpeed
            selectedSessions().filter { it.isMelk && it.connected && it.stripOn }.forEach { d ->
                sendMelkModeSpeed(d, sceneSpeed)
            }
        }

        fun applyScene(index: Int) {
            val preset = scenePresets[index]
            val targets = selectedSessions().filter { it.connected && it.characteristic != null }
            if (targets.isEmpty()) {
                Toast.makeText(this@MainActivity, "Спочатку підключи пристрій", Toast.LENGTH_SHORT).show()
                return
            }
            sceneSpeedPercent = sceneSpeed
            targets.forEach { d ->
                stopActiveEffect(d)
                if (!d.stripOn) {
                    d.stripOn = true
                    d.stripStateKnown = true
                    sendPowerCommand(d, true)
                }
                if (d.isMelk) {
                    handler.postDelayed({
                        if (d.connected && d.stripOn) {
                            if (preset.isScene) sendMelkScene(d, preset.id) else sendMelkMode(d, preset.id)
                            handler.postDelayed({
                                if (d.connected && d.stripOn) sendMelkModeSpeed(d, sceneSpeed)
                            }, 100L)
                        }
                    }, 120L)
                } else {
                    d.mode = EffectMode.SCENE
                }
            }
            if (targets.any { !it.isMelk }) startSceneAnimation(preset.id, preset.isScene)
            refreshEffectButtons()
            activateSelectedSession()
        }

        fun styleSceneIcon(view: TextView, active: Boolean) {
            view.setTextColor(if (active) Color.rgb(255, 142, 105) else Color.WHITE)
            view.background = rounded(
                if (active) Color.rgb(48, 39, 39) else Color.TRANSPARENT,
                10,
                if (active) Color.rgb(105, 62, 55) else Color.TRANSPARENT
            )
        }

        fun showScene(nextIndex: Int, swipeLeft: Boolean) {
            selectedSceneIndex = (nextIndex + scenePresets.size) % scenePresets.size
            val selected = scenePresets[selectedSceneIndex]
            val exitX = if (swipeLeft) -dp(28).toFloat() else dp(28).toFloat()
            sceneContent.animate().cancel()
            sceneContent.animate().translationX(exitX).alpha(0f).setDuration(110L).withEndAction {
                sceneSymbol.text = selected.symbol
                sceneSymbol.textSize = 60f
                sceneName.text = selected.name
                sceneContent.translationX = -exitX
                sceneContent.animate().translationX(0f).alpha(1f).setDuration(190L).start()
                for (i in 0 until sceneModeIcons.childCount) {
                    val icon = sceneModeIcons.getChildAt(i) as? TextView ?: continue
                    styleSceneIcon(icon, i == selectedSceneIndex)
                }
                applyScene(selectedSceneIndex)
            }.start()
        }

        scenePresets.forEachIndexed { index, preset ->
            val icon = text(preset.symbol, 18f, Color.WHITE, true).apply {
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (index != selectedSceneIndex) showScene(index, index > selectedSceneIndex)
                }
            }
            styleSceneIcon(icon, index == selectedSceneIndex)
            sceneModeIcons.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(1)
                marginEnd = dp(1)
            })
        }

        var sceneTouchStartX = 0f
        var sceneTouchStartY = 0f
        sceneContent.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sceneTouchStartX = event.x
                    sceneTouchStartY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - sceneTouchStartX
                    val deltaY = event.y - sceneTouchStartY
                    if (kotlin.math.abs(deltaX) >= dp(45) &&
                        kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.25f) {
                        if (deltaX < 0f) showScene(selectedSceneIndex + 1, true)
                        else showScene(selectedSceneIndex - 1, false)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
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
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
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
        musicCard.addView(musicContent, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = -dp(8) })
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
                        sendMelkMicEnabled(d, true)
                        handler.postDelayed({
                            if (d.connected && d.mode == EffectMode.MIC) {
                                sendMelkMicEffect(d, preset.id)
                                handler.postDelayed({
                                    if (d.connected && d.mode == EffectMode.MIC)
                                        sendMelkMicSensitivity(d, musicSensitivity)
                                }, 100L)
                            }
                        }, 100L)
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
            selectedSessions().filter { it.isMelk && it.mode == EffectMode.MIC }.forEach { d ->
                sendMelkMicSensitivity(d, musicSensitivity)
            }
        }

        // The bottom navigation stays visible; shared controls remain outside pageHost.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(3), dp(7), dp(3), dp(4))
            setBackground(rounded(Color.rgb(18, 21, 29), 20, Color.rgb(35, 41, 54)))
        }
        val navLabels = listOf("Вогонь", "Хвиля", "Веселка", "Сцени", "Музика", "Конструктор")
        val navGlyphs = listOf("", "≋", "◉", "✦", "♪", "⌘")
        val navItems = mutableListOf<LinearLayout>()

        fun selectTab(index: Int) {
            val showColorPicker = index != 3 && index != 4
            colorTitle.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            pickerLabel.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            wheelFrame.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            preview.visibility = if (showColorPicker) View.VISIBLE else View.GONE
            pageHost.removeAllViews()
            when (index) {
                0 -> pageHost.addView(firePage, FrameLayout.LayoutParams(-1, -1))
                2 -> pageHost.addView(rainbowPage, FrameLayout.LayoutParams(-1, -1))
                3 -> {
                    pageHost.addView(scenePage, FrameLayout.LayoutParams(-1, -1))
                    applyScene(selectedSceneIndex)
                }
                4 -> {
                    pageHost.addView(musicPage, FrameLayout.LayoutParams(-1, -1))
                    applyMusicProgram(selectedMusicIndex)
                }
            }
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
        selectTab(0)

        screenRoot = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(screenRoot)
    }

    private fun deviceDisplayName(address: String): String {
        val fallback = when {
            address.equals(melkAddress, true) -> deviceNames[1]
            address.equals(targetAddress, true) -> deviceNames[0]
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
                val added = managedDeviceAddresses.contains(address)
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
                        if (managedDeviceAddresses.contains(address)) {
                            managedDeviceAddresses.remove(address)
                            selectedDeviceAddresses.remove(address)
                            if (selectedDeviceAddress.equals(address, true)) {
                                selectedDeviceAddress = selectedDeviceAddresses.firstOrNull() ?: managedDeviceAddresses.firstOrNull().orEmpty()
                            }
                            sessions.remove(address)?.let { session ->
                                stopActiveEffect(session)
                                try { session.gatt?.disconnect(); session.gatt?.close() } catch (_: Exception) {}
                            }
                            activateSelectedSession()
                        } else {
                            managedDeviceAddresses.add(address)
                            saveManagedDevices()
                            connect(found.device)
                        }
                        saveManagedDevices()
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
            val defaultName = if (device.isMelk) deviceNames[1] else deviceNames[0]
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
            stopActiveEffect(device)
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
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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
                ) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
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
        val missingAddresses = managedDeviceAddresses.filter { address ->
            sessions[address.uppercase()]?.gatt == null
        }
        if (!discoverAll && missingAddresses.isEmpty()) {
            status.text = "Усі додані пристрої вже підключені"
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
            val filters = if (discoverAll) emptyList() else missingAddresses.map { address ->
                ScanFilter.Builder().setDeviceAddress(address).build()
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            activeScanner.startScan(filters, settings, scanCallback)
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
            val isManaged = managedDeviceAddresses.contains(address)
            if (!discoverAllScanning && !isManaged) return
            val foundName = try {
                result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: result.device.name?.takeIf { it.isNotBlank() }
            } catch (_: SecurityException) { null } ?: if (address == targetAddress.uppercase()) deviceNames[0]
                else if (address == melkAddress.uppercase()) deviceNames[1] else "BLE-пристрій"
            runOnUiThread {
                discoveredDevices[address] = DiscoveredBle(result.device, foundName, result.rssi)
                refreshDiscoveryDialog?.invoke()
                if (isManaged && sessions[address]?.gatt == null) {
                    status.text = "Знайшов ${deviceDisplayName(address)} · підключаю..."
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
        // ELK-BLEDOB drives every LED with one shared RGB value. The future
        // sequential panel will be added here as a separate device identity.
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
        if (connectedAddresses.size == 1) {
            selectedDeviceAddresses.clear()
            selectedDeviceAddresses.add(connectedAddresses.first())
        } else if (selectedDeviceAddresses.isEmpty() && connectedAddresses.isNotEmpty()) {
            selectedDeviceAddresses.add(connectedAddresses.first())
        }
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
        refreshDevicePicker()
    }

    private fun connect(device: BluetoothDevice) {
        val address = try { device.address.uppercase() } catch (_: SecurityException) { return }
        val isMelk = address == melkAddress.uppercase()
        val session = sessions.getOrPut(address) { DeviceSession(address, isMelk) }
        if (session.gatt != null) return
        if (address == selectedDeviceAddress.uppercase()) {
            status.text = "Підключення..."
            connected = false
        }
        try {
            session.gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            refreshDevicePicker()
        } catch (_: SecurityException) {
            if (address == selectedDeviceAddress.uppercase()) status.text = "Помилка дозволу Bluetooth"
        }
    }

    private fun sessionFor(g: BluetoothGatt): DeviceSession? =
        sessions.values.firstOrNull { it.gatt === g }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            val session = sessionFor(g) ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                session.connected = true
                session.stripStateKnown = false
                session.stripOn = false
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}
                runOnUiThread {
                    activateSelectedSession()
                }
                try { g.discoverServices() } catch (_: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                session.connected = false
                selectedDeviceAddresses.remove(session.address)
                if (selectedDeviceAddress.equals(session.address, true)) {
                    selectedDeviceAddress = ""
                }
                session.characteristic = null
                session.notifyCharacteristic = null
                session.stripStateKnown = false
                session.stripOn = false
                stopActiveEffect(session)
                try { g.close() } catch (_: Exception) {}
                session.gatt = null
                runOnUiThread {
                    activateSelectedSession()
                    refreshDevicePicker()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val session = sessionFor(g) ?: return
            val service = g.getService(serviceUUID)
            val writeChar = service?.getCharacteristic(writeUUID)
            val powerState = service?.getCharacteristic(powerNotifyUUID)
            session.characteristic = writeChar
            session.notifyCharacteristic = powerState
            runOnUiThread {
                if (statusCode == BluetoothGatt.GATT_SUCCESS && writeChar != null) {
                    activateSelectedSession()
                    val canNotify = powerState != null &&
                        (powerState.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                    val canRead = powerState != null &&
                        (powerState.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                    if (canNotify) {
                        try {
                            g.setCharacteristicNotification(powerState, true)
                            val descriptor = powerState.getDescriptor(cccdUUID)
                            if (descriptor != null) {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    val value = if ((powerState.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    g.writeDescriptor(descriptor, value)
                                } else {
                                    @Suppress("DEPRECATION")
                                    descriptor.value = if ((powerState.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    g.writeDescriptor(descriptor)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    if (session.isMelk) {
                        if (session.address == selectedDeviceAddress.uppercase())
                            status.text = "MELK знайдено · ініціалізую контролер…"
                        writeBlePacketTo(g, writeChar, byteArrayOf(0x7E, 0x07, 0x83.toByte()))
                        handler.postDelayed({
                            if (session.gatt === g && session.connected) {
                                writeBlePacketTo(g, writeChar, byteArrayOf(0x7E, 0x04, 0x04))
                                if (session.address == selectedDeviceAddress.uppercase())
                                    status.text = "MELK-OA21 2C підключено · стан не передається"
                            }
                        }, 140L)
                    } else {
                        if (canRead && !canNotify) {
                            try { g.readCharacteristic(powerState) } catch (_: Exception) {}
                        }
                        if (session.address == selectedDeviceAddress.uppercase()) {
                            status.text = if (canNotify || canRead)
                                "Підключено · перевіряю стан стрічки…"
                            else "Підключено · контролер не повідомляє стан"
                            handler.removeCallbacks(powerStateTimeout)
                            handler.postDelayed(powerStateTimeout, 1800L)
                        }
                    }
                    refreshDevicePicker()
                } else if (session.address == selectedDeviceAddress.uppercase()) {
                    status.text = "Не знайдено FFF0 / FFF3"
                }
            }
        }

        @Deprecated("Deprecated in Android 13; kept for older BLE callbacks")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val session = sessionFor(g) ?: return
            if (characteristic.uuid == powerNotifyUUID)
                updatePowerStateFromPacket(session, characteristic.value)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val session = sessionFor(g) ?: return
            if (characteristic.uuid == powerNotifyUUID)
                updatePowerStateFromPacket(session, value)
        }
        @Deprecated("Deprecated in Android 13; kept for older BLE callbacks")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val session = sessionFor(g) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == powerNotifyUUID)
                updatePowerStateFromPacket(session, characteristic.value)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val session = sessionFor(g) ?: return
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == powerNotifyUUID)
                updatePowerStateFromPacket(session, value)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val session = sessionFor(g) ?: return
            if (status != BluetoothGatt.GATT_SUCCESS || descriptor.uuid != cccdUUID) return
            val powerState = session.notifyCharacteristic ?: return
            if ((powerState.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                try { g.readCharacteristic(powerState) } catch (_: Exception) {}
            }
        }
    }

    private fun updatePowerStateFromPacket(session: DeviceSession, packet: ByteArray?) {
        if(packet==null||packet.size<4||packet[0]!=0x7E.toByte()||packet[2]!=0x04.toByte())return
        val on=when(packet[3].toInt() and 255){0xF0,0xFF->true;0x00->false;else->return}
        session.stripOn=on;session.stripStateKnown=true
        if(!on) stopActiveEffect(session)
        runOnUiThread { if(selectedDeviceAddresses.contains(session.address)) activateSelectedSession() }
    }

    private fun sendPowerCommand(session: DeviceSession,on:Boolean) {
        val packet=if(on) byteArrayOf(0x7E,0x04,0x04,0xF0.toByte(),0,1,0xFF.toByte(),0,0xEF.toByte())
            else byteArrayOf(0x7E,0x04,0x04,0,0,0,0xFF.toByte(),0,0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return,session.characteristic ?: return,packet)
    }

    private fun sendMelkMode(session: DeviceSession, mode: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x05, 0x03, mode.toByte(), 0x06, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
    }

    private fun sendMelkScene(session: DeviceSession, sceneId: Int) {
        if (!session.isMelk || !session.connected) return
        // MELK-OA21 scene frame (confirmed from the OA21 protocol capture):
        // 7E 05 31 <scene> 07 FF FF 01 EF
        val packet = byteArrayOf(0x7E, 0x05, 0x31, sceneId.toByte(), 0x07,
            0xFF.toByte(), 0xFF.toByte(), 0x01, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
    }

    private fun sendMelkModeSpeed(session: DeviceSession, value: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x02, value.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
    }

    private fun sendMelkBrightness(session: DeviceSession, value: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x01, value.coerceIn(0, 100).toByte(),
            0x01, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
    }

    private fun sendMelkMicEnabled(session: DeviceSession, enabled: Boolean) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x07, if (enabled) 0x01 else 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
        session.micEnabled = enabled
    }

    private fun sendMelkMicEffect(session: DeviceSession, effect: Int) {
        if (!session.isMelk || !session.connected) return
        val encoded = (0x80 + (effect.coerceIn(1, 8) - 1)).toByte()
        val packet = byteArrayOf(0x7E, 0x05, 0x03, encoded, 0x04,
            0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
        session.micEffect = effect.coerceIn(1, 8)
    }

    private fun sendMelkMicSensitivity(session: DeviceSession, sensitivity: Int) {
        if (!session.isMelk || !session.connected) return
        val packet = byteArrayOf(0x7E, 0x04, 0x06, sensitivity.coerceIn(0, 100).toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xEF.toByte())
        writeBlePacketTo(session.gatt ?: return, session.characteristic ?: return, packet)
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

    private fun startSceneAnimation(sceneId: Int, isPreset: Boolean) {
        activeSceneId = sceneId
        activeSceneIsPreset = isPreset
        if (sceneStartedAt == 0L || sceneRunner == null) sceneStartedAt = SystemClock.uptimeMillis()
        val runner = sceneRunner ?: object : Runnable {
            override fun run() {
                val targets = selectedSessions().filter { !it.isMelk && it.mode == EffectMode.SCENE && it.stripOn }
                if (targets.isEmpty()) {
                    sceneRunner = null
                    return
                }
                val elapsed = (SystemClock.uptimeMillis() - sceneStartedAt).coerceAtLeast(0L) / 1000f
                val rgb = sceneColor(activeSceneId, activeSceneIsPreset, elapsed, sceneSpeedPercent)
                targets.forEach { sendRGBTo(it, Color.red(rgb), Color.green(rgb), Color.blue(rgb)) }
                handler.postDelayed(this, 60L)
            }
        }
        sceneRunner = runner
        handler.removeCallbacks(runner)
        handler.post(runner)
    }

    private fun sceneColor(id: Int, isPreset: Boolean, elapsed: Float, speed: Int): Int {
        val rate = 0.30f + speed.coerceIn(0, 100) / 100f * 2.7f
        val p = elapsed * rate
        val wave = ((kotlin.math.sin(p * 2f * Math.PI.toFloat()) + 1f) * 0.5f).coerceIn(0f, 1f)
        val pulse = ((kotlin.math.sin(p * 2.8f * Math.PI.toFloat()) + 1f) * 0.5f).coerceIn(0f, 1f)
        fun hsv(h: Float, s: Float, v: Float): Int =
            Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
        fun noise(seed: Int): Float {
            val x = kotlin.math.sin(seed * 12.9898 + id * 78.233) * 43758.5453
            return (x - kotlin.math.floor(x)).toFloat()
        }
        val step = (p * 2.2f).toInt()
        if (!isPreset) return when (id) {
            1 -> hsv((step % 6) * 60f, 1f, 1f) // colour jumps
            2 -> hsv(0f, 0f, if ((p * 5f).toInt() % 2 == 0) 1f else 0.015f) // strobe
            3 -> hsv(28f, .78f, .08f + .92f * wave) // breathing
            4 -> hsv(if ((p * 2f).toInt() % 2 == 0) 38f else 0f, 1f, if ((p * 2f).toInt() % 2 == 0) 1f else .12f)
            else -> hsv(30f, .8f, wave)
        }
        return when (id) {
            1 -> hsv(8f + 38f * wave, .95f, .10f + .90f * wave) // sunrise
            2 -> hsv(12f + 275f * wave, .9f, .18f + .82f * wave) // sunset
            3 -> hsv((step % 6) * 60f, .9f, if (pulse > .18f) 1f else .48f)
            4 -> hsv(24f + noise(step) * 13f, .92f, .45f + noise(step + 7) * .42f)
            5 -> hsv((step * 71 % 360).toFloat(), .85f, if (noise(step) > .73f) 1f else .025f + pulse * .12f)
            6 -> hsv((p * 150f) % 360f, 1f, .55f + .45f * pulse)
            7 -> hsv(335f + 18f * wave, .62f, .45f + .48f * pulse)
            8 -> hsv(218f + 35f * wave, .72f, .12f + .75f * if (noise(step) > .76f) 1f else pulse * .22f)
            9 -> hsv(320f + 22f * wave, .48f, .52f + .42f * pulse)
            10 -> hsv((p * 210f).toInt().let { (it % 6) * 60f }, 1f, if (step % 2 == 0) 1f else .3f)
            11 -> hsv((p * 92f) % 360f, 1f, 1f)
            12 -> hsv(if (wave > .5f) 215f else 34f, .58f, .18f + .35f * pulse)
            13 -> hsv(if (step % 2 == 0) 0f else 125f, .95f, .55f + .45f * pulse)
            14 -> hsv((p * 68f + 185f * wave) % 360f, .78f, .35f + .55f * pulse)
            15 -> hsv(225f + 22f * wave, .72f, .08f + .34f * pulse)
            16 -> hsv(190f + 35f * wave, .88f, .25f + .70f * pulse)
            17 -> hsv(105f + 38f * wave, .78f, .16f + .65f * pulse)
            18 -> hsv(38f + 8f * wave, .48f, .62f + .30f * pulse)
            19 -> hsv(205f + 12f * wave, .20f, .72f + .22f * pulse)
            20 -> hsv((p * 105f) % 360f, .72f, if (noise(step) > .82f) 1f else .3f + .35f * pulse)
            21 -> hsv(185f + 105f * wave, .28f, .48f + .42f * pulse)
            22 -> hsv(42f + 300f * wave, .24f, .62f + .34f * pulse)
            23 -> hsv(190f + 22f * wave, .22f, .58f + .40f * pulse)
            24 -> hsv(12f + noise(step) * 28f, .98f, .34f + noise(step + 11) * .58f)
            25 -> hsv(205f, .08f, if (noise(step) > .70f) 1f else .025f + pulse * .10f)
            26 -> hsv(340f + 18f * wave, .9f, .42f + .55f * pulse)
            27 -> hsv(if (step % 2 == 0) 28f else 275f, .95f, .38f + .58f * pulse)
            28 -> hsv(if (step % 2 == 0) 38f else 0f, 1f, if (pulse > .48f) 1f else .08f)
            150 -> hsv((p * 125f) % 360f, .9f, .25f + .72f * wave)
            152 -> hsv((360f - (p * 105f) % 360f) % 360f, .82f, .28f + .68f * pulse)
            154 -> hsv(205f + 18f * wave, .92f, if (noise(step) > .72f) 1f else .04f + .24f * pulse)
            155 -> hsv(275f + 65f * wave, .82f, if (noise(step + 3) > .70f) 1f else .04f + .22f * pulse)
            156 -> hsv((step * 47 % 360).toFloat(), .9f, if (noise(step) > .68f) 1f else .025f + .12f * pulse)
            else -> hsv((p * 60f) % 360f, .8f, .7f)
        }
    }

    private fun sendRGBTo(d:DeviceSession,r:Int,g:Int,b:Int) {
        if(!d.connected||d.characteristic==null||d.gatt==null)return
        val factor=if (d.isMelk) 1.0 else d.brightness/100.0
        val rr=(r*factor).toInt().coerceIn(0,255);val gg=(g*factor).toInt().coerceIn(0,255);val bb=(b*factor).toInt().coerceIn(0,255)
        d.pending=if(d.isMelk) byteArrayOf(0x7E,0,5,3,rr.toByte(),gg.toByte(),bb.toByte(),0,0xEF.toByte())
            else byteArrayOf(0x7E,7,5,3,rr.toByte(),gg.toByte(),bb.toByte(),0x10,0xEF.toByte())
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
        writeBlePacketTo(g,ch,packet);d.lastWrite=SystemClock.uptimeMillis()
        if(d.pending!=null&&!d.flushQueued){d.flushQueued=true;handler.postDelayed(d.flushRunner!!,if(d.isMelk && d.mode==EffectMode.RAINBOW)50L else if(d.isMelk)100L else 35L)}
    }

    private fun sendCurrentColor() { selectedSessions().forEach{sendRGBTo(it,it.red,it.green,it.blue)} }
    private fun sendRGB(r:Int,g:Int,b:Int) { selectedSessions().forEach{sendRGBTo(it,r,g,b)} }

    private fun writeBlePacketTo(g: BluetoothGatt, char: BluetoothGattCharacteristic, packet: ByteArray) {
        try {
            val supportsNoResponse =
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val writeType = if (supportsNoResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            val result = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(char, packet, writeType)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = packet
                @Suppress("DEPRECATION")
                if (g.writeCharacteristic(char)) BluetoothGatt.GATT_SUCCESS
                else BluetoothGatt.GATT_FAILURE
            }
            if (g === gatt) lastWriteAt = SystemClock.uptimeMillis()
            if (result != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { status.text = "BLE не прийняв команду живлення (код $result)" }
            }
        } catch (_: SecurityException) {
            runOnUiThread { status.text = "Помилка дозволу на запис Bluetooth" }
        }
    }

    private fun stopActiveEffect(d:DeviceSession) {
        d.fireRunner?.let{handler.removeCallbacks(it)};d.rainbowRunner?.let{handler.removeCallbacks(it)}
        d.fillSpeedRunner?.let{handler.removeCallbacks(it)}
        if (d.isMelk && d.micEnabled) sendMelkMicEnabled(d, false)
        d.mode=EffectMode.NONE
        if (sessions.values.none { it.mode == EffectMode.SCENE }) {
            sceneRunner?.let { handler.removeCallbacks(it) }
            sceneRunner = null
        }
    }
    private fun stopActiveEffect() { selectedSessions().forEach{stopActiveEffect(it)};refreshEffectButtons() }

    private fun toggleStrip() {
        val targets=selectedSessions()
        if(targets.isEmpty()){Toast.makeText(this,"Спочатку підключи пристрій",Toast.LENGTH_SHORT).show();return}
        val turnOff=targets.all{it.stripStateKnown&&it.stripOn}
        targets.forEach { d ->
            if(turnOff){stopActiveEffect(d);d.stripOn=false;d.stripStateKnown=true;d.pending=null;d.flushQueued=false;d.flushRunner?.let{handler.removeCallbacks(it)};sendPowerCommand(d,false)}
            else {d.stripOn=true;d.stripStateKnown=true;sendPowerCommand(d,true);handler.postDelayed({if(d.connected&&d.stripOn&&d.mode==EffectMode.NONE)sendRGBTo(d,d.red,d.green,d.blue)},180L)}
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
                resetFireMotion(d);ensureRunners(d)
                if(d.stripOn)handler.postDelayed({d.fireRunner?.run()},if(d.stripStateKnown)180L else 0L)
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
                stopActiveEffect(d);d.mode=EffectMode.RAINBOW;d.phase=0f;d.lastTick=0L
                if(!d.stripOn){d.stripOn=true;d.stripStateKnown=true;sendPowerCommand(d,true)}
                ensureRunners(d);handler.postDelayed({d.rainbowRunner?.run()},180L)
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
            stopActiveEffect(d)
            val old=d.gatt;d.gatt=null;d.characteristic=null;d.notifyCharacteristic=null;d.connected=false
            d.stripOn=false;d.stripStateKnown=false
            try{old?.disconnect();old?.close()}catch(_:Exception){}
        }
        gatt=null;connected=false;characteristic=null;notifyCharacteristic=null
    }

    private fun disconnect() {
        stopScan();closeGattConnection();sessions.clear();selectedDeviceAddresses.clear();selectedDeviceAddress=""
        status.text="Статус: не підключено";refreshDevicePicker();bulbButton.setLit(null)
        fireButton.text="Увімкнути вогонь";rainbowButton.text="Увімкнути веселку"
    }

    override fun onDestroy() {
        // Release the BLE peripheral when the Activity is closed. Leaving a GATT
        // client open can keep the strip connected and invisible to other scanners.
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