/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.server

import android.content.pm.ServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "LocalApiService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "local_api_server_channel"

@AndroidEntryPoint
class LocalApiService : Service() {

    @Inject
    lateinit var dataStoreRepository: DataStoreRepository

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var server: LocalApiServer? = null

    private val _serverStatus = MutableStateFlow(ServerStatus.STOPPED)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus

    private val _serverIp = MutableStateFlow<String?>(null)
    val serverIp: StateFlow<String?> = _serverIp

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    enum class ServerStatus {
        STARTING, RUNNING, STOPPED, ERROR
    }

    inner class LocalApiBinder : Binder() {
        fun getService(): LocalApiService = this@LocalApiService
    }

    private val binder = LocalApiBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()

        // Install a safety net to prevent native crashes from killing the app.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on thread ${thread.name}", throwable)
            _serverStatus.value = ServerStatus.ERROR
            _errorMessage.value = "Unexpected crash: ${throwable.message}"
            ModelProvider.setServerActiveModel(null)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action=$action")
        when (action) {
            "START" -> {
                val modelName = intent.getStringExtra("MODEL_NAME")
                val accelerator = intent.getStringExtra("ACCELERATOR") ?: Accelerator.CPU.label
                startServer(modelName, accelerator)
            }
            "STOP" -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer(modelName: String? = null, accelerator: String = Accelerator.CPU.label) {
        Log.d(TAG, "=== startServer BEGIN === modelName=$modelName")
        if (_serverStatus.value == ServerStatus.RUNNING || _serverStatus.value == ServerStatus.STARTING) {
            Log.d(TAG, "Server already starting or running")
            return
        }

        _serverStatus.value = ServerStatus.STARTING
        _errorMessage.value = null

        // Step 0: Start foreground (must be done synchronously on main thread).
        try {
            Log.d(TAG, "Step 0: Creating notification and calling startForeground...")
            val notification = createNotification("Starting API Server...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Step 0: startForeground OK")
        } catch (t: Throwable) {
            Log.e(TAG, "Step 0: FAILED in startForeground", t)
            handleError("Failed to start foreground service: ${t.message}")
            return
        }

        // Everything else runs in a background coroutine.
        serviceScope.launch(Dispatchers.Default) {
            try {
                // Step 1: Start the HTTP server FIRST (before model init).
                Log.d(TAG, "Step 1: Starting HTTP server...")

                val ip = getLocalIpAddress()
                _serverIp.value = ip
                Log.d(TAG, "Step 1: IP = $ip")

                val apiKey = withContext(Dispatchers.IO) {
                    dataStoreRepository.readSecret("api_key")
                }
                val port = withContext(Dispatchers.IO) {
                    dataStoreRepository.readSecret("api_port")?.toIntOrNull() ?: 8080
                }
                Log.d(TAG, "Step 1: port=$port, apiKey=${if (apiKey.isNullOrBlank()) "none" else "set"}")

                val bridge = InferenceBridge()
                val newServer = LocalApiServer(bridge, apiKey)

                Log.d(TAG, "Step 1: Calling newServer.start($port)...")
                newServer.start(port)
                server = newServer
                Log.d(TAG, "Step 1: HTTP server started OK on port $port")

                // Step 2: Initialize the model (if requested).
                if (modelName != null) {
                    Log.d(TAG, "Step 2: Model initialization requested for '$modelName'")
                    withContext(Dispatchers.Main) {
                        updateNotification("Loading model $modelName (CPU mode)...")
                    }

                    val model = ModelProvider.getRegisteredModel(modelName)
                    if (model == null) {
                        Log.e(TAG, "Step 2: Model '$modelName' NOT found in ModelProvider")
                        throw Exception("Model '$modelName' not found. Please select it again.")
                    }
                    Log.d(TAG, "Step 2: Model found. instance=${model.instance != null}")

                    if (model.instance == null) {
                        // Validate model file exists before attempting native init.
                        val modelPath = model.getPath(context = applicationContext)
                        Log.d(TAG, "Step 2: Model path = $modelPath")

                        val modelFile = File(modelPath)
                        if (!modelFile.exists()) {
                            Log.e(TAG, "Step 2: Model file DOES NOT EXIST at $modelPath")
                            throw Exception("Model file not found at: $modelPath")
                        }
                        Log.d(TAG, "Step 2: Model file exists, size=${modelFile.length()} bytes")

                        // Use the accelerator from the UI config. Save original to restore later.
                        val originalAccelerator = model.configValues.toMutableMap().let {
                            it[ConfigKeys.ACCELERATOR.label]
                        }
                        Log.d(TAG, "Step 2: Original accelerator = $originalAccelerator, using $accelerator")

                        // Override accelerator to the user's choice
                        val mutableConfig = model.configValues.toMutableMap()
                        mutableConfig[ConfigKeys.ACCELERATOR.label] = accelerator
                        model.configValues = mutableConfig

                        // Initialize the model — catch Throwable to handle native errors.
                        Log.d(TAG, "Step 2: Calling runtimeHelper.initialize() with CPU backend...")
                        var initError = ""
                        try {
                            model.runtimeHelper.initialize(
                                context = applicationContext,
                                model = model,
                                supportImage = true,
                                supportAudio = false,
                                onDone = { error -> initError = error }
                            )
                        } catch (t: Throwable) {
                            Log.e(TAG, "Step 2: NATIVE CRASH caught in initialize()", t)
                            // Restore original accelerator
                            if (originalAccelerator != null) {
                                val restoreConfig = model.configValues.toMutableMap()
                                restoreConfig[ConfigKeys.ACCELERATOR.label] = originalAccelerator
                                model.configValues = restoreConfig
                            }
                            throw Exception("Native engine crash: ${t.message}")
                        }

                        // Restore original accelerator value
                        if (originalAccelerator != null) {
                            val restoreConfig = model.configValues.toMutableMap()
                            restoreConfig[ConfigKeys.ACCELERATOR.label] = originalAccelerator
                            model.configValues = restoreConfig
                        }

                        if (initError.isNotEmpty()) {
                            Log.e(TAG, "Step 2: Model init returned error: $initError")
                            throw Exception("Model load failed: $initError")
                        }

                        Log.d(TAG, "Step 2: Model initialized OK. instance=${model.instance != null}")
                        ModelProvider.registerModel(model)
                    } else {
                        Log.d(TAG, "Step 2: Model already initialized, skipping.")
                    }

                    ModelProvider.setServerActiveModel(modelName)
                    Log.d(TAG, "Step 2: Model '$modelName' set as server-active")
                }

                // Done!
                _serverStatus.value = ServerStatus.RUNNING
                withContext(Dispatchers.Main) {
                    updateNotification("API Server running at http://$ip:$port")
                }
                Log.i(TAG, "=== startServer DONE === http://$ip:$port")

            } catch (t: Throwable) {
                Log.e(TAG, "=== startServer FAILED ===", t)
                handleError(t.message ?: "Unknown error")
            }
        }
    }

    private fun handleError(message: String) {
        _errorMessage.value = message
        _serverStatus.value = ServerStatus.ERROR
        ModelProvider.setServerActiveModel(null)

        // Stop the HTTP server if it was started.
        try { server?.stop() } catch (_: Throwable) {}
        server = null

        // Auto-reset: stop the service and set status to STOPPED after a short delay
        // so the user can see the error and then retry.
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                updateNotification("Error: $message")
            }
            delay(3000) // Show error for 3 seconds
            _serverStatus.value = ServerStatus.STOPPED
            _serverIp.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer() {
        serviceScope.launch(Dispatchers.IO) {
            server?.stop()
            server = null
            ModelProvider.setServerActiveModel(null)
            _serverStatus.value = ServerStatus.STOPPED
            _serverIp.value = null
            _errorMessage.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return "127.0.0.1"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Local API Server"
            val descriptionText = "Notifications for Local API Server status"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        // Tap notification → open app
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        }

        // Stop action → sends STOP command to this service
        val stopIntent = Intent(this, LocalApiService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local AI API Server")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        super.onDestroy()
        ModelProvider.setServerActiveModel(null)
        serviceJob.cancel()
        server?.stop()
    }

    companion object {
        const val EXTRA_MODEL_NAME = "MODEL_NAME"
        const val EXTRA_ACCELERATOR = "ACCELERATOR"
    }
}
