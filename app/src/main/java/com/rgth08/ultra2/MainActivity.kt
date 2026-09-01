package com.rgth08.ultra2

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val DEVICE_MAC = "27:E2:F7:00:08:ED"
    private val SERVICE_UUID = UUID.fromString("6e400801-b5a3-f393-e0a9-e50e24dcca9d")
    private val CHAR_WRITE = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9d")
    private val CHAR_NOTIFY = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9d")
    private val DESCRIPTOR_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var isConnected by mutableStateOf(false)
    private var status by mutableStateOf("Desconectado")
    private var logs by mutableStateOf(listOf<String>())

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                this@MainActivity.status = "Conectado"
                addLog("✅ Conectado a $DEVICE_MAC")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false
                this@MainActivity.status = "Desconectado"
                addLog("❌ Desconectado")
                this@MainActivity.gatt = null
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@MainActivity.status = "Listo"
                addLog("📡 Servicios descubiertos")
                enableNotifications(gatt)
            } else {
                this@MainActivity.status = "Error servicios"
                addLog("❌ Error descubriendo servicios: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("✅ Escritura exitosa")
            } else {
                addLog("❌ Escritura falló: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            val data = char.value
            if (data != null) {
                addLog("📩 Notificación: ${bytesToHex(data)}")
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID)
        val char = service?.getCharacteristic(CHAR_NOTIFY)
        if (char != null) {
            gatt.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(DESCRIPTOR_CCCD)
            desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(desc)
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logs = logs + "[$ts] $msg"
        Log.d("Ultra2", msg)
    }

    private fun sendCommand(cmd: Int, key: Int, payload: ByteArray): Boolean {
        val g = gatt ?: return false
        val service = g.getService(SERVICE_UUID) ?: return false
        val char = service.getCharacteristic(CHAR_WRITE) ?: return false

        val totalLen = 1 + 2 + 1 + 1 + 1 + 2 + payload.size
        val buffer = java.nio.ByteBuffer.allocate(totalLen)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(0xCD.toByte())
        buffer.putShort(totalLen.toShort())
        buffer.put(cmd.toByte())
        buffer.put(0x01)
        buffer.put(key.toByte())
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)

        val packet = buffer.array()
        var offset = 0
        val mtu = 20
        while (offset < packet.size) {
            val chunkSize = minOf(mtu, packet.size - offset)
            val chunk = packet.copyOfRange(offset, offset + chunkSize)
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!g.writeCharacteristic(char)) {
                addLog("❌ Falló escritura en offset $offset")
                return false
            }
            offset += chunkSize
            Thread.sleep(50)
        }
        addLog("✅ Comando enviado (${packet.size} bytes)")
        return true
    }

    // Funciones públicas
    fun vibrate() {
        val payload = byteArrayOf(0x11, 0x18, 0x00, 0x00)
        sendCommand(0x01, 0x01, payload)
    }

    fun findPhone() {
        val payload = byteArrayOf(0x01, 0x00)
        sendCommand(0x02, 0x01, payload)
    }

    fun testImage(key: Int) {
        val bmp = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.RGB_565)
        for (y in 0 until 240) {
            for (x in 0 until 240) {
                val color = if (x < 100 && y < 100) android.graphics.Color.RED else android.graphics.Color.BLACK
                bmp.setPixel(x, y, color)
            }
        }
        val pixels = IntArray(240 * 240)
        bmp.getPixels(pixels, 0, 240, 0, 0, 240, 240)
        val buffer = java.nio.ByteBuffer.allocate(240 * 240 * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val rgb565 = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)
            buffer.putShort(rgb565.toShort())
        }
        val payload = buffer.array()
        sendCommand(0x1F, key, payload)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                ActivityCompat.requestPermissions(this, perms, 1)
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Ultra2 Companion", style = MaterialTheme.typography.headlineSmall)
                        Text("Estado: $status", style = MaterialTheme.typography.bodyLarge)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { connect() }, enabled = !isConnected) { Text("Conectar") }
                            Button(onClick = { disconnect() }, enabled = isConnected) { Text("Desconectar") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vibrate() }, enabled = isConnected) { Text("Vibrar") }
                            Button(onClick = { findPhone() }, enabled = isConnected) { Text("Buscar") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { testImage(0x01) }, enabled = isConnected) { Text("Img Key=01") }
                            Button(onClick = { testImage(0x1F) }, enabled = isConnected) { Text("Img Key=1F") }
                        }

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(logs) { log ->
                                Text(log, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun connect() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status = "Sin permiso"
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            status = "BT no disponible"
            return
        }
        val device = adapter.getRemoteDevice(DEVICE_MAC) ?: run {
            status = "No se encontró $DEVICE_MAC"
            return
        }
        status = "Conectando..."
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
        status = "Desconectado"
        addLog("Desconectado manualmente")
    }
}
