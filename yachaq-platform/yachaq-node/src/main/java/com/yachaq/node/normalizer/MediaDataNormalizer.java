package com.yachaq.node.normalizer;

import com.yachaq.node.normalizer.CanonicalEvent.*;

import java.time.Instant;
import java.util.*;

/**
 * Normalizer for media data (photos, videos, music).
 * Requirement 308.2: Create deterministic mapping per source type.
 */
public class MediaDataNormalizer extends AbstractNormalizer {

    public static final String SOURCE_TYPE = "media";

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public List<CanonicalEvent> normalize(Map<String, Object> rawData, String sourceId) {
        if (rawData == null || rawData.isEmpty()) {
            return List.of();
        }

        String recordType = getString(rawData, "type", "unknown");
        
        return switch (recordType.toLowerCase()) {
            case "photo", "image" -> normalizePhoto(rawData, sourceId);
            case "video" -> normalizeVideo(rawData, sourceId);
            case "music", "audio", "track" -> normalizeMusic(rawData, sourceId);
            case "stream", "playback" -> normalizePlayback(rawData, sourceId);
            default -> normalizeGenericMedia(rawData, sourceId, recordType);
        };
    }

    @Override
    protected void validateRequiredFields(Map<String, Object> rawData, List<String> errors) {
        if (!rawData.containsKey("type")) {
            errors.add("Missing required field: type");
        }
    }

    private List<CanonicalEvent> normalizePhoto(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "takenAt");
        }
        if (timestamp == null) {
            timestamp = getInstant(rawData, "createdAt");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Dimensions
        Long width = getLong(rawData, "width");
        Long height = getLong(rawData, "height");
        if (width != null) attributes.put("width", width);
        if (height != null) attributes.put("height", height);
        
        // File size
        Long fileSize = getLong(rawData, "fileSize");
        if (fileSize != null) {
            attributes.put("fileSizeBytes", fileSize);
        }
        
        // Camera info
        String camera = getString(rawData, "camera");
        if (camera != null) {
            attributes.put("camera", camera);
        }
        
        // Has faces detected
        Boolean hasFaces = getBoolean(rawData, "hasFaces");
        if (hasFaces != null) {
            attributes.put("hasFaces", hasFaces);
        }
        
        // Scene/content type
        String scene = getString(rawData, "scene");
        if (scene != null) {
            attributes.put("scene", scene);
        }

        // Location
        GeoLocation location = extractLocation(rawData);

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.MEDIA)
                .eventType("photo")
                .timestamp(timestamp)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeVideo(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "recordedAt");
        }
        if (timestamp == null) {
            timestamp = getInstant(rawData, "createdAt");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Dimensions
        Long width = getLong(rawData, "width");
        Long height = getLong(rawData, "height");
        if (width != null) attributes.put("width", width);
        if (height != null) attributes.put("height", height);
        
        // Duration
        Long durationSecs = getLong(rawData, "duration");
        if (durationSecs != null) {
            attributes.put("durationSeconds", durationSecs);
        }
        
        // File size
        Long fileSize = getLong(rawData, "fileSize");
        if (fileSize != null) {
            attributes.put("fileSizeBytes", fileSize);
        }
        
        // Has audio
        Boolean hasAudio = getBoolean(rawData, "hasAudio");
        if (hasAudio != null) {
            attributes.put("hasAudio", hasAudio);
        }

        // Location
        GeoLocation location = extractLocation(rawData);

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.MEDIA)
                .eventType("video")
                .timestamp(timestamp)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeMusic(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "playedAt");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Track info (privacy-safe: genre, not specific track)
        String genre = getString(rawData, "genre");
        if (genre != null) {
            attributes.put("genre", genre);
        }
        
        // Duration
        Long durationSecs = getLong(rawData, "duration");
        if (durationSecs != null) {
            attributes.put("durationSeconds", durationSecs);
        }
        
        // Play count
        Long playCount = getLong(rawData, "playCount");
        if (playCount != null) {
            attributes.put("playCount", playCount);
        }
        
        // Skipped
        Boolean skipped = getBoolean(rawData, "skipped");
        if (skipped != null) {
            attributes.put("skipped", skipped);
        }
        
        // Mood/energy (derived features)
        String mood = getString(rawData, "mood");
        if (mood != null) {
            attributes.put("mood", mood);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.MEDIA)
                .eventType("music")
                .timestamp(timestamp)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizePlayback(Map<String, Object> rawData, String sourceId) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "startedAt");
        }
        if (timestamp == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Content type (movie, show, podcast)
        String contentType = getString(rawData, "contentType");
        if (contentType != null) {
            attributes.put("contentType", contentType);
        }
        
        // Genre
        String genre = getString(rawData, "genre");
        if (genre != null) {
            attributes.put("genre", genre);
        }
        
        // Duration watched
        Long durationSecs = getLong(rawData, "duration");
        if (durationSecs != null) {
            attributes.put("durationSeconds", durationSecs);
        }
        
        // Completion percentage
        Double completion = getDouble(rawData, "completionPercent");
        if (completion != null) {
            attributes.put("completionPercent", completion);
        }
        
        // Platform
        String platform = getString(rawData, "platform");
        if (platform != null) {
            attributes.put("platform", platform);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.CONTENT)
                .eventType("playback")
                .timestamp(timestamp)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeGenericMedia(Map<String, Object> rawData, String sourceId, String recordType) {
        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "createdAt");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        Map<String, Object> attributes = new HashMap<>(rawData);
        attributes.remove("type");
        attributes.remove("timestamp");
        attributes.remove("createdAt");

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.MEDIA)
                .eventType(recordType)
                .timestamp(timestamp)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private GeoLocation extractLocation(Map<String, Object> rawData) {
        Double lat = getDouble(rawData, "latitude");
        Double lon = getDouble(rawData, "longitude");
        
        if (lat == null || lon == null) {
            Map<String, Object> locationMap = getMap(rawData, "location");
            if (locationMap != null) {
                lat = getDouble(locationMap, "latitude");
                lon = getDouble(locationMap, "longitude");
            }
        }
        
        if (lat != null && lon != null) {
            return GeoLocation.exact(lat, lon, null);
        }
        
        return null;
    }
}
