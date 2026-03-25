package com.example.noisedetector

import android.app.Application
import com.example.noisedetector.util.AppPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class NoiseDetectorApp : Application() {

    private val _noiseFloor = MutableStateFlow(Float.NaN)
    val noiseFloor = _noiseFloor.asStateFlow()

    private val _listenerDb = MutableStateFlow(0f)
    val listenerDb = _listenerDb.asStateFlow()

    private val _listenerClients = MutableStateFlow(0)
    val listenerClients = _listenerClients.asStateFlow()

    private val _listenerRunning = MutableStateFlow(false)
    val listenerRunning = _listenerRunning.asStateFlow()

    private val _controllerDb = MutableStateFlow(0f)
    val controllerDb = _controllerDb.asStateFlow()

    private val _controllerConnected = MutableStateFlow(false)
    val controllerConnected = _controllerConnected.asStateFlow()

    private val _controllerRunning = MutableStateFlow(false)
    val controllerRunning = _controllerRunning.asStateFlow()

    fun setListenerDb(value: Float) {
        _listenerDb.value = value
    }

    fun setListenerClients(count: Int) {
        _listenerClients.value = count
    }

    fun setListenerRunning(running: Boolean) {
        _listenerRunning.value = running
    }

    fun setControllerDb(value: Float) {
        _controllerDb.value = value
    }

    fun setControllerConnected(connected: Boolean) {
        _controllerConnected.value = connected
    }

    fun setControllerRunning(running: Boolean) {
        _controllerRunning.value = running
    }

    fun publishNoiseFloor(db: Float) {
        _noiseFloor.value = db
    }

    override fun onCreate() {
        super.onCreate()
        val f = AppPrefs(this).noiseFloorDb
        if (!f.isNaN()) {
            _noiseFloor.value = f
        }
    }

    companion object {
        const val WS_PORT = 17890
    }
}
