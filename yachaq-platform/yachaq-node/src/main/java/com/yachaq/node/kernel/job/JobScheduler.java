package com.yachaq.node.kernel.job;

import com.yachaq.node.kernel.event.EventBus;
import com.yachaq.node.kernel.event.KernelEvent;
import com.yachaq.node.kernel.event.KernelEventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Job scheduler for background tasks with resource constraint enforcement.
 * Requirement 302.2: Job scheduling with constraints.
 */
public class JobScheduler {

    private final EventBus eventBus;
    private final ExecutorService executor;
    private final Map<String, ScheduledJob> activeJobs;
    private final AtomicBoolean running;
    private final Map<JobType, JobExecutor> jobExecutors;

    public JobScheduler(EventBus eventBus) {
        this.eventBus = eventBus;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.activeJobs = new ConcurrentHashMap<>();
        this.running = new AtomicBoolean(false);
        this.jobExecutors = new ConcurrentHashMap<>();
        
        // Register default job executors
        registerDefaultExecutors();
    }

    /**
     * Starts the scheduler.
     */
    public void start() {
        running.set(true);
    }

    /**
     * Stops the scheduler and cancels pending jobs.
     */
    public void stop() {
        running.set(false);
        activeJobs.values().forEach(job -> job.future().cancel(true));
        activeJobs.clear();
        executor.shutdown();
    }

    /**
     * Schedules a job for execution.
     * 
     * @param jobType Type of job
     * @param constraints Resource constraints
     * @return Future with job result
     */
    public CompletableFuture<JobResult> schedule(JobType jobType, JobConstraints constraints) {
        if (!running.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Scheduler not running"));
        }

        String jobId = UUID.randomUUID().toString();
        Instant startTime = Instant.now();
        
        eventBus.emit(new KernelEvent(
                KernelEventType.JOB_SCHEDULED, 
                jobId, 
                startTime,
                "Job scheduled: " + jobType));

        CompletableFuture<JobResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                eventBus.emit(new KernelEvent(
                        KernelEventType.JOB_STARTED,
                        jobId,
                        Instant.now(),
                        "Job started: " + jobType));

                // Get executor for this job type
                JobExecutor jobExecutor = jobExecutors.get(jobType);
                Object result = null;
                
                if (jobExecutor != null) {
                    result = jobExecutor.execute(jobId, constraints);
                }

                Instant endTime = Instant.now();
                JobResult jobResult = new JobResult(
                        jobId, jobType, true, "Job completed successfully", result, startTime, endTime);

                eventBus.emit(new KernelEvent(
                        KernelEventType.JOB_COMPLETED,
                        jobId,
                        endTime,
                        "Job completed: " + jobType));

                return jobResult;

            } catch (Exception e) {
                Instant endTime = Instant.now();
                eventBus.emit(new KernelEvent(
                        KernelEventType.JOB_FAILED,
                        jobId,
                        endTime,
                        "Job failed: " + e.getMessage()));

                return new JobResult(jobId, jobType, false, e.getMessage(), null, startTime, endTime);
            } finally {
                activeJobs.remove(jobId);
            }
        }, executor);

        // Apply timeout
        CompletableFuture<JobResult> timedFuture = future.orTimeout(
                constraints.maxExecutionTimeSeconds(), TimeUnit.SECONDS);

        activeJobs.put(jobId, new ScheduledJob(jobId, jobType, constraints, timedFuture, startTime));
        
        return timedFuture;
    }

    /**
     * Registers a job executor for a specific job type.
     */
    public void registerExecutor(JobType jobType, JobExecutor executor) {
        jobExecutors.put(jobType, executor);
    }

    /**
     * Gets the count of active jobs.
     */
    public int getActiveJobCount() {
        return activeJobs.size();
    }

    /**
     * Checks if the scheduler is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    private void registerDefaultExecutors() {
        // Default no-op executors for all job types
        for (JobType type : JobType.values()) {
            jobExecutors.put(type, (jobId, constraints) -> {
                // Default implementation does nothing
                return null;
            });
        }
    }

    /**
     * Functional interface for job execution.
     */
    @FunctionalInterface
    public interface JobExecutor {
        Object execute(String jobId, JobConstraints constraints) throws Exception;
    }

    private record ScheduledJob(
            String jobId,
            JobType jobType,
            JobConstraints constraints,
            CompletableFuture<JobResult> future,
            Instant scheduledAt
    ) {}
}
