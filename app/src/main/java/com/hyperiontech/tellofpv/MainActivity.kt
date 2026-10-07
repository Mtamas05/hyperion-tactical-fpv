/**
 * Copyright (c) 2026 Hyperion Tech SRL
 * Developed by Major Tamás
 * Autonomous FPV Flight Station & Edge AI Tracking Engine
 */
package com.hyperiontech.tellofpv

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream

class MainActivity : ComponentActivity() {
    private var gamepadHandler: GamepadHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val windowInsetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE

        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = Color.Black)) {
                FpvScreen(onRegisterGamepad = { gamepadHandler = it })
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadHandler?.handleMotionEvent(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadHandler?.handleKeyEvent(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (gamepadHandler?.handleKeyEvent(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }
}

// 2026 Cyber-Tactical Színpaletta
private val GlassDark = Color(0xCC0D131A)
private val GlassBorder = Color(0x3300FF66)
private val NeonGreen = Color(0xFF00FF66)
private val CyberCyan = Color(0xFF00F0FF)
private val WarningRed = Color(0xFFFF2A4B)

@Composable
private fun FpvScreen(onRegisterGamepad: (GamepadHandler) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { TelloFlightSession(context) }
    val videoDecoder = remember { TelloVideoDecoder(scope) }
    val videoRecorder = remember { TelloStreamRecorder(context).also { videoDecoder.recorder = it } }
    val blackbox = remember { TelloBlackboxRecorder(context, scope) }
    val missionEngine = remember { TelloMissionEngine(session, scope) }
    val activeTracker = remember { TelloActiveTracker(session) }
    val yoloDetector = remember { YoloDetector(context) }

    
    val missionStatus by missionEngine.status.collectAsState()
    val isMissionRunning by missionEngine.isRunning.collectAsState()
    val isTracking by activeTracker.isTracking.collectAsState()
    val trackedTarget by activeTracker.currentTarget.collectAsState()

    val isConnected by remember(session) {
        session.telemetry.map { it.connected }.distinctUntilChanged()
    }.collectAsState(initial = false)

    var activeSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var trackingJob by remember { mutableStateOf<Job?>(null) }

    var drawerOpen by remember { mutableStateOf(false) }
    var fastMode by remember { mutableStateOf(false) }
    var isFlying by remember { mutableStateOf(false) }
    var flightStartTimeMs by remember { mutableStateOf<Long?>(null) }
    var isRecordingBlackbox by remember { mutableStateOf(false) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    val rssi = remember { wifiRssi(context) }

    val currentFastMode by androidx.compose.runtime.rememberUpdatedState(fastMode)
    val currentIsFlying by androidx.compose.runtime.rememberUpdatedState(isFlying)
    val currentIsTracking by androidx.compose.runtime.rememberUpdatedState(isTracking)

    androidx.compose.runtime.LaunchedEffect(session) {
            session.connect()
            session.startTelemetry()
        }

        DisposableEffect(Unit) {
            val handler = GamepadHandler(
                onAxesChanged = { r, p, t, y -> session.sendControl(r, p, t, y, currentFastMode) },
                onTakeoffLand = {
                    if (currentIsFlying) {
                        session.land()
                        isFlying = false
                    } else {
                        session.takeOff()
                        isFlying = true
                    }
                },
                onHover = { session.stopAndHover() },
                onKillSwitch = { 
                    trackingJob?.cancel()
                    if (currentIsTracking) activeTracker.stopTracking()
                    videoRecorder.stop()
                    missionEngine.abort()
                    session.killMotors()
                    isFlying = false
                },
                onSpeedToggle = { fastMode = !currentFastMode }
            )
            onRegisterGamepad(handler)
            onDispose {}
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    videoRecorder.stop()
                    trackingJob?.cancel()
                    activeTracker.stopTracking()
                    missionEngine.abort()
                    blackbox.stop()
                    videoDecoder.stop()
                    session.stopVideoStream()
                } else if (event == Lifecycle.Event.ON_RESUME) {
                    session.startVideoStream()
                    // videoDecoder starts in surfaceCreated
                } else if (event == Lifecycle.Event.ON_DESTROY) {
                    yoloDetector.close()
                    session.close()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }


    

    Box(Modifier.fillMaxSize().background(Color(0xFF05080C))) {
        // 1. Videostream háttér
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    activeSurfaceView = this
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            videoDecoder.start(holder.surface)
                            session.startVideoStream()
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            activeSurfaceView = null
                            videoDecoder.stop()
                            session.stopVideoStream()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Professzionális Taktikai FPV Műszerfal (Pitch Ladder + Műhorizont)
        TacticalFpvOverlay(session = session, trackedTarget = trackedTarget, modifier = Modifier.fillMaxSize())

        // 3. Felső Taktikai Telemetria Sáv (Bal oldalt)
        Box(modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 16.dp)) {
            TacticalTopHud(session, rssi, flightStartTimeMs)
        }

        // 3/B. Dinamikus Badgek (Középen)
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRecordingVideo) {
                    TacticalBadge("REC MP4", WarningRed)
                }
                if (isRecordingBlackbox) {
                    TacticalBadge("BLACKBOX", Color(0xFFFF9100))
                }
                if (isTracking) {
                    TacticalBadge("ACTIVE-TRACK", CyberCyan)
                }
            }

            if (isMissionRunning) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xEE2A0845))
                        .border(1.dp, CyberCyan, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = NeonGreen, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(missionStatus, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 4. Jobb felső Lebegő Sziget (Kameravezérlők)
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 24.dp, top = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassDark)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            HudIconButton(icon = Icons.Default.CameraAlt, label = "FOTÓ", color = NeonGreen, enabled = isConnected) {
                saveSnapshot(context, activeSurfaceView)
            }

            HudIconButton(
                icon = if (isRecordingVideo) Icons.Default.Stop else Icons.Default.Videocam,
                label = if (isRecordingVideo) "STOP" else "VIDEÓ",
                color = if (isRecordingVideo) WarningRed else Color.White,
                enabled = isConnected || isRecordingVideo
            ) {
                if (isRecordingVideo) {
                    videoRecorder.stop()
                    isRecordingVideo = false
                    Toast.makeText(context, "Videó mentve a Movies mappába!", Toast.LENGTH_SHORT).show()
                } else {
                    videoRecorder.start()
                    isRecordingVideo = true
                }
            }

            HudIconButton(
                icon = Icons.Default.CenterFocusStrong,
                label = if (isTracking) "TRACK KI" else "AI TRACK",
                color = if (isTracking) CyberCyan else Color.White,
                enabled = isConnected || isTracking
            ) {
                if (isTracking) {
                    trackingJob?.cancel()
                    trackingJob = null
                    activeTracker.stopTracking()
                } else {
                    missionEngine.abort()
                    activeTracker.startTracking()
                    val sv = activeSurfaceView
                    if (sv != null && sv.holder.surface.isValid) {
                        trackingJob = scope.launch(Dispatchers.Default) {
                            val handlerThread = android.os.HandlerThread("PixelCopyThread").apply { start() }
                            val handler = Handler(handlerThread.looper)
                            var trackingBitmap: Bitmap? = null
                            try {
                                while (isActive && activeTracker.isTracking.value) {
                                    val w = sv.width.coerceAtLeast(1)
                                    val h = sv.height.coerceAtLeast(1)
                                    if (trackingBitmap == null || trackingBitmap.width != w || trackingBitmap.height != h) {
                                        trackingBitmap?.recycle()
                                        trackingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    }
                                    val currentBitmap = trackingBitmap ?: continue

                                    val latch = java.util.concurrent.CountDownLatch(1)
                                    PixelCopy.request(sv, currentBitmap, { result ->
                                        if (result == PixelCopy.SUCCESS) {
                                            val target = yoloDetector.detect(currentBitmap)
                                            activeTracker.onFrameDetection(target)
                                        } else {
                                            activeTracker.onFrameDetection(null)
                                        }
                                        latch.countDown()
                                    }, handler)
                                    latch.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                                }
                            } finally {
                                handlerThread.quitSafely()
                                trackingBitmap?.recycle()
                            }
                        }
                    } else {
                        Toast.makeText(context, "Nincs videó a követéshez!", Toast.LENGTH_SHORT).show()
                        activeTracker.stopTracking()
                    }
                }
            }

            HudIconButton(
                icon = Icons.Default.Speed,
                label = if (fastMode) "100%" else "35%",
                color = if (fastMode) Color(0xFF00E676) else Color.Gray
            ) {
                fastMode = !fastMode
            }
        }

        // 5. Virtuális Joystickok (Futurisztikus kör- és célkereszt design)
        TacticalJoystick(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 32.dp),
            label = "MAGASSÁG / FORGÁS",
            onMove = { x, y -> session.sendControl(0, 0, (-y * 100).toInt(), (x * 100).toInt(), fastMode) }
        )

        TacticalJoystick(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp),
            label = "IRÁNY (ELŐRE/OLDAL)",
            onMove = { x, y -> session.sendControl((x * 100).toInt(), (-y * 100).toInt(), 0, 0, fastMode) }
        )

        // 6. Alsó Közép: KILL SWITCH & Szervizpanel
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { 
                        trackingJob?.cancel()
                        trackingJob = null
                        activeTracker.stopTracking()
                        videoRecorder.stop()
                        missionEngine.abort()
                        session.killMotors()
                        isFlying = false
                            flightStartTimeMs = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("KILL SWITCH", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                }

                Button(
                    onClick = { 
                        if (!isConnected && !isFlying) {
                            Toast.makeText(context, "A drón nincs csatlakoztatva!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isFlying) {
                            session.land()
                            isFlying = false
                            flightStartTimeMs = null
                        } else {
                            session.takeOff()
                            isFlying = true
                            flightStartTimeMs = System.currentTimeMillis()
                        }
                    },
                    enabled = isConnected || isFlying,
                    colors = ButtonDefaults.buttonColors(containerColor = if (isFlying) Color(0xFFFF9100) else Color(0xFF00C853)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(42.dp)
                ) {
                    Icon(if (isFlying) Icons.Default.FlightLand else Icons.Default.FlightTakeoff, null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isFlying) "LESZÁLLÁS" else "FELSZÁLLÁS", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Button(
                    onClick = { drawerOpen = !drawerOpen },
                    colors = ButtonDefaults.buttonColors(containerColor = GlassDark),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text(if (drawerOpen) "PANEL CSUKÁSA" else "SZERVIZ & TRÜKKÖK", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                    Icon(if (drawerOpen) Icons.Default.ExpandMore else Icons.Default.ExpandLess, null, tint = NeonGreen)
                }
            }

            AnimatedVisibility(drawerOpen) {
                ToolsDrawer(
                    session = session,
                    isConnected = isConnected,
                    isMissionRunning = isMissionRunning,
                    isRecordingBlackbox = isRecordingBlackbox,
                    onToggleDemo = {
                        if (isMissionRunning) {
                            missionEngine.abort()
                        } else {
                            trackingJob?.cancel()
                            trackingJob = null
                            activeTracker.stopTracking()
                            missionEngine.startDemoMission(onPhotoTrigger = { saveSnapshot(context, activeSurfaceView) })
                        }
                    },
                    onToggleBlackbox = {
                        if (isRecordingBlackbox) {
                            blackbox.stop()
                            isRecordingBlackbox = false
                            Toast.makeText(context, "CSV elmentve a Dokumentumokba!", Toast.LENGTH_SHORT).show()
                        } else {
                            blackbox.start { session.telemetry.value }
                            isRecordingBlackbox = true
                        }
                    },
                    onFlightStateChanged = { isFlying = it }
                )
            }
        }
    }
}

@Composable
private fun TacticalFpvOverlay(session: TelloFlightSession, trackedTarget: TrackedTarget?, modifier: Modifier = Modifier) {
    val telemetry by session.telemetry.collectAsState()
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        // Repülőgép célkereszt
        val crosshairColor = NeonGreen.copy(alpha = 0.85f)
        drawLine(crosshairColor, Offset(cx - 24.dp.toPx(), cy), Offset(cx - 8.dp.toPx(), cy), 2.dp.toPx())
        drawLine(crosshairColor, Offset(cx + 8.dp.toPx(), cy), Offset(cx + 24.dp.toPx(), cy), 2.dp.toPx())
        drawLine(crosshairColor, Offset(cx, cy - 14.dp.toPx()), Offset(cx, cy - 6.dp.toPx()), 2.dp.toPx())
        drawCircle(crosshairColor, 3.dp.toPx(), Offset(cx, cy))

        // Dőlésszög-létra (Pitch Ladder)
        val pitchOffset = (session.telemetry.value.pitch * 3.5.dp.toPx()).coerceIn(-cy * 0.7f, cy * 0.7f)
        rotate(degrees = -session.telemetry.value.roll.toFloat(), pivot = Offset(cx, cy)) {
            val horizonY = cy + pitchOffset

            // 0 fokos horizont vonal
            drawLine(
                color = NeonGreen,
                start = Offset(cx - 90.dp.toPx(), horizonY),
                end = Offset(cx + 90.dp.toPx(), horizonY),
                strokeWidth = 2.dp.toPx()
            )

            // +15 és -15 fokos létravonalak
            val step15 = 15 * 3.5.dp.toPx()
            val ladderEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)

            // +15 fok
            drawLine(
                color = CyberCyan.copy(alpha = 0.65f),
                start = Offset(cx - 50.dp.toPx(), horizonY - step15),
                end = Offset(cx + 50.dp.toPx(), horizonY - step15),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = ladderEffect
            )

            // -15 fok
            drawLine(
                color = CyberCyan.copy(alpha = 0.65f),
                start = Offset(cx - 50.dp.toPx(), horizonY + step15),
                end = Offset(cx + 50.dp.toPx(), horizonY + step15),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = ladderEffect
            )
        }

        // ActiveTrack Bounding Box & Target HUD
        trackedTarget?.let { target ->
            val left = target.rect.left * size.width
            val top = target.rect.top * size.height
            val width = (target.rect.right - target.rect.left) * size.width
            val height = (target.rect.bottom - target.rect.top) * size.height

            // Célpont sarkok kirajzolása (Tactical corner bracket stílus)
            val cornerLen = 14.dp.toPx()
            val bracketColor = CyberCyan

            // Bal felső
            drawLine(bracketColor, Offset(left, top), Offset(left + cornerLen, top), 3.dp.toPx())
            drawLine(bracketColor, Offset(left, top), Offset(left, top + cornerLen), 3.dp.toPx())
            // Jobb felső
            drawLine(bracketColor, Offset(left + width, top), Offset(left + width - cornerLen, top), 3.dp.toPx())
            drawLine(bracketColor, Offset(left + width, top), Offset(left + width, top + cornerLen), 3.dp.toPx())
            // Bal alsó
            drawLine(bracketColor, Offset(left, top + height), Offset(left + cornerLen, top + height), 3.dp.toPx())
            drawLine(bracketColor, Offset(left, top + height), Offset(left, top + height - cornerLen), 3.dp.toPx())
            // Jobb alsó
            drawLine(bracketColor, Offset(left + width, top + height), Offset(left + width - cornerLen, top + height), 3.dp.toPx())
            drawLine(bracketColor, Offset(left + width, top + height), Offset(left + width, top + height - cornerLen), 3.dp.toPx())

            // Célpont középpontja
            drawCircle(WarningRed, 4.dp.toPx(), Offset(left + width / 2f, top + height / 2f))
        }
    }
}

@Composable
private fun TacticalTopHud(session: TelloFlightSession, rssi: Int?, flightStartTimeMs: Long?, modifier: Modifier = Modifier) {
    val telemetry by session.telemetry.collectAsState()
    var flightTimeStr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("00:00") }
    androidx.compose.runtime.LaunchedEffect(flightStartTimeMs) {
        while (true) {
            if (flightStartTimeMs != null) {
                val elapsed = (System.currentTimeMillis() - flightStartTimeMs) / 1000
                flightTimeStr = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
            } else {
                flightTimeStr = "00:00"
            }
            kotlinx.coroutines.delay(1000)
        }
    }
    val batteryColor = when {
        telemetry.battery > 50 -> NeonGreen
        telemetry.battery > 20 -> Color(0xFFFFB300)
        else -> WarningRed
    }
    val pulse by rememberInfiniteTransition(label = "wifi").animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "signal"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GlassDark)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, "Idő", tint = CyberCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(flightTimeStr, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NetworkWifi, "Wi-Fi", tint = CyberCyan, modifier = Modifier.size(16.dp).alpha(pulse))
            Spacer(Modifier.width(5.dp))
            Text(rssi?.let { "$it dBm" } ?: "-- dBm", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BatteryAlert, "Akku", tint = batteryColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("${telemetry.battery}%", color = batteryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        Text("ALT: ${session.telemetry.value.heightCm} cm", color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Text("TEMP: ${telemetry.temperatureC}°C", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TacticalBadge(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GlassDark)
            .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun HudIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Button(
        onClick = { if (enabled) onClick() else Toast.makeText(context, "A drón nincs csatlakoztatva!", Toast.LENGTH_SHORT).show() },
        colors = ButtonDefaults.buttonColors(containerColor = GlassDark),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) GlassBorder else GlassBorder.copy(alpha = 0.2f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TacticalJoystick(
    modifier: Modifier = Modifier,
    label: String,
    onMove: (Float, Float) -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxRadius = with(density) { 50.dp.toPx() }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(105.dp)
                .clip(CircleShape)
                .background(Color(0x2200FF66))
                .border(1.5.dp, GlassBorder, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            dragOffset = Offset.Zero
                            onMove(0f, 0f)
                        }
                    ) { _, dragAmount ->
                        val newOffset = dragOffset + dragAmount
                        val distance = newOffset.getDistance()
                        dragOffset = if (distance > maxRadius) newOffset * (maxRadius / distance) else newOffset
                        onMove(dragOffset.x / maxRadius, dragOffset.y / maxRadius)
                    }
                }
        ) {
            // Finom célkereszt vonalak a stick alatt
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                drawLine(Color(0x3300FF66), Offset(c.x - 18.dp.toPx(), c.y), Offset(c.x + 18.dp.toPx(), c.y), 1.dp.toPx())
                drawLine(Color(0x3300FF66), Offset(c.x, c.y - 18.dp.toPx()), Offset(c.x, c.y + 18.dp.toPx()), 1.dp.toPx())
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.Center)
                    .offset { androidx.compose.ui.unit.IntOffset(dragOffset.x.toInt(), dragOffset.y.toInt()) }
                    .clip(CircleShape)
                    .background(NeonGreen)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color(0xAAFFFFFF), fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToolsDrawer(
    session: TelloFlightSession,
    isConnected: Boolean,
    isMissionRunning: Boolean,
    isRecordingBlackbox: Boolean,
    onToggleDemo: () -> Unit,
    onToggleBlackbox: () -> Unit,
    onFlightStateChanged: (Boolean) -> Unit
) {
    var simulationMode by remember { mutableStateOf(session.simulationMode) }

    Column(
        Modifier
            .fillMaxWidth(0.85f)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GlassDark)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AKROBATIKA (360° SZALTÓK)", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlipDirection.entries.forEach { dir ->
                        Button(
                            onClick = { if (isConnected) session.flip(dir) },
                            enabled = isConnected,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16232E)),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x3300F0FF))
                        ) {
                            Text(dir.label, fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("KÜLDETÉS & LOG", color = Color(0xFFD500F9), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Button(
                        onClick = { if (isConnected || isMissionRunning) onToggleDemo() else Toast.makeText(context, "A drón nincs csatlakoztatva!", Toast.LENGTH_SHORT).show() },
                        enabled = isConnected || isMissionRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = if (isMissionRunning) WarningRed else Color(0xFF3B0059)),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text(if (isMissionRunning) "MEGSZAKÍT" else "DEMO", fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace) }

                    Button(
                        onClick = { if (isConnected || isRecordingBlackbox) onToggleBlackbox() else Toast.makeText(context, "A drón nincs csatlakoztatva!", Toast.LENGTH_SHORT).show() },
                        enabled = isConnected || isRecordingBlackbox,
                        colors = ButtonDefaults.buttonColors(containerColor = if (isRecordingBlackbox) WarningRed else Color(0xFFE65100)),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text(if (isRecordingBlackbox) "LOG KI" else "LOG BE", fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SZERVIZ & FAIL-SAFE VEZÉRLÉS", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = { session.stopAndHover() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("LEBEGÉS", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }

                    Button(
                        onClick = { session.forceTakeoffBypass(60) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("BYPASS (60%)", fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                }
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("FEJLESZTŐI ESZKÖZÖK", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        simulationMode = !simulationMode
                        session.simulationMode = simulationMode
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (simulationMode) NeonGreen else Color.DarkGray),
                    shape = RoundedCornerShape(6.dp)
                ) { Text(if (simulationMode) "SZIMULÁCIÓ (BE)" else "SZIMULÁCIÓ (KI)", fontSize = 10.sp, color = if (simulationMode) Color.Black else Color.White, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

private fun Offset.getDistance(): Float = kotlin.math.sqrt(x * x + y * y)

private fun wifiRssi(context: Context): Int? = runCatching {
    @Suppress("DEPRECATION")
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi
}.getOrNull()?.takeIf { it in -100..0 }

private fun saveSnapshot(context: Context, surfaceView: SurfaceView?) {
    if (surfaceView == null || !surfaceView.holder.surface.isValid) {
        Toast.makeText(context, "Nincs aktív videó a fotóhoz!", Toast.LENGTH_SHORT).show()
        return
    }
    
    val bitmap = Bitmap.createBitmap(surfaceView.width.coerceAtLeast(1), surfaceView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val handler = Handler(Looper.getMainLooper())
    
    PixelCopy.request(surfaceView, bitmap, { result ->
        if (result == PixelCopy.SUCCESS) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "TELLO_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/TelloFPV")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Nem hozható létre média bejegyzés")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                }.onSuccess { launch(Dispatchers.Main) { Toast.makeText(context, "Pillanatkép mentve a Galériába!", Toast.LENGTH_SHORT).show() } }
                 .onFailure { launch(Dispatchers.Main) { Toast.makeText(context, "Képmentési hiba történt", Toast.LENGTH_SHORT).show() } }
            }
        } else {
            Toast.makeText(context, "Nem sikerült a képet rögzíteni", Toast.LENGTH_SHORT).show()
        }
    }, handler)
}
