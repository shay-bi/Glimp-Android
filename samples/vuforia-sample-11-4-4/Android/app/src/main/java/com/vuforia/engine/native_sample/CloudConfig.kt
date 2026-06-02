/*===============================================================================
Copyright (c) 2024 PTC Inc. and/or Its Subsidiary Companies. All Rights Reserved.

Vuforia is a trademark of PTC Inc., registered in the United States and other
countries.
===============================================================================*/

package com.vuforia.engine.native_sample

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder


/**
 * Configuration and utilities for cloud image recognition and Firebase Storage.
 */
object CloudConfig {
    private const val TAG = "CloudConfig"

    // Firebase Storage configuration
    private const val FIREBASE_BUCKET = "glimp-fa17d.firebasestorage.app"
    private const val FIREBASE_BASE_PATH = "uploads/"
    private const val FIREBASE_URL_TEMPLATE = "https://firebasestorage.googleapis.com/v0/b/%s/o/%s?alt=media"

    // Fallback target widths for legacy targets (in meters)
    private const val DEFAULT_TARGET_WIDTH = 0.06f


    /**
     * Parse cloud metadata JSON string.
     * @param metadataString JSON metadata string from Vuforia cloud target
     * @return Parsed JSONObject or null if parsing fails
     */
    fun parseCloudMetadata(metadataString: String?): JSONObject? {
        if (metadataString.isNullOrBlank()) {
            Log.w(TAG, "Metadata string is null or empty")
            return null
        }

        return try {
            JSONObject(metadataString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse metadata JSON: ${e.message}")
            null
        }
    }


    /**
     * Extract video URL from parsed metadata.
     * Tries multiple fields: videoUrl, video, url, or constructs from orderId/uniqueId.
     * @param metadata Parsed JSON metadata
     * @return Video URL string or null if not found
     */
    fun extractVideoURL(metadata: JSONObject?): String? {
        if (metadata == null) {
            return null
        }

        // Try direct video URL fields first
        val directUrl = metadata.optString("videoUrl", null)
            ?: metadata.optString("video", null)
            ?: metadata.optString("url", null)

        if (!directUrl.isNullOrBlank()) {
            return directUrl
        }

        // Construct URL from orderId and uniqueId
        val orderId = metadata.optString("orderId", null)
        val uniqueId = metadata.optString("uniqueId", null)

        if (!orderId.isNullOrBlank() && !uniqueId.isNullOrBlank()) {
            return constructFirebaseURL(orderId, uniqueId)
        }

        Log.w(TAG, "Could not extract video URL from metadata")
        return null
    }


    /**
     * Construct Firebase Storage URL from orderId and uniqueId.
     * @param orderId Order ID from metadata
     * @param uniqueId Unique ID from metadata
     * @return Full Firebase Storage URL
     */
    private fun constructFirebaseURL(orderId: String, uniqueId: String): String {
        // Construct path: uploads/{orderId}/{uniqueId}-video.mp4
        val path = "$FIREBASE_BASE_PATH$orderId/$uniqueId-video.mp4"
        
        // Firebase Storage expects the full path to be URL encoded in the 'o/' parameter.
        // The '/' characters must be encoded as '%2F'.
        val encodedPath = URLEncoder.encode(path, "UTF-8")
            .replace("+", "%20") // Spaces should be %20, not +

        return String.format(FIREBASE_URL_TEMPLATE, FIREBASE_BUCKET, encodedPath)
    }


    /**
     * Get target width from metadata, with fallback to default.
     * @param metadata Parsed JSON metadata
     * @return Target width in meters
     */
    fun getTargetWidth(metadata: JSONObject?): Float {
        if (metadata == null) {
            return DEFAULT_TARGET_WIDTH
        }

        return try {
            val width = metadata.optDouble("width", DEFAULT_TARGET_WIDTH.toDouble())
            width.toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get target width from metadata: ${e.message}")
            DEFAULT_TARGET_WIDTH
        }
    }


    /**
     * Check if video should autoplay from metadata.
     * @param metadata Parsed JSON metadata
     * @return true if autoplay is enabled (default: true)
     */
    fun shouldAutoPlay(metadata: JSONObject?): Boolean {
        if (metadata == null) {
            return true
        }

        return metadata.optBoolean("autoPlay", true)
    }


    /**
     * Check if video should loop from metadata.
     * @param metadata Parsed JSON metadata
     * @return true if loop is enabled (default: true)
     */
    fun shouldLoop(metadata: JSONObject?): Boolean {
        if (metadata == null) {
            return true
        }

        return metadata.optBoolean("loop", true)
    }


    /**
     * Check if video should be muted from metadata.
     * @param metadata Parsed JSON metadata
     * @return true if muted (default: false)
     */
    fun shouldMute(metadata: JSONObject?): Boolean {
        if (metadata == null) {
            return false
        }

        return metadata.optBoolean("muted", false)
    }

    /**
     * Extract video rotation from metadata.
     * @param metadata Parsed JSON metadata
     * @return Video rotation in degrees (0, 90, 180, 270)
     */
    fun getVideoRotation(metadata: JSONObject?): Int {
        if (metadata == null) {
            return 0
        }

        return metadata.optInt("videoRotation", 0)
    }

    /**
     * Check if metadata contains an eventId.
     * @param metadata Parsed JSON metadata
     * @return true if eventId is present
     */
    fun hasEventId(metadata: JSONObject?): Boolean {
        return metadata?.has("eventId") == true
    }
}
