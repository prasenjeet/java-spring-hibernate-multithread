package com.example.hibernatemultithread.service;

import com.example.hibernatemultithread.entity.Order;
import com.example.hibernatemultithread.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * PATTERN 4 — Parallel Batch Processing with Independent Transactions
 *
 * <h2>Design goals</h2>
 * <ol>
 *   <li>Process a large list of orders concurrently across a fixed thread pool.</li>
 *   <li>Each mini-batch runs in its own transaction so a failure in one batch
 *       does NOT roll back successfully processed batches.</li>
 *   <li>Report per-batch outcome (success / partial failure) back to the caller
 *       via {@link CompletableFuture}.</li>
 * </ol>
 *
 * <h2>Transaction propagation &amp; the self-invocation pitfall</h2>
 * <p>Spring's {@code @Transactional} works through AOP proxies: the proxy
 * intercepts calls coming from <em>outside</em> the bean.  When a method calls
 * another method on the <em>same</em> object ({@code this.processBatch(…)}),
 * the call bypasses the proxy and the annotation has no effect.
 *
 * <p>The fix used here: the transactional work is moved to a separate Spring
 * bean ({@link BatchTransactionHelper}).  Calls from this class go through
 * the helper's proxy, so {@code REQUIRES_NEW} is properly honoured.
 *
 * <h2>Thread safety</h2>
 * <p>Each {@code @Async} invocation gets a different thread from the
 * {@code batchExecutor} pool.  Spring binds a separate Hibernate session and
 * JDBC connection to each thread — there is no shared mutable state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParallelBatchService {

    private static final int BATCH_SIZE = 5;

    private final OrderRepository      orderRepository;
    private final BatchTransactionHelper batchHelper;   // separate bean → proxy works

    /**
     * Processes all pending orders in parallel mini-batches.
     *
     * <p>Steps:
     * <ol>
     *   <li>Load all PENDING order IDs in a single read-only transaction.</li>
     *   <li>Partition them into chunks of {@value #BATCH_SIZE}.</li>
     *   <li>Submit each chunk to the {@code batchExecutor} pool asynchronously.</li>
     *   <li>Wait for all futures and return a summary.</li>
     * </ol>
     *
     * @return summary string with success/failure counts
     */
    @Transactional(readOnly = true)
    public String processAllPendingOrders() {
        List<Order> pending = orderRepository.findPendingOrders();
        if (pending.isEmpty()) {
            log.info("No pending orders to process.");
            return "No orders processed.";
        }

        log.info("Found {} pending orders — partitioning into batches of {}", pending.size(), BATCH_SIZE);

        // Pass only IDs across thread boundaries — never detached entities.
        List<Long> orderIds = pending.stream().map(Order::getId).toList();
        List<List<Long>> partitions = partition(orderIds, BATCH_SIZE);

        log.info("Submitting {} batches to the batchExecutor pool…", partitions.size());
        // Call through batchHelper so @Async and @Transactional proxies are applied.
        List<CompletableFuture<BatchResult>> futures = partitions.stream()
            .map(batchHelper::processBatchAsync)
            .toList();

        // Wait for all batches to commit
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        int totalOk   = futures.stream().mapToInt(f -> f.join().successCount()).sum();
        int totalFail = futures.stream().mapToInt(f -> f.join().failureCount()).sum();

        String summary = "Processed %d orders: %d succeeded, %d failed."
            .formatted(pending.size(), totalOk, totalFail);
        log.info(summary);
        return summary;
    }

    /** Creates bulk test orders in a single transaction. */
    @Transactional
    public List<Order> createTestOrders(int count) {
        List<Order> orders = new ArrayList<>();
        String[] customers = {"Alice", "Bob", "Carol", "Dave", "Eve"};
        String[] products  = {"Laptop", "Phone", "Tablet", "Monitor", "Keyboard"};

        for (int i = 0; i < count; i++) {
            orders.add(new Order(
                customers[i % customers.length],
                products[i % products.length],
                (i % 5) + 1,
                java.math.BigDecimal.valueOf(99.99 * ((i % 5) + 1))
            ));
        }
        return orderRepository.saveAll(orders);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return partitions;
    }

    /**
     * Immutable result record for a single batch execution.
     *
     * @param total         total orders in the batch
     * @param successCount  successfully completed orders
     * @param failureCount  failed orders
     * @param processedBy   name of the thread that handled the batch
     */
    public record BatchResult(int total, int successCount, int failureCount, String processedBy) {}
}
