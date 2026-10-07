/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

/** A Tello motorjait kizárólag a [killMotors] kapcsolhatja le azonnal. */
class TelloFlightSession(private val context: Context? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = InetAddress.getByName("192.168.10.1")
    private val commandPort = 8889
    private val _telemetry = MutableStateFlow(TelloTelemetry())
    val telemetry: StateFlow<TelloTelemetry> = _telemetry.asStateFlow()

    private var dangerousTiltSamples = 0
    private var keepAliveJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var telemetrySocket: DatagramSocket? = null

    init {
        bindProcessToDroneWifi()
    }

    private fun bindProcessToDroneWifi() {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
            }
        }
        networkCallback?.let { cm.requestNetwork(request, it) }
    }

    fun connect() {
        send("command")
        startKeepAlive()
    }

    fun startVideoStream() = send("streamon")
    fun stopVideoStream() = send("streamoff")
    fun takeOff() = send("takeoff")
    fun land() = send("land")

    fun stopAndHover() = send("rc 0 0 0 0")
    fun killMotors() = send("emergency")
    fun flip(direction: FlipDirection) = send("flip ${direction.sdkValue}")

    fun sendControl(leftRight: Int, forwardBack: Int, upDown: Int, yaw: Int, fast: Boolean) {
        val scale = if (fast) 1f else 0.35f
        fun scaled(value: Int) = (value * scale).toInt().coerceIn(-100, 100)
        send("rc ${scaled(leftRight)} ${scaled(forwardBack)} ${scaled(upDown)} ${scaled(yaw)}")
    }

    fun forceTakeoffBypass(throttlePercent: Int = 60) {
        send("rc 0 0 $throttlePercent 0")
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(5000)
                send("command")
            }
        }
    }

    fun onTelemetry(raw: Map<String, Int>) {
        val pitch = raw["pitch"] ?: 0
        val roll = raw["roll"] ?: 0
        val temp = raw["temph"] ?: raw["templ"] ?: 0
        val height = raw["h"] ?: 0
        val tof = raw["tof"] ?: 0
        val bat = raw["bat"] ?: _telemetry.value.battery

        _telemetry.value = TelloTelemetry(
            battery = bat,
            temperatureC = temp,
            pitch = pitch,
            roll = roll,
            heightCm = height * 10,
            tofCm = tof,
            connected = true
        )

        dangerousTiltSamples = if (abs(pitch) >= 50 || abs(roll) >= 50) dangerousTiltSamples + 1 else 0
        if (dangerousTiltSamples >= 5) {
            stopAndHover()
            dangerousTiltSamples = 0
        }
    }

    private var telemetryJob: Job? = null
    private var simulationJob: Job? = null
    var simulationMode = false
        set(value) {
            field = value
            if (value) {
                startSimulation()
            } else {
                simulationJob?.cancel()
                simulationJob = null
                startTelemetry()
            }
        }

    private fun startSimulation() {
        telemetryJob?.cancel()
        telemetrySocket?.close()
        simulationJob?.cancel()
        simulationJob = scope.launch {
            var time = 0.0
            while (isActive) {
                val pitchOscillation = (kotlin.math.sin(time) * 15).toInt()
                val rollOscillation = (kotlin.math.cos(time * 0.8) * 10).toInt()
                _telemetry.value = TelloTelemetry(
                    battery = 85,
                    temperatureC = 42,
                    pitch = pitchOscillation,
                    roll = rollOscillation,
                    heightCm = 120,
                    tofCm = 120,
                    connected = true
                )
                time += 0.1
                delay(100) // 10Hz
            }
        }
    }

    fun startTelemetry() {
        if (simulationMode) {
            startSimulation()
            return
        }
        telemetryJob?.cancel()
        telemetrySocket?.close()
        simulationJob?.cancel()
        telemetryJob = scope.launch {
            runCatching {
                val currentSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(8890))
                    soTimeout = 2000
                }
                telemetrySocket = currentSocket
                
                val buffer = ByteArray(1024)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        currentSocket.receive(packet)
                        val data = packet.data.decodeToString(0, packet.length)
                            .split(';')
                            .mapNotNull { item ->
                                val pair = item.split(':', limit = 2)
                                if (pair.size == 2) {
                                    val value = pair[1].toIntOrNull()
                                    if (value != null) pair[0] to value else null
                                } else null
                            }.toMap()
                        onTelemetry(data)
                    } catch (e: java.net.SocketTimeoutException) {
                        _telemetry.value = _telemetry.value.copy(connected = false)
                    } catch (e: Exception) {
                        break
                    }
                }
            }.also {
                if (telemetrySocket?.isClosed == false) {
                    telemetrySocket?.close()
                }
            }
        }
    }

    fun close() {
        telemetrySocket?.close()
        telemetrySocket = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        simulationJob?.cancel()
        simulationJob = null
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cm?.unregisterNetworkCallback(it) }
        cm?.bindProcessToNetwork(null)
        scope.cancel()
    }

    private fun send(command: String) = scope.launch {
        runCatching {
            DatagramSocket().use { socket ->
                val bytes = command.encodeToByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, host, commandPort))
            }
        }
    }
}

enum class FlipDirection(val sdkValue: String, val label: String) {
    FORWARD("f", "ELŐRE"),
    BACK("b", "HÁTRA"),
    LEFT("l", "BALRA"),
    RIGHT("r", "JOBBRA")
}

data class TelloTelemetry(
    val battery: Int = 0,
    val temperatureC: Int = 0,
    val pitch: Int = 0,
    val roll: Int = 0,
    val heightCm: Int = 0,
    val tofCm: Int = 0,
    val connected: Boolean = false
)
