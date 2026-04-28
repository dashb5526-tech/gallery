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

package com.google.ai.edge.gallery.ui.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.google.ai.edge.gallery.server.LocalApiService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.DataStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ServerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStoreRepository: DataStoreRepository
) : ViewModel() {

    private val _isBound = MutableStateFlow(false)
    val isBound = _isBound.asStateFlow()

    private val _service = MutableStateFlow<LocalApiService?>(null)

    private val _apiKey = MutableStateFlow(dataStoreRepository.readSecret("api_key") ?: "")
    val apiKey = _apiKey.asStateFlow()

    private val _port = MutableStateFlow(dataStoreRepository.readSecret("api_port") ?: "8080")
    val port = _port.asStateFlow()

    private val _selectedModelName = MutableStateFlow(dataStoreRepository.readSecret("api_selected_model") ?: "")
    val selectedModelName = _selectedModelName.asStateFlow()

    // Config settings (persisted)
    private val _accelerator = MutableStateFlow(dataStoreRepository.readSecret("api_accelerator") ?: Accelerator.CPU.label)
    val accelerator = _accelerator.asStateFlow()

    private val _topK = MutableStateFlow((dataStoreRepository.readSecret("api_topk") ?: "64").toIntOrNull() ?: 64)
    val topK = _topK.asStateFlow()

    private val _topP = MutableStateFlow((dataStoreRepository.readSecret("api_topp") ?: "0.95").toFloatOrNull() ?: 0.95f)
    val topP = _topP.asStateFlow()

    private val _temperature = MutableStateFlow((dataStoreRepository.readSecret("api_temperature") ?: "1.0").toFloatOrNull() ?: 1.0f)
    val temperature = _temperature.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LocalApiService.LocalApiBinder
            _service.value = binder.getService()
            _isBound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
            _isBound.value = false
        }
    }

    val serverStatus: StateFlow<LocalApiService.ServerStatus> = _service.flatMapLatest { service ->
        service?.serverStatus ?: MutableStateFlow(LocalApiService.ServerStatus.STOPPED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalApiService.ServerStatus.STOPPED)

    val serverIp: StateFlow<String?> = _service.flatMapLatest { service ->
        service?.serverIp ?: MutableStateFlow(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val errorMessage: StateFlow<String?> = _service.flatMapLatest { service ->
        service?.errorMessage ?: MutableStateFlow(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        bindService()
    }

    private fun bindService() {
        val intent = Intent(context, LocalApiService::class.java)
        // Use 0 flags — do NOT use BIND_AUTO_CREATE, which would start the service
        // just by opening the UI. We only want to bind if the service is already running.
        try {
            context.bindService(intent, serviceConnection, 0)
        } catch (e: Exception) {
            // Service not running — that's fine
        }
    }

    fun startServer() {
        val intent = Intent(context, LocalApiService::class.java).apply {
            action = "START"
            putExtra(LocalApiService.EXTRA_MODEL_NAME, _selectedModelName.value)
            putExtra(LocalApiService.EXTRA_ACCELERATOR, _accelerator.value)
        }
        context.startForegroundService(intent)
        // After explicitly starting, bind with BIND_AUTO_CREATE to ensure connection
        if (!_isBound.value) {
            val bindIntent = Intent(context, LocalApiService::class.java)
            context.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun selectModel(modelName: String) {
        _selectedModelName.value = modelName
        dataStoreRepository.saveSecret("api_selected_model", modelName)
    }

    fun stopServer() {
        val intent = Intent(context, LocalApiService::class.java).apply {
            action = "STOP"
        }
        context.startService(intent)
    }

    fun saveApiKey(newKey: String) {
        _apiKey.value = newKey
        dataStoreRepository.saveSecret("api_key", newKey)
    }

    fun generateApiKey() {
        val newKey = "sk-" + UUID.randomUUID().toString().replace("-", "").take(32)
        saveApiKey(newKey)
    }

    fun savePort(newPort: String) {
        _port.value = newPort
        dataStoreRepository.saveSecret("api_port", newPort)
    }

    // Config setters
    fun saveAccelerator(value: String) {
        _accelerator.value = value
        dataStoreRepository.saveSecret("api_accelerator", value)
    }

    fun saveTopK(value: Int) {
        _topK.value = value
        dataStoreRepository.saveSecret("api_topk", value.toString())
    }

    fun saveTopP(value: Float) {
        _topP.value = value
        dataStoreRepository.saveSecret("api_topp", value.toString())
    }

    fun saveTemperature(value: Float) {
        _temperature.value = value
        dataStoreRepository.saveSecret("api_temperature", value.toString())
    }

    override fun onCleared() {
        super.onCleared()
        if (_isBound.value) {
            context.unbindService(serviceConnection)
            _isBound.value = false
        }
    }
}
