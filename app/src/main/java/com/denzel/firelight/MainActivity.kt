
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

    private var red = 255
    private var green = 92
    private var blue = 12
    private var brightness = 100
    private var speed = 8

    private var fireFrom = intArrayOf(255, 95, 8)
    private var fireTo = intArrayOf(235, 55, 3)
    private var fireTransitionStart = 0L
    private var fireTransitionDuration = 600L

    private lateinit var status: TextView
    private lateinit var fireButton: Button
    private lateinit var preview: TextView
    private var pendingPacket: ByteArray? = null
    private var writeScheduled = false
    private var lastWriteAt = 0L
    private val targetAddress = "BE:27:8A:00:73:DE"

    private val fireTask = object : Runnable {
        override fun run() {
            if (!fireEnabled || !connected) return

            val now = SystemClock.uptimeMillis()
            var progress = ((now - fireTransitionStart).toFloat() /
                fireTransitionDuration.toFloat()).coerceIn(0f, 1f)

            if (progress >= 1f) {
                fireFrom = fireTo.copyOf()
                fireTo = nextFireColor()
                fireTransitionStart = now
                fireTransitionDuration = nextTransitionDuration()
                progress = 0f
            }

            // Smoothstep easing: no abrupt jumps between fire shades.
            val eased = progress * progress * (3f - 2f * progress)
            fun blend(index: Int): Int =
                (fireFrom[index] + (fireTo[index] - fireFrom[index]) * eased)
                    .toInt().coerceIn(0, 255)

            sendRGB(blend(0), blend(1), blend(2))
            handler.postDelayed(this, nextFireTick())
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
    private fun nextFireTick(): Long = (230L - speed * 9L).coerceIn(50L, 221L)

    private fun nextTransitionDuration(): Long {
        val base = 2600L - speed * 115L
        return Random.nextLong((base * 0.70f).toLong().coerceAtLeast(180L),
            (base * 1.30f).toLong().coerceAtLeast(260L))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
        requestBluetoothPermissions()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(18, 20, 25))
        }

        fun label(text: String, size: Float = 16f): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(Color.WHITE)
                setPadding(0, 12, 0, 8)
            }
        }

        fun button(text: String, action: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            }
        }

        fun addSlider(
            title: String,
            initial: Int,
            maxValue: Int,
            changed: (Int) -> Unit
        ) {
            val titleView = label("$title: $initial", 15f)
            root.addView(titleView)

            val bar = SeekBar(this).apply {
                max = maxValue
                progress = initial
            }

            root.addView(bar)

            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        titleView.text = "$title: $value"
                        if (fromUser) changed(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        root.addView(label("FIRELIGHT", 28f))
        root.addView(label("LED Bluetooth Controller", 14f))

        status = label("Статус: не підключено", 14f)
        root.addView(status)

        root.addView(
            button("Підключити ELK-BLEDOB") {
                startScan()
            }
        )

        root.addView(
            button("Від'єднати") {
                disconnect()
            }
        )

        root.addView(label("КЕРУВАННЯ КОЛЬОРОМ", 20f))

        preview = label("RGB: $red, $green, $blue", 16f)
        root.addView(preview)

        val colorWheel = ColorWheelView(this) { selectedColor ->
            red = Color.red(selectedColor)
            green = Color.green(selectedColor)
            blue = Color.blue(selectedColor)
            stopFireForManual()
            updatePreview()
            if (connected) sendCurrentColor()
        }
        val wheelSize = (280 * resources.displayMetrics.density).toInt()
        root.addView(colorWheel, LinearLayout.LayoutParams(wheelSize, wheelSize))

        addSlider("Яскравість", brightness, 100) {
            brightness = it
            if (!fireEnabled) sendCurrentColor()
        }

        addSlider("Швидкість вогню", speed, 20) {
            speed = it.coerceAtLeast(1)
        }

        fireButton = button("Увімкнути ефект вогню") {
            toggleFire()
        }

        root.addView(fireButton)

        root.addView(
            label(
                "Ефект вогню працює, поки застосунок відкритий.",
                13f
            )
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
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
                handler.removeCallbacks(fireTask)

                runOnUiThread {
                    status.text = "Від'єднано"
                    fireButton.text = "Увімкнути ефект вогню"
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
                    sendCurrentColor()
                } else {
                    status.text = "Не знайдено FFF0 / FFF3"
                }
            }
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
                runOnUiThread { status.text = "BLE не прийняв колір (код $result)" }
            } else {
                runOnUiThread {
                    status.text = "Підключено • RGB ${packet[4].toInt() and 255}, ${packet[5].toInt() and 255}, ${packet[6].toInt() and 255}"
                }
            }
        } catch (_: SecurityException) {
            runOnUiThread { status.text = "Помилка дозволу на запис Bluetooth" }
        }

        if (pendingPacket != null && !writeScheduled) {
            writeScheduled = true
            handler.postDelayed({ flushPendingColor() }, 65L)
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

        fireEnabled = !fireEnabled

        if (fireEnabled) {
            fireButton.text = "Вимкнути ефект вогню"
            fireFrom = intArrayOf(red, green, blue)
            fireTo = nextFireColor()
            fireTransitionStart = SystemClock.uptimeMillis()
            fireTransitionDuration = nextTransitionDuration()
            fireTask.run()
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
        fireButton.text = "Увімкнути ефект вогню"
    }

    override fun onDestroy() {
        stopScan()
        handler.removeCallbacks(fireTask)

        try {
            gatt?.close()
        } catch (_: Exception) {
        }

        super.onDestroy()
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
                    hsv[0] = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
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
        val markerAngle = Math.toRadians(selectedHue.toDouble())
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
        if (event.action != MotionEvent.ACTION_DOWN &&
            event.action != MotionEvent.ACTION_MOVE) return true
        val radius = minOf(width, height) / 2f
        val dx = event.x - radius
        val dy = radius - event.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        selectedSaturation = (distance / radius).coerceIn(0f, 1f)
        selectedHue = ((Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
        val color = Color.HSVToColor(floatArrayOf(selectedHue, selectedSaturation, 1f))
        onColorSelected(color)
        invalidate()
        return true
    }
}
