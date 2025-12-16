package com.yachaq.node.kernel.job;

import java.time.Duration;
import java.time.Instant;

/**
 * Result of a job execution.
 */
public record JobResult(
        String jobId,
        JobType jobType,
        boolean success,
        String message,
        Object result,
        Instant startTime,
        Instant endTime
) {
    public JobResult(String jobId, JobType jobType, boolean success, String message, Object result) {
        this(jobId, jobType, success, message, result, null, null);
    }

    public Duration getDuration() {
        if (startTime != null && endTime != null) {
            return Duration.between(startTime, endTime);
        }
        return Duration.ZERO;
    }
}
