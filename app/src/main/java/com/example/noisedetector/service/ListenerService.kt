package com.example.noisedetector.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.noisedetector.NoiseDetectorApp
import com.example.noisedetector.audio.DecibelMeter
import com.example.noisedetector.notify.AppNotifications
import com.example.noisedetector.notify.NotificationIds
import com.example.noisedetector.util.AppPrefs
import com.example.noisedetector.util.PairingCodes
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ListenerService : Service() {

    private val app by lazy { application as NoiseDetectorApp }
    private val prefs by lazy { AppPrefs(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioThread: Thread? = null
    private val running = AtomicBoolean(false)
    private var server: LevelsWebSocketServer? = null
    private val clientCount = AtomicInteger(0)
    private var lastNotifDb = Float.NaN
    private var lastNotifAt = 0L

    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    @Suppress("DEPRECATION")
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val fromIntent = intent?.getStringExtra(EXTRA_PAIR_CODE)
                val code = when {
                    fromIntent != null -> PairingCodes.normalize(fromIntent)?.also { prefs.listenerPairCode = it }
                    else -> PairingCodes.normalize(prefs.listenerPairCode)
                }
                startInternal(code)
            }
        }
        return START_STICKY
    }

    private fun startInternal(pairCode: String?) {
        if (running.getAndSet(true)) return
        AppNotifications.ensureChannels(this)
        val initial = AppNotifications.listenerForeground(this, 0, 0f)
        startListenerForeground(initial)
        app.setListenerRunning(true)

        val port = NoiseDetectorApp.WS_PORT
        server = LevelsWebSocketServer(port) {
            val c = clientCount.get()
            app.setListenerClients(c)
            maybeUpdateNotification()
        }.also { s ->
            s.isReuseAddr = true
            s.start()
        }

        mainHandler.post { registerNsd(pairCode) }

        val sampleRate = 44_100
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        val bufferSize = (minBuf * 2).coerceAtLeast(sampleRate / 5)

        audioThread = thread(name = "listener-audio") {
            val recorder = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        channel,
                        encoding,
                        bufferSize
                    )
                } else {
                    @Suppress("DEPRECATION")
                    AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channel,
                        encoding,
                        bufferSize
                    )
                }
            } catch (_: SecurityException) {
                running.set(false)
                return@thread
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                running.set(false)
                return@thread
            }
            val buf = ShortArray(bufferSize / 2)
            recorder.startRecording()
            try {
                while (running.get()) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val db = DecibelMeter.rmsToDisplayDb(buf, n)
                    app.setListenerDb(db)
                    val json = JSONObject().put("dB", db.toDouble()).toString()
                    server?.broadcast(json)
                    maybeUpdateNotificationThrottled(db)
                }
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
            }
        }
    }

    private fun registerNsd(pairCode: String?) {
        if (pairCode == null) return
        unregisterNsdQuietly()
        acquireMulticastLock()
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = nsd
        val name = PairingCodes.serviceNameForCode(pairCode)
        @Suppress("DEPRECATION")
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = PairingCodes.SERVICE_TYPE
            port = NoiseDetectorApp.WS_PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                nsdRegistrationListener = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        nsdRegistrationListener = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            nsdRegistrationListener = null
            releaseMulticastLock()
        }
    }

    private fun unregisterNsdQuietly() {
        val nsd = nsdManager
        val reg = nsdRegistrationListener
        if (nsd != null && reg != null) {
            try {
                nsd.unregisterService(reg)
            } catch (_: Exception) {
            }
        }
        nsdRegistrationListener = null
        nsdManager = null
        releaseMulticastLock()
    }

    @Suppress("DEPRECATION")
    private fun acquireMulticastLock() {
        releaseMulticastLock()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("footstep_listener_nsd")
        lock.setReferenceCounted(false)
        lock.acquire()
        multicastLock = lock
    }

    @Suppress("DEPRECATION")
    private fun releaseMulticastLock() {
        multicastLock?.release()
        multicastLock = null
    }

    private fun maybeUpdateNotificationThrottled(db: Float) {
        val now = System.currentTimeMillis()
        if (now - lastNotifAt < 900 && kotlin.math.abs(db - lastNotifDb) < 1.5f) return
        lastNotifAt = now
        lastNotifDb = db
        maybeUpdateNotification()
    }

    private fun startListenerForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationIds.LISTENER_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NotificationIds.LISTENER_SERVICE, notification)
        }
    }

    private fun maybeUpdateNotification() {
        val n = AppNotifications.listenerForeground(
            this,
            clientCount.get(),
            app.listenerDb.value
        )
        startListenerForeground(n)
    }

    private fun stopInternal() {
        if (!running.getAndSet(false)) return
        mainHandler.post { unregisterNsdQuietly() }
        audioThread?.join(4_000)
        audioThread = null
        try {
            server?.stop(2_000)
        } catch (_: Exception) {
        }
        server = null
        clientCount.set(0)
        app.setListenerClients(0)
        app.setListenerRunning(false)
        app.setListenerDb(0f)
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }

    private inner class LevelsWebSocketServer(
        port: Int,
        private val onClientsChanged: () -> Unit
    ) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            clientCount.incrementAndGet()
            onClientsChanged()
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            clientCount.decrementAndGet()
            if (clientCount.get() < 0) clientCount.set(0)
            onClientsChanged()
        }

        override fun onMessage(conn: WebSocket?, message: String?) {}

        override fun onError(conn: WebSocket?, ex: Exception?) {}

        override fun onStart() {}
    }

    companion object {
        const val ACTION_STOP = "com.example.noisedetector.LISTENER_STOP"
        const val EXTRA_PAIR_CODE = "pair_code"
    }
}
