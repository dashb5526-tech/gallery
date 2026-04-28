package com.google.ai.edge.gallery.server

import android.graphics.Bitmap

/**
 * In-memory store for passing images from the test chat UI to InferenceBridge
 * without serializing through HTTP/JSON. Since both run in the same process,
 * this avoids the OOM crash from base64-encoding large images in JSON.
 */
object PendingImageStore {
    private var pendingBytes: ByteArray? = null

    fun set(bytes: ByteArray?) {
        pendingBytes = bytes
    }

    fun take(): ByteArray? {
        val b = pendingBytes
        pendingBytes = null
        return b
    }
}
