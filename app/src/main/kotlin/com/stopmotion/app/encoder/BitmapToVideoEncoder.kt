package com.stopmotion.app.encoder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import kotlin.math.roundToInt

/**
 * Encodes still images to H.264/MP4 using the codec's YUV input buffers.
 * ByteBuffer input is used so every frame receives a deterministic PTS;
 * drawing speed can no longer make the resulting video duration collapse.
 */
class BitmapToVideoEncoder(
    private val outputPath: String,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int,
    private val frameRate: Int,
    private val onProgress: ((Float) -> Unit)? = null,
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val inputColorFormat: Int
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var presentationTimeUs = 0L
    private var totalFrames = 0
    private var encodedFrames = 0
    private var released = false
    private var eosQueued = false
    private val bufferInfo = MediaCodec.BufferInfo()

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "Video dimensions must be positive even numbers (got ${width}x$height)"
        }
        require(frameRate in 1..60) { "Frame rate must be between 1 and 60" }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val capabilities = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        inputColorFormat = chooseInputColorFormat(capabilities.colorFormats)
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, inputColorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun setTotalFrames(count: Int) {
        totalFrames = count.coerceAtLeast(1)
    }

    /** Queues one image with a deterministic PTS in microseconds. */
    fun encodeFrame(bitmap: Bitmap, durationUs: Long) {
        check(!released) { "Encoder has been released" }
        check(!eosQueued) { "End of stream has already been queued" }
        require(durationUs > 0) { "Frame duration must be positive" }
        queueInput(bitmapToYuv(bitmap), presentationTimeUs)
        drainEncoder(endOfStream = false)
        presentationTimeUs += durationUs
        encodedFrames++
        onProgress?.invoke(
            if (totalFrames > 0) {
                (encodedFrames.toFloat() / totalFrames).coerceIn(0f, 1f)
            } else 0f,
        )
    }

    /** Signals EOS, drains all output and closes the MP4. */
    fun finish() {
        if (released) return
        try {
            if (!eosQueued) {
                queueInput(ByteArray(0), presentationTimeUs, endOfStream = true)
                eosQueued = true
            }
            drainEncoder(endOfStream = true)
        } finally {
            release()
        }
    }

    private fun queueInput(data: ByteArray, ptsUs: Long, endOfStream: Boolean = false) {
        val deadline = System.nanoTime() + INPUT_TIMEOUT_NS
        while (true) {
            check(System.nanoTime() < deadline) { "Timed out waiting for encoder input buffer" }
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drainEncoder(endOfStream = false)
                continue
            }
            val input = codec.getInputBuffer(index)
                ?: error("Encoder returned a null input buffer")
            input.clear()
            check(input.remaining() >= data.size) {
                "Input buffer too small (${input.remaining()} < ${data.size})"
            }
            if (data.isNotEmpty()) input.put(data)
            codec.queueInputBuffer(
                index,
                0,
                data.size,
                ptsUs,
                if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
            )
            return
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val deadline = if (endOfStream) System.nanoTime() + EOS_TIMEOUT_NS else Long.MAX_VALUE
        while (true) {
            if (endOfStream) check(System.nanoTime() < deadline) {
                "Timed out draining H.264 encoder"
            }
            when (val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder output format changed twice" }
                    videoTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (index >= 0) {
                    val output = codec.getOutputBuffer(index)
                    try {
                        // Codec configuration is supplied to MediaMuxer through
                        // INFO_OUTPUT_FORMAT_CHANGED, never as a media sample.
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "Encoder produced data before output format" }
                            val encodedOutput = checkNotNull(output) {
                                "Encoder returned a null output buffer"
                            }
                            encodedOutput.position(bufferInfo.offset)
                            encodedOutput.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedOutput, bufferInfo)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    val end = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (end) return
                }
            }
        }
    }

    /** Creates a letterboxed ARGB bitmap and converts it to YUV420. */
    private fun bitmapToYuv(source: Bitmap): ByteArray {
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.BLACK)
            val scale = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
            val drawWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
            val drawHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
            val left = (width - drawWidth) / 2f
            val top = (height - drawHeight) / 2f
            drawBitmap(
                source,
                null,
                android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        frame.recycle()

        val yPlane = ByteArray(width * height)
        val chroma = ByteArray(width * height / 2)
        var chromaIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                yPlane[y * width + x] = yValue(r, g, b)
                if ((y and 1) == 0 && (x and 1) == 0) {
                    val u = uValue(r, g, b)
                    val v = vValue(r, g, b)
                    if (inputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        chroma[chromaIndex++] = u
                    } else {
                        chroma[chromaIndex++] = u
                        chroma[chromaIndex++] = v
                    }
                }
            }
        }
        if (inputColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            var vIndex = width * height / 4
            for (y in 0 until height step 2) {
                for (x in 0 until width step 2) {
                    val color = pixels[y * width + x]
                    vIndex.let { chroma[it] = vValue((color shr 16) and 0xff, (color shr 8) and 0xff, color and 0xff) }
                    vIndex++
                }
            }
        }
        return yPlane + chroma
    }

    private fun yValue(r: Int, g: Int, b: Int): Byte =
        (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()

    private fun uValue(r: Int, g: Int, b: Int): Byte =
        (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()

    private fun vValue(r: Int, g: Int, b: Int): Byte =
        (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()

    private fun release() {
        if (released) return
        try { codec.stop() } catch (e: Exception) { Log.w(TAG, "Codec stop failed", e) }
        try { codec.release() } catch (e: Exception) { Log.w(TAG, "Codec release failed", e) }
        try {
            if (muxerStarted) muxer.stop()
            muxer.release()
        } catch (e: Exception) { Log.w(TAG, "Muxer release failed", e) }
        released = true
    }

    companion object {
        private const val TAG = "BitmapToVideoEncoder"
        private const val TIMEOUT_US = 10_000L
        private const val INPUT_TIMEOUT_NS = 10_000_000_000L
        private const val EOS_TIMEOUT_NS = 30_000_000_000L

        private fun chooseInputColorFormat(formats: IntArray): Int {
            val preferred = intArrayOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            )
            return preferred.firstOrNull { it in formats }
                ?: throw IllegalStateException("No supported YUV420 input format for AVC encoder")
        }

        fun fromSpec(
            output: File,
            spec: ExportResolutionSpec,
            frameRate: Int,
            onProgress: ((Float) -> Unit)? = null,
        ): BitmapToVideoEncoder = BitmapToVideoEncoder(
            outputPath = output.absolutePath,
            width = spec.width,
            height = spec.height,
            bitRate = spec.bitRate,
            frameRate = frameRate,
            onProgress = onProgress,
        )
    }
}

data class ExportResolutionSpec(
    val width: Int,
    val height: Int,
    val bitRate: Int,
)
