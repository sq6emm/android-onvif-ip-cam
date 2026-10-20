package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.onvif.Soap.esc
import pl.arhea.onvifipcam.rtsp.VideoCodec

/** Shared XML fragments for Media v1 and Media2. */
internal object MediaXml {
    const val VIDEO_SOURCE = "vs0"
    const val VSC = "vsc0"
    const val AUDIO_SOURCE = "as0"
    const val ASC = "asc0"
    const val AEC = "aec0"

    val MAIN_RESOLUTIONS = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720)
    val SUB_RESOLUTIONS = listOf(1280 to 720, 960 to 540, 854 to 480, 640 to 360)

    fun videoSourceConfig(d: OnvifDevice, tag: String = "tt:VideoSourceConfiguration"): String {
        val (w, h) = d.sourceSize()
        return "<$tag token=\"$VSC\"><tt:Name>VideoSourceConfig</tt:Name><tt:UseCount>${d.profiles().size}</tt:UseCount>" +
            "<tt:SourceToken>$VIDEO_SOURCE</tt:SourceToken><tt:Bounds x=\"0\" y=\"0\" width=\"$w\" height=\"$h\"/></$tag>"
    }

    fun audioSourceConfig(d: OnvifDevice, tag: String = "tt:AudioSourceConfiguration") =
        "<$tag token=\"$ASC\"><tt:Name>AudioSourceConfig</tt:Name><tt:UseCount>${d.profiles().size}</tt:UseCount>" +
            "<tt:SourceToken>$AUDIO_SOURCE</tt:SourceToken></$tag>"

    private const val MULTICAST =
        "<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type><tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address>" +
            "<tt:Port>0</tt:Port><tt:TTL>1</tt:TTL><tt:AutoStart>false</tt:AutoStart></tt:Multicast>"

    /** Media v1 encoder config. v1 cannot describe H.265, so H.265 streams are reported as H264 here. */
    fun videoEncoderConfig(p: StreamProfile, tag: String = "tt:VideoEncoderConfiguration") =
        "<$tag token=\"${p.encoderToken}\"><tt:Name>${esc(p.name)}Encoder</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>H264</tt:Encoding><tt:Resolution><tt:Width>${p.width}</tt:Width><tt:Height>${p.height}</tt:Height></tt:Resolution>" +
            "<tt:Quality>5</tt:Quality><tt:RateControl><tt:FrameRateLimit>${p.fps}</tt:FrameRateLimit>" +
            "<tt:EncodingInterval>1</tt:EncodingInterval><tt:BitrateLimit>${p.bitrateKbps}</tt:BitrateLimit></tt:RateControl>" +
            "<tt:H264><tt:GovLength>${p.fps * p.keyframeIntervalS}</tt:GovLength><tt:H264Profile>Main</tt:H264Profile></tt:H264>" +
            MULTICAST + "<tt:SessionTimeout>PT60S</tt:SessionTimeout></$tag>"

    fun videoEncoder2Config(p: StreamProfile, tag: String = "tr2:Configurations") =
        "<$tag token=\"${p.encoderToken}\" GovLength=\"${p.fps * p.keyframeIntervalS}\" " +
            "Profile=\"Main\">" +
            "<tt:Name>${esc(p.name)}Encoder</tt:Name><tt:UseCount>1</tt:UseCount>" +
            "<tt:Encoding>${if (p.codec == VideoCodec.H265) "H265" else "H264"}</tt:Encoding>" +
            "<tt:Resolution><tt:Width>${p.width}</tt:Width><tt:Height>${p.height}</tt:Height></tt:Resolution>" +
            "<tt:RateControl ConstantBitRate=\"true\"><tt:FrameRateLimit>${p.fps}</tt:FrameRateLimit>" +
            "<tt:BitrateLimit>${p.bitrateKbps}</tt:BitrateLimit></tt:RateControl>" +
            MULTICAST + "<tt:Quality>5</tt:Quality></$tag>"

    fun audioEncoderConfig(a: AudioInfo, tag: String = "tt:AudioEncoderConfiguration") =
        "<$tag token=\"$AEC\"><tt:Name>AudioEncoder</tt:Name><tt:UseCount>2</tt:UseCount><tt:Encoding>AAC</tt:Encoding>" +
            "<tt:Bitrate>${a.bitrateKbps}</tt:Bitrate><tt:SampleRate>${a.sampleRate / 1000}</tt:SampleRate>" +
            MULTICAST + "<tt:SessionTimeout>PT60S</tt:SessionTimeout></$tag>"

    fun audioEncoder2Config(a: AudioInfo, tag: String = "tr2:Configurations") =
        "<$tag token=\"$AEC\"><tt:Name>AudioEncoder</tt:Name><tt:UseCount>2</tt:UseCount><tt:Encoding>MP4A-LATM</tt:Encoding>" +
            MULTICAST + "<tt:Bitrate>${a.bitrateKbps}</tt:Bitrate><tt:SampleRate>${a.sampleRate}</tt:SampleRate></$tag>"

    fun resolutionsFor(p: StreamProfile, d: OnvifDevice): List<Pair<Int, Int>> {
        val main = d.profiles().firstOrNull()?.encoderToken == p.encoderToken
        val base = if (main) MAIN_RESOLUTIONS else SUB_RESOLUTIONS
        val portrait = p.height > p.width
        return base.map { (w, h) -> if (portrait) h to w else w to h }
    }

    fun findProfile(d: OnvifDevice, token: String?): StreamProfile =
        d.profiles().firstOrNull { it.token == token } ?: if (token.isNullOrEmpty()) d.profiles().first() else throw Soap.noProfile()

    fun findEncoder(d: OnvifDevice, token: String?): StreamProfile =
        d.profiles().firstOrNull { it.encoderToken == token }
            ?: throw Soap.invalidArg("ter:NoConfig", "Configuration token does not exist")

    fun parseEncoderChange(cfg: Xml): EncoderChange {
        val res = cfg.child("Resolution")
        val rc = cfg.child("RateControl")
        val gov = cfg.path("H264", "GovLength")?.text()?.toIntOrNull() ?: cfg.attr("GovLength")?.toIntOrNull()
        val fps = rc?.childText("FrameRateLimit")?.toFloatOrNull()?.toInt()
        val codec = when (cfg.childText("Encoding")?.uppercase()) {
            "H265", "HEVC" -> VideoCodec.H265
            "H264" -> VideoCodec.H264
            else -> null
        }
        return EncoderChange(
            width = res?.childText("Width")?.toIntOrNull(),
            height = res?.childText("Height")?.toIntOrNull(),
            fps = fps,
            bitrateKbps = rc?.childText("BitrateLimit")?.toIntOrNull(),
            keyframeIntervalS = if (gov != null && fps != null && fps > 0) maxOf(1, gov / fps) else null,
            codec = codec,
        )
    }
}

class MediaService {

    fun handle(c: Ctx): String? {
        val d = c.device
        val op = c.op
        return when (c.soap.opName) {
            "GetServiceCapabilities" -> "<trt:GetServiceCapabilitiesResponse>${capabilities(d)}</trt:GetServiceCapabilitiesResponse>"
            "GetProfiles" -> "<trt:GetProfilesResponse>" +
                d.profiles().joinToString("") { profile(d, it, "trt:Profiles") } + "</trt:GetProfilesResponse>"
            "GetProfile" -> "<trt:GetProfileResponse>" +
                profile(d, MediaXml.findProfile(d, op.childText("ProfileToken")), "trt:Profile") + "</trt:GetProfileResponse>"
            "GetStreamUri" -> {
                val p = MediaXml.findProfile(d, op.childText("ProfileToken"))
                "<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>${esc(c.rtspUri(p.path))}</tt:Uri>" +
                    "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>" +
                    "<tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetStreamUriResponse>"
            }
            "GetSnapshotUri" -> {
                val p = MediaXml.findProfile(d, op.childText("ProfileToken"))
                "<trt:GetSnapshotUriResponse><trt:MediaUri><tt:Uri>${esc(c.snapshotUri(p.token))}</tt:Uri>" +
                    "<tt:InvalidAfterConnect>false</tt:InvalidAfterConnect><tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>" +
                    "<tt:Timeout>PT0S</tt:Timeout></trt:MediaUri></trt:GetSnapshotUriResponse>"
            }
            "GetVideoSources" -> {
                val (w, h) = d.sourceSize()
                val fps = d.profiles().first().fps
                "<trt:GetVideoSourcesResponse><trt:VideoSources token=\"${MediaXml.VIDEO_SOURCE}\"><tt:Framerate>$fps</tt:Framerate>" +
                    "<tt:Resolution><tt:Width>$w</tt:Width><tt:Height>$h</tt:Height></tt:Resolution></trt:VideoSources></trt:GetVideoSourcesResponse>"
            }
            "GetVideoSourceConfigurations", "GetCompatibleVideoSourceConfigurations" ->
                "<trt:${c.soap.opName}Response>${MediaXml.videoSourceConfig(d, "trt:Configurations")}</trt:${c.soap.opName}Response>"
            "GetVideoSourceConfiguration" ->
                "<trt:GetVideoSourceConfigurationResponse>${MediaXml.videoSourceConfig(d, "trt:Configuration")}</trt:GetVideoSourceConfigurationResponse>"
            "GetVideoSourceConfigurationOptions" -> {
                val (w, h) = d.sourceSize()
                "<trt:GetVideoSourceConfigurationOptionsResponse><trt:Options><tt:BoundsRange>" +
                    "<tt:XRange><tt:Min>0</tt:Min><tt:Max>0</tt:Max></tt:XRange><tt:YRange><tt:Min>0</tt:Min><tt:Max>0</tt:Max></tt:YRange>" +
                    "<tt:WidthRange><tt:Min>$w</tt:Min><tt:Max>$w</tt:Max></tt:WidthRange><tt:HeightRange><tt:Min>$h</tt:Min><tt:Max>$h</tt:Max></tt:HeightRange>" +
                    "</tt:BoundsRange><tt:VideoSourceTokensAvailable>${MediaXml.VIDEO_SOURCE}</tt:VideoSourceTokensAvailable>" +
                    "</trt:Options></trt:GetVideoSourceConfigurationOptionsResponse>"
            }
            "GetVideoEncoderConfigurations" -> "<trt:GetVideoEncoderConfigurationsResponse>" +
                d.profiles().joinToString("") { MediaXml.videoEncoderConfig(it, "trt:Configurations") } +
                "</trt:GetVideoEncoderConfigurationsResponse>"
            "GetCompatibleVideoEncoderConfigurations" -> {
                val p = MediaXml.findProfile(d, op.childText("ProfileToken"))
                "<trt:GetCompatibleVideoEncoderConfigurationsResponse>${MediaXml.videoEncoderConfig(p, "trt:Configurations")}" +
                    "</trt:GetCompatibleVideoEncoderConfigurationsResponse>"
            }
            "GetVideoEncoderConfiguration" -> {
                val p = MediaXml.findEncoder(d, op.childText("ConfigurationToken"))
                "<trt:GetVideoEncoderConfigurationResponse>${MediaXml.videoEncoderConfig(p, "trt:Configuration")}</trt:GetVideoEncoderConfigurationResponse>"
            }
            "GetVideoEncoderConfigurationOptions" -> encoderOptions(c)
            "SetVideoEncoderConfiguration" -> {
                val cfg = op.child("Configuration") ?: throw Soap.invalidArg("ter:ConfigModify", "missing Configuration")
                val p = MediaXml.findEncoder(d, cfg.attr("token"))
                if (!d.changeEncoder(p.encoderToken, MediaXml.parseEncoderChange(cfg))) {
                    throw Soap.invalidArg("ter:ConfigModify", "Unsupported encoder parameters")
                }
                "<trt:SetVideoEncoderConfigurationResponse/>"
            }
            "GetAudioSources" -> if (d.audio() != null) {
                "<trt:GetAudioSourcesResponse><trt:AudioSources token=\"${MediaXml.AUDIO_SOURCE}\"><tt:Channels>1</tt:Channels></trt:AudioSources></trt:GetAudioSourcesResponse>"
            } else "<trt:GetAudioSourcesResponse/>"
            "GetAudioSourceConfigurations", "GetCompatibleAudioSourceConfigurations" -> "<trt:${c.soap.opName}Response>" +
                (if (d.audio() != null) MediaXml.audioSourceConfig(d, "trt:Configurations") else "") + "</trt:${c.soap.opName}Response>"
            "GetAudioSourceConfiguration" ->
                "<trt:GetAudioSourceConfigurationResponse>${MediaXml.audioSourceConfig(d, "trt:Configuration")}</trt:GetAudioSourceConfigurationResponse>"
            "GetAudioEncoderConfigurations", "GetCompatibleAudioEncoderConfigurations" -> "<trt:${c.soap.opName}Response>" +
                (d.audio()?.let { MediaXml.audioEncoderConfig(it, "trt:Configurations") } ?: "") + "</trt:${c.soap.opName}Response>"
            "GetAudioEncoderConfiguration" -> {
                val a = d.audio() ?: throw Soap.invalidArg("ter:NoConfig", "Audio disabled")
                "<trt:GetAudioEncoderConfigurationResponse>${MediaXml.audioEncoderConfig(a, "trt:Configuration")}</trt:GetAudioEncoderConfigurationResponse>"
            }
            "GetAudioEncoderConfigurationOptions" -> "<trt:GetAudioEncoderConfigurationOptionsResponse><trt:Options>" +
                "<tt:Options><tt:Encoding>AAC</tt:Encoding><tt:BitrateList><tt:Items>64</tt:Items></tt:BitrateList>" +
                "<tt:SampleRateList><tt:Items>44</tt:Items></tt:SampleRateList></tt:Options>" +
                "</trt:Options></trt:GetAudioEncoderConfigurationOptionsResponse>"
            "GetAudioOutputs" -> "<trt:GetAudioOutputsResponse/>"
            "GetMetadataConfigurations", "GetCompatibleMetadataConfigurations",
            "GetVideoAnalyticsConfigurations", "GetCompatibleVideoAnalyticsConfigurations",
            "GetAudioOutputConfigurations", "GetAudioDecoderConfigurations", "GetOSDs" ->
                "<trt:${c.soap.opName}Response/>"
            "SetSynchronizationPoint" -> "<trt:SetSynchronizationPointResponse/>"
            "AddVideoEncoderConfiguration", "AddVideoSourceConfiguration", "AddAudioEncoderConfiguration",
            "AddAudioSourceConfiguration", "AddPTZConfiguration", "RemovePTZConfiguration", "SetVideoSourceConfiguration" ->
                "<trt:${c.soap.opName}Response/>"
            else -> null
        }
    }

    private fun profile(d: OnvifDevice, p: StreamProfile, tag: String): String {
        val a = d.audio()
        return "<$tag token=\"${p.token}\" fixed=\"true\"><tt:Name>${esc(p.name)}</tt:Name>" +
            MediaXml.videoSourceConfig(d) +
            (if (a != null) MediaXml.audioSourceConfig(d) else "") +
            MediaXml.videoEncoderConfig(p) +
            (if (a != null) MediaXml.audioEncoderConfig(a) else "") +
            (if (d.ptz != null) PtzService.configuration("tt:PTZConfiguration") else "") +
            "</$tag>"
    }

    private fun encoderOptions(c: Ctx): String {
        val d = c.device
        val token = c.op.childText("ConfigurationToken") ?: c.op.childText("ProfileToken")?.let { pt ->
            d.profiles().firstOrNull { it.token == pt }?.encoderToken
        }
        val p = d.profiles().firstOrNull { it.encoderToken == token } ?: d.profiles().first()
        val res = MediaXml.resolutionsFor(p, d).joinToString("") { (w, h) ->
            "<tt:ResolutionsAvailable><tt:Width>$w</tt:Width><tt:Height>$h</tt:Height></tt:ResolutionsAvailable>"
        }
        val h264 = "$res<tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>300</tt:Max></tt:GovLengthRange>" +
            "<tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>30</tt:Max></tt:FrameRateRange>" +
            "<tt:EncodingIntervalRange><tt:Min>1</tt:Min><tt:Max>1</tt:Max></tt:EncodingIntervalRange>" +
            "<tt:H264ProfilesSupported>Main</tt:H264ProfilesSupported>"
        return "<trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options>" +
            "<tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange>" +
            "<tt:H264>$h264</tt:H264><tt:Extension><tt:H264>$h264" +
            "<tt:BitrateRange><tt:Min>100</tt:Min><tt:Max>20000</tt:Max></tt:BitrateRange></tt:H264></tt:Extension>" +
            "</trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>"
    }

    companion object {
        fun capabilities(d: OnvifDevice) =
            "<trt:Capabilities SnapshotUri=\"true\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\">" +
                "<trt:ProfileCapabilities MaximumNumberOfProfiles=\"${d.profiles().size}\"/>" +
                "<trt:StreamingCapabilities RTPMulticast=\"false\" RTP_TCP=\"true\" RTP_RTSP_TCP=\"true\" " +
                "NonAggregateControl=\"false\" NoRTSPStreaming=\"false\"/></trt:Capabilities>"
    }
}

class Media2Service {

    fun handle(c: Ctx): String? {
        val d = c.device
        val op = c.op
        return when (c.soap.opName) {
            "GetServiceCapabilities" -> "<tr2:GetServiceCapabilitiesResponse>${capabilities(d)}</tr2:GetServiceCapabilitiesResponse>"
            "GetProfiles" -> {
                val token = op.childText("Token")
                val types = op.children("Type").map { it.text() }
                val all = types.contains("All")
                val profiles = d.profiles().filter { token == null || it.token == token }
                if (token != null && profiles.isEmpty()) throw Soap.noProfile()
                "<tr2:GetProfilesResponse>" + profiles.joinToString("") { profile(d, it, all || types.isNotEmpty(), types) } +
                    "</tr2:GetProfilesResponse>"
            }
            "GetStreamUri" -> {
                val p = MediaXml.findProfile(d, op.childText("ProfileToken"))
                "<tr2:GetStreamUriResponse><tr2:Uri>${esc(c.rtspUri(p.path))}</tr2:Uri></tr2:GetStreamUriResponse>"
            }
            "GetSnapshotUri" -> {
                val p = MediaXml.findProfile(d, op.childText("ProfileToken"))
                "<tr2:GetSnapshotUriResponse><tr2:Uri>${esc(c.snapshotUri(p.token))}</tr2:Uri></tr2:GetSnapshotUriResponse>"
            }
            "GetVideoSourceConfigurations" ->
                "<tr2:GetVideoSourceConfigurationsResponse>${MediaXml.videoSourceConfig(d, "tr2:Configurations")}</tr2:GetVideoSourceConfigurationsResponse>"
            "GetVideoEncoderConfigurations" -> {
                val token = op.childText("ConfigurationToken")
                "<tr2:GetVideoEncoderConfigurationsResponse>" +
                    d.profiles().filter { token == null || it.encoderToken == token }
                        .joinToString("") { MediaXml.videoEncoder2Config(it) } +
                    "</tr2:GetVideoEncoderConfigurationsResponse>"
            }
            "GetAudioSourceConfigurations" -> "<tr2:GetAudioSourceConfigurationsResponse>" +
                (if (d.audio() != null) MediaXml.audioSourceConfig(d, "tr2:Configurations") else "") +
                "</tr2:GetAudioSourceConfigurationsResponse>"
            "GetAudioEncoderConfigurations" -> "<tr2:GetAudioEncoderConfigurationsResponse>" +
                (d.audio()?.let { MediaXml.audioEncoder2Config(it) } ?: "") + "</tr2:GetAudioEncoderConfigurationsResponse>"
            "GetVideoEncoderConfigurationOptions" -> {
                val token = op.childText("ConfigurationToken")
                val p = d.profiles().firstOrNull { it.encoderToken == token } ?: d.profiles().first()
                val res = MediaXml.resolutionsFor(p, d).joinToString("") { (w, h) ->
                    "<tt:ResolutionsAvailable><tt:Width>$w</tt:Width><tt:Height>$h</tt:Height></tt:ResolutionsAvailable>"
                }
                fun opt(enc: String) =
                    "<tr2:Options GovLengthRange=\"1 300\" FrameRatesSupported=\"5 10 15 20 25 30\" ProfilesSupported=\"Main\" " +
                        "ConstantBitRateSupported=\"true\"><tt:Encoding>$enc</tt:Encoding>" +
                        "<tt:QualityRange><tt:Min>1</tt:Min><tt:Max>5</tt:Max></tt:QualityRange>$res" +
                        "<tt:BitrateRange><tt:Min>100</tt:Min><tt:Max>20000</tt:Max></tt:BitrateRange></tr2:Options>"
                val main = d.profiles().first().encoderToken == p.encoderToken
                "<tr2:GetVideoEncoderConfigurationOptionsResponse>${opt("H264")}${if (main) opt("H265") else ""}" +
                    "</tr2:GetVideoEncoderConfigurationOptionsResponse>"
            }
            "SetVideoEncoderConfiguration" -> {
                val cfg = op.child("Configuration") ?: throw Soap.invalidArg("ter:ConfigModify", "missing Configuration")
                val p = MediaXml.findEncoder(d, cfg.attr("token"))
                if (!d.changeEncoder(p.encoderToken, MediaXml.parseEncoderChange(cfg))) {
                    throw Soap.invalidArg("ter:ConfigModify", "Unsupported encoder parameters")
                }
                "<tr2:SetVideoEncoderConfigurationResponse/>"
            }
            "GetMetadataConfigurations", "GetAnalyticsConfigurations", "GetAudioOutputConfigurations",
            "GetAudioDecoderConfigurations", "GetOSDs", "GetMasks" -> "<tr2:${c.soap.opName}Response/>"
            "SetSynchronizationPoint" -> "<tr2:SetSynchronizationPointResponse/>"
            "AddConfiguration", "RemoveConfiguration" -> "<tr2:${c.soap.opName}Response/>"
            else -> null
        }
    }

    private fun profile(d: OnvifDevice, p: StreamProfile, withConfigs: Boolean, types: List<String>): String {
        fun want(t: String) = "All" in types || t in types
        val a = d.audio()
        val cfg = if (!withConfigs) "" else buildString {
            append("<tr2:Configurations>")
            if (want("VideoSource")) append(MediaXml.videoSourceConfig(d, "tr2:VideoSource"))
            if (want("AudioSource") && a != null) append(MediaXml.audioSourceConfig(d, "tr2:AudioSource"))
            if (want("VideoEncoder")) append(MediaXml.videoEncoder2Config(p, "tr2:VideoEncoder"))
            if (want("AudioEncoder") && a != null) append(MediaXml.audioEncoder2Config(a, "tr2:AudioEncoder"))
            if (want("PTZ") && d.ptz != null) append(PtzService.configuration("tr2:PTZ"))
            append("</tr2:Configurations>")
        }
        return "<tr2:Profiles token=\"${p.token}\" fixed=\"true\"><tr2:Name>${esc(p.name)}</tr2:Name>$cfg</tr2:Profiles>"
    }

    companion object {
        fun capabilities(d: OnvifDevice) =
            "<tr2:Capabilities SnapshotUri=\"true\" Rotation=\"false\" VideoSourceMode=\"false\" OSD=\"false\" " +
                "TemporaryOSDText=\"false\" Mask=\"false\" SourceMask=\"false\">" +
                "<tr2:ProfileCapabilities MaximumNumberOfProfiles=\"${d.profiles().size}\" " +
                "ConfigurationsSupported=\"VideoSource VideoEncoder AudioSource AudioEncoder PTZ\"/>" +
                "<tr2:StreamingCapabilities RTSPStreaming=\"true\" RTPMulticast=\"false\" RTP_RTSP_TCP=\"true\" " +
                "NonAggregateControl=\"false\" AutoStartMulticast=\"false\"/></tr2:Capabilities>"
    }
}
