package pl.arhea.onvifipcam.onvif

/**
 * Motion detection as an ONVIF analytics rule (tt:CellMotionDetector, rule "MotionDetectorRule").
 * Creating/deleting the rule switches detection on/off; the "Sensitivity" parameter (0..100) maps to the app setting.
 */
class AnalyticsService {
    fun handle(c: Ctx): String? {
        val d = c.device
        val op = c.op
        return when (c.soap.opName) {
            "GetServiceCapabilities" -> "<tan:GetServiceCapabilitiesResponse>${capabilities()}</tan:GetServiceCapabilitiesResponse>"
            "GetSupportedRules" -> "<tan:GetSupportedRulesResponse><tan:SupportedRules>" +
                "<tt:RuleContentSchemaLocation>http://www.onvif.org/ver10/schema/onvif.xsd</tt:RuleContentSchemaLocation>" +
                "<tt:RuleDescription Name=\"$RULE_TYPE\"><tt:Parameters>" +
                "<tt:SimpleItemDescription Name=\"$SENSITIVITY\" Type=\"xsd:integer\"/></tt:Parameters>" +
                "<tt:Messages IsProperty=\"true\"><tt:Source>" +
                "<tt:SimpleItemDescription Name=\"VideoSourceConfigurationToken\" Type=\"tt:ReferenceToken\"/>" +
                "<tt:SimpleItemDescription Name=\"VideoAnalyticsConfigurationToken\" Type=\"tt:ReferenceToken\"/>" +
                "<tt:SimpleItemDescription Name=\"Rule\" Type=\"xsd:string\"/></tt:Source>" +
                "<tt:Data><tt:SimpleItemDescription Name=\"IsMotion\" Type=\"xsd:boolean\"/></tt:Data>" +
                "<tt:ParentTopic>tns1:RuleEngine/CellMotionDetector/Motion</tt:ParentTopic></tt:Messages>" +
                "</tt:RuleDescription></tan:SupportedRules></tan:GetSupportedRulesResponse>"
            "GetRules" -> "<tan:GetRulesResponse>${rules(d, "tan:Rule")}</tan:GetRulesResponse>"
            "GetRuleOptions" -> "<tan:GetRuleOptionsResponse><tan:RuleOptions RuleType=\"$RULE_TYPE\" Name=\"$SENSITIVITY\" " +
                "Type=\"tt:IntRange\"><tt:Min>0</tt:Min><tt:Max>100</tt:Max></tan:RuleOptions></tan:GetRuleOptionsResponse>"
            "CreateRules", "ModifyRules" -> {
                val rule = op.children("Rule").firstOrNull { it.attr("Name") == RULE_NAME || it.attr("Type")?.endsWith("CellMotionDetector") == true }
                    ?: throw Soap.invalidArg("ter:InvalidRule", "Only $RULE_TYPE rule \"$RULE_NAME\" is supported")
                val sens = rule.child("Parameters")?.children("SimpleItem")
                    ?.firstOrNull { it.attr("Name") == SENSITIVITY }?.attr("Value")?.toIntOrNull()
                if (sens != null && sens !in 0..100) throw Soap.invalidArg("ter:InvalidRule", "Sensitivity must be 0..100")
                d.configuration.change(ConfigChange(motionEnabled = true, motionSensitivity = sens))
                "<tan:${c.soap.opName}Response/>"
            }
            "DeleteRules" -> {
                if (op.children("RuleName").any { it.text() == RULE_NAME }) d.configuration.change(ConfigChange(motionEnabled = false))
                "<tan:DeleteRulesResponse/>"
            }
            "GetSupportedAnalyticsModules" -> "<tan:GetSupportedAnalyticsModulesResponse><tan:SupportedAnalyticsModules/>" +
                "</tan:GetSupportedAnalyticsModulesResponse>"
            "GetAnalyticsModules" -> "<tan:GetAnalyticsModulesResponse/>"
            else -> null
        }
    }

    companion object {
        const val CONFIG = "vac0"
        const val RULE_NAME = "MotionDetectorRule"
        private const val RULE_TYPE = "tt:CellMotionDetector"
        private const val SENSITIVITY = "Sensitivity"

        fun capabilities() =
            "<tan:Capabilities RuleSupport=\"true\" AnalyticsModuleSupport=\"false\" CellBasedSceneDescriptionSupported=\"false\" " +
                "RuleOptionsSupported=\"true\" AnalyticsModuleOptionsSupported=\"false\"/>"

        fun rules(d: OnvifDevice, tag: String): String {
            val cfg = d.configuration.config()
            if (!cfg.motionEnabled) return ""
            return "<$tag Name=\"$RULE_NAME\" Type=\"$RULE_TYPE\"><tt:Parameters>" +
                "<tt:SimpleItem Name=\"$SENSITIVITY\" Value=\"${cfg.motionSensitivity}\"/></tt:Parameters></$tag>"
        }
    }
}
