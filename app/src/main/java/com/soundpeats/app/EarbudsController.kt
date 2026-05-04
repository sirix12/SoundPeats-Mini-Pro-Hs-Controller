package com.soundpeats.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class EarbudsController {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: java.io.InputStream? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _ancMode = MutableStateFlow<Int?>(null)
    val ancMode: StateFlow<Int?> = _ancMode.asStateFlow()

    private val _isGameMode = MutableStateFlow<Boolean?>(null)
    val isGameMode: StateFlow<Boolean?> = _isGameMode.asStateFlow()

    private val _isTouchEnabled = MutableStateFlow<Boolean?>(null)
    val isTouchEnabled: StateFlow<Boolean?> = _isTouchEnabled.asStateFlow()

    private val _eqPreset = MutableStateFlow<Int?>(null)
    val eqPreset: StateFlow<Int?> = _eqPreset.asStateFlow()

    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
    private var readJob: kotlinx.coroutines.Job? = null

    // ── PEQ Band Definitions ──────────────────────────────────────────────
    // WuQi WQ7033 PEQ engine: 7 bands identified by hex IDs 0x71–0x77.
    // Each band has a fixed center frequency (in Hz) and the hardware's
    // hardcoded Q-factor of 0xFCF4.

    data class PeqBand(
        val bandId: Byte,       // 0x71 – 0x77
        val frequencyHz: Int,   // Center frequency in Hz
        val qFactor: Int        // Q-factor hex
    )

    companion object {
        /** The 7 PEQ bands exposed by the hardware. */
        val PEQ_BANDS = listOf(
            PeqBand(0x71, 60, 0x1000),      // Sub-bass / Deep Rumble
            PeqBand(0x72, 180, 0x04cc),     // Mid-bass / Punch
            PeqBand(0x73, 540, 0x0ccc),     // Lower Mids / Warmth
            PeqBand(0x74, 1200, 0x0999),    // Mids / Vocals
            PeqBand(0x75, 3000, 0x08cc),    // Upper Mids / Attack
            PeqBand(0x76, 6600, 0x2000),    // Treble / Sibilance
            PeqBand(0x77, 15000, 0x0ccc)    // High Treble / Air
        )

        /** Delay between sequential band packets (ms) to prevent buffer drops. */
        private const val INTER_BAND_DELAY_MS: Long = 100L

        /** Slider range in dB – the UI exposes -12 to +10 dB. */
        const val MIN_GAIN_DB = -12
        const val MAX_GAIN_DB = 10
    }

    // ── Connection ────────────────────────────────────────────────────────

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothSocket?.connect()
            outputStream = bluetoothSocket?.outputStream
            inputStream = bluetoothSocket?.inputStream
            _isConnected.value = true
            
            readJob = scope.launch { listenToSocket() }
            
            // Query configurations after connection
            scope.launch {
                delay(500)
                queryConfigurations()
            }
            
            true
        } catch (e: IOException) {
            Log.e("EarbudsController", "Connection failed", e)
            disconnect()
            false
        }
    }

    suspend fun autoConnect(devices: List<BluetoothDevice>): Boolean = withContext(Dispatchers.IO) {
        // Prioritize devices that are currently connected to the phone
        val sortedDevices = devices.sortedByDescending { device ->
            try {
                val method = device.javaClass.getMethod("isConnected")
                method.invoke(device) as Boolean
            } catch (e: Exception) {
                false
            }
        }

        for (device in sortedDevices) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                inputStream = bluetoothSocket?.inputStream
                _isConnected.value = true
                
                readJob = scope.launch { listenToSocket() }
                
                // Clear any previous state
                _ancMode.value = null
                
                // Send query to verify if it's a Soundpeats device
                sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x00, 0x00, 0x0a, 0x03, 0x11))
                
                // Wait up to 2 seconds for a response
                var timeout = 0
                while (_ancMode.value == null && timeout < 20 && _isConnected.value) {
                    delay(100)
                    timeout++
                }
                
                if (_ancMode.value != null) {
                    // Valid response received! It is a Soundpeats device.
                    scope.launch {
                        delay(100)
                        queryConfigurations()
                    }
                    return@withContext true
                } else {
                    // No valid response, probably not our device
                    disconnect()
                }
            } catch (e: Exception) {
                disconnect()
            }
        }
        return@withContext false
    }

    private suspend fun queryConfigurations() = withContext(Dispatchers.IO) {
        var retries = 0
        while (_isConnected.value && retries < 5) {
            if (_ancMode.value == null) {
                // Query ANC
                sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x00, 0x00, 0x0a, 0x03, 0x11))
                delay(150)
            }
            if (_isGameMode.value == null) {
                // Query Game Mode
                sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x00, 0x00, 0x0a, 0x03, 0x0f))
                delay(150)
            }
            if (_isTouchEnabled.value == null) {
                // Query Touch Sensors
                sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x00, 0x00, 0x0a, 0x03, 0x13))
                delay(150)
            }
            if (_eqPreset.value == null) {
                // Query EQ Preset
                sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x00, 0x00, 0x0a, 0x03, 0x06))
                delay(150)
            }
            
            if (_ancMode.value != null && _isGameMode.value != null && _isTouchEnabled.value != null && _eqPreset.value != null) {
                break
            }
            delay(500)
            retries++
        }
    }

    private suspend fun listenToSocket() {
        val buffer = ByteArray(1024)
        val packetBuffer = mutableListOf<Byte>()
        while (_isConnected.value) {
            try {
                val bytes = inputStream?.read(buffer) ?: -1
                if (bytes == -1) {
                    disconnect()
                    break
                }
                for (i in 0 until bytes) {
                    packetBuffer.add(buffer[i])
                }
                
                while (packetBuffer.isNotEmpty()) {
                    val idx = packetBuffer.indexOf(0xff.toByte())
                    if (idx == -1) {
                        packetBuffer.clear()
                        break
                    }
                    if (idx > 0) {
                        repeat(idx) { packetBuffer.removeAt(0) }
                    }
                    
                    if (packetBuffer.size >= 9) {
                        if (packetBuffer[1] == 0x04.toByte() && packetBuffer[5] == 0x0a.toByte() && packetBuffer[6] == 0x03.toByte()) {
                            val id = packetBuffer[7]
                            val value = packetBuffer[8]
                            when (id) {
                                0x11.toByte() -> _ancMode.value = value.toInt()
                                0x0f.toByte() -> _isGameMode.value = value == 0x01.toByte()
                                0x13.toByte() -> _isTouchEnabled.value = value == 0x01.toByte()
                                0x06.toByte() -> _eqPreset.value = value.toInt()
                            }
                            repeat(9) { packetBuffer.removeAt(0) }
                        } else if (packetBuffer.size >= 10) {
                            if (packetBuffer[1] == 0x04.toByte() && packetBuffer[6] == 0x83.toByte()) {
                                if (packetBuffer[7] == 0x10.toByte() && packetBuffer[8] == 0x00.toByte()) {
                                    _ancMode.value = packetBuffer[9].toInt()
                                } else if (packetBuffer[7] == 0x0e.toByte() && packetBuffer[8] == 0x00.toByte()) {
                                    _isGameMode.value = packetBuffer[9] == 0x01.toByte()
                                }
                                repeat(10) { packetBuffer.removeAt(0) }
                            } else {
                                packetBuffer.removeAt(0)
                            }
                        } else {
                            // Need more data for old format check
                            break
                        }
                    } else {
                        // Need more data for new format check
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("EarbudsController", "Read failed", e)
                disconnect()
                break
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e("EarbudsController", "Close failed", e)
        }
        bluetoothSocket = null
        outputStream = null
        inputStream = null
        _isConnected.value = false
        _ancMode.value = null
        _isGameMode.value = null
        _isTouchEnabled.value = null
        _eqPreset.value = null
    }

    // ── Low-level send ────────────────────────────────────────────────────

    private fun sendCommand(command: ByteArray) {
        if (_isConnected.value) {
            try {
                outputStream?.write(command)
                outputStream?.flush()
                Log.d("EarbudsController", "Sent: ${command.joinToString(" ") { "%02x".format(it) }}")
            } catch (e: IOException) {
                Log.e("EarbudsController", "Send failed", e)
                disconnect()
            }
        }
    }

    // ── ANC ───────────────────────────────────────────────────────────────

    fun setAncMode(mode: Int) {
        val validMode = mode.coerceIn(0, 2)
        val payload = byteArrayOf(
            0xff.toByte(), 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x11, validMode.toByte()
        )
        sendCommand(payload)
    }

    // ── Game Mode ─────────────────────────────────────────────────────────

    fun setGameMode(enabled: Boolean) {
        val modeByte: Byte = if (enabled) 0x01 else 0x00
        val payload = byteArrayOf(
            0xff.toByte(), 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x0f, modeByte
        )
        sendCommand(payload)
    }

    // ── Touch Sensors ─────────────────────────────────────────────────────

    fun setTouchEnabled(enabled: Boolean) {
        val modeByte: Byte = if (enabled) 0x01 else 0x00
        val payload = byteArrayOf(
            0xff.toByte(), 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x13, modeByte
        )
        sendCommand(payload)
    }

    // ── PEQ (Parametric EQ) ───────────────────────────────────────────────
    //
    // The WuQi WQ7033 chipset uses a Parametric EQ engine, NOT a Graphic EQ.
    // Each band must be configured individually with a dedicated 17-byte packet:
    //
    //   ff 04 00 09 00  – Header & payload length (9 bytes follow)
    //   1d 0e 01        – PEQ engine route
    //   <bandId>        – Target band (0x71–0x77)
    //   <qFactor 2B>    – Q-factor / bandwidth (hardcoded to 0xFCF4)
    //   <freq 2B>       – Center frequency in Hz (big-endian)
    //   <gain 2B>       – Gain = dB × 100, 16-bit signed (two's complement)
    //   <footer 2B>     – PEQ footer (0x1000)
    //
    // Bands must be sent sequentially with ~100ms delay to prevent buffer drops.

    /**
     * Convert a gain in dB (e.g. +10, -6, +6.5) to a 16-bit signed value
     * using the hardware's multiplier: gainDb × 100, encoded as two's complement.
     *
     * Examples:
     *   +10.0 dB → 1000  → 0x03E8
     *    +6.5 dB →  650  → 0x028A
     *     0.0 dB →    0  → 0x0000
     *    -6.0 dB → -600  → 0xFDA8
     *   -12.0 dB → -1200 → 0xFB50
     */
    private fun gainDbToHex(gainDb: Float): Int {
        val raw = (gainDb * 100).toInt()
        // Mask to 16-bit unsigned for two's complement representation
        return raw and 0xFFFF
    }

    /**
     * Build a single 17-byte PEQ instruction packet for one band.
     */
    private fun buildPeqPacket(band: PeqBand, gainDb: Float): ByteArray {
        val clampedGain = gainDb.coerceIn(MIN_GAIN_DB.toFloat(), MAX_GAIN_DB.toFloat())
        val gainHex = gainDbToHex(clampedGain)
        val freqHex = band.frequencyHz
        val qHex = band.qFactor

        return byteArrayOf(
            // Header & payload length (9 bytes of payload follow)
            0xff.toByte(), 0x04, 0x00, 0x09, 0x00,
            // PEQ engine route
            0x1d, 0x0e, 0x01,
            // Target band
            band.bandId,
            // Magic bytes FCF4
            0xfc.toByte(), 0xf4.toByte(),
            // Center frequency (big-endian, 2 bytes)
            (freqHex shr 8 and 0xFF).toByte(),
            (freqHex and 0xFF).toByte(),
            // Gain (big-endian, 2 bytes) – dB × 100, two's complement
            (gainHex shr 8 and 0xFF).toByte(),
            (gainHex and 0xFF).toByte(),
            // Q-Factor (big-endian, 2 bytes)
            (qHex shr 8 and 0xFF).toByte(),
            (qHex and 0xFF).toByte()
        )
    }

    /**
     * Send a single PEQ band update. Use this when only one slider changed.
     * To commit the change to the physical audio, we must ensure Band 7 is sent.
     *
     * @param bandIndex  0-based index into [PEQ_BANDS] (0–6)
     * @param gainDb     Gain in dB for the target band
     * @param band7Gain  Gain in dB for Band 7 (needed for commit)
     */
    suspend fun setEqBand(bandIndex: Int, gainDb: Float, band7Gain: Float) {
        if (bandIndex !in PEQ_BANDS.indices) return
        withContext(Dispatchers.IO) {
            // Force Custom EQ bank
            sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x06, 0x09))
            delay(100)
            
            val packet = buildPeqPacket(PEQ_BANDS[bandIndex], gainDb)
            sendCommand(packet)
            
            if (bandIndex != 6) {
                delay(INTER_BAND_DELAY_MS)
                val commitPacket = buildPeqPacket(PEQ_BANDS[6], band7Gain)
                sendCommand(commitPacket)
            }
        }
    }

    /**
     * Send all 7 PEQ bands sequentially with a delay between each packet.
     * Must be called from a coroutine (suspending function).
     *
     * @param gainsDb  List of 7 gain values in dB
     */
    suspend fun setCustomEq(gainsDb: List<Float>) {
        if (gainsDb.size != PEQ_BANDS.size) return

        withContext(Dispatchers.IO) {
            // Force Custom EQ bank
            sendCommand(byteArrayOf(0xff.toByte(), 0x04, 0x00, 0x01, 0x00, 0x0a, 0x03, 0x06, 0x09))
            delay(100)

            for (i in PEQ_BANDS.indices) {
                val packet = buildPeqPacket(PEQ_BANDS[i], gainsDb[i])
                sendCommand(packet)
                // The PEQ engine is slow — wait between bands to prevent buffer drops
                if (i < PEQ_BANDS.lastIndex) {
                    delay(INTER_BAND_DELAY_MS)
                }
            }
        }
        Log.d("EarbudsController", "All 7 PEQ bands sent successfully")
    }
}
