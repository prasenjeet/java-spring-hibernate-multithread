package com.example.hibernatemultithread.demo;

import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.ProductRepository;
import com.example.hibernatemultithread.service.OptimisticLockingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo 2 — Optimistic Locking with Retry
 *
 * <p>Simulates 10 threads concurrently trying to decrement stock on the same
 * product.  Without optimistic locking some updates would be silently lost
 * (the "lost update" anomaly).  With {@code @Version} Hibernate detects
 * conflicts and our retry loop ensures every decrement is eventually applied.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Demo2OptimisticLocking {

    private final OptimisticLockingService optimisticLockingService;
    private final ProductRepository        productRepository;

    @Transactional
    public Product seedProduct() {
        Product p = new Product("High-Demand Item", new BigDecimal("49.99"), 1000);
        return productRepository.save(p);
    }

    public void run() throws Exception {
        log.info("═══════════════════════════════════════════════════════");
        log.info("DEMO 2 — Optimistic Locking with Retry");
        log.info("═══════════════════════════════════════════════════════");

        Product product = seedProduct();
        int initialStock = product.getStock();
        log.info("Seeded '{}' with {} units in stock", product.getName(), initialStock);

        int threadCount  = 10;
        int decrPerThread = 5;   // each thread decrements by 5 units

        CountDownLatch startGate = new CountDownLatch(1);   // all threads start simultaneously
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger  success   = new AtomicInteger();
        AtomicInteger  failures  = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();   // wait until all threads are ready
                    optimisticLockingService.decrementStock(product.getId(), decrPerThread);
                    success.incrementAndGet();
                } catch (Exception e) {
                    log.error("Thread {} failed: {}", Thread.currentThread().getName(), e.getMessage());
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        log.info("Releasing {} threads simultaneously…", threadCount);
        startGate.countDown();  // release all threads at once to maximise contention
        doneLatch.await();
        pool.shutdown();

        int expectedStock = initialStock - (success.get() * decrPerThread);
        Product finalProduct = productRepository.findById(product.getId()).orElseThrow();

        log.info("Results:");
        log.info("  Threads: {}, each decrement: {}", threadCount, decrPerThread);
        log.info("  Succeeded: {}, Failed: {}", success.get(), failures.get());
        log.info("  Initial stock:  {}", initialStock);
        log.info("  Expected stock: {}", expectedStock);
        log.info("  Actual stock:   {}", finalProduct.getStock());
        log.info("  Version (# updates): {}", finalProduct.getVersion());

        if (finalProduct.getStock() == expectedStock) {
            log.info("  ✓ No lost updates — optimistic locking worked correctly!");
        } else {
            log.warn("  ✗ Stock mismatch — possible lost update!");
        }

        log.info("Demo 2 complete.\n");
    }
}
