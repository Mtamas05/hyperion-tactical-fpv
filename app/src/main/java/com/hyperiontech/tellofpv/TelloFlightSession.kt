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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.abs

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    ONLINE
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

/** A Tello motorjait kizárólag a [killMotors] kapcsolhatja le azonnal. */
class TelloFlightSession(private val context: Context? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val host = InetAddress.getByName("192.168.10.1")
    private val commandPort = 8889

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(TelloTelemetry())
    val telemetry: StateFlow<TelloTelemetry> = _telemetry.asStateFlow()

    private var dangerousTiltSamples = 0
    private var connectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var telemetryJob: Job? = null
    private var simulationJob: Job? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val networkBoundSignal = CompletableDeferred<Boolean>()
    @Volatile var isNetworkBound: Boolean = false
        private set

    @Volatile private var commandSocket: DatagramSocket? = null
    @Volatile private var telemetrySocket: DatagramSocket? = null
    private val sendMutex = Mutex()

    init {
        bindProcessToDroneWifi()
    }

    private fun bindProcessToDroneWifi() {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            isNetworkBound = true
            if (!networkBoundSignal.isCompleted) networkBoundSignal.complete(true)
            return
        }

        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                runCatching { cm.bindProcessToNetwork(active) }
                isNetworkBound = true
                if (!networkBoundSignal.isCompleted) networkBoundSignal.complete(true)
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { cm.bindProcessToNetwork(network) }
                isNetworkBound = true
                if (!networkBoundSignal.isCompleted) {
                    networkBoundSignal.complete(true)
                }
                if (_connectionState.value == ConnectionState.DISCONNECTED) {
                    connect()
                }
            }

            override fun onLost(network: Network) {
                isNetworkBound = false
                _connectionState.value = ConnectionState.DISCONNECTED
                _telemetry.value = _telemetry.value.copy(connected = false)
            }
        }

        runCatching {
            networkCallback?.let { cm.requestNetwork(request, it) }
        }.onFailure {
            isNetworkBound = true
            if (!networkBoundSignal.isCompleted) {
                networkBoundSignal.complete(true)
            }
        }
    }

    private fun getOrCreateCommandSocket(): DatagramSocket {
        val existing = commandSocket
        if (existing != null && !existing.isClosed) {
            return existing
        }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 1500
            bind(InetSocketAddress(0))
        }
        commandSocket = socket
        return socket
    }

    private suspend fun sendAndAwaitReply(command: String, timeoutMs: Int = 1500): String? = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            runCatching {
                val socket = getOrCreateCommandSocket()

                // Kiürítjük az esetleges beragadt korábbi csomagokat
                val drainBuffer = ByteArray(256)
                val drainPacket = DatagramPacket(drainBuffer, drainBuffer.size)
                socket.soTimeout = 1
                while (true) {
                    try {
                        socket.receive(drainPacket)
                    } catch (_: Exception) {
                        break
                    }
                }

                // Parancs küldése
                val bytes = command.encodeToByteArray()
                val packet = DatagramPacket(bytes, bytes.size, host, commandPort)
                socket.soTimeout = timeoutMs
                socket.send(packet)

                // Válasz fogadása
                val receiveBuffer = ByteArray(256)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(receivePacket)
                receivePacket.data.decodeToString(0, receivePacket.length).trim()
            }.getOrNull()
        }
    }

    fun connect() {
        if (simulationMode) {
            _connectionState.value = ConnectionState.ONLINE
            _telemetry.value = _telemetry.value.copy(connected = true)
            return
        }

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            _connectionState.value = ConnectionState.CONNECTING

            // 1. Megvárjuk a Wi-Fi hálózati kötést (max 3 mp)
            withTimeoutOrNull(3000) {
                networkBoundSignal.await()
            }

            // 2. Kézfogás küldése retry mechanizmussal (3 próbálkozás, 1.5s timeout)
            var handshakeOk = false
            for (attempt in 1..3) {
                if (!isActive) break
                val reply = sendAndAwaitReply("command", timeoutMs = 1500)
                if (reply != null && reply.contains("ok", ignoreCase = true)) {
                    handshakeOk = true
                    break
                }
                delay(300)
            }

            if (handshakeOk) {
                _connectionState.value = ConnectionState.ONLINE
                _telemetry.value = _telemetry.value.copy(connected = true)
                startKeepAlive()
                startTelemetry()
                startVideoStream()
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                _telemetry.value = _telemetry.value.copy(connected = false)
            }
        }
    }

    fun startVideoStream() {
        if (_connectionState.value == ConnectionState.ONLINE) {
            send("streamon")
        }
    }

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
        keepAliveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(4000)
                if (_connectionState.value == ConnectionState.ONLINE) {
                    send("command")
                }
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
        if (_connectionState.value != ConnectionState.ONLINE) {
            _connectionState.value = ConnectionState.ONLINE
        }

        dangerousTiltSamples = if (abs(pitch) >= 50 || abs(roll) >= 50) dangerousTiltSamples + 1 else 0
        if (dangerousTiltSamples >= 5) {
            stopAndHover()
            dangerousTiltSamples = 0
        }
    }

    var simulationMode = false
        set(value) {
            field = value
            if (value) {
                startSimulation()
            } else {
                simulationJob?.cancel()
                simulationJob = null
                startTelemetry()
                connect()
            }
        }

    private fun startSimulation() {
        telemetryJob?.cancel()
        telemetrySocket?.close()
        simulationJob?.cancel()
        _connectionState.value = ConnectionState.ONLINE
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
        telemetryJob = scope.launch(Dispatchers.IO) {
            // Megvárjuk a hálózati kötést a telemetria socket előtt is
            withTimeoutOrNull(3000) {
                networkBoundSignal.await()
            }

            runCatching {
                val currentSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8890))
                    soTimeout = 2500
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
                        if (_connectionState.value == ConnectionState.ONLINE) {
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
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
        connectJob?.cancel()
        connectJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        simulationJob?.cancel()
        simulationJob = null

        commandSocket?.close()
        commandSocket = null
        telemetrySocket?.close()
        telemetrySocket = null

        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let {
            runCatching { cm?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        runCatching { cm?.bindProcessToNetwork(null) }
        scope.cancel()
    }

    fun send(command: String) = scope.launch(Dispatchers.IO) {
        runCatching {
            val socket = getOrCreateCommandSocket()
            val bytes = command.encodeToByteArray()
            val packet = DatagramPacket(bytes, bytes.size, host, commandPort)
            sendMutex.withLock {
                socket.send(packet)
            }
        }
    }
}
