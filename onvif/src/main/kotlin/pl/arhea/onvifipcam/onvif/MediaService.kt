package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.onvif.Soap.esc
import pl.arhea.onvifipcam.rtsp.VideoCodec

/** Shared XML fragments for Media v1 and Media2. */
internal object MediaXml {
    const val VSC = "vsc0"
    const val AUDIO_SOURCE = "as0"
    const val ASC = "asc0"
    const val AEC = "aec0"

    val MAIN_RESOLUTIONS = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720)
    val SUB_RESOLUTIONS = listOf(1280 to 720, 960 to 540, 854 to 480, 640 to 360)

    const val OSD = "osd_datetime"
    /** CameraConfig.rotation value for Rotate Mode AUTO. */
    const val ROTATE_AUTO = -1
    private const val DEFAULT_AUDIO_KBPS = 64
    private const val DEFAULT_AUDIO_RATE = 44100

    fun videoSourceConfig(d: OnvifDevice, tag: String = "tt:VideoSourceConfiguration"): String {
        val (w, h) = d.sourceSize()
        val cfg = d.configuration.config()
        val rotate = when (cfg.rotation) {
            ROTATE_AUTO -> "<tt:Mode>AUTO</tt:Mode>"
            0 -> "<tt:Mode>OFF</tt:Mode>"
            else -> "<tt:Mode>ON</tt:Mode><tt:Degree>${cfg.rotation}</tt:Degree>"
        }
        return "<$tag token=\"$VSC\"><tt:Name>VideoSourceConfig</tt:Name><tt:UseCount>${d.profiles().size}</tt:UseCount>" +
            "<tt:SourceToken>${esc(cfg.currentSource)}</tt:SourceToken><tt:Bounds x=\"0\" y=\"0\" width=\"$w\" height=\"$h\"/>" +
            "<tt:Extension><tt:Rotate>$rotate</tt:Rotate></tt:Extension></$tag>"
    }

    /** Audio settings for listing configurations even while audio is disabled. */
    fun audioOrDefault(d: OnvifDevice) = d.audio() ?: AudioInfo(DEFAULT_AUDIO_RATE, DEFAULT_AUDIO_KBPS)

    fun videoSources(d: OnvifDevice, tag: String): String {
        val cfg = d.configuration.config()
        val fps = d.profiles().first().fps
        val (cw, ch) = d.sourceSize()
        return cfg.sources.joinToString("") { src ->
            val (w, h) = if (src.token == cfg.currentSource) cw to ch else src.width to src.height
            "<$tag token=\"${esc(src.token)}\"><tt:Framerate>$fps</tt:Framerate>" +
                "<tt:Resolution><tt:Width>$w</tt:Width><tt:Height>$h</tt:Height></tt:Resolution></$tag>"
        }
    }

    fun videoSourceOptions(d: OnvifDevice, tag: String): String {
        val (w, h) = d.sourceSize()
        return "<$tag><tt:BoundsRange>" +
            "<tt:XRange><tt:Min>0</tt:Min><tt:Max>0</tt:Max></tt:XRange><tt:YRange><tt:Min>0</tt:Min><tt:Max>0</tt:Max></tt:YRange>" +
            "<tt:WidthRange><tt:Min>$w</tt:Min><tt:Max>$w</tt:Max></tt:WidthRange><tt:HeightRange><tt:Min>$h</tt:Min><tt:Max>$h</tt:Max></tt:HeightRange>" +
            "</tt:BoundsRange>" +
            d.configuration.config().sources.joinToString("") { "<tt:VideoSourceTokensAvailable>${esc(it.token)}</tt:VideoSourceTokensAvailable>" } +
            "<tt:Extension><tt:Rotate><tt:Mode>OFF</tt:Mode><tt:Mode>ON</tt:Mode><tt:Mode>AUTO</tt:Mode>" +
            "<tt:DegreeList><tt:Items>90</tt:Items><tt:Items>180</tt:Items><tt:Items>270</tt:Items></tt:DegreeList>" +
            "</tt:Rotate></tt:Extension></$tag>"
    }

    /** SetVideoSourceConfiguration: SourceToken selects the camera, Extension/Rotate the rotation. */
    fun applyVideoSourceConfig(d: OnvifDevice, cfg: Xml) {
        val source = cfg.childText("SourceToken")
        val rotate = cfg.path("Extension", "Rotate")
        val rotation = rotate?.let { r ->
            when (r.childText("Mode")?.uppercase()) {
                "OFF" -> 0
                "AUTO" -> ROTATE_AUTO
                // ON without Degree means 180 per the ONVIF schema.
                else -> r.childText("Degree")?.toIntOrNull() ?: 180
            }
        }
        if (source != null && d.configuration.config().sources.none { it.token == source }) {
            throw Soap.invalidArg("ter:NoVideoSource", "Unknown video source $source")
        }
        if (!d.configuration.change(ConfigChange(source = source, rotation = rotation))) {
            throw Soap.invalidArg("ter:ConfigModify", "Unsupported video source configuration")
        }
    }

    fun osd(d: OnvifDevice, tag: String): String =
        if (!d.configuration.config().osdEnabled) "" else
            "<$tag token=\"$OSD\"><tt:VideoSourceConfigurationToken>$VSC</tt:VideoSourceConfigurationToken>" +
                "<tt:Type>Text</tt:Type><tt:Position><tt:Type>UpperLeft</tt:Type></tt:Position>" +
                "<tt:TextString><tt:Type>DateAndTime</tt:Type><tt:DateFormat>yyyy-MM-dd</tt:DateFormat>" +
                "<tt:TimeFormat>HH:mm:ss</tt:TimeFormat></tt:TextString></$tag>"

    fun osdOptions(tag: String) =
        "<$tag><tt:MaximumNumberOfOSDs Total=\"1\" Image=\"0\" PlainText=\"0\" Date=\"0\" Time=\"0\" DateAndTime=\"1\"/>" +
            "<tt:Type>Text</tt:Type><tt:PositionOption>UpperLeft</tt:PositionOption>" +
            "<tt:TextOption><tt:Type>DateAndTime</tt:Type><tt:DateFormat>yyyy-MM-dd</tt:DateFormat>" +
            "<tt:TimeFormat>HH:mm:ss</tt:TimeFormat></tt:TextOption></$tag>"

    fun setOsd(d: OnvifDevice, enabled: Boolean) {
        if (!d.configuration.change(ConfigChange(osdEnabled = enabled))) throw Soap.invalidArg("ter:InvalidArgVal", "OSD change rejected")
    }

    fun setAudio(d: OnvifDevice, enabled: Boolean) {
        if (!d.configuration.change(ConfigChange(audioEnabled = enabled))) throw Soap.invalidArg("ter:InvalidArgVal", "Audio change rejected")
    }

    /** The motion detector exposed as a video analytics configuration with one cell-motion rule. */
    fun analyticsConfig(d: OnvifDevice, tag: String = "tt:VideoAnalyticsConfiguration"): String =
        "<$tag token=\"${AnalyticsService.CONFIG}\"><tt:Name>MotionDetection</tt:Name><tt:UseCount>${d.profiles().size}</tt:UseCount>" +
            "<tt:AnalyticsEngineConfiguration/><tt:RuleEngineConfiguration>${AnalyticsService.rules(d, "tt:Rule")}</tt:RuleEngineConfiguration></$tag>"

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
            "GetVideoSources" -> "<trt:GetVideoSourcesResponse>${MediaXml.videoSources(d, "trt:VideoSources")}</trt:GetVideoSourcesResponse>"
            "SetVideoSourceConfiguration" -> {
                MediaXml.applyVideoSourceConfig(d, op.child("Configuration") ?: throw Soap.invalidArg("ter:ConfigModify", "missing Configuration"))
                "<trt:SetVideoSourceConfigurationResponse/>"
            }
            "GetVideoSourceConfigurations", "GetCompatibleVideoSourceConfigurations" ->
                "<trt:${c.soap.opName}Response>${MediaXml.videoSourceConfig(d, "trt:Configurations")}</trt:${c.soap.opName}Response>"
            "GetVideoSourceConfiguration" ->
                "<trt:GetVideoSourceConfigurationResponse>${MediaXml.videoSourceConfig(d, "trt:Configuration")}</trt:GetVideoSourceConfigurationResponse>"
            "GetVideoSourceConfigurationOptions" ->
                "<trt:GetVideoSourceConfigurationOptionsResponse>${MediaXml.videoSourceOptions(d, "trt:Options")}</trt:GetVideoSourceConfigurationOptionsResponse>"
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
            "GetAudioSources" ->
                "<trt:GetAudioSourcesResponse><trt:AudioSources token=\"${MediaXml.AUDIO_SOURCE}\"><tt:Channels>1</tt:Channels></trt:AudioSources></trt:GetAudioSourcesResponse>"
            "GetAudioSourceConfigurations", "GetCompatibleAudioSourceConfigurations" -> "<trt:${c.soap.opName}Response>" +
                MediaXml.audioSourceConfig(d, "trt:Configurations") + "</trt:${c.soap.opName}Response>"
            "GetAudioSourceConfiguration" ->
                "<trt:GetAudioSourceConfigurationResponse>${MediaXml.audioSourceConfig(d, "trt:Configuration")}</trt:GetAudioSourceConfigurationResponse>"
            "GetAudioEncoderConfigurations", "GetCompatibleAudioEncoderConfigurations" -> "<trt:${c.soap.opName}Response>" +
                MediaXml.audioEncoderConfig(MediaXml.audioOrDefault(d), "trt:Configurations") + "</trt:${c.soap.opName}Response>"
            "GetAudioEncoderConfiguration" ->
                "<trt:GetAudioEncoderConfigurationResponse>${MediaXml.audioEncoderConfig(MediaXml.audioOrDefault(d), "trt:Configuration")}" +
                    "</trt:GetAudioEncoderConfigurationResponse>"
            "AddAudioSourceConfiguration", "AddAudioEncoderConfiguration" -> {
                MediaXml.setAudio(d, true)
                "<trt:${c.soap.opName}Response/>"
            }
            "RemoveAudioSourceConfiguration", "RemoveAudioEncoderConfiguration" -> {
                MediaXml.setAudio(d, false)
                "<trt:${c.soap.opName}Response/>"
            }
            "GetOSDs" -> "<trt:GetOSDsResponse>${MediaXml.osd(d, "trt:OSDs")}</trt:GetOSDsResponse>"
            "GetOSD" -> {
                val x = MediaXml.osd(d, "trt:OSD")
                if (x.isEmpty() || op.childText("OSDToken") != MediaXml.OSD) throw Soap.invalidArg("ter:NoConfig", "OSD does not exist")
                "<trt:GetOSDResponse>$x</trt:GetOSDResponse>"
            }
            "GetOSDOptions" -> "<trt:GetOSDOptionsResponse>${MediaXml.osdOptions("trt:OSDOptions")}</trt:GetOSDOptionsResponse>"
            "CreateOSD" -> {
                MediaXml.setOsd(d, true)
                "<trt:CreateOSDResponse><trt:OSDToken>${MediaXml.OSD}</trt:OSDToken></trt:CreateOSDResponse>"
            }
            "SetOSD" -> {
                MediaXml.setOsd(d, true)
                "<trt:SetOSDResponse/>"
            }
            "DeleteOSD" -> {
                MediaXml.setOsd(d, false)
                "<trt:DeleteOSDResponse/>"
            }
            "GetVideoAnalyticsConfigurations", "GetCompatibleVideoAnalyticsConfigurations" ->
                "<trt:${c.soap.opName}Response>${MediaXml.analyticsConfig(d, "trt:Configurations")}</trt:${c.soap.opName}Response>"
            "GetVideoAnalyticsConfiguration" ->
                "<trt:GetVideoAnalyticsConfigurationResponse>${MediaXml.analyticsConfig(d, "trt:Configuration")}</trt:GetVideoAnalyticsConfigurationResponse>"
            "GetAudioEncoderConfigurationOptions" -> "<trt:GetAudioEncoderConfigurationOptionsResponse><trt:Options>" +
                "<tt:Options><tt:Encoding>AAC</tt:Encoding><tt:BitrateList><tt:Items>64</tt:Items></tt:BitrateList>" +
                "<tt:SampleRateList><tt:Items>44</tt:Items></tt:SampleRateList></tt:Options>" +
                "</trt:Options></trt:GetAudioEncoderConfigurationOptionsResponse>"
            "GetAudioOutputs" -> "<trt:GetAudioOutputsResponse/>"
            "GetMetadataConfigurations", "GetCompatibleMetadataConfigurations",
            "GetAudioOutputConfigurations", "GetAudioDecoderConfigurations" ->
                "<trt:${c.soap.opName}Response/>"
            "SetSynchronizationPoint" -> "<trt:SetSynchronizationPointResponse/>"
            "AddVideoEncoderConfiguration", "AddVideoSourceConfiguration", "AddVideoAnalyticsConfiguration",
            "AddPTZConfiguration", "RemovePTZConfiguration" ->
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
            MediaXml.analyticsConfig(d) +
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
            "<trt:Capabilities SnapshotUri=\"true\" Rotation=\"true\" VideoSourceMode=\"false\" OSD=\"true\">" +
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
            "GetVideoSourceConfigurationOptions" ->
                "<tr2:GetVideoSourceConfigurationOptionsResponse>${MediaXml.videoSourceOptions(d, "tr2:Options")}</tr2:GetVideoSourceConfigurationOptionsResponse>"
            "SetVideoSourceConfiguration" -> {
                MediaXml.applyVideoSourceConfig(d, op.child("Configuration") ?: throw Soap.invalidArg("ter:ConfigModify", "missing Configuration"))
                "<tr2:SetVideoSourceConfigurationResponse/>"
            }
            "GetOSDs" -> "<tr2:GetOSDsResponse>${MediaXml.osd(d, "tr2:OSDs")}</tr2:GetOSDsResponse>"
            "GetOSDOptions" -> "<tr2:GetOSDOptionsResponse>${MediaXml.osdOptions("tr2:OSDOptions")}</tr2:GetOSDOptionsResponse>"
            "CreateOSD" -> {
                MediaXml.setOsd(d, true)
                "<tr2:CreateOSDResponse><tr2:OSDToken>${MediaXml.OSD}</tr2:OSDToken></tr2:CreateOSDResponse>"
            }
            "SetOSD" -> { MediaXml.setOsd(d, true); "<tr2:SetOSDResponse/>" }
            "DeleteOSD" -> { MediaXml.setOsd(d, false); "<tr2:DeleteOSDResponse/>" }
            "GetVideoEncoderConfigurations" -> {
                val token = op.childText("ConfigurationToken")
                "<tr2:GetVideoEncoderConfigurationsResponse>" +
                    d.profiles().filter { token == null || it.encoderToken == token }
                        .joinToString("") { MediaXml.videoEncoder2Config(it) } +
                    "</tr2:GetVideoEncoderConfigurationsResponse>"
            }
            "GetAudioSourceConfigurations" -> "<tr2:GetAudioSourceConfigurationsResponse>" +
                MediaXml.audioSourceConfig(d, "tr2:Configurations") + "</tr2:GetAudioSourceConfigurationsResponse>"
            "GetAudioEncoderConfigurations" -> "<tr2:GetAudioEncoderConfigurationsResponse>" +
                MediaXml.audioEncoder2Config(MediaXml.audioOrDefault(d)) + "</tr2:GetAudioEncoderConfigurationsResponse>"
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
            "GetAnalyticsConfigurations" ->
                "<tr2:GetAnalyticsConfigurationsResponse>${MediaXml.analyticsConfig(d, "tr2:Configurations")}</tr2:GetAnalyticsConfigurationsResponse>"
            "GetMetadataConfigurations", "GetAudioOutputConfigurations",
            "GetAudioDecoderConfigurations", "GetMasks" -> "<tr2:${c.soap.opName}Response/>"
            "SetSynchronizationPoint" -> "<tr2:SetSynchronizationPointResponse/>"
            "AddConfiguration", "RemoveConfiguration" -> {
                // Adding/removing the audio configurations switches audio on/off; other types are fixed.
                val types = op.children("Configuration").mapNotNull { it.childText("Type") }
                if (types.any { it == "AudioSource" || it == "AudioEncoder" || it == "All" }) {
                    MediaXml.setAudio(d, c.soap.opName == "AddConfiguration")
                }
                "<tr2:${c.soap.opName}Response/>"
            }
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
            if (want("Analytics")) append(MediaXml.analyticsConfig(d, "tr2:Analytics"))
            if (want("PTZ") && d.ptz != null) append(PtzService.configuration("tr2:PTZ"))
            append("</tr2:Configurations>")
        }
        return "<tr2:Profiles token=\"${p.token}\" fixed=\"true\"><tr2:Name>${esc(p.name)}</tr2:Name>$cfg</tr2:Profiles>"
    }

    companion object {
        fun capabilities(d: OnvifDevice) =
            "<tr2:Capabilities SnapshotUri=\"true\" Rotation=\"true\" VideoSourceMode=\"false\" OSD=\"true\" " +
                "TemporaryOSDText=\"false\" Mask=\"false\" SourceMask=\"false\">" +
                "<tr2:ProfileCapabilities MaximumNumberOfProfiles=\"${d.profiles().size}\" " +
                "ConfigurationsSupported=\"VideoSource VideoEncoder AudioSource AudioEncoder PTZ\"/>" +
                "<tr2:StreamingCapabilities RTSPStreaming=\"true\" RTPMulticast=\"false\" RTP_RTSP_TCP=\"true\" " +
                "NonAggregateControl=\"false\" AutoStartMulticast=\"false\"/></tr2:Capabilities>"
    }
}
