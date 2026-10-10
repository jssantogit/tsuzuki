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
 */
internal class ProviderP2pAlertCounters : AlertListener {

    private val counts = ConcurrentHashMap<AlertType, AtomicLong>()

    override fun types(): IntArray = OBSERVED_SWIG_TYPES.copyOf()

    override fun alert(alert: Alert<*>) {
        runCatching { increment(alert.type()) }
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

    internal fun recordTypeForTest(type: AlertType) {
        increment(type)
    }

    internal fun snapshotForTest(): Map<String, Long> = snapshot()

    private fun increment(type: AlertType) {
        if (type !in FIELD_BY_TYPE) return
        counts.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()
    }

    private fun snapshot(): Map<String, Long> =
        FIELD_BY_TYPE.mapNotNull { (type, field) ->
            counts[type]
                ?.get()
                ?.takeIf { it > 0L }
                ?.let { field to it }
        }.toMap()

    private companion object {
        val FIELD_BY_TYPE = linkedMapOf(
            AlertType.METADATA_RECEIVED to "alertMetadataReceived",
            AlertType.METADATA_FAILED to "alertMetadataFailed",
            AlertType.TRACKER_REPLY to "alertTrackerReply",
            AlertType.TRACKER_WARNING to "alertTrackerWarning",
            AlertType.TRACKER_ERROR to "alertTrackerError",
            AlertType.DHT_BOOTSTRAP to "alertDhtBootstrap",
            AlertType.DHT_GET_PEERS to "alertDhtGetPeers",
            AlertType.DHT_GET_PEERS_REPLY to "alertDhtGetPeersReply",
            AlertType.DHT_ERROR to "alertDhtError",
            AlertType.LISTEN_SUCCEEDED to "alertListenSucceeded",
            AlertType.LISTEN_FAILED to "alertListenFailed",
            AlertType.UDP_ERROR to "alertUdpError",
            AlertType.PORTMAP to "alertPortmap",
            AlertType.PORTMAP_ERROR to "alertPortmapError",
            AlertType.SESSION_ERROR to "alertSessionError",
            AlertType.TORRENT_ERROR to "alertTorrentError",
            AlertType.PEER_CONNECT to "alertPeerConnect",
            AlertType.PEER_DISCONNECTED to "alertPeerDisconnected",
            AlertType.PEER_ERROR to "alertPeerError",
            AlertType.FILE_ERROR to "alertFileError",
            AlertType.HASH_FAILED to "alertHashFailed",
            AlertType.FILE_COMPLETED to "alertFileCompleted",
            AlertType.TORRENT_FINISHED to "alertTorrentFinished",
            AlertType.ALERTS_DROPPED to "alertAlertsDropped",
        )
        val OBSERVED_SWIG_TYPES = FIELD_BY_TYPE.keys.map(AlertType::swig).toIntArray()
    }
}
