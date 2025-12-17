package com.yachaq.node.normalizer;

import com.yachaq.node.normalizer.CanonicalEvent.*;

import java.time.Instant;
import java.util.*;

/**
 * Normalizer for location data from various sources.
 * Requirement 308.2: Create deterministic mapping per source type.
 */
public class LocationDataNormalizer extends AbstractNormalizer {

    public static final String SOURCE_TYPE = "location";

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public List<CanonicalEvent> normalize(Map<String, Object> rawData, String sourceId) {
        if (rawData == null || rawData.isEmpty()) {
            return List.of();
        }

        String recordType = getString(rawData, "type", "point");
        
        return switch (recordType.toLowerCase()) {
            case "point" -> normalizePoint(rawData, sourceId);
            case "visit" -> normalizeVisit(rawData, sourceId);
            case "trip" -> normalizeTrip(rawData, sourceId);
            case "place" -> normalizePlace(rawData, sourceId);
            default -> normalizePoint(rawData, sourceId);
        };
    }

    @Override
    protected void validateRequiredFields(Map<String, Object> rawData, List<String> errors) {
        if (!rawData.containsKey("latitude") && !rawData.containsKey("lat")) {
            errors.add("Missing required field: latitude");
        }
        if (!rawData.containsKey("longitude") && !rawData.containsKey("lon") && !rawData.containsKey("lng")) {
            errors.add("Missing required field: longitude");
        }
    }

    private List<CanonicalEvent> normalizePoint(Map<String, Object> rawData, String sourceId) {
        Double lat = getLatitude(rawData);
        Double lon = getLongitude(rawData);
        
        if (lat == null || lon == null) {
            return List.of();
        }

        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        Double accuracy = getDouble(rawData, "accuracy");
        GeoLocation location = GeoLocation.exact(lat, lon, accuracy);

        Map<String, Object> attributes = new HashMap<>();
        Double altitude = getDouble(rawData, "altitude");
        if (altitude != null) {
            attributes.put("altitude", altitude);
        }
        Double speed = getDouble(rawData, "speed");
        if (speed != null) {
            attributes.put("speedMps", speed);
        }
        Double heading = getDouble(rawData, "heading");
        if (heading != null) {
            attributes.put("heading", heading);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.LOCATION)
                .eventType("location_point")
                .timestamp(timestamp)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeVisit(Map<String, Object> rawData, String sourceId) {
        Double lat = getLatitude(rawData);
        Double lon = getLongitude(rawData);
        
        if (lat == null || lon == null) {
            return List.of();
        }

        Instant arrivalTime = getInstant(rawData, "arrivalTime");
        if (arrivalTime == null) {
            arrivalTime = getInstant(rawData, "startTime");
        }
        if (arrivalTime == null) {
            arrivalTime = getInstant(rawData, "timestamp");
        }
        if (arrivalTime == null) {
            return List.of();
        }

        Instant departureTime = getInstant(rawData, "departureTime");
        if (departureTime == null) {
            departureTime = getInstant(rawData, "endTime");
        }

        GeoLocation location = GeoLocation.exact(lat, lon, getDouble(rawData, "accuracy"));

        Map<String, Object> attributes = new HashMap<>();
        String placeName = getString(rawData, "placeName");
        if (placeName != null) {
            attributes.put("placeName", placeName);
        }
        String placeCategory = getString(rawData, "placeCategory");
        if (placeCategory != null) {
            attributes.put("placeCategory", placeCategory);
        }

        Long durationSecs = null;
        if (departureTime != null) {
            durationSecs = departureTime.getEpochSecond() - arrivalTime.getEpochSecond();
            attributes.put("durationMinutes", durationSecs / 60);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.LOCATION)
                .eventType("visit")
                .timestamp(arrivalTime)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizeTrip(Map<String, Object> rawData, String sourceId) {
        Instant startTime = getInstant(rawData, "startTime");
        if (startTime == null) {
            startTime = getInstant(rawData, "timestamp");
        }
        if (startTime == null) {
            return List.of();
        }

        Map<String, Object> attributes = new HashMap<>();
        
        // Start location
        Map<String, Object> startLoc = getMap(rawData, "startLocation");
        if (startLoc != null) {
            Double startLat = getLatitude(startLoc);
            Double startLon = getLongitude(startLoc);
            if (startLat != null && startLon != null) {
                attributes.put("startLatitude", startLat);
                attributes.put("startLongitude", startLon);
            }
        }
        
        // End location
        Map<String, Object> endLoc = getMap(rawData, "endLocation");
        if (endLoc != null) {
            Double endLat = getLatitude(endLoc);
            Double endLon = getLongitude(endLoc);
            if (endLat != null && endLon != null) {
                attributes.put("endLatitude", endLat);
                attributes.put("endLongitude", endLon);
            }
        }

        Double distance = getDouble(rawData, "distance");
        if (distance != null) {
            attributes.put("distanceMeters", distance);
        }

        String mode = getString(rawData, "mode");
        if (mode != null) {
            attributes.put("transportMode", mode);
        }

        Long durationSecs = getLong(rawData, "duration");
        Instant endTime = getInstant(rawData, "endTime");
        if (durationSecs == null && endTime != null) {
            durationSecs = endTime.getEpochSecond() - startTime.getEpochSecond();
        }

        // Use start location as primary location
        GeoLocation location = null;
        if (startLoc != null) {
            Double lat = getLatitude(startLoc);
            Double lon = getLongitude(startLoc);
            if (lat != null && lon != null) {
                location = GeoLocation.exact(lat, lon, null);
            }
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.TRAVEL)
                .eventType("trip")
                .timestamp(startTime)
                .duration(durationSecs != null ? Duration.ofSeconds(durationSecs) : null)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private List<CanonicalEvent> normalizePlace(Map<String, Object> rawData, String sourceId) {
        Double lat = getLatitude(rawData);
        Double lon = getLongitude(rawData);
        
        if (lat == null || lon == null) {
            return List.of();
        }

        Instant timestamp = getInstant(rawData, "timestamp");
        if (timestamp == null) {
            timestamp = getInstant(rawData, "createdAt");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        GeoLocation location = GeoLocation.exact(lat, lon, null);

        Map<String, Object> attributes = new HashMap<>();
        String name = getString(rawData, "name");
        if (name != null) {
            attributes.put("placeName", name);
        }
        String category = getString(rawData, "category");
        if (category != null) {
            attributes.put("placeCategory", category);
        }
        String address = getString(rawData, "address");
        if (address != null) {
            attributes.put("address", address);
        }

        return List.of(CanonicalEvent.builder()
                .generateId()
                .sourceType(SOURCE_TYPE)
                .sourceId(sourceId)
                .category(EventCategory.LOCATION)
                .eventType("place")
                .timestamp(timestamp)
                .location(location)
                .attributes(attributes)
                .contentHash(computeContentHash(rawData))
                .build());
    }

    private Double getLatitude(Map<String, Object> data) {
        Double lat = getDouble(data, "latitude");
        if (lat == null) {
            lat = getDouble(data, "lat");
        }
        return lat;
    }

    private Double getLongitude(Map<String, Object> data) {
        Double lon = getDouble(data, "longitude");
        if (lon == null) {
            lon = getDouble(data, "lon");
        }
        if (lon == null) {
            lon = getDouble(data, "lng");
        }
        return lon;
    }
}
