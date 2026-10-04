package com.pluxy.tv.api

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
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

    @OptIn(UnstableApi::class)
    fun capabilities(): ClientCapabilities {
        val ctx = PluxyApplication.instance
        val hevc = hevcInfo()            // (supporté, main10, hauteurMax)
        val (audioCodecs, maxCh) = audioCapabilities(ctx)
        val videoCodecs = videoCodecs()
        val detected = ClientCapabilities(
            supportsHevc = hevc.supported,
            supportsHevc10bit = hevc.main10,
            supportsH264 = supportsCodec(MediaFormat.MIMETYPE_VIDEO_AVC),
            supportsAv1 = "av1" in videoCodecs,
            supportsHdr10 = supportsHdr10(ctx),
            supportsHdr = supportsHdr10(ctx),
            supportedContainers = listOf("mp4", "mkv", "webm", "ts"),
            supportedAudioCodecs = audioCodecs,
            supportedVideoCodecs = videoCodecs,
            maxAudioChannels = maxCh,
            maxBitrateMbps = null,
            maxVideoHeight = maxVideoHeight().coerceAtLeast(hevc.maxHeight),
            screenWidth = displaySize(ctx).first,
            screenHeight = displaySize(ctx).second,
        )
        return TvProfileStore(ctx).applyTo(detected)
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
    @OptIn(UnstableApi::class)
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

    /** Liste normalisée des décodeurs vidéo réellement publiés par MediaCodec. */
    private fun videoCodecs(): List<String> {
        val names = linkedSetOf<String>()
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder) return@forEach
                info.supportedTypes.forEach { mime ->
                    when (mime.lowercase()) {
                        MediaFormat.MIMETYPE_VIDEO_AVC -> names += "h264"
                        MediaFormat.MIMETYPE_VIDEO_HEVC -> names += "hevc"
                        "video/av01" -> names += "av1"
                        "video/x-vnd.on2.vp9" -> names += "vp9"
                        "video/x-vnd.on2.vp8" -> names += "vp8"
                        "video/mpeg2" -> names += "mpeg2video"
                    }
                }
            }
        }
        return names.toList()
    }

    private fun maxVideoHeight(): Int {
        var height = 0
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder) return@forEach
                info.supportedTypes.filter { it.startsWith("video/", ignoreCase = true) }
                    .forEach { mime ->
                        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                        height = maxOf(height, caps?.videoCapabilities?.supportedHeights?.upper ?: 0)
                    }
            }
        }
        return height.takeIf { it > 0 } ?: 1080
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
            // Repli : un panneau Android TV peut signaler à tort « pas de HDR » (selon
            // l'état de la dalle au moment de la requête). Comme le décodeur gère le
            // HEVC Main10, on considère la TV comme HDR-capable -> on ne dégrade PAS en
            // SDR par erreur. (Le serveur a de toute façon le dernier mot via ses réglages.)
            isTelevision(ctx) && hevcInfo().main10
        } catch (_: Throwable) {
            false
        }
    }

    private fun isTelevision(ctx: Context): Boolean {
        val ui = ctx.getSystemService(Context.UI_MODE_SERVICE)
            as? android.app.UiModeManager
        return ui?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun displaySize(ctx: Context): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(1280) to dm.heightPixels.coerceAtLeast(720)
    }
}
