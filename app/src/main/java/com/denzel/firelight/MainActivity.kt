
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

    private var connected = false
    private var fireEnabled = false
    private var scanning = false
    private var deviceAdapter: ArrayAdapter<String>? = null
    private val deviceNames = listOf("ELK-BLEDOB", "Пристрій 2", "Пристрій 3", "Пристрій 4")

    private var red = 255
    private var green = 92
    private var blue = 12
    private var brightness = 100
    private var speed = 15
    private var fireColorSpread = 0
    private var stripOn = true
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

    private val fireTask = object : Runnable {
        override fun run() {
            if (!fireEnabled || !connected || !stripOn) return

            val now = SystemClock.uptimeMillis()
            val elapsed = if (fireLastTickAt == 0L) 80L
                else (now - fireLastTickAt).coerceIn(1L, 250L)
            fireLastTickAt = now

            // Fast, irregular flame flicker. The previous speed factor was inverted:
            // at the fastest slider setting it actually waited several seconds.
            if (now >= fireNextChangeAt) {
                val roll = Random.nextFloat()
                fireLevelTarget = when {
                    roll < 0.13f -> Random.nextFloat() * 0.42f + 0.56f // quick flare
                    roll < 0.34f -> Random.nextFloat() * 0.38f + 0.30f // ember dip
                    else -> Random.nextFloat() * 0.38f + 0.48f
                }
                // Color spread is user-controlled: 0 keeps the exact selected hue
                // and saturation; 100 allows the full hue wheel to appear.
                val spread = fireColorSpread.coerceIn(0, 100) / 100f
                val hueRange = 180f * spread
                fireHueTarget = if (spread == 0f) 0f else Random.nextFloat() * hueRange * 2f - hueRange
                fireSaturationTarget = 1f - Random.nextFloat() * 0.42f * spread
                // Most flickers stay sharp; occasionally the flame rolls gently
                // into its next state, like a slower, softer tongue of fire.
                fireSmoothingMs = if (Random.nextFloat() < 0.32f) {
                    Random.nextFloat() * 250f + 260f
                } else {
                    Random.nextFloat() * 48f + 58f
                }
                if (roll < 0.13f) fireFlare = Random.nextFloat() * 0.24f + 0.12f
                val interval = 330L - (speed.coerceIn(11, 20) - 11) * 28L
                fireNextChangeAt = now + Random.nextLong((interval * 0.55f).toLong().coerceAtLeast(45L),
                    (interval * 1.15f).toLong().coerceAtLeast(70L))
            }

            val blend = 1f - kotlin.math.exp(-elapsed.toFloat() / fireSmoothingMs)
            fireLevel += (fireLevelTarget - fireLevel) * blend
            fireHueShift += (fireHueTarget - fireHueShift) * (blend * 0.9f)
            fireSaturation += (fireSaturationTarget - fireSaturation) * blend
            fireFlare *= kotlin.math.exp(-elapsed.toFloat() / 150f)

            val slowWave = kotlin.math.sin(now / 510.0).toFloat() * 0.045f
            val secondaryWave = kotlin.math.sin(now / 91.0).toFloat() * 0.035f
            val level = (fireLevel + slowWave + secondaryWave + fireFlare).coerceIn(0.22f, 1f)

            val hsv = FloatArray(3)
            Color.RGBToHSV(red, green, blue, hsv)
            val hue = (hsv[0] + fireHueShift + 360f) % 360f
            val saturation = (hsv[1] * fireSaturation).coerceIn(0f, 1f)
            val rgb = Color.HSVToColor(floatArrayOf(hue, saturation, level))
            sendRGB(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
            handler.postDelayed(this, nextFireTick())
        }
    }
    private fun resetFireMotion() {
        fireLevel = 0.76f
        fireLevelTarget = 0.76f
        fireHueShift = 0f
        fireHueTarget = 0f
        fireSaturation = 1f
        fireSaturationTarget = 1f
        fireNextChangeAt = 0L
        fireLastTickAt = 0L
        fireFlare = 0f
        fireSmoothingMs = 78f
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
        val live = text("●  BLE", 11f, Color.rgb(117, 224, 173), true).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackground(rounded(Color.rgb(25, 55, 48), 30))
        }
        header.addView(live)
        root.addView(header)
        addGap(root, 8)

        val connectionCard = card()
        connectionCard.addView(text("ПІДКЛЮЧЕННЯ", 11f, muted, true))
        addGap(connectionCard, 5)
        deviceAdapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, deviceNames
        ) {
            override fun isEnabled(position: Int): Boolean = position == 0 && connected

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return (convertView as? TextView ?: TextView(this@MainActivity)).apply {
                    text = deviceNames[position]
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setTextColor(if (position == 0 && connected) Color.WHITE else Color.rgb(112, 120, 137))
                    setPadding(dp(12), 0, dp(34), 0)
                    includeFontPadding = false
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return (convertView as? TextView ?: TextView(this@MainActivity)).apply {
                    text = if (position == 0 && connected) "ELK-BLEDOB     Підключено"
                        else if (position == 0) "ELK-BLEDOB     Недоступний"
                        else "${deviceNames[position]}     Недоступний"
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setTextColor(if (position == 0 && connected) Color.WHITE else Color.rgb(112, 120, 137))
                    setPadding(dp(16), 0, dp(12), 0)
                    setBackgroundColor(Color.TRANSPARENT)
                    minHeight = dp(48)
                    includeFontPadding = false
                }
            }
        }
        val deviceSpinner = Spinner(this, null, android.R.attr.spinnerStyle, Spinner.MODE_DROPDOWN).apply {
            adapter = deviceAdapter
            setSelection(0)
            setBackground(rounded(Color.rgb(29, 34, 46), 13, Color.rgb(61, 70, 88)))
            setPadding(0, 0, dp(28), 0)
            dropDownWidth = resources.displayMetrics.widthPixels - dp(48)
            dropDownHorizontalOffset = 0
            dropDownVerticalOffset = dp(42)
            setPopupBackgroundDrawable(rounded(Color.rgb(17, 21, 30), 16, Color.rgb(61, 70, 88)))
        }
        val deviceField = FrameLayout(this).apply {
            addView(deviceSpinner, FrameLayout.LayoutParams(-1, dp(38)))
            addView(object : View(this@MainActivity) {
                private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(190, 199, 215)
                    style = Paint.Style.STROKE
                    strokeWidth = dp(2).toFloat()
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val cx = width / 2f
                    val cy = height / 2f
                    val half = dp(5).toFloat()
                    canvas.drawLine(cx - half, cy - dp(2), cx, cy + dp(3), arrowPaint)
                    canvas.drawLine(cx, cy + dp(3), cx + half, cy - dp(2), arrowPaint)
                }
            }, FrameLayout.LayoutParams(dp(36), -1, android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL))
        }
        connectionCard.addView(deviceField, LinearLayout.LayoutParams(-1, dp(38)))
        addGap(connectionCard, 5)
        status = text("Шукаю ELK-BLEDOB…", 14f, Color.WHITE, true)
        connectionCard.addView(status)
        addGap(connectionCard, 8)
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
        root.addView(pageHost)

        val colorCard = card().apply {
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        val colorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        colorHeader.addView(text("КОЛІР СВІТІННЯ", 11f, muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        bulbButton = BulbToggleView(this) { toggleStrip() }.apply {
            setLit(stripOn)
        }
        colorHeader.addView(bulbButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            topMargin = dp(8)
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
            stopFireForManual()
            updatePreview()
            if (connected) sendCurrentColor()
        }
        val wheelSize = dp(168)
        wheelFrame.addView(colorWheel, FrameLayout.LayoutParams(wheelSize, wheelSize, android.view.Gravity.CENTER))
        colorCard.addView(wheelFrame)

        preview = text("●  RGB  $red  /  $green  /  $blue", 13f, Color.rgb(red, green, blue), true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        colorCard.addView(preview)
        firePage.addView(colorCard)
        addGap(firePage, 12)

        val tuningCard = card().apply {
            setPadding(dp(12), dp(12), dp(12), dp(6))
        }
        tuningCard.addView(text("НАЛАШТУВАННЯ", 11f, muted, true))
        addGap(tuningCard, 3)

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
            if (!fireEnabled) sendCurrentColor()
        }
        addModernSlider(tuningCard, "Швидкість вогню", speed - 10, 10, "/10") {
            speed = (it + 10).coerceIn(11, 20)
        }
        addModernSlider(tuningCard, "Розкид кольорів вогню", fireColorSpread, 100, "%", 2) {
            fireColorSpread = it.coerceIn(0, 100)
            if (fireEnabled) {
                fireHueTarget = 0f
                fireSaturationTarget = 1f
                fireHueShift *= fireColorSpread / 100f
                fireSaturation = 1f - (1f - fireSaturation) * fireColorSpread / 100f
            }
        }
        firePage.addView(tuningCard)
        addGap(firePage, 12)

        val effectsCard = card()
        effectsCard.addView(text("ЕФЕКТИ ТА ЖИВЛЕННЯ", 11f, muted, true))
        addGap(effectsCard, 12)
        val effectActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        fireButton = actionButton("Увімкнути вогонь", true) { toggleFire() }
        effectActions.addView(fireButton, LinearLayout.LayoutParams(-1, dp(40)))
        effectsCard.addView(effectActions)
        firePage.addView(effectsCard)

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
        if (connected) {
            status.text = "Вже підключено до ELK-BLEDOB"
            return
        }
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            Toast.makeText(
                this,
                "Надай дозволи Bluetooth",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val manager =
            getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter

        if (!adapter.isEnabled) {
            Toast.makeText(
                this,
                "Увімкни Bluetooth",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        stopScan()
        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            status.text = "BLE-сканер недоступний"
            return
        }

        status.text = "Шукаю тільки ELK-BLEDOB ($targetAddress)..."
        scanning = true
        try {
            val filter = ScanFilter.Builder().setDeviceAddress(targetAddress).build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            activeScanner.startScan(listOf(filter), settings, scanCallback)
            handler.postDelayed({
                if (scanning) {
                    stopScan()
                    status.text = "ELK-BLEDOB не знайдено. Перевір, що контролер увімкнений."
                    deviceAdapter?.notifyDataSetChanged()
                }
            }, 10000)
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
            if (address.equals(targetAddress, ignoreCase = true)) {
                stopScan()
                runOnUiThread {
                    status.text = "Знайшов ELK-BLEDOB. Підключаюся..."
                    connect(result.device)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.firstOrNull {
                try { it.device.address.equals(targetAddress, ignoreCase = true) }
                catch (_: SecurityException) { false }
            }?.let { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread { status.text = "Помилка BLE-сканування: $errorCode" }
        }
    }

    private fun stopScan() {
        if (!scanning) return

        scanning = false

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun connect(device: BluetoothDevice) {
        status.text = "Підключення..."

        try {
            gatt?.close()

            gatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

        } catch (e: SecurityException) {
            status.text = "Помилка дозволу Bluetooth"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            statusCode: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}

                runOnUiThread {
                    status.text = "Підключено. Пошук характеристик..."
                }

                try {
                    g.discoverServices()
                } catch (_: SecurityException) {
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                characteristic = null
                fireEnabled = false
                stripOn = true
                handler.removeCallbacks(fireTask)

                runOnUiThread {
                    status.text = "Від'єднано"
                    deviceAdapter?.notifyDataSetChanged()
                    fireButton.text = "Увімкнути ефект вогню"
                    bulbButton.setLit(stripOn)
                }

                g.close()
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            statusCode: Int
        ) {
            val service = g.getService(serviceUUID)
            characteristic = service?.getCharacteristic(writeUUID)

            runOnUiThread {
                if (characteristic != null) {
                    status.text = "Підключено до ELK-BLEDOB"
                    deviceAdapter?.notifyDataSetChanged()
                    // The controller does not report its physical power state.
                    // Treat it as unknown/off after connecting so the first tap
                    // always sends a real POWER ON command (not another OFF).
                    stripOn = false
                    bulbButton.setLit(stripOn)
                    sendCurrentColor()
                } else {
                    status.text = "Не знайдено FFF0 / FFF3"
                }
            }
        }
    }

    private fun sendPowerCommand(on: Boolean) {
        // ELK-BLEDOM / Lotus Lantern dedicated power command on FFF0:FFF3.
        val packet = if (on) {
            byteArrayOf(0x7E, 0x04, 0x04, 0xF0.toByte(), 0x00, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
        } else {
            byteArrayOf(0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEF.toByte())
        }
        writeBlePacket(packet)
    }

    private fun writeBlePacket(packet: ByteArray) {
        val g = gatt ?: return
        val char = characteristic ?: return
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
            lastWriteAt = SystemClock.uptimeMillis()
            if (result != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread { status.text = "BLE не прийняв команду живлення (код $result)" }
            }
        } catch (_: SecurityException) {
            runOnUiThread { status.text = "Помилка дозволу на запис Bluetooth" }
        }
    }

    private fun sendCurrentColor() {
        sendRGB(red, green, blue)
    }

    private fun sendRGB(r: Int, g: Int, b: Int) {
        if (characteristic == null || gatt == null) return
        val factor = brightness / 100.0
        val rr = (r * factor).toInt().coerceIn(0, 255)
        val gg = (g * factor).toInt().coerceIn(0, 255)
        val bb = (b * factor).toInt().coerceIn(0, 255)

        pendingPacket = byteArrayOf(
            0x7E, 0x07, 0x05, 0x03,
            rr.toByte(), gg.toByte(), bb.toByte(),
            0x10, 0xEF.toByte()
        )

        if (!writeScheduled) {
            writeScheduled = true
            val wait = (65L - (SystemClock.uptimeMillis() - lastWriteAt)).coerceAtLeast(0L)
            handler.postDelayed({ flushPendingColor() }, wait)
        }
    }

    private fun flushPendingColor() {
        writeScheduled = false
        val packet = pendingPacket ?: return
        pendingPacket = null
        val g = gatt ?: return
        val char = characteristic ?: return

        writeBlePacket(packet)

        if (pendingPacket != null && !writeScheduled) {
            writeScheduled = true
            handler.postDelayed({ flushPendingColor() }, 65L)
        }
    }

    private fun toggleStrip() {
        if (!connected) {
            Toast.makeText(this, "Спочатку підключи контролер", Toast.LENGTH_SHORT).show()
            return
        }
        stripOn = !stripOn
        if (!stripOn) {
            sendPowerCommand(false)
            bulbButton.setLit(stripOn)
            fireEnabled = false
            handler.removeCallbacks(fireTask)
            fireButton.text = "Увімкнути ефект вогню"
        } else {
            bulbButton.setLit(stripOn)
            // Wake the controller with its dedicated power command before sending RGB.
            sendPowerCommand(true)
            handler.postDelayed({
                if (!connected || !stripOn) return@postDelayed
                if (fireEnabled) {
                    resetFireMotion()
                    handler.removeCallbacks(fireTask)
                    fireTask.run()
                } else {
                    sendCurrentColor()
                }
            }, 180L)
        }
    }

    private fun toggleFire() {
        if (!connected) {
            Toast.makeText(
                this,
                "Спочатку підключи контролер",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val wasStripOff = !stripOn
        if (wasStripOff) {
            stripOn = true
            bulbButton.setLit(stripOn)
            sendPowerCommand(true)
        }
        fireEnabled = !fireEnabled

        if (fireEnabled) {
            fireButton.text = "Вимкнути ефект вогню"
            resetFireMotion()
            if (wasStripOff) {
                handler.postDelayed({
                    if (fireEnabled && connected && stripOn) fireTask.run()
                }, 180L)
            } else {
                fireTask.run()
            }
        } else {
            handler.removeCallbacks(fireTask)
            fireButton.text = "Увімкнути ефект вогню"
            sendCurrentColor()
        }
    }

    private fun stopFireForManual() {
        if (fireEnabled) {
            fireEnabled = false
            handler.removeCallbacks(fireTask)
            fireButton.text = "Увімкнути ефект вогню"
        }
    }

    private fun updatePreview() {
        preview.setTextColor(Color.rgb(red, green, blue))
        preview.text = "● RGB: $red, $green, $blue"
    }

    private fun disconnect() {
        stopScan()
        fireEnabled = false
        handler.removeCallbacks(fireTask)

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }

        gatt = null
        characteristic = null
        pendingPacket = null
        writeScheduled = false
        connected = false

        status.text = "Статус: не підключено"
        deviceAdapter?.notifyDataSetChanged()
        fireButton.text = "Увімкнути ефект вогню"
        stripOn = true
        bulbButton.setLit(stripOn)
    }

    override fun onDestroy() {
        // Foreground service keeps the process alive while the activity is hidden.
        // Keep the BLE connection and fire loop running when the activity is destroyed.
        stopScan()
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
    private var lit = true
    private val accent = Color.rgb(255, 142, 105)
    private val dim = Color.rgb(151, 160, 178)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Увімкнути або вимкнути гірлянду"
        setOnClickListener { onToggle() }
    }

    fun setLit(value: Boolean) {
        lit = value
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
        paint.color = if (lit) Color.rgb(55, 39, 39) else Color.rgb(29, 34, 46)
        canvas.drawRoundRect(left, top, left + side, top + side, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = if (lit) Color.rgb(111, 68, 60) else Color.rgb(61, 70, 88)
        canvas.drawRoundRect(left, top, left + side, top + side, radius, radius, paint)

        // Compact, symmetrical lamp glyph. All strokes stay inside a 24dp
        // square, leaving a safe margin so the lower base cannot be clipped.
        val cx = w / 2f
        val cy = h / 2f
        val d = density
        val iconColor = if (lit) accent else dim
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
        paint.style = if (lit) Paint.Style.FILL else Paint.Style.STROKE
        if (lit) {
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
