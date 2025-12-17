package com.yachaq.node.normalizer;

import com.yachaq.node.normalizer.CanonicalEvent.*;

import java.time.Instant;
import java.util.*;

/**
 * Normalizer for communication data (messages, calls, emails).
 * Requirement 308.2: Create deterministic mapping per source type.
 */
public class CommunicationDataNormalizer extends AbstractNormalizer {

    public static final String SOURCE_TYPE = "communication";

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public List<CanonicalEvent> normalize(Map<String, Object> rawData, String sourceId) {
        if (rawData == null || rawData.isEmpty()) {
            return List.of();
        }

        String recordType = getString(rawData, "type", "message");
        
        return switch (recordType.toLowerCase()) {
            case "message", "sms", "chat" -> normalizeMessage(rawData, sourceId);
            case "call", "phone_call" -> normalizeCall(rawData, sourceId);
            case "email" -> normalizeEmail(rawData, sourceId);
            default -> normalizeGenericCommunication(rawData, sourceId, recordType);
        };
    }

    @Override
    protected void validateRequiredFields(Map<String, Object> rawData, List<String> errors) {
        if (!rawData.containsKey("timestamp") && !rawData.containsKey("sentAt")) {
            errors.add("Missing required field: timestamp or sentAt");
        }
    }

    private List<CanonicalEvent> normalizeMessage(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "sentAt");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Direction (sent/received)
        String direction = getString(rawData, "direction");
        if (direction == null) {
            Boolean isOutgoing = getBoolean(rawData, "isOutgoing");
            direction = Boolean.TRUE.equals(isOutgoing) ? "sent" : "received";
        }
        attributes.put("direction", direction);
        
        // Platform
        String platform = getString(rawData, "platform");
        if (platform != null) {
            attributes.put("platform", platform);
        }
        
        // Message type (text, image, video, etc.)
        String messageType = getString(rawData, "messageType");
        if (messageType != null) {
            attributes.put("messageType", messageType);
        }
        
        // Word count (privacy-safe metric)
        Long wordCount = getLong(rawData, "wordCount");
        if (wordCount != null) {
            attributes.put("wordCount", wordCount);
        }
        
        // Has attachment
        Boolean hasAttachment = getBoolean(rawData, "hasAttachment");
        if (hasAttachment != null) {
            attributes.put("hasAttachment", hasAttachment);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.COMMUNICATION)
                .eventType("message")
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeCall(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "startTime");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Direction
        String direction = getString(rawData, "direction");
        if (direction == null) {
            Boolean isOutgoing = getBoolean(rawData, "isOutgoing");
            direction = Boolean.TRUE.equals(isOutgoing) ? "outgoing" : "incoming";
        }
        attributes.put("direction", direction);
        
        // Call type (voice, video)
        String callType = getString(rawData, "callType");
        if (callType != null) {
            attributes.put("callType", callType);
        }
        
        // Status (answered, missed, rejected)
        String status = getString(rawData, "status");
        if (status != null) {
            attributes.put("status", status);
        }

        // Duration
        Long durationSecs = getLong(rawData, "duration");
        if (durationSecs != null) {
            attributes.put("durationSeconds", durationSecs);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.COMMUNICATION)
                .eventType("call")
                .timestamp(timestamp)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeEmail(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "sentAt");
        }
        if (timestamp == null) {
            timestamp = getInstant(rawData, "receivedAt");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Direction
        String direction = getString(rawData, "direction");
        if (direction == null) {
            String folder = getString(rawData, "folder");
            direction = "sent".equalsIgnoreCase(folder) ? "sent" : "received";
        }
        attributes.put("direction", direction);
        
        // Has attachments
        Boolean hasAttachments = getBoolean(rawData, "hasAttachments");
        if (hasAttachments != null) {
            attributes.put("hasAttachments", hasAttachments);
        }
        
        // Attachment count
        Long attachmentCount = getLong(rawData, "attachmentCount");
        if (attachmentCount != null) {
            attributes.put("attachmentCount", attachmentCount);
        }
        
        // Is read
        Boolean isRead = getBoolean(rawData, "isRead");
        if (isRead != null) {
            attributes.put("isRead", isRead);
        }
        
        // Labels/folders
        String labels = getString(rawData, "labels");
        if (labels != null) {
            attributes.put("labels", labels);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.COMMUNICATION)
                .eventType("email")
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeGenericCommunication(Map<String, Object> rawData, String sourceId, String recordType) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        Map<String, Object> attributes = new HashMap<>(rawData);
        attributes.remove("type");
        attributes.remove("timestamp");

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.COMMUNICATION)
                .eventType(recordType)
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }
}
