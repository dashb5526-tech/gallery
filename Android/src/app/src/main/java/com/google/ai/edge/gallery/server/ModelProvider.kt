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

import com.google.ai.edge.gallery.data.Model
import java.util.concurrent.ConcurrentHashMap

/**
 * A singleton that holds references to currently initialized and active models.
 * This allows the Local API Server to access model instances regardless of the
 * ViewModel lifecycle.
 */
object ModelProvider {
    private val activeModels = ConcurrentHashMap<String, Model>()

    /**
     * The name of the model currently being used by the Local API Server.
     * When set, ModelManagerViewModel should NOT clean up this model on navigation.
     */
    @Volatile
    var serverActiveModelName: String? = null
        private set

    /**
     * Marks a model as actively being used by the server.
     */
    fun setServerActiveModel(modelName: String?) {
        serverActiveModelName = modelName
    }

    /**
     * Checks if a model is currently being used by the server.
     */
    fun isModelUsedByServer(modelName: String): Boolean {
        return serverActiveModelName == modelName
    }

    /**
     * Registers a model as active. Should be called after successful initialization.
     */
    fun registerModel(model: Model) {
        activeModels[model.name] = model
    }

    /**
     * Unregisters a model. Should be called during model cleanup.
     */
    fun unregisterModel(modelName: String) {
        activeModels.remove(modelName)
    }

    /**
     * Returns a list of all currently active (initialized) models.
     */
    fun getActiveModels(): List<Model> {
        return activeModels.values.filter { it.instance != null }
    }

    /**
     * Finds a registered model by its name, regardless of whether it has been initialized.
     * Used by LocalApiService to get the Model object for initialization.
     */
    fun getRegisteredModel(name: String): Model? {
        return activeModels[name]
    }

    /**
     * Finds an active model by its name.
     */
    fun getModel(name: String): Model? {
        val model = activeModels[name]
        return if (model?.instance != null) model else null
    }
}
