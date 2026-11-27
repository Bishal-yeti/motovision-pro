package com.motovision.pro.video

import androidx.camera.video.Quality

/**
 * Video recording configuration tailored for the Motorola Moto G23 (MediaTek Helio G85).
 * Supports standard and high-bitrate options (up to 40 Mbps to eliminate stock camera macroblocking).
 */
data class VideoConfiguration(
    val resolution: VideoResolution = VideoResolution.FHD_1080P,
    val codec: VideoCodec = VideoCodec.H264,
    val bitrateMode: BitrateMode = BitrateMode.HIGH,
    val targetFps: Int = 30,
    val audioEnabled: Boolean = true,
    val audioSampleRate: Int = 48000,
    val audioBitrate: Int = 192000, // 192 kbps high-fidelity AAC
    val isEisStabilizationEnabled: Boolean = true
) {
    enum class VideoResolution(val width: Int, val height: Int, val displayName: String, val quality: Quality) {
        FHD_1080P(1920, 1080, "1080p FHD", Quality.FHD),
        HD_720P(1280, 720, "720p HD", Quality.HD)
    }

    enum class VideoCodec(val mimeType: String, val displayName: String) {
        H264("video/avc", "H.264 (AVC)"),
        HEVC("video/hevc", "H.265 (HEVC)")
    }

    enum class BitrateMode(val displayName: String) {
        STANDARD("Standard (16 Mbps)"),
        HIGH("High Bitrate (35-40 Mbps)")
    }

    /**
     * Calculates optimal target video bitrate in bits/second.
     * Moto G23 stock camera compresses 1080p at ~14 Mbps, causing macroblock smearing.
     * MotoVision Pro High Bitrate mode delivers 38 Mbps for crystalline motion definition.
     */
    fun calculateTargetBitrate(): Int {
        val baseBitrate = when (resolution) {
            VideoResolution.FHD_1080P -> when (bitrateMode) {
                BitrateMode.HIGH -> 38_000_000 // 38 Mbps
                BitrateMode.STANDARD -> 16_000_000 // 16 Mbps
            }
            VideoResolution.HD_720P -> when (bitrateMode) {
                BitrateMode.HIGH -> 20_000_000 // 20 Mbps
                BitrateMode.STANDARD -> 10_000_000 // 10 Mbps
            }
        }

        // HEVC is ~30% more efficient at equivalent perceived quality
        return if (codec == VideoCodec.HEVC) {
            (baseBitrate * 0.75f).toInt()
        } else {
            baseBitrate
        }
    }

    /**
     * Calculates estimated file size in megabytes for a given duration in seconds.
     */
    fun estimateFileSizeMb(durationSeconds: Int): Float {
        val totalBitsPerSec = calculateTargetBitrate() + if (audioEnabled) audioBitrate else 0
        val totalBytes = (totalBitsPerSec.toLong() * durationSeconds) / 8L
        return totalBytes / (1024f * 1024f)
    }

    /**
     * Electronic Image Stabilization (EIS) requires an ~8-10% sensor crop margin
     * for motion damping vector displacement.
     */
    fun getEisCropMarginFactor(): Float {
        return if (isEisStabilizationEnabled) 0.90f else 1.0f
    }
}
