package com.stopmotion.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import kotlin.math.max

/**
 * Bitmap loading utilities tuned for stop-motion workloads.
 *
 * The key entry point is [loadDownsampled] which reads a bitmap from a
 * file path or content [Uri] and downsamples it to a maximum dimension.
 * This avoids OOM errors when feeding large CameraX photos (often 4000+
 * pixels) into the encoder surface (typically 720-1080 pixels).
 *
 * EXIF rotation is **not** applied here — CameraX frames are written with
 * TargetRotation set, and picked images are expected to be already
 * correctly oriented. If you need explicit EXIF rotation handling, add
 * the `androidx.exifinterface:exifinterface` dependency and extend the
 * loader accordingly.
 */
object BitmapLoader {

    /**
     * Loads a bitmap from either [filePath] or [uri], downsampled so that
     * its longest edge does not exceed [targetMaxDim] pixels.
     *
     * Returns null if the source could not be decoded.
     */
    fun loadDownsampled(
        context: Context,
        filePath: String?,
        uri: Uri?,
        targetMaxDim: Int = 1920,
    ): Bitmap? {
        if (filePath == null && uri == null) return null

        // Step 1 — bounds-only decode to compute sample size.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (filePath != null) {
            BitmapFactory.decodeFile(filePath, opts)
        } else {
            context.contentResolver.openInputStream(uri!!)?.use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        val maxDim = max(opts.outWidth, opts.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > targetMaxDim) sampleSize *= 2

        // Step 2 — actual decode with sample size.
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return if (filePath != null) {
            BitmapFactory.decodeFile(filePath, decodeOpts)
        } else {
            context.contentResolver.openInputStream(uri!!)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            }
        }
    }

    /**
     * Variant that resizes the loaded bitmap to exactly the requested
     * [targetWidth] x [targetHeight] using a center-crop strategy. Useful
     * for generating fixed-size thumbnails.
     */
    fun loadResizedCenterCrop(
        context: Context,
        filePath: String?,
        uri: Uri?,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val source = loadDownsampled(context, filePath, uri, targetMaxDim = maxOf(targetWidth, targetHeight) * 2)
            ?: return null
        return centerCrop(source, targetWidth, targetHeight)
    }

    private fun centerCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val scale = maxOf(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (srcW * scale).toInt().coerceAtLeast(targetW)
        val scaledH = (srcH * scale).toInt().coerceAtLeast(targetH)
        val scaled = if (scale != 1f) {
            Bitmap.createScaledBitmap(src, scaledW, scaledH, true).also {
                if (it != src) src.recycle()
            }
        } else src
        val left = ((scaledW - targetW) / 2f).toInt().coerceAtLeast(0)
        val top = ((scaledH - targetH) / 2f).toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(scaled, left, top, targetW, targetH).also {
            if (it != scaled) scaled.recycle()
        }
    }
}
