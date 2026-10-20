package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.onvif.Soap.esc
import java.util.concurrent.ConcurrentHashMap

data class Preset(val token: String, val name: String, val pan: Float, val tilt: Float, val zoom: Float)

/** Persists presets as text; implemented by the app (SharedPreferences) or in memory. */
interface PresetStore {
    fun load(): String?
    fun save(data: String)

    class Memory : PresetStore {
        private var data: String? = null
        override fun load() = data
        override fun save(data: String) { this.data = data }
    }
}

class PtzService(private val store: PresetStore) {
    private val presets = ConcurrentHashMap<String, Preset>()
    @Volatile private var home = Preset("home", "Home", 0f, 0f, 0f)

    init {
        store.load()?.lines()?.forEach { line ->
            val p = line.split('\t')
            if (p.size == 5) {
                val preset = Preset(p[0], p[1], p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                if (preset.token == HOME_TOKEN) home = preset else presets[preset.token] = preset
            }
        }
    }

    fun handle(c: Ctx): String? {
        val ptz = c.device.ptz ?: return null
        val op = c.op
        return when (c.soap.opName) {
            "GetServiceCapabilities" -> "<tptz:GetServiceCapabilitiesResponse>${capabilities()}</tptz:GetServiceCapabilitiesResponse>"
            "GetNodes" -> "<tptz:GetNodesResponse>${node("tptz:PTZNode")}</tptz:GetNodesResponse>"
            "GetNode" -> "<tptz:GetNodeResponse>${node("tptz:PTZNode")}</tptz:GetNodeResponse>"
            "GetConfigurations" -> "<tptz:GetConfigurationsResponse>${configuration("tptz:PTZConfiguration")}</tptz:GetConfigurationsResponse>"
            "GetConfiguration" -> "<tptz:GetConfigurationResponse>${configuration("tptz:PTZConfiguration")}</tptz:GetConfigurationResponse>"
            "GetCompatibleConfigurations" ->
                "<tptz:GetCompatibleConfigurationsResponse>${configuration("tptz:PTZConfiguration")}</tptz:GetCompatibleConfigurationsResponse>"
            "GetConfigurationOptions" -> "<tptz:GetConfigurationOptionsResponse><tptz:PTZConfigurationOptions>" +
                "<tt:Spaces>${spaces()}</tt:Spaces><tt:PTZTimeout><tt:Min>PT1S</tt:Min><tt:Max>PT60S</tt:Max></tt:PTZTimeout>" +
                "</tptz:PTZConfigurationOptions></tptz:GetConfigurationOptionsResponse>"
            "SetConfiguration" -> "<tptz:SetConfigurationResponse/>"
            "ContinuousMove" -> {
                val v = op.child("Velocity")
                val pt = v?.child("PanTilt")
                val timeout = Time.duration(op.childText("Timeout"))?.toMillis() ?: DEFAULT_TIMEOUT_MS
                ptz.continuousMove(pt?.attrFloat("x") ?: 0f, pt?.attrFloat("y") ?: 0f, v?.child("Zoom")?.attrFloat("x") ?: 0f, timeout)
                "<tptz:ContinuousMoveResponse/>"
            }
            "AbsoluteMove" -> {
                val pos = op.child("Position")
                val pt = pos?.child("PanTilt")
                ptz.absoluteMove(pt?.attrFloat("x"), pt?.attrFloat("y"), pos?.child("Zoom")?.attrFloat("x"))
                "<tptz:AbsoluteMoveResponse/>"
            }
            "RelativeMove" -> {
                val t = op.child("Translation")
                val pt = t?.child("PanTilt")
                val x = pt?.attrFloat("x") ?: 0f
                val y = pt?.attrFloat("y") ?: 0f
                val z = t?.child("Zoom")?.attrFloat("x") ?: 0f
                if (pt?.attr("space")?.contains("Fov", ignoreCase = true) == true) {
                    ptz.relativeMoveFov(x, y)
                    if (z != 0f) ptz.relativeMove(0f, 0f, z)
                } else {
                    ptz.relativeMove(x, y, z)
                }
                "<tptz:RelativeMoveResponse/>"
            }
            "Stop" -> {
                ptz.stop(op.childText("PanTilt") != "false", op.childText("Zoom") != "false")
                "<tptz:StopResponse/>"
            }
            "GetStatus" -> {
                val p = ptz.position()
                "<tptz:GetStatusResponse><tptz:PTZStatus>${position(p.pan, p.tilt, p.zoom)}" +
                    "<tt:MoveStatus><tt:PanTilt>${if (p.panTiltMoving) "MOVING" else "IDLE"}</tt:PanTilt>" +
                    "<tt:Zoom>${if (p.zoomMoving) "MOVING" else "IDLE"}</tt:Zoom></tt:MoveStatus>" +
                    "<tt:UtcTime>${Time.iso()}</tt:UtcTime></tptz:PTZStatus></tptz:GetStatusResponse>"
            }
            "GetPresets" -> "<tptz:GetPresetsResponse>" +
                presets.values.sortedBy { it.token.toIntOrNull() ?: Int.MAX_VALUE }.joinToString("") { p ->
                    "<tptz:Preset token=\"${esc(p.token)}\"><tt:Name>${esc(p.name)}</tt:Name>" +
                        "<tt:PTZPosition>${positionBody(p.pan, p.tilt, p.zoom)}</tt:PTZPosition></tptz:Preset>"
                } + "</tptz:GetPresetsResponse>"
            "SetPreset" -> {
                val pos = ptz.position()
                val token = op.childText("PresetToken")?.takeIf { it.isNotEmpty() }
                    ?: (1..MAX_PRESETS).map { it.toString() }.firstOrNull { !presets.containsKey(it) }
                    ?: throw SoapFault("Receiver", "ter:TooManyPresets", "Maximum number of presets reached")
                val name = op.childText("PresetName")?.takeIf { it.isNotEmpty() } ?: presets[token]?.name ?: "Preset $token"
                presets[token] = Preset(token, name, pos.pan, pos.tilt, pos.zoom)
                persist()
                "<tptz:SetPresetResponse><tptz:PresetToken>${esc(token)}</tptz:PresetToken></tptz:SetPresetResponse>"
            }
            "RemovePreset" -> {
                presets.remove(op.childText("PresetToken")) ?: throw SoapFault("Sender", "ter:NoToken", "Preset does not exist")
                persist()
                "<tptz:RemovePresetResponse/>"
            }
            "GotoPreset" -> {
                val p = presets[op.childText("PresetToken")] ?: throw SoapFault("Sender", "ter:NoToken", "Preset does not exist")
                ptz.absoluteMove(p.pan, p.tilt, p.zoom)
                "<tptz:GotoPresetResponse/>"
            }
            "GotoHomePosition" -> {
                val h = home
                ptz.absoluteMove(h.pan, h.tilt, h.zoom)
                "<tptz:GotoHomePositionResponse/>"
            }
            "SetHomePosition" -> {
                val p = ptz.position()
                home = Preset(HOME_TOKEN, "Home", p.pan, p.tilt, p.zoom)
                persist()
                "<tptz:SetHomePositionResponse/>"
            }
            else -> null
        }
    }

    private fun persist() {
        val all = presets.values + home
        store.save(all.joinToString("\n") { "${it.token}\t${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.pan}\t${it.tilt}\t${it.zoom}" })
    }

    companion object {
        const val NODE = "node0"
        const val CONFIG = "ptz0"
        private const val HOME_TOKEN = "__home"
        private const val MAX_PRESETS = 32
        private const val DEFAULT_TIMEOUT_MS = 10_000L

        const val ABS_PT = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace"
        const val ABS_Z = "http://www.onvif.org/ver10/tptz/ZoomSpaces/PositionGenericSpace"
        const val REL_PT = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationGenericSpace"
        const val REL_PT_FOV = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationSpaceFov"
        const val REL_Z = "http://www.onvif.org/ver10/tptz/ZoomSpaces/TranslationGenericSpace"
        const val CONT_PT = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace"
        const val CONT_Z = "http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace"
        const val SPEED_PT = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/GenericSpeedSpace"
        const val SPEED_Z = "http://www.onvif.org/ver10/tptz/ZoomSpaces/ZoomGenericSpeedSpace"

        fun capabilities() =
            "<tptz:Capabilities EFlip=\"false\" Reverse=\"false\" GetCompatibleConfigurations=\"true\" " +
                "MoveStatus=\"true\" StatusPosition=\"true\"/>"

        private fun range(min: Float, max: Float) = "<tt:Min>$min</tt:Min><tt:Max>$max</tt:Max>"
        private fun space2(tag: String, uri: String, min: Float, max: Float) =
            "<tt:$tag><tt:URI>$uri</tt:URI><tt:XRange>${range(min, max)}</tt:XRange><tt:YRange>${range(min, max)}</tt:YRange></tt:$tag>"
        private fun space1(tag: String, uri: String, min: Float, max: Float) =
            "<tt:$tag><tt:URI>$uri</tt:URI><tt:XRange>${range(min, max)}</tt:XRange></tt:$tag>"

        fun spaces() =
            space2("AbsolutePanTiltPositionSpace", ABS_PT, -1f, 1f) +
                space1("AbsoluteZoomPositionSpace", ABS_Z, 0f, 1f) +
                space2("RelativePanTiltTranslationSpace", REL_PT, -2f, 2f) +
                space2("RelativePanTiltTranslationSpace", REL_PT_FOV, -1f, 1f) +
                space1("RelativeZoomTranslationSpace", REL_Z, -1f, 1f) +
                space2("ContinuousPanTiltVelocitySpace", CONT_PT, -1f, 1f) +
                space1("ContinuousZoomVelocitySpace", CONT_Z, -1f, 1f) +
                space1("PanTiltSpeedSpace", SPEED_PT, 0f, 1f) +
                space1("ZoomSpeedSpace", SPEED_Z, 0f, 1f)

        fun node(tag: String) =
            "<$tag token=\"$NODE\" FixedHomePosition=\"false\" GeoMove=\"false\"><tt:Name>DigitalPTZ</tt:Name>" +
                "<tt:SupportedPTZSpaces>${spaces()}</tt:SupportedPTZSpaces>" +
                "<tt:MaximumNumberOfPresets>$MAX_PRESETS</tt:MaximumNumberOfPresets><tt:HomeSupported>true</tt:HomeSupported></$tag>"

        fun configuration(tag: String) =
            "<$tag token=\"$CONFIG\" MoveRamp=\"0\" PresetRamp=\"0\" PresetTourRamp=\"0\"><tt:Name>PTZConfig</tt:Name><tt:UseCount>2</tt:UseCount>" +
                "<tt:NodeToken>$NODE</tt:NodeToken>" +
                "<tt:DefaultAbsolutePantTiltPositionSpace>$ABS_PT</tt:DefaultAbsolutePantTiltPositionSpace>" +
                "<tt:DefaultAbsoluteZoomPositionSpace>$ABS_Z</tt:DefaultAbsoluteZoomPositionSpace>" +
                "<tt:DefaultRelativePanTiltTranslationSpace>$REL_PT</tt:DefaultRelativePanTiltTranslationSpace>" +
                "<tt:DefaultRelativeZoomTranslationSpace>$REL_Z</tt:DefaultRelativeZoomTranslationSpace>" +
                "<tt:DefaultContinuousPanTiltVelocitySpace>$CONT_PT</tt:DefaultContinuousPanTiltVelocitySpace>" +
                "<tt:DefaultContinuousZoomVelocitySpace>$CONT_Z</tt:DefaultContinuousZoomVelocitySpace>" +
                "<tt:DefaultPTZSpeed><tt:PanTilt x=\"1\" y=\"1\" space=\"$SPEED_PT\"/><tt:Zoom x=\"1\" space=\"$SPEED_Z\"/></tt:DefaultPTZSpeed>" +
                "<tt:DefaultPTZTimeout>PT10S</tt:DefaultPTZTimeout>" +
                "<tt:PanTiltLimits><tt:Range><tt:URI>$ABS_PT</tt:URI><tt:XRange>${range(-1f, 1f)}</tt:XRange>" +
                "<tt:YRange>${range(-1f, 1f)}</tt:YRange></tt:Range></tt:PanTiltLimits>" +
                "<tt:ZoomLimits><tt:Range><tt:URI>$ABS_Z</tt:URI><tt:XRange>${range(0f, 1f)}</tt:XRange></tt:Range></tt:ZoomLimits>" +
                "</$tag>"

        private fun positionBody(pan: Float, tilt: Float, zoom: Float) =
            "<tt:PanTilt x=\"$pan\" y=\"$tilt\" space=\"$ABS_PT\"/><tt:Zoom x=\"$zoom\" space=\"$ABS_Z\"/>"

        fun position(pan: Float, tilt: Float, zoom: Float) = "<tt:Position>${positionBody(pan, tilt, zoom)}</tt:Position>"
    }
}
