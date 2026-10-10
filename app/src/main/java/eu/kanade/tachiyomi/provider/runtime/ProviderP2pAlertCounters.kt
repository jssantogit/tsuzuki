package eu.kanade.tachiyomi.provider.runtime

import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregate-only libtorrent alert listener.
 *
 * Alert payloads are intentionally ignored because they may contain peer addresses, tracker
 * URLs, paths or other content-derived values. Only an allowlisted alert type counter survives.
 * Listener and snapshot work is best-effort so diagnostics can never disturb libtorrent.
 *
 * The aggregation model is string-based and native-free. AlertType values are resolved lazily
 * only when the real jlibtorrent listener is attached, keeping ordinary JVM tests independent
 * from FrostWire native linkage.
 */
internal class ProviderP2pAlertCounters : AlertListener {

    private val counts = ConcurrentHashMap<String, AtomicLong>()

    override fun types(): IntArray = observedAlertTypes()
        .map(AlertType::swig)
        .toIntArray()

    override fun alert(alert: Alert<*>) {
        runCatching {
            fieldFor(alert.type())?.let(::incrementField)
        }
    }

    fun recordSnapshot(
        operationId: String,
        phase: String,
    ) {
        runCatching {
            ProviderP2pDiagnostics.record(
                event = ProviderP2pDiagnosticEvent.NATIVE_ALERT_SUMMARY,
                operationId = operationId,
                codes = mapOf("phase" to phase),
                numbers = snapshot(),
            )
        }
    }

    internal fun recordFieldForTest(field: String) {
        if (field in SAFE_FIELDS) {
            incrementField(field)
        }
    }

    internal fun snapshotForTest(): Map<String, Long> = snapshot()

    private fun incrementField(field: String) {
        counts.computeIfAbsent(field) { AtomicLong() }.incrementAndGet()
    }

    private fun snapshot(): Map<String, Long> =
        SAFE_FIELDS.mapNotNull { field ->
            counts[field]
                ?.get()
                ?.takeIf { it > 0L }
                ?.let { field to it }
        }.toMap()

    private fun fieldFor(type: AlertType): String? = when (type) {
        AlertType.METADATA_RECEIVED -> "alertMetadataReceived"
        AlertType.METADATA_FAILED -> "alertMetadataFailed"
        AlertType.TRACKER_REPLY -> "alertTrackerReply"
        AlertType.TRACKER_WARNING -> "alertTrackerWarning"
        AlertType.TRACKER_ERROR -> "alertTrackerError"
        AlertType.DHT_BOOTSTRAP -> "alertDhtBootstrap"
        AlertType.DHT_GET_PEERS -> "alertDhtGetPeers"
        AlertType.DHT_GET_PEERS_REPLY -> "alertDhtGetPeersReply"
        AlertType.DHT_ERROR -> "alertDhtError"
        AlertType.LISTEN_SUCCEEDED -> "alertListenSucceeded"
        AlertType.LISTEN_FAILED -> "alertListenFailed"
        AlertType.UDP_ERROR -> "alertUdpError"
        AlertType.PORTMAP -> "alertPortmap"
        AlertType.PORTMAP_ERROR -> "alertPortmapError"
        AlertType.SESSION_ERROR -> "alertSessionError"
        AlertType.TORRENT_ERROR -> "alertTorrentError"
        AlertType.PEER_CONNECT -> "alertPeerConnect"
        AlertType.PEER_DISCONNECTED -> "alertPeerDisconnected"
        AlertType.PEER_ERROR -> "alertPeerError"
        AlertType.FILE_ERROR -> "alertFileError"
        AlertType.HASH_FAILED -> "alertHashFailed"
        AlertType.FILE_COMPLETED -> "alertFileCompleted"
        AlertType.TORRENT_FINISHED -> "alertTorrentFinished"
        AlertType.ALERTS_DROPPED -> "alertAlertsDropped"
        else -> null
    }

    private fun observedAlertTypes(): List<AlertType> = listOf(
        AlertType.METADATA_RECEIVED,
        AlertType.METADATA_FAILED,
        AlertType.TRACKER_REPLY,
        AlertType.TRACKER_WARNING,
        AlertType.TRACKER_ERROR,
        AlertType.DHT_BOOTSTRAP,
        AlertType.DHT_GET_PEERS,
        AlertType.DHT_GET_PEERS_REPLY,
        AlertType.DHT_ERROR,
        AlertType.LISTEN_SUCCEEDED,
        AlertType.LISTEN_FAILED,
        AlertType.UDP_ERROR,
        AlertType.PORTMAP,
        AlertType.PORTMAP_ERROR,
        AlertType.SESSION_ERROR,
        AlertType.TORRENT_ERROR,
        AlertType.PEER_CONNECT,
        AlertType.PEER_DISCONNECTED,
        AlertType.PEER_ERROR,
        AlertType.FILE_ERROR,
        AlertType.HASH_FAILED,
        AlertType.FILE_COMPLETED,
        AlertType.TORRENT_FINISHED,
        AlertType.ALERTS_DROPPED,
    )

    private companion object {
        val SAFE_FIELDS = linkedSetOf(
            "alertMetadataReceived",
            "alertMetadataFailed",
            "alertTrackerReply",
            "alertTrackerWarning",
            "alertTrackerError",
            "alertDhtBootstrap",
            "alertDhtGetPeers",
            "alertDhtGetPeersReply",
            "alertDhtError",
            "alertListenSucceeded",
            "alertListenFailed",
            "alertUdpError",
            "alertPortmap",
            "alertPortmapError",
            "alertSessionError",
            "alertTorrentError",
            "alertPeerConnect",
            "alertPeerDisconnected",
            "alertPeerError",
            "alertFileError",
            "alertHashFailed",
            "alertFileCompleted",
            "alertTorrentFinished",
            "alertAlertsDropped",
        )
    }
}
