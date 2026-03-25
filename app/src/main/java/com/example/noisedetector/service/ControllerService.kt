package com.example.noisedetector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.noisedetector.NoiseDetectorApp
import com.example.noisedetector.R
import com.example.noisedetector.notify.AppNotifications
import com.example.noisedetector.notify.NotificationIds
import com.example.noisedetector.util.AppPrefs
import com.example.noisedetector.util.PairingCodes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ControllerService : Service() {

    private val app by lazy { application as NoiseDetectorApp }
    private val prefs by lazy { AppPrefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var host: String = ""
    private var reconnectAttempt = 0
    private val shouldReconnect = AtomicBoolean(true)

    private var pairingMode = false
    private var activePairCode = ""

    private var calibratingUntil = 0L
    private val calibrateSamples = ArrayList<Float>(256)

    private var lastNotifUpdate = 0L
    private var lastRepeatAlertAt = 0L

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveLock = AtomicBoolean(false)

    @Suppress("DEPRECATION")
    private var multicastLock: WifiManager.MulticastLock? = null

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || !shouldReconnect.get()) return
            val h = prefs.controllerHost.trim()
            if (h.isEmpty() && pairingMode && activePairCode.isNotEmpty()) {
                startNsdDiscovery(activePairCode)
            } else if (h.isNotEmpty()) {
                connectWs()
            } else {
                scheduleReconnect(5_000L)
            }
        }
    }

    private val discoveryTimeoutRunnable = Runnable {
        stopNsdDiscovery()
        if (running.get() && pairingMode && host.isEmpty()) {
            bumpReconnect()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALIBRATE -> {
                startOrResume(intent)
                beginCalibration()
            }
            else -> startOrResume(intent)
        }
        return START_STICKY
    }

    private fun beginCalibration() {
        calibrateSamples.clear()
        calibratingUntil = System.currentTimeMillis() + 2_200L
        prefs.clearNoiseFloor()
        app.publishNoiseFloor(Float.NaN)
        handler.postDelayed({
            if (calibrateSamples.isNotEmpty()) {
                val avg = calibrateSamples.average().toFloat()
                prefs.noiseFloorDb = avg
                app.publishNoiseFloor(avg)
                calibrateSamples.clear()
            }
            calibratingUntil = 0L
        }, 2_450L)
    }

    private fun startOrResume(intent: Intent?) {
        val hostFromIntent = intent?.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
        val pairFromIntent = intent?.getStringExtra(EXTRA_PAIR_CODE)?.let { PairingCodes.normalize(it) }.orEmpty()

        when {
            hostFromIntent.isNotEmpty() -> {
                pairingMode = false
                activePairCode = ""
                prefs.controllerHost = hostFromIntent
            }
            pairFromIntent.isNotEmpty() -> {
                pairingMode = true
                activePairCode = pairFromIntent
                prefs.lastPairCode = pairFromIntent
                prefs.controllerHost = ""
            }
            else -> {
                if (prefs.controllerHost.isNotEmpty()) {
                    pairingMode = false
                    activePairCode = ""
                } else if (prefs.lastPairCode.isNotEmpty()) {
                    pairingMode = true
                    activePairCode = prefs.lastPairCode
                }
            }
        }

        host = prefs.controllerHost.trim()

        if (pairingMode && activePairCode.isEmpty()) {
            activePairCode = prefs.lastPairCode
        }

        val firstStart = !running.getAndSet(true)
        if (firstStart) {
            AppNotifications.ensureChannels(this)
            val initial = AppNotifications.controllerForeground(this, controllerSummaryLine(), 0f)
            startControllerForeground(initial)
            app.setControllerRunning(true)
        }

        if (intent?.action == ACTION_CALIBRATE && !firstStart) return

        shouldReconnect.set(true)
        reconnectAttempt = 0
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(discoveryTimeoutRunnable)
        try {
            socket?.cancel()
        } catch (_: Exception) {
        }
        socket = null
        app.setControllerConnected(false)

        when {
            pairingMode && activePairCode.isNotEmpty() && host.isEmpty() -> {
                stopNsdDiscovery()
                startNsdDiscovery(activePairCode)
            }
            host.isNotEmpty() -> scheduleReconnect(250L)
            else -> {
                updateForegroundThrottled(force = true)
                scheduleReconnect(5_000L)
            }
        }
    }

    private fun controllerSummaryLine(): String {
        val connected = app.controllerConnected.value
        return when {
            connected && host.isNotEmpty() -> getString(R.string.notif_summary_connected, host)
            host.isNotEmpty() -> getString(R.string.notif_summary_connecting, host)
            pairingMode && activePairCode.isNotEmpty() -> getString(R.string.notif_summary_searching, activePairCode)
            else -> getString(R.string.status_idle)
        }
    }

    private fun sameServiceType(found: String): Boolean {
        val a = found.trimEnd('.').lowercase()
        val b = PairingCodes.SERVICE_TYPE.trimEnd('.').lowercase()
        return a == b
    }

    private fun startNsdDiscovery(code: String) {
        stopNsdDiscovery()
        resolveLock.set(false)
        acquireMulticastLock()
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd

        val dListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post { scheduleReconnect(3_000L) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!sameServiceType(serviceInfo.serviceType)) return
                val foundCode = PairingCodes.codeFromServiceName(serviceInfo.serviceName) ?: return
                if (foundCode != code) return
                if (!resolveLock.compareAndSet(false, true)) return
                handler.post {
                    try {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(serviceInfo, createResolveListener(code))
                    } catch (_: Exception) {
                        resolveLock.set(false)
                    }
                }
            }
        }
        discoveryListener = dListener
        try {
            nsd.discoverServices(PairingCodes.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dListener)
        } catch (_: Exception) {
            releaseMulticastLock()
            scheduleReconnect(3_000L)
            return
        }
        handler.removeCallbacks(discoveryTimeoutRunnable)
        handler.postDelayed(discoveryTimeoutRunnable, 45_000L)
        updateForegroundThrottled(force = true)
    }

    private fun createResolveListener(code: String): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolveLock.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handler.post {
                    stopNsdDiscovery()
                    val ip = serviceInfo.host?.hostAddress
                    if (ip.isNullOrBlank()) {
                        resolveLock.set(false)
                        if (running.get() && shouldReconnect.get()) {
                            startNsdDiscovery(code)
                        }
                        return@post
                    }
                    host = ip
                    prefs.controllerHost = ip
                    resolveLock.set(false)
                    connectWs()
                    updateForegroundThrottled(force = true)
                }
            }
        }
    }

    private fun stopNsdDiscovery() {
        handler.removeCallbacks(discoveryTimeoutRunnable)
        val nsd = nsdManager
        val disc = discoveryListener
        if (nsd != null && disc != null) {
            try {
                nsd.stopServiceDiscovery(disc)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null
        releaseMulticastLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireMulticastLock() {
        releaseMulticastLock()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("footstep_controller_nsd")
        lock.setReferenceCounted(false)
        lock.acquire()
        multicastLock = lock
    }

    @Suppress("DEPRECATION")
    private fun releaseMulticastLock() {
        multicastLock?.release()
        multicastLock = null
    }

    private fun startControllerForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationIds.CONTROLLER_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationIds.CONTROLLER_SERVICE, notification)
        }
    }

    private fun stopInternal() {
        shouldReconnect.set(false)
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(discoveryTimeoutRunnable)
        stopNsdDiscovery()
        resolveLock.set(false)
        running.set(false)
        pairingMode = false
        activePairCode = ""
        prefs.controllerHost = ""
        app.setControllerConnected(false)
        app.setControllerRunning(false)
        app.setControllerDb(0f)
        try {
            socket?.close(1000, "stop")
        } catch (_: Exception) {
        }
        socket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun scheduleReconnect(delayMs: Long) {
        handler.removeCallbacks(reconnectRunnable)
        if (!shouldReconnect.get() || !running.get()) return
        handler.postDelayed(reconnectRunnable, delayMs.coerceAtLeast(200L))
    }

    private fun connectWs() {
        if (!running.get() || !shouldReconnect.get()) return
        val h = prefs.controllerHost.trim()
        if (h.isEmpty()) {
            if (pairingMode && activePairCode.isNotEmpty()) {
                scheduleReconnect(2_000L)
            } else {
                scheduleReconnect(5_000L)
            }
            return
        }
        host = h
        val url = "ws://$h:${NoiseDetectorApp.WS_PORT}/"
        val request = Request.Builder().url(url).build()
        socket?.cancel()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    reconnectAttempt = 0
                    app.setControllerConnected(true)
                    updateForegroundThrottled(force = true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handler.post { handleLevelMessage(text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post {
                    app.setControllerConnected(false)
                    bumpReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    app.setControllerConnected(false)
                    bumpReconnect()
                }
            }
        })
    }

    private fun bumpReconnect() {
        if (!running.get() || !shouldReconnect.get()) return
        reconnectAttempt++
        val base = 900L
        val max = 30_000L
        val exp = (base * (1 shl reconnectAttempt.coerceAtMost(5))).coerceAtMost(max)
        scheduleReconnect(exp)
    }

    private fun handleLevelMessage(text: String) {
        val db = try {
            JSONObject(text).getDouble("dB").toFloat()
        } catch (_: Exception) {
            return
        }
        app.setControllerDb(db)

        val now = System.currentTimeMillis()
        if (now < calibratingUntil) {
            calibrateSamples.add(db)
            return
        }
        if (calibrateSamples.isNotEmpty()) {
            val avg = calibrateSamples.average().toFloat()
            calibrateSamples.clear()
            calibratingUntil = 0L
            prefs.noiseFloorDb = avg
            app.publishNoiseFloor(avg)
        }

        evaluateAlerts(db)
        updateForegroundThrottled(force = false)
    }

    private fun evaluateAlerts(db: Float) {
        if (!prefs.alertsEnabled) {
            lastRepeatAlertAt = 0L
            return
        }
        val floor = prefs.noiseFloorDb.let { if (it.isNaN()) DEFAULT_UNCALIBRATED_FLOOR else it }
        val trigger = floor + prefs.thresholdMarginDb
        val clearLevel = trigger - HYSTERESIS_DB
        val now = System.currentTimeMillis()

        if (db >= trigger) {
            if (now - lastRepeatAlertAt >= ALERT_REPEAT_MS) {
                lastRepeatAlertAt = now
                pulseStrongVibration()
                AppNotifications.thresholdAlert(this, db, trigger)
            }
        } else if (db < clearLevel) {
            lastRepeatAlertAt = 0L
        }
    }

    private fun pulseStrongVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 380, 160, 380, 160, 520)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun updateForegroundThrottled(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifUpdate < 1_000L) return
        lastNotifUpdate = now
        val n = AppNotifications.controllerForeground(
            this,
            controllerSummaryLine(),
            app.controllerDb.value
        )
        startControllerForeground(n)
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.noisedetector.CONTROLLER_STOP"
        const val ACTION_CALIBRATE = "com.example.noisedetector.CONTROLLER_CALIBRATE"
        const val EXTRA_HOST = "host"
        const val EXTRA_PAIR_CODE = "pair_code"

        private const val DEFAULT_UNCALIBRATED_FLOOR = 28f
        private const val HYSTERESIS_DB = 4f
        private const val ALERT_REPEAT_MS = 6_500L
    }
}
