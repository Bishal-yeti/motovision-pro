package com.motovision.pro.video

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * High-performance video recording engine using CameraX VideoCapture and Recorder.
 * Manages video recording lifecycles, audio streaming, duration tracking, and file outputs.
 */
class VideoRecordingEngine(
    private val context: Context,
    private val executor: Executor
) {
    enum class RecordingState {
        IDLE,
        RECORDING,
        PAUSED,
        FINALIZED,
        ERROR
    }

    data class RecordingProgress(
        val durationMs: Long,
        val formattedTime: String,
        val bytesRecorded: Long,
        val state: RecordingState
    )

    var currentConfig: VideoConfiguration = VideoConfiguration()
        private set

    private var recorder: Recorder? = null
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    private var activeRecording: Recording? = null
    var currentState: RecordingState = RecordingState.IDLE
        private set

    var onProgressUpdate: ((RecordingProgress) -> Unit)? = null
    var onRecordingFinalized: ((File, Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        buildVideoCapture()
    }

    fun updateConfiguration(config: VideoConfiguration) {
        currentConfig = config
        buildVideoCapture()
    }

    /**
     * Builds the VideoCapture use case with the desired quality selector.
     */
    fun buildVideoCapture(): VideoCapture<Recorder> {
        val quality = currentConfig.resolution.quality
        val fallbackQuality = if (quality == Quality.FHD) Quality.HD else Quality.SD
        val qualitySelector = QualitySelector.from(
            quality,
            FallbackStrategy.lowerQualityOrHigherThan(fallbackQuality)
        )

        val newRecorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setExecutor(executor)
            .build()

        recorder = newRecorder
        val newVideoCapture = VideoCapture.withOutput(newRecorder)
        videoCapture = newVideoCapture
        return newVideoCapture
    }

    /**
     * Starts recording to a media file in Pictures/MotoVisionPro.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        customOutputFile: File? = null,
        onStarted: (() -> Unit)? = null
    ) {
        if (currentState == RecordingState.RECORDING || currentState == RecordingState.PAUSED) {
            Log.w(TAG, "Recording already in progress")
            return
        }

        val rec = recorder ?: run {
            onError?.invoke("Video recorder not initialized")
            return
        }

        val outputFile = customOutputFile ?: createVideoOutputFile()
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        var pending = rec.prepareRecording(context, outputOptions)
        if (currentConfig.audioEnabled) {
            pending = pending.withAudioEnabled()
        }

        val mainExecutor = ContextCompat.getMainExecutor(context)
        activeRecording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    currentState = RecordingState.RECORDING
                    onStarted?.invoke()
                    dispatchProgress(0L, 0L, RecordingState.RECORDING)
                }
                is VideoRecordEvent.Status -> {
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    val bytes = event.recordingStats.numBytesRecorded
                    dispatchProgress(durationMs, bytes, currentState)
                }
                is VideoRecordEvent.Pause -> {
                    currentState = RecordingState.PAUSED
                    dispatchProgress(0L, 0L, RecordingState.PAUSED)
                }
                is VideoRecordEvent.Resume -> {
                    currentState = RecordingState.RECORDING
                    dispatchProgress(0L, 0L, RecordingState.RECORDING)
                }
                is VideoRecordEvent.Finalize -> {
                    currentState = RecordingState.IDLE
                    activeRecording = null
                    if (event.hasError()) {
                        val errMsg = "Recording error code: ${event.error}"
                        Log.e(TAG, errMsg, event.cause)
                        onError?.invoke(errMsg)
                        onRecordingFinalized?.invoke(outputFile, false)
                    } else {
                        Log.i(TAG, "Video successfully saved to: ${outputFile.absolutePath}")
                        onRecordingFinalized?.invoke(outputFile, true)
                    }
                }
            }
        }
    }

    fun pauseRecording() {
        if (currentState == RecordingState.RECORDING) {
            activeRecording?.pause()
        }
    }

    fun resumeRecording() {
        if (currentState == RecordingState.PAUSED) {
            activeRecording?.resume()
        }
    }

    fun stopRecording() {
        if (currentState == RecordingState.RECORDING || currentState == RecordingState.PAUSED) {
            activeRecording?.stop()
        }
    }

    private fun dispatchProgress(durationMs: Long, bytes: Long, state: RecordingState) {
        val seconds = (durationMs / 1000L) % 60
        val minutes = (durationMs / (1000L * 60)) % 60
        val hours = durationMs / (1000L * 60 * 60)
        val formatted = if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }

        onProgressUpdate?.invoke(
            RecordingProgress(
                durationMs = durationMs,
                formattedTime = formatted,
                bytesRecorded = bytes,
                state = state
            )
        )
    }

    fun createVideoOutputFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.let {
            File(it, "MotoVisionPro").apply { mkdirs() }
        } ?: context.filesDir

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "MV_VID_${timeStamp}.mp4")
    }

    companion object {
        private const val TAG = "VideoRecordingEngine"

        /**
         * Formats milliseconds into standard recording timer (MM:SS or HH:MM:SS).
         */
        fun formatDuration(durationMs: Long): String {
            val seconds = (durationMs / 1000L) % 60
            val minutes = (durationMs / (1000L * 60)) % 60
            val hours = durationMs / (1000L * 60 * 60)
            return if (hours > 0) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.US, "%02d:%02d", minutes, seconds)
            }
        }
    }
}
