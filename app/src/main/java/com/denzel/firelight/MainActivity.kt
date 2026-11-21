
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
        var red: Int = 255, var green: Int = 92, var blue: Int = 12, var brightness: Int = 100,
        var speed: Int = 15, var spread: Int = 0, var rainbowSpeed: Int = 15,
        var phase: Float = 0f, var lastTick: Long = 0L,
        var level: Float = .76f, var targetLevel: Float = .76f, var hueShift: Float = 0f,
        var targetHue: Float = 0f, var saturation: Float = .94f, var targetSaturation: Float = .94f,
        var nextChange: Long = 0L, var fireTick: Long = 0L, var flare: Float = 0f,
        var smoothing: Float = 78f, var pending: ByteArray? = null, var flushQueued: Boolean = false,
        var lastWrite: Long = 0L, var fireRunner: Runnable? = null, var rainbowRunner: Runnable? = null,
        var flushRunner: Runnable? = null
    )
    private val sessions = linkedMapOf<String, DeviceSession>()
    private val selectedDeviceAddresses = linkedSetOf("BE:27:8A:00:73:DE")
    private var suppressFirstDeviceSelection = true

    private var connected = false
    private var fireEnabled = false
    private enum class EffectMode { NONE, FIRE, RAINBOW }
    private enum class DeviceLayout { SIMULTANEOUS, SEQUENTIAL }
    private var activeMode = EffectMode.NONE
    private var deviceLayout = DeviceLayout.SIMULTANEOUS
    private var rainbowSpeed = 15
    private var rainbowPhase = 0f
    private var rainbowLastTickAt = 0L
    private lateinit var rainbowButton: Button
    private var scanning = false
    private var deviceAdapter: ArrayAdapter<String>? = null
    private lateinit var deviceField: Spinner
    private var updatingDevicePicker = false
    private val deviceNames = listOf("ELK-BLEDOB", "MELK-OA21 2C")
    private var selectedDeviceAddress = "BE:27:8A:00:73:DE"

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
            val found = sessions.values.count { it.gatt != null }
            status.text = if (found > 0) "Підключено $found із 2 контролерів"
                else "Контролери не знайдено. Перевір, що пристрої увімкнені."
            deviceAdapter?.notifyDataSetChanged()
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
        deviceAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, deviceNames) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return TextView(this@MainActivity).apply {
                    text = "Пристрої · ${selectedDeviceAddresses.size} обрано"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(12), 0)
                    minHeight = dp(42)
                }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val address = if (position == 0) targetAddress else melkAddress
                val selected = selectedDeviceAddresses.contains(address.uppercase())
                return LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(16), 0, dp(14), 0)
                    minimumHeight = dp(52)
                    setBackgroundColor(Color.TRANSPARENT)
                    isLongClickable = true
                    setOnLongClickListener {
                        selectedDeviceAddresses.clear()
                        selectedDeviceAddresses.add(address.uppercase())
                        selectedDeviceAddress = address.uppercase()
                        activateSelectedSession()
                        true
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = deviceDisplayName(address)
                        textSize = 14f
                        setTextColor(Color.rgb(226, 231, 241))
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = if (selected) "✓" else ""
                        textSize = 15f
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                        setTextColor(Color.rgb(255, 142, 105))
                        gravity = android.view.Gravity.CENTER
                        setBackground(if (selected) rounded(Color.rgb(75, 48, 45), 10) else rounded(Color.TRANSPARENT, 10))
                    }, LinearLayout.LayoutParams(dp(28), dp(28)))
                }
            }
        }
        deviceField = Spinner(this, null, android.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN).apply {
            adapter = deviceAdapter
            setSelection(if (selectedDeviceAddress.equals(melkAddress, true)) 1 else 0)
            setBackground(rounded(Color.rgb(29, 34, 46), 13, Color.rgb(61, 70, 88)))
            setPadding(dp(4), 0, dp(8), 0)
            dropDownWidth = -1
            dropDownVerticalOffset = dp(3)
            setPopupBackgroundDrawable(rounded(Color.rgb(27, 32, 43), 14, Color.rgb(61, 70, 88)))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (updatingDevicePicker) return
                    val address = (if (position == 0) targetAddress else melkAddress).uppercase()
                    if (!selectedDeviceAddresses.add(address)) selectedDeviceAddresses.remove(address)
                    if (selectedDeviceAddresses.isEmpty()) selectedDeviceAddresses.add(address)
                    selectedDeviceAddress = if (selectedDeviceAddresses.contains(address)) address else selectedDeviceAddresses.first()
                    activateSelectedSession()
                }
            }
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
        colorHeader.addView(text("КОЛІР СВІТІННЯ", 11f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
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
        colorCard.addView(text("Обери свій відтінок", 17f, Color.WHITE, true))
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
            selectedSessions().forEach { it.brightness=brightness }
            selectedSessions().filter { it.mode == EffectMode.NONE }.forEach { sendRGBTo(it,it.red,it.green,it.blue) }
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

        // Bottom navigation stays visible on every page. Only the first page
        // is implemented for now; the remaining tabs are intentionally blank.
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
            pageHost.removeAllViews()
            if (index == 0) {
                pageHost.addView(firePage, FrameLayout.LayoutParams(-1, -1))
            } else if (index == 2) {
                pageHost.addView(rainbowPage, FrameLayout.LayoutParams(-1, -1))
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

        setContentView(root)
    }

    private fun deviceDisplayName(address: String): String {
        val fallback = if (address.equals(melkAddress, true)) deviceNames[1] else deviceNames[0]
        return getSharedPreferences("firelight_device_names", MODE_PRIVATE)
            .getString(address.uppercase(), fallback)?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun showAppMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add("Перейменувати девайси").setOnMenuItemClickListener {
            showRenameDevicesDialog()
            true
        }
        menu.menu.add("Вимкнути все").setOnMenuItemClickListener {
            turnOffAllConnectedDevices()
            true
        }
        menu.show()
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
                deviceAdapter?.notifyDataSetChanged()
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

    private fun startScan() {
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
        val missingAddresses = listOf(targetAddress, melkAddress).filter { address ->
            sessions[address.uppercase()]?.gatt == null
        }
        if (missingAddresses.isEmpty()) {
            status.text = "Обидва контролери вже підключені"
            return
        }
        stopScan()
        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            status.text = "BLE-сканер недоступний"
            return
        }
        status.text = "Шукаю обидва контролери..."
        scanning = true
        try {
            val filters = missingAddresses.map { address ->
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
            val address = try { result.device.address } catch (_: SecurityException) { "" }
            if (!address.equals(targetAddress, true) && !address.equals(melkAddress, true)) return
            if (sessions[address.uppercase()]?.gatt != null) return
            runOnUiThread {
                status.text = if (address.equals(melkAddress, true))
                    "Знайшов MELK-OA21 2C · підключаю..."
                else "Знайшов ELK-BLEDOB · підключаю..."
                connect(result.device)
                if (sessions.values.count { it.gatt != null } >= 2) stopScan()
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
        val d=sessions[selectedDeviceAddress.uppercase()]
        gatt=d?.gatt; characteristic=d?.characteristic; notifyCharacteristic=d?.notifyCharacteristic
        connected=d?.connected==true; isMelkDevice=d?.isMelk==true
        deviceLayout=if(isMelkDevice) DeviceLayout.SEQUENTIAL else DeviceLayout.SIMULTANEOUS
        if(d!=null){red=d.red;green=d.green;blue=d.blue;brightness=d.brightness;speed=d.speed;fireColorSpread=d.spread;rainbowSpeed=d.rainbowSpeed;if(::preview.isInitialized)updatePreview()}
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
        deviceAdapter?.notifyDataSetChanged()
        if (::deviceField.isInitialized) {
            updatingDevicePicker = true
            deviceField.setSelection(if (selectedDeviceAddress.equals(melkAddress, true)) 0 else 1)
            updatingDevicePicker = false
        }
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
            deviceAdapter?.notifyDataSetChanged()
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
                    if (selectedDeviceAddresses.contains(session.address)) activateSelectedSession()
                    else {
                        status.text = "Підключено один контролер · шукаю другий..."
                        deviceAdapter?.notifyDataSetChanged()
                    }
                }
                try { g.discoverServices() } catch (_: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                session.connected = false
                session.characteristic = null
                session.notifyCharacteristic = null
                session.stripStateKnown = false
                session.stripOn = false
                stopActiveEffect(session)
                try { g.close() } catch (_: Exception) {}
                session.gatt = null
                runOnUiThread {
                    if (selectedDeviceAddresses.contains(session.address)) activateSelectedSession()
                    deviceAdapter?.notifyDataSetChanged()
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
                    if (selectedDeviceAddresses.contains(session.address)) activateSelectedSession()
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
                    deviceAdapter?.notifyDataSetChanged()
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

    private fun sendRGBTo(d:DeviceSession,r:Int,g:Int,b:Int) {
        if(!d.connected||d.characteristic==null||d.gatt==null)return
        val factor=d.brightness/100.0
        val rr=(r*factor).toInt().coerceIn(0,255);val gg=(g*factor).toInt().coerceIn(0,255);val bb=(b*factor).toInt().coerceIn(0,255)
        d.pending=if(d.isMelk) byteArrayOf(0x7E,0,5,3,rr.toByte(),gg.toByte(),bb.toByte(),0,0xEF.toByte())
            else byteArrayOf(0x7E,7,5,3,rr.toByte(),gg.toByte(),bb.toByte(),0x10,0xEF.toByte())
        ensureRunners(d)
        if(!d.flushQueued) {
            d.flushQueued=true
            val interval=if(d.isMelk)100L else if(d.mode==EffectMode.RAINBOW)30L else 50L
            handler.postDelayed(d.flushRunner!!,(interval-(SystemClock.uptimeMillis()-d.lastWrite)).coerceAtLeast(0L))
        }
    }

    private fun flushPendingColor(d:DeviceSession) {
        d.flushQueued=false
        val packet=d.pending ?: return;d.pending=null
        val g=d.gatt ?: return;val ch=d.characteristic ?: return
        writeBlePacketTo(g,ch,packet);d.lastWrite=SystemClock.uptimeMillis()
        if(d.pending!=null&&!d.flushQueued){d.flushQueued=true;handler.postDelayed(d.flushRunner!!,if(d.isMelk)100L else 35L)}
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
        d.mode=EffectMode.NONE
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
        stopScan();closeGattConnection();sessions.clear();selectedDeviceAddresses.clear()
        selectedDeviceAddresses.add(targetAddress.uppercase());selectedDeviceAddress=targetAddress.uppercase()
        status.text="Статус: не підключено";deviceAdapter?.notifyDataSetChanged();bulbButton.setLit(null)
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
