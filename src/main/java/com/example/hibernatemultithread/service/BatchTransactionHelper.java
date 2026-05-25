package com.example.hibernatemultithread.service;

import com.example.hibernatemultithread.entity.Order;
import com.example.hibernatemultithread.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extracted transaction boundary for a single batch partition.
 *
 * <h2>Why a separate class?</h2>
 * <p>Spring's {@code @Transactional} works via AOP proxies: the proxy wraps
 * the bean and intercepts method calls made from <em>outside</em> the bean.
 * When a method in class A calls another method in the <em>same</em> class A,
 * the call goes directly to {@code this} — bypassing the proxy.  This is the
 * well-known "self-invocation" limitation.
 *
 * <p>The fix is simple: move the transactional method to a <em>different</em>
 * Spring bean (this class).  Now when {@link ParallelBatchService} calls
 * {@link #processBatch}, it goes through the Spring proxy and
 * {@code REQUIRES_NEW} works as intended.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchTransactionHelper {

    private final OrderRepository orderRepository;

    /**
     * Async entry point — runs on a {@code batchExecutor} thread and then
     * calls {@link #processBatch} through Spring's proxy so that
     * {@code REQUIRES_NEW} is properly honoured.
     *
     * <p>Both {@code @Async} and {@code @Transactional(REQUIRES_NEW)} live in
     * this class so callers always invoke them through the Spring proxy.
     */
    @Async("batchExecutor")
    public CompletableFuture<ParallelBatchService.BatchResult> processBatchAsync(List<Long> orderIds) {
        ParallelBatchService.BatchResult result = processBatch(orderIds);
        return CompletableFuture.completedFuture(result);
    }

    /**
     * Processes a single batch in a brand-new, independent transaction.
     *
     * <p>{@link Propagation#REQUIRES_NEW} suspends any caller transaction,
     * opens a new one, and commits (or rolls back) it independently.
     * A failure here never affects sibling batches processed in parallel.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ParallelBatchService.BatchResult processBatch(List<Long> orderIds) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] Processing batch of {} orders: {}", thread, orderIds.size(), orderIds);

        int successCount = 0;
        int failureCount = 0;

        for (Long orderId : orderIds) {
            try {
                Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

                order.setStatus(Order.OrderStatus.PROCESSING);
                order.setProcessedByThread(thread);
                orderRepository.save(order);

                // Simulate processing time (e.g. inventory check, payment gateway)
                Thread.sleep(50);

                // Simulate occasional failure (every 7th order by ID)
                if (orderId % 7 == 0) {
                    throw new RuntimeException("Simulated processing failure for order #" + orderId);
                }

                order.setStatus(Order.OrderStatus.COMPLETED);
                order.setProcessedAt(LocalDateTime.now());
                orderRepository.save(order);
                successCount++;

                log.debug("[{}] Order #{} completed", thread, orderId);

            } catch (RuntimeException e) {
                log.warn("[{}] Order #{} failed: {}", thread, orderId, e.getMessage());
                orderRepository.findById(orderId).ifPresent(o -> {
                    o.setStatus(Order.OrderStatus.FAILED);
                    o.setProcessedAt(LocalDateTime.now());
                    o.setProcessedByThread(thread);
                    orderRepository.save(o);
                });
                failureCount++;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("[{}] Batch done: {} succeeded, {} failed", thread, successCount, failureCount);
        return new ParallelBatchService.BatchResult(orderIds.size(), successCount, failureCount, thread);
    }
}
