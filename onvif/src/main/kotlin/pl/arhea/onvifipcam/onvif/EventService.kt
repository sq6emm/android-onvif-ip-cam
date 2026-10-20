package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.onvif.Soap.esc
import pl.arhea.onvifipcam.rtsp.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One ONVIF event message (a property change). */
class Event(
    val topic: String,
    val source: List<Pair<String, String>>,
    val data: List<Pair<String, String>>,
    val time: Instant = Instant.now(),
    val operation: String = "Changed",
) {
    fun withOperation(op: String) = Event(topic, source, data, Instant.now(), op)

    fun xml(): String =
        "<wsnt:NotificationMessage><wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">$topic</wsnt:Topic>" +
            "<wsnt:Message><tt:Message UtcTime=\"${Time.iso(time)}\" PropertyOperation=\"$operation\">" +
            "<tt:Source>${items(source)}</tt:Source><tt:Data>${items(data)}</tt:Data></tt:Message></wsnt:Message>" +
            "</wsnt:NotificationMessage>"

    private fun items(l: List<Pair<String, String>>) =
        l.joinToString("") { (k, v) -> "<tt:SimpleItem Name=\"${esc(k)}\" Value=\"${esc(v)}\"/>" }
}

/**
 * Holds property state, pull-point subscriptions (queues drained by PullMessages) and push
 * subscriptions (Notify POSTed to the consumer).
 */
class EventHub(private val logger: Logger) {
    private class PullPoint(val id: String, @Volatile var termination: Instant) {
        val queue = ArrayDeque<Event>()
        val lock = Object()
    }

    private class Push(val id: String, val consumer: String, @Volatile var termination: Instant)

    private val random = SecureRandom()
    private val pulls = ConcurrentHashMap<String, PullPoint>()
    private val pushes = ConcurrentHashMap<String, Push>()
    /** Last value of every property topic+source, replayed as "Initialized" to new subscribers. */
    private val properties = ConcurrentHashMap<String, Event>()
    private val pushExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "onvif-notify").apply { isDaemon = true } }

    fun publish(e: Event) {
        properties[e.topic + e.source.joinToString { it.second }] = e
        expire()
        for (p in pulls.values) offer(p, e)
        if (pushes.isNotEmpty()) pushExecutor.execute { for (p in pushes.values) notify(p, listOf(e)) }
    }

    fun createPullPoint(termination: Instant): String {
        val id = newId()
        val p = PullPoint(id, termination)
        properties.values.forEach { offer(p, it.withOperation("Initialized")) }
        pulls[id] = p
        logger.log("ONVIF pull-point subscription $id created")
        return id
    }

    fun createPush(consumer: String, termination: Instant): String {
        val id = newId()
        val p = Push(id, consumer, termination)
        pushes[id] = p
        logger.log("ONVIF push subscription $id -> $consumer")
        val initial = properties.values.map { it.withOperation("Initialized") }
        if (initial.isNotEmpty()) pushExecutor.execute { notify(p, initial) }
        return id
    }

    fun exists(id: String) = pulls.containsKey(id) || pushes.containsKey(id)

    fun termination(id: String): Instant? = pulls[id]?.termination ?: pushes[id]?.termination

    fun renew(id: String, termination: Instant): Boolean {
        pulls[id]?.let { it.termination = termination; return true }
        pushes[id]?.let { it.termination = termination; return true }
        return false
    }

    fun unsubscribe(id: String): Boolean {
        val p = pulls.remove(id)
        p?.let { synchronized(it.lock) { it.lock.notifyAll() } }
        return p != null || pushes.remove(id) != null
    }

    /** Re-sends current property values (SetSynchronizationPoint). */
    fun synchronize(id: String?) {
        val targets = if (id != null) listOfNotNull(pulls[id]) else pulls.values.toList()
        for (p in targets) properties.values.forEach { offer(p, it.withOperation("Initialized")) }
    }

    /** Waits up to [timeout] for messages; returns null if the subscription doesn't exist. */
    fun pull(id: String, timeout: Duration, limit: Int): List<Event>? {
        val p = pulls[id] ?: return null
        // Pulling counts as activity: clients like HA renew regularly, but keep the subscription alive anyway.
        val deadline = System.currentTimeMillis() + timeout.toMillis().coerceIn(0, 60_000)
        synchronized(p.lock) {
            while (p.queue.isEmpty() && pulls.containsKey(id)) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                p.lock.wait(left)
            }
            val out = ArrayList<Event>()
            while (out.size < limit && p.queue.isNotEmpty()) out.add(p.queue.removeFirst())
            return out
        }
    }

    fun stop() {
        pulls.values.forEach { synchronized(it.lock) { it.lock.notifyAll() } }
        pulls.clear()
        pushes.clear()
        pushExecutor.shutdownNow()
    }

    private fun offer(p: PullPoint, e: Event) {
        synchronized(p.lock) {
            if (p.queue.size >= MAX_QUEUE) p.queue.removeFirst()
            p.queue.addLast(e)
            p.lock.notifyAll()
        }
    }

    private fun expire() {
        val now = Instant.now()
        pulls.values.filter { it.termination.isBefore(now) }.forEach {
            logger.log("ONVIF subscription ${it.id} expired")
            unsubscribe(it.id)
        }
        pushes.values.filter { it.termination.isBefore(now) }.forEach { pushes.remove(it.id) }
    }

    private fun notify(p: Push, events: List<Event>) {
        val body = Soap.envelope(
            Ns.SOAP12,
            "<wsnt:Notify>${events.joinToString("") { it.xml() }}</wsnt:Notify>",
            "<wsa:Action>http://docs.oasis-open.org/wsn/bw-2/NotificationConsumer/Notify</wsa:Action>" +
                "<wsa:To>${esc(p.consumer)}</wsa:To>",
        ).toByteArray()
        try {
            val conn = URL(p.consumer).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            if (code !in 200..299) logger.log("ONVIF Notify to ${p.consumer} returned $code")
        } catch (e: Exception) {
            logger.log("ONVIF Notify to ${p.consumer} failed: $e")
        }
    }

    private fun newId(): String = (1..4).joinToString("") { "%04x".format(random.nextInt(0x10000)) }

    companion object {
        private const val MAX_QUEUE = 200
    }
}

class EventService(private val hub: EventHub) {

    fun handle(c: Ctx, subscriptionId: String?): String? {
        val op = c.op
        return when (c.soap.opName) {
            "GetServiceCapabilities" -> "<tev:GetServiceCapabilitiesResponse>${capabilities()}</tev:GetServiceCapabilitiesResponse>"
            "GetEventProperties" -> eventProperties()
            "CreatePullPointSubscription" -> {
                val term = Time.termination(op.childText("InitialTerminationTime"), DEFAULT_TERMINATION)
                val id = hub.createPullPoint(term)
                "<tev:CreatePullPointSubscriptionResponse><tev:SubscriptionReference>" +
                    "<wsa:Address>${esc(subscriptionUrl(c, id))}</wsa:Address></tev:SubscriptionReference>" +
                    "<wsnt:CurrentTime>${Time.iso()}</wsnt:CurrentTime><wsnt:TerminationTime>${Time.iso(term)}</wsnt:TerminationTime>" +
                    "</tev:CreatePullPointSubscriptionResponse>"
            }
            "Subscribe" -> {
                val consumer = op.path("ConsumerReference", "Address")?.text()
                    ?: throw Soap.invalidArg("ter:InvalidArgVal", "missing ConsumerReference")
                val term = Time.termination(op.childText("InitialTerminationTime"), DEFAULT_TERMINATION)
                val id = hub.createPush(consumer, term)
                "<wsnt:SubscribeResponse><wsnt:SubscriptionReference><wsa:Address>${esc(subscriptionUrl(c, id))}</wsa:Address>" +
                    "</wsnt:SubscriptionReference><wsnt:CurrentTime>${Time.iso()}</wsnt:CurrentTime>" +
                    "<wsnt:TerminationTime>${Time.iso(term)}</wsnt:TerminationTime></wsnt:SubscribeResponse>"
            }
            "PullMessages" -> {
                val id = requireSub(subscriptionId)
                val timeout = Time.duration(op.childText("Timeout")) ?: Duration.ofSeconds(10)
                val limit = op.childText("MessageLimit")?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
                val events = hub.pull(id, timeout, limit) ?: throw unknownSubscription()
                "<tev:PullMessagesResponse><tev:CurrentTime>${Time.iso()}</tev:CurrentTime>" +
                    "<tev:TerminationTime>${Time.iso(hub.termination(id) ?: Instant.now())}</tev:TerminationTime>" +
                    events.joinToString("") { it.xml() } + "</tev:PullMessagesResponse>"
            }
            "Renew" -> {
                val id = requireSub(subscriptionId)
                val term = Time.termination(op.childText("TerminationTime"), DEFAULT_TERMINATION)
                if (!hub.renew(id, term)) throw unknownSubscription()
                "<wsnt:RenewResponse><wsnt:TerminationTime>${Time.iso(term)}</wsnt:TerminationTime>" +
                    "<wsnt:CurrentTime>${Time.iso()}</wsnt:CurrentTime></wsnt:RenewResponse>"
            }
            "Unsubscribe" -> {
                val id = requireSub(subscriptionId)
                if (!hub.unsubscribe(id)) throw unknownSubscription()
                "<wsnt:UnsubscribeResponse/>"
            }
            "SetSynchronizationPoint" -> {
                hub.synchronize(subscriptionId)
                "<tev:SetSynchronizationPointResponse/>"
            }
            else -> null
        }
    }

    private fun requireSub(id: String?): String = id?.takeIf { hub.exists(it) } ?: throw unknownSubscription()

    private fun unknownSubscription() =
        SoapFault("Sender", "ter:InvalidArgVal", "Unknown subscription")

    private fun subscriptionUrl(c: Ctx, id: String) = "${c.base}/onvif/${Paths.EVENTS}/sub/$id"

    private fun eventProperties(): String {
        val dialects = "<wsnt:TopicExpressionDialect>http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet</wsnt:TopicExpressionDialect>" +
            "<wsnt:TopicExpressionDialect>http://docs.oasis-open.org/wsn/t-1/TopicExpression/Concrete</wsnt:TopicExpressionDialect>"
        return "<tev:GetEventPropertiesResponse>" +
            "<tev:TopicNamespaceLocation>http://www.onvif.org/onvif/ver10/topics/topicns.xml</tev:TopicNamespaceLocation>" +
            "<wsnt:FixedTopicSet>true</wsnt:FixedTopicSet>" +
            "<wstop:TopicSet>" +
            "<tns1:VideoSource wstop:topic=\"false\"><MotionAlarm wstop:topic=\"true\"><tt:MessageDescription IsProperty=\"true\">" +
            "<tt:Source><tt:SimpleItemDescription Name=\"Source\" Type=\"tt:ReferenceToken\"/></tt:Source>" +
            "<tt:Data><tt:SimpleItemDescription Name=\"State\" Type=\"xsd:boolean\"/></tt:Data>" +
            "</tt:MessageDescription></MotionAlarm></tns1:VideoSource>" +
            "<tns1:RuleEngine wstop:topic=\"false\"><CellMotionDetector wstop:topic=\"false\"><Motion wstop:topic=\"true\">" +
            "<tt:MessageDescription IsProperty=\"true\"><tt:Source>" +
            "<tt:SimpleItemDescription Name=\"VideoSourceConfigurationToken\" Type=\"tt:ReferenceToken\"/>" +
            "<tt:SimpleItemDescription Name=\"VideoAnalyticsConfigurationToken\" Type=\"tt:ReferenceToken\"/>" +
            "<tt:SimpleItemDescription Name=\"Rule\" Type=\"xsd:string\"/></tt:Source>" +
            "<tt:Data><tt:SimpleItemDescription Name=\"IsMotion\" Type=\"xsd:boolean\"/></tt:Data>" +
            "</tt:MessageDescription></Motion></CellMotionDetector></tns1:RuleEngine>" +
            "</wstop:TopicSet>$dialects" +
            "<tev:MessageContentFilterDialect>http://www.onvif.org/ver10/tev/messageContentFilter/ItemFilter</tev:MessageContentFilterDialect>" +
            "<tev:MessageContentSchemaLocation>http://www.onvif.org/onvif/ver10/schema/onvif.xsd</tev:MessageContentSchemaLocation>" +
            "</tev:GetEventPropertiesResponse>"
    }

    companion object {
        private val DEFAULT_TERMINATION = Duration.ofMinutes(10)

        fun capabilities() =
            "<tev:Capabilities WSSubscriptionPolicySupport=\"false\" WSPullPointSupport=\"true\" " +
                "WSPausableSubscriptionManagerInterfaceSupport=\"false\" MaxNotificationProducers=\"10\" MaxPullPoints=\"10\" " +
                "PersistentNotificationStorage=\"false\"/>"

        /** Both motion topics Home Assistant and NVRs understand. */
        fun motionEvents(active: Boolean): List<Event> = listOf(
            Event(
                "tns1:RuleEngine/CellMotionDetector/Motion",
                listOf(
                    "VideoSourceConfigurationToken" to MediaXml.VSC,
                    "VideoAnalyticsConfigurationToken" to "vac0",
                    "Rule" to "MotionDetectorRule",
                ),
                listOf("IsMotion" to active.toString()),
            ),
            Event("tns1:VideoSource/MotionAlarm", listOf("Source" to MediaXml.VIDEO_SOURCE), listOf("State" to active.toString())),
        )
    }
}
