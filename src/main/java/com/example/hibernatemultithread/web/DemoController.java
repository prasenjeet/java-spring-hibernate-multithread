package com.example.hibernatemultithread.web;

import com.example.hibernatemultithread.entity.BankAccount;
import com.example.hibernatemultithread.entity.Order;
import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.BankAccountRepository;
import com.example.hibernatemultithread.repository.OrderRepository;
import com.example.hibernatemultithread.repository.ProductRepository;
import com.example.hibernatemultithread.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST API that exposes each of the five multithreading demos as an
 * HTTP POST endpoint so they can be driven from the web UI.
 *
 * <p>Each endpoint:
 * <ol>
 *   <li>Cleans up any data left by a previous run of the same demo.</li>
 *   <li>Seeds the minimal data the demo needs.</li>
 *   <li>Runs the concurrent logic.</li>
 *   <li>Returns a structured result record that the UI can render.</li>
 * </ol>
 *
 * <p>All heavy lifting is delegated to the existing service layer —
 * this controller only handles orchestration and result packaging.
 */
@Slf4j
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    // ── Services ──────────────────────────────────────────────────────────────
    private final AsyncProductService      asyncProductService;
    private final OptimisticLockingService optimisticLockingService;
    private final BankTransferService      bankTransferService;
    private final ParallelBatchService     parallelBatchService;
    private final SessionPerThreadService  sessionPerThreadService;

    // ── Repositories (for setup / teardown) ───────────────────────────────────
    private final ProductRepository     productRepository;
    private final BankAccountRepository bankAccountRepository;
    private final OrderRepository       orderRepository;

    // ══════════════════════════════════════════════════════════════════════════
    // DEMO 1 — @Async + @Transactional
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Seeds 5 products, fires 5 concurrent 10 % price increases via {@code @Async}
     * methods (each with its own transaction), waits for all to commit, then
     * returns the before/after prices.
     */
    @PostMapping("/1")
    public Demo1Result runDemo1() throws Exception {
        log.info("API — Demo 1 start");
        productRepository.deleteAllInBatch();

        List<Product> seeded = productRepository.saveAll(List.of(
            new Product("Widget A",  new BigDecimal("10.00"),  100),
            new Product("Widget B",  new BigDecimal("20.00"),  200),
            new Product("Widget C",  new BigDecimal("30.00"),  150),
            new Product("Gadget X",  new BigDecimal("99.99"),   50),
            new Product("Gadget Y",  new BigDecimal("149.99"),  30)
        ));

        // Snapshot initial prices before any async update touches them
        Map<Long, BigDecimal> initialPrices = new LinkedHashMap<>();
        for (Product p : seeded) {
            initialPrices.put(p.getId(), p.getPrice());
        }

        long start = System.currentTimeMillis();

        // Launch all 5 updates concurrently — each on a pool thread with its own TX
        List<CompletableFuture<Product>> futures = new ArrayList<>();
        for (Product p : seeded) {
            futures.add(asyncProductService.applyPriceIncreaseAsync(p.getId(), 10.0));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        long elapsedMs = System.currentTimeMillis() - start;

        List<ProductUpdate> updates = futures.stream()
            .map(CompletableFuture::join)
            .map(p -> new ProductUpdate(
                p.getName(),
                initialPrices.get(p.getId()),
                p.getPrice()))
            .sorted(Comparator.comparing(ProductUpdate::name))
            .toList();

        log.info("API — Demo 1 done in {}ms", elapsedMs);
        return new Demo1Result(updates, elapsedMs);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DEMO 2 — Optimistic Locking with Retry
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Seeds one high-demand product (stock = 1000), then releases 10 threads
     * simultaneously to each decrement stock by 5. {@code @Version} detects
     * conflicts; an exponential-backoff retry loop ensures no lost updates.
     */
    @PostMapping("/2")
    public Demo2Result runDemo2() throws InterruptedException {
        log.info("API — Demo 2 start");
        productRepository.deleteAllInBatch();

        Product product = productRepository.save(
            new Product("High-Demand Item", new BigDecimal("49.99"), 1000));
        int initialStock  = product.getStock();
        int threadCount   = 10;
        int decrPerThread = 5;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger  succeeded = new AtomicInteger();
        AtomicInteger  failed    = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    optimisticLockingService.decrementStock(product.getId(), decrPerThread);
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();   // release all threads simultaneously
        doneLatch.await();
        pool.shutdown();

        Product after        = productRepository.findById(product.getId()).orElseThrow();
        int     expectedStock = initialStock - (succeeded.get() * decrPerThread);

        log.info("API — Demo 2 done: succeeded={}, failed={}, stock={}→{}",
            succeeded.get(), failed.get(), initialStock, after.getStock());

        return new Demo2Result(
            threadCount, decrPerThread,
            succeeded.get(), failed.get(),
            initialStock, expectedStock, after.getStock(),
            after.getVersion(),
            after.getStock() == expectedStock
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DEMO 3 — Pessimistic Locking + Deadlock Prevention
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates Alice and Bob (10 000 each), then fires 20 bidirectional transfer
     * threads simultaneously. Canonical ascending-ID lock ordering prevents
     * deadlocks; all transfers succeed and the total is conserved.
     */
    @PostMapping("/3")
    public Demo3Result runDemo3() throws InterruptedException {
        log.info("API — Demo 3 start");
        bankAccountRepository.deleteAllInBatch();

        BankAccount alice = bankTransferService.createAccount("ACC-A", "Alice", new BigDecimal("10000.00"));
        BankAccount bob   = bankTransferService.createAccount("ACC-B", "Bob",   new BigDecimal("10000.00"));

        int         transferCount = 20;
        BigDecimal  amount        = new BigDecimal("100.00");
        AtomicInteger succeeded   = new AtomicInteger();
        AtomicInteger failed      = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(transferCount);
        ExecutorService pool     = Executors.newFixedThreadPool(transferCount);

        for (int i = 0; i < transferCount; i++) {
            final boolean aliceToBob = (i % 2 == 0);
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (aliceToBob) {
                        bankTransferService.transfer(alice.getId(), bob.getId(), amount);
                    } else {
                        bankTransferService.transfer(bob.getId(), alice.getId(), amount);
                    }
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await();
        pool.shutdown();

        BigDecimal aliceFinal    = bankTransferService.getBalance(alice.getId());
        BigDecimal bobFinal      = bankTransferService.getBalance(bob.getId());
        BigDecimal initialTotal  = new BigDecimal("20000.00");
        BigDecimal finalTotal    = aliceFinal.add(bobFinal);

        log.info("API — Demo 3 done: total={}, conserved={}", finalTotal,
            finalTotal.compareTo(initialTotal) == 0);

        return new Demo3Result(
            transferCount, succeeded.get(), failed.get(),
            new BigDecimal("10000.00"), new BigDecimal("10000.00"),
            aliceFinal, bobFinal,
            initialTotal, finalTotal,
            finalTotal.compareTo(initialTotal) == 0
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DEMO 4 — Parallel Batch Processing
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Creates 20 PENDING orders, then processes them in 4 parallel batches of 5.
     * Each batch runs in its own {@code REQUIRES_NEW} transaction — one failure
     * per batch is intentional and does not roll back the rest.
     */
    @PostMapping("/4")
    public Demo4Result runDemo4() throws Exception {
        log.info("API — Demo 4 start");
        orderRepository.deleteAllInBatch();

        int orderCount = 20;
        parallelBatchService.createTestOrders(orderCount);

        long   start   = System.currentTimeMillis();
        parallelBatchService.processAllPendingOrders();
        long   elapsed = System.currentTimeMillis() - start;

        Map<String, Long> breakdown = new LinkedHashMap<>();
        for (Order.OrderStatus s : Order.OrderStatus.values()) {
            breakdown.put(s.name(), orderRepository.countByStatus(s));
        }

        long succeeded = orderRepository.countByStatus(Order.OrderStatus.COMPLETED);
        long failed    = orderRepository.countByStatus(Order.OrderStatus.FAILED);

        log.info("API — Demo 4 done in {}ms: completed={}, failed={}", elapsed, succeeded, failed);
        return new Demo4Result(orderCount, (int) succeeded, (int) failed, elapsed, breakdown);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DEMO 5 — Session-per-Thread (Bulk Insert)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Bulk-inserts 200 products across 4 threads, each managing its own
     * {@link jakarta.persistence.EntityManager}. Periodic flush/clear
     * keeps the first-level cache small regardless of row count.
     */
    @PostMapping("/5")
    public Demo5Result runDemo5() throws Exception {
        log.info("API — Demo 5 start");
        productRepository.deleteAllInBatch();

        int totalProducts = 200;
        int threadCount   = 4;

        long start = System.currentTimeMillis();
        sessionPerThreadService.bulkInsertProducts(totalProducts, threadCount);
        long elapsed = System.currentTimeMillis() - start;

        long totalInDb = sessionPerThreadService.countAllProducts();

        log.info("API — Demo 5 done in {}ms: totalInDb={}", elapsed, totalInDb);
        return new Demo5Result(totalProducts, threadCount, elapsed, totalInDb);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Result types  (serialised to JSON by Jackson)
    // ══════════════════════════════════════════════════════════════════════════

    public record ProductUpdate(String name, BigDecimal oldPrice, BigDecimal newPrice) {}

    public record Demo1Result(
        List<ProductUpdate> updates,
        long elapsedMs
    ) {}

    public record Demo2Result(
        int     threadCount,
        int     decrementPerThread,
        int     succeeded,
        int     failed,
        int     initialStock,
        int     expectedStock,
        int     finalStock,
        Long    version,
        boolean noLostUpdates
    ) {}

    public record Demo3Result(
        int        transferCount,
        int        succeeded,
        int        failed,
        BigDecimal aliceInitial,
        BigDecimal bobInitial,
        BigDecimal aliceFinal,
        BigDecimal bobFinal,
        BigDecimal expectedTotal,
        BigDecimal finalTotal,
        boolean    moneyConserved
    ) {}

    public record Demo4Result(
        int               totalOrders,
        int               succeeded,
        int               failed,
        long              elapsedMs,
        Map<String, Long> statusBreakdown
    ) {}

    public record Demo5Result(
        int  productsRequested,
        int  threadsUsed,
        long elapsedMs,
        long totalProductsInDb
    ) {}
}
