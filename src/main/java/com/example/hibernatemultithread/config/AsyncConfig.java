package com.example.hibernatemultithread.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Thread-pool configuration for asynchronous Spring services.
 *
 * <h2>Why configure a custom pool?</h2>
 * Spring's default {@code SimpleAsyncTaskExecutor} creates a brand-new
 * {@link Thread} for <em>every</em> task. Under load this leads to:
 * <ul>
 *   <li>Unbounded thread creation → OutOfMemoryError</li>
 *   <li>No connection-pool back-pressure (each thread grabs a JDBC connection)</li>
 * </ul>
 * A bounded {@link ThreadPoolTaskExecutor} caps both threads and queued work.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * General-purpose async executor used by {@code @Async} methods.
     *
     * <p>Pool sizing rule of thumb for IO-bound (JDBC) work:
     * {@code threads ≈ cores × (1 + wait_time / compute_time)}.
     * Here we use 8 threads and keep the queue modest so callers get
     * back-pressure rather than silent queuing of unbounded work.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-worker-");
        // Caller runs rejected tasks instead of discarding them silently
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated executor for the batch-processing demo.
     *
     * <p>Intentionally sized smaller to make the "partitioned batch" pattern
     * observable: only {@code batchSize} orders process in parallel at a time.
     */
    @Bean(name = "batchExecutor")
    public Executor batchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);           // fixed-size for predictable demo output
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("batch-worker-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Virtual-thread executor (Java 21 preview — Project Loom).
     *
     * <p>Virtual threads are extremely cheap to create, making them ideal for
     * IO-bound JDBC work. Each virtual thread blocks on the JDBC call while
     * the JVM parks it and schedules other virtual threads on the same OS thread.
     * This avoids the "thread-per-request" memory overhead of platform threads.
     *
     * <p><strong>Note:</strong> HikariCP still governs the max number of open
     * connections; virtual threads don't bypass connection-pool limits.
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
