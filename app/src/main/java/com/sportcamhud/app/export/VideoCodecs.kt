package com.sportcamhud.app.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes

/** Container stays MP4; the user chooses the video elementary stream codec. */
enum class VideoCodec(val label: String, val mime: String) {
    AVC("H.264 / AVC", MimeTypes.VIDEO_H264),
    HEVC("H.265 / HEVC", MimeTypes.VIDEO_H265),
    AV1("AV1", MimeTypes.VIDEO_AV1);

    companion object {
        fun fromPreference(name: String?): VideoCodec = values().firstOrNull { it.name == name } ?: AVC
    }
}

data class EncoderAvailability(
    val codec: VideoCodec,
    val hardwareEncoder: String?,
    val estimatedMaxFps: Double?,
) {
    /** AVC keeps the historically working Media3 path; HEVC/AV1 require real hardware. */
    val enabled: Boolean get() = codec == VideoCodec.AVC || hardwareEncoder != null
}

/**
 * A decoder being present does NOT imply an encoder is present (particularly for AV1).
 * Only advertise non-AVC formats backed by a hardware encoder accepting an input Surface
 * at the actual requested output size. The codec API is advisory: real export is validated.
 */
object DeviceVideoCodecs {
    fun availability(width: Int, height: Int): List<EncoderAvailability> {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList())
        return VideoCodec.values().map { codec ->
            val candidates = infos.asSequence()
                .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(codec.mime, true) } }
                .mapNotNull { info ->
                    val caps = runCatching { info.getCapabilitiesForType(codec.mime) }.getOrNull()
                        ?: return@mapNotNull null
                    if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
                        return@mapNotNull null
                    val video = caps.videoCapabilities ?: return@mapNotNull null
                    if (!runCatching { video.isSizeSupported(width, height) }.getOrDefault(false))
                        return@mapNotNull null
                    val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.", true) &&
                        !info.name.startsWith("c2.android.", true) &&
                        !info.name.startsWith("c2.google.", true)
                    if (!isHardware) return@mapNotNull null
                    val estimated = runCatching {
                        video.getAchievableFrameRatesFor(width, height)?.upper?.toDouble()
                    }.getOrNull()
                    info.name to estimated
                }.toList()
            // Prefer a faster estimate when the driver publishes it.
            val preferred = candidates.maxByOrNull { it.second ?: -1.0 }
            EncoderAvailability(codec, preferred?.first, preferred?.second)
        }
    }

    fun requireSupported(codec: VideoCodec, width: Int, height: Int) {
        val support = availability(width, height).first { it.codec == codec }
        require(support.enabled) {
            "Brak sprzętowego kodera ${codec.label} dla ${width}×${height}. Wybierz inny kodek lub rozdzielczość."
        }
    }
}
