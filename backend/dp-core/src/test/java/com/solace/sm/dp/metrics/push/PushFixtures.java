package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import java.util.UUID;

/**
 * Plan 011 P11-5.1 test arrangement: File Mesh-shaped events (the measured shape of
 * {@code docs/design/backend/evidence/011-filemesh-publisher-facts.md}: F2 the nine-level topic, F9 the header, F11 the
 * heartbeat body, F12 the file-complete body) and the declarations that read them. Synthetic VALUES in a measured SHAPE: no
 * captured log line, host name or user name is reproduced here.
 */
final class PushFixtures {

    static final String ADAPTER_ID_FIELD = "file-events.source.general.adapter_id";
    static final String COMPONENT = "COMP_SOURCE_FTP@FILEEVENTS";

    private PushFixtures() {
    }

    /** F9: the header, eleven fields in the captured order, {@code channel} omitted as Gson omits a null. */
    static String header(String adapterId, int eventType) {
        return "\"header\":{\"timestamp\":\"2026/02/05 11:23:30.56\",\"adapterType\":\"SOURCE\",\"adapterVersion\":\"1.0\","
                + "\"adapterID\":\"" + adapterId + "\",\"runID\":\"run-1\",\"host\":\"connector-host\",\"user\":\"svc\","
                + "\"ip\":\"127.0.1.1\",\"eventType\":" + eventType + ",\"adapterStartTime\":\"1770270000000\"}";
    }

    /** F11: a heartbeat body carries no number. */
    static byte[] heartbeat(String adapterId) {
        return ("{" + header(adapterId, 6) + ",\"adapterState\":{\"heartBeatTimeStamp\":\"2026/02/05 11:23:30\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** F12: a file-complete body, {@code runTimeMs} the one number a gauge can read. */
    static byte[] fileComplete(String adapterId, String runTimeMsJson) {
        return ("{" + header(adapterId, 12) + ",\"fileStateEvent\":{\"currentPath\":{\"src\":\"/in\",\"dest\":\"/out\"},"
                + "\"file\":{\"src\":\"a.txt\",\"dest\":\"a.txt\"},\"isDir\":false,\"fileId\":\"f-1\",\"size\":123,"
                + "\"totalParts\":1,\"runTimeMs\":" + runTimeMsJson + "}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** F2: nine levels, the adapter id the seventh (index 7), the state the last. */
    static String topic(String adapterId, String state) {
        return "DEFAULT/NONE/Alice/SE/FE/SOURCE/NWFILE/" + adapterId + "/" + state;
    }

    static final String HEARTBEAT_PATTERN = "*/*/*/*/*/*/*/*/heartbeat";
    static final String FCOMPLETE_PATTERN = "*/*/*/*/*/*/*/*/fcomplete";

    /** The source JSON a package declares, as {@code MetricsDeclarationBuilder} stores it ({@code json(source)}). */
    static String source(String pattern, String extract, String valuePointer, String filterJson, String configField) {
        StringBuilder json = new StringBuilder("{\"kind\":\"solace_event\",\"topicPattern\":\"").append(pattern)
                .append("\",\"instanceKey\":{\"configField\":\"").append(configField)
                .append("\",\"pointer\":\"/header/adapterID\"},\"onUnavailable\":\"degrade\"");
        if (extract != null) {
            json.append(",\"extract\":\"").append(extract).append('"');
        }
        if (valuePointer != null) {
            json.append(",\"valuePointer\":\"").append(valuePointer).append('"');
        }
        if (filterJson != null) {
            json.append(",\"eventFilter\":").append(filterJson);
        }
        return json.append('}').toString();
    }

    /** {@code filemesh.heartbeats}: a counter of heartbeat events, exactly as the reviewed fixture declares it. */
    static MetricDefinitionSpec heartbeats() {
        return new MetricDefinitionSpec("filemesh.heartbeats", COMPONENT, "counter", "events", "solace_event",
                source(HEARTBEAT_PATTERN, "count", null, null, ADAPTER_ID_FIELD), null, true);
    }

    /** A numeric extraction in the measured fcomplete shape (SYNTHETIC: the reviewed fixture declares the count only). */
    static MetricDefinitionSpec runtime() {
        return new MetricDefinitionSpec("filemesh.file_run_time_ms", COMPONENT, "gauge", "ms", "solace_event",
                source(FCOMPLETE_PATTERN, "value", "/fileStateEvent/runTimeMs", null, ADAPTER_ID_FIELD), null, true);
    }

    static UUID id() {
        return UUID.randomUUID();
    }
}
