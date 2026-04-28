package com.google.ai.edge.gallery.server

import android.graphics.Bitmap

/**
 * In-memory store for passing images from the test chat UI to InferenceBridge
 * without serializing through HTTP/JSON. Since both run in the same process,
 * this avoids the OOM crash from base64-encoding large images in JSON.
 */
object PendingImageStore {
    private var pendingImage: Bitmap? = null

    fun set(image: Bitmap?) {
        pendingImage = image
    }

    fun take(): Bitmap? {
        val img = pendingImage
        pendingImage = null
        return img
    }
}
