package com.pluxy.tv.api

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import com.pluxy.tv.PluxyApplication

/**
 * Détecte dynamiquement les capacités RÉELLES de décodage de l'appareil
 * (téléphone OU TV) : support HEVC, HEVC **Main10 (10 bits)**, hauteur max
 * décodable, HDR. Le serveur s'en sert pour choisir DÈS LE DÉPART le bon flux
 * (Direct Play vs transcodage compat H.264) — sans tenter une lecture vouée à l'échec.
 */
object DeviceProfile {

    @UnstableApi
    fun capabilities(): ClientCapabilities {
        val ctx = PluxyApplication.instance
        val hevc = hevcInfo()            // (supporté, main10, hauteurMax)
        val (audioCodecs, maxCh) = audioCapabilities(ctx)
        return ClientCapabilities(
            supportsHevc = hevc.supported,
            supportsHevc10bit = hevc.main10,
            supportsH264 = supportsCodec(MediaFormat.MIMETYPE_VIDEO_AVC),
            supportsHdr10 = supportsHdr10(ctx),
            supportsHdr = supportsHdr10(ctx),
            supportedContainers = listOf("mp4", "mkv", "webm", "ts"),
            supportedAudioCodecs = audioCodecs,
            maxAudioChannels = maxCh,
            maxBitrateMbps = null,
            maxVideoHeight = hevc.maxHeight,
            screenWidth = displaySize(ctx).first,
            screenHeight = displaySize(ctx).second,
        )
    }

    /**
     * Codecs audio RÉELLEMENT restituables, sans rien supposer :
     *   - DÉCODAGE logiciel/matériel effectif (MediaCodecList) : AAC, AC3, EAC3, MP3,
     *     FLAC, Opus, Vorbis… On ne suppose PAS AC3/EAC3 (beaucoup de mobiles sans
     *     licence Dolby ne les décodent pas).
     *   - PASSTHROUGH/bitstream vers l'ampli (AudioCapabilities) : DTS, DTS-HD, TrueHD,
     *     Atmos (EAC3 JOC)… transmis TELS QUELS quand l'ampli/HDMI le permet.
     * AAC est toujours inclus (Android le décode partout) -> le serveur a toujours une
     * cible de repli audible. Garantit que TOUS les formats audio passent.
     */
    @UnstableApi
    private fun audioCapabilities(ctx: Context): Pair<List<String>, Int> {
        val codecs = linkedSetOf("aac")                 // toujours décodable sous Android
        // 1) Décodeurs présents sur l'appareil (vérité terrain).
        val mimeToName = mapOf(
            "audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3",
            "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/eac3-joc" to "eac3",
            "audio/flac" to "flac", "audio/opus" to "opus", "audio/vorbis" to "vorbis",
            "audio/vnd.dts" to "dts", "audio/vnd.dts.hd" to "dtshd", "audio/true-hd" to "truehd",
        )
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (t in info.supportedTypes) {
                    mimeToName[t.lowercase()]?.let { codecs.add(it) }
                }
            }
        } catch (_: Throwable) {
        }
        // 2) Passthrough bitstream vers l'ampli (HDMI/eARC).
        var maxCh = 2
        try {
            val caps = AudioCapabilities.getCapabilities(ctx)
            fun pt(enc: Int, name: String) { if (caps.supportsEncoding(enc)) codecs.add(name) }
            pt(C.ENCODING_AC3, "ac3"); pt(C.ENCODING_E_AC3, "eac3")
            pt(C.ENCODING_E_AC3_JOC, "eac3")
            pt(C.ENCODING_DTS, "dts"); pt(C.ENCODING_DTS_HD, "dtshd")
            pt(C.ENCODING_DOLBY_TRUEHD, "truehd")
            maxCh = caps.maxChannelCount.coerceAtLeast(2)
        } catch (_: Throwable) {
        }
        return codecs.toList() to maxCh
    }

    private data class HevcInfo(val supported: Boolean, val main10: Boolean, val maxHeight: Int)

    /** Inspecte tous les décodeurs HEVC et agrège leurs capacités. */
    private fun hevcInfo(): HevcInfo {
        var supported = false
        var main10 = false
        var maxHeight = 0
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            val type = info.supportedTypes.firstOrNull {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            } ?: continue
            supported = true
            val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull() ?: continue
            if (caps.profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                }) main10 = true
            caps.videoCapabilities?.supportedHeights?.upper?.let { if (it > maxHeight) maxHeight = it }
        }
        if (maxHeight == 0) maxHeight = if (supported) 2160 else 1080
        return HevcInfo(supported, main10, maxHeight)
    }

    private fun supportsCodec(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    /**
     * Panneau HDR ? On accepte TOUTE forme de HDR (HDR10, HDR10+, HLG, Dolby Vision) :
     * si l'écran fait du HDR, on lui envoie le HDR tel quel (pas de tone mapping SDR).
     * Détection via l'API ancienne (hdrCapabilities) ET la nouvelle (Display.Mode, API 34+).
     */
    private fun supportsHdr10(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val display = (ctx.getSystemService(Context.DISPLAY_SERVICE)
                as android.hardware.display.DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            @Suppress("DEPRECATION")
            val legacy = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            if (legacy.isNotEmpty()) return true        // toute capacité HDR du panneau
            if (Build.VERSION.SDK_INT >= 34) {
                val modeTypes = display.mode?.supportedHdrTypes ?: IntArray(0)
                if (modeTypes.isNotEmpty()) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun displaySize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1280) to dm.heightPixels.coerceAtLeast(720)
    }
}
