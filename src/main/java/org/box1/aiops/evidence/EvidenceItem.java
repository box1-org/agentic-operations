package org.box1.aiops.evidence;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One cited evidence entry. {@code link} is a trusted identifier built by this service (source plus validated ID),
 * never a URL taken from evidence text. {@code data} is untrusted.
 */
public record EvidenceItem(String ref, String type, String source, Instant timestamp, Instant retrievedAt,
        String retrievalStatus, String detail, String link, boolean truncated, JsonNode data) {

    public static final String OK = "OK";

    public boolean ok() {
        return OK.equals(retrievalStatus);
    }

    public EvidenceItem withRef(String newRef) {
        return new EvidenceItem(newRef, type, source, timestamp, retrievedAt, retrievalStatus, detail, link, truncated,
            data);
    }

    public EvidenceItem withData(JsonNode newData, boolean wasTruncated) {
        return new EvidenceItem(ref, type, source, timestamp, retrievedAt, retrievalStatus, detail, link,
            truncated || wasTruncated, newData);
    }
}
