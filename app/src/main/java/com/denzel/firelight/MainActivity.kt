
package com.denzel.firelight

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.view.ViewGroup
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
    private var brightness = 75
    private var speed = 8

    private lateinit var status: TextView
    private lateinit var fireButton: Button
    private lateinit var preview: TextView

    private val fireTask = object : Runnable {
        override fun run() {
            if (!fireEnabled || !connected) return

            val pulse = Random.nextInt(-35, 26)
            val r = (225 + pulse).coerceIn(150, 255)
            val g = (Random.nextInt(48, 112) + pulse / 3)
                .coerceIn(25, 135)
            val b = Random.nextInt(0, 13)

            sendRGB(r, g, b)

            handler.postDelayed(
                this,
                (260L - speed * 10L).coerceIn(60L, 250L)
            )
        }
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
            button("Знайти ELK-BLEDOB") {
                startScan()
            }
        )

        root.addView(
            button("Від'єднати") {
                disconnect()
            }
        )

        root.addView(label("КЕРУВАННЯ КОЛЬОРОМ", 20f))

        preview = label("● Поточний колір", 20f)
        root.addView(preview)

        addSlider("Червоний", red, 255) {
            red = it
            stopFireForManual()
            sendCurrentColor()
            updatePreview()
        }

        addSlider("Зелений", green, 255) {
            green = it
            stopFireForManual()
            sendCurrentColor()
            updatePreview()
        }

        addSlider("Синій", blue, 255) {
            blue = it
            stopFireForManual()
            sendCurrentColor()
            updatePreview()
        }

        addSlider("Яскравість", brightness, 100) {
            brightness = it
            if (!fireEnabled) sendCurrentColor()
        }

        addSlider("Швидкість вогню", speed, 20) {
            speed = it.coerceAtLeast(1)
        }

        root.addView(
            button("Надіслати колір") {
                stopFireForManual()
                sendCurrentColor()
            }
        )

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

        scanner = adapter.bluetoothLeScanner

        status.text = "Статус: пошук контролера..."
        scanning = true

        try {
            scanner?.startScan(scanCallback)

            handler.postDelayed({
                if (scanning) {
                    stopScan()
                    if (!connected) {
                        status.text = "Контролер не знайдено"
                    }
                }
            }, 12000)

        } catch (e: SecurityException) {
            status.text = "Немає дозволу Bluetooth"
        }
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device

            val name = try {
                result.scanRecord?.deviceName ?: device.name
            } catch (e: SecurityException) {
                null
            }

            val address = try {
    device.address
} catch (e: SecurityException) {
    null
}

if (name?.trim()?.equals("ELK-BLEDOB", ignoreCase = true) == true ||
    address?.equals("BE:27:8A:00:73:DE", ignoreCase = true) == true
) {
    stopScan()
    connect(device)
}
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread {
                status.text = "Помилка пошуку: $errorCode"
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return

        scanning = false

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
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
        val char = characteristic ?: return

        val factor = brightness / 100.0

        val rr = (r * factor).toInt().coerceIn(0, 255)
        val gg = (g * factor).toInt().coerceIn(0, 255)
        val bb = (b * factor).toInt().coerceIn(0, 255)

        val packet = byteArrayOf(
            0x7E,
            0x07,
            0x05,
            0x03,
            rr.toByte(),
            gg.toByte(),
            bb.toByte(),
            0x10,
            0xEF.toByte()
        )

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt?.writeCharacteristic(
                    char,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                char.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                char.value = packet

                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(char)
            }
        } catch (_: SecurityException) {
            runOnUiThread {
                status.text = "Помилка Bluetooth"
            }
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
