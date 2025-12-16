package com.yachaq.node.kernel.event;

import java.time.Instant;
import java.util.Map;

/**
 * Event emitted by the kernel for inter-module communication.
 */
public record KernelEvent(
        KernelEventType eventType,
        String nodeId,
        Instant timestamp,
        String message,
        Map<String, Object> metadata
) {
    public KernelEvent(KernelEventType eventType, String nodeId, Instant timestamp) {
        this(eventType, nodeId, timestamp, null, Map.of());
    }

    public KernelEvent(KernelEventType eventType, String nodeId, Instant timestamp, String message) {
        this(eventType, nodeId, timestamp, message, Map.of());
    }
}
