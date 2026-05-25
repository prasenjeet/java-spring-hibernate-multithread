package com.example.hibernatemultithread.demo;

import com.example.hibernatemultithread.repository.OrderRepository;
import com.example.hibernatemultithread.service.ParallelBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.example.hibernatemultithread.entity.Order;

/**
 * Demo 4 — Parallel Batch Order Processing
 *
 * <p>Creates 20 PENDING orders, then processes them in parallel batches
 * of 5.  Each batch runs in its own independent transaction.  One order
 * in each batch intentionally fails (orderId % 7 == 0) to show that
 * the failure is contained to that order without rolling back the whole batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Demo4ParallelBatch {

    private final ParallelBatchService parallelBatchService;
    private final OrderRepository      orderRepository;

    public void run() throws Exception {
        log.info("═══════════════════════════════════════════════════════");
        log.info("DEMO 4 — Parallel Batch Processing");
        log.info("═══════════════════════════════════════════════════════");

        int orderCount = 20;
        parallelBatchService.createTestOrders(orderCount);
        log.info("Created {} pending orders", orderCount);

        long before = System.currentTimeMillis();
        String summary = parallelBatchService.processAllPendingOrders();
        long elapsed = System.currentTimeMillis() - before;

        log.info(summary);
        log.info("Wall-clock time: {} ms", elapsed);

        // Show final status distribution
        log.info("Order status breakdown:");
        for (Order.OrderStatus status : Order.OrderStatus.values()) {
            log.info("  {}: {}", status, orderRepository.countByStatus(status));
        }

        log.info("Demo 4 complete.\n");
    }
}
