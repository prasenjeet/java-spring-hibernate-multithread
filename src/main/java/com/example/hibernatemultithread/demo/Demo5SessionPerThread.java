package com.example.hibernatemultithread.demo;

import com.example.hibernatemultithread.service.SessionPerThreadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Demo 5 — Manual Session-per-Thread with Bulk Inserts
 *
 * <p>Demonstrates direct {@link jakarta.persistence.EntityManager} lifecycle
 * management: each thread opens its own session, inserts rows in batches,
 * flushes/clears periodically to control memory, and closes the session.
 *
 * <p>This pattern is useful for ETL pipelines, data migrations, and any
 * scenario where you need fine control over flush/clear cycles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Demo5SessionPerThread {

    private final SessionPerThreadService sessionPerThreadService;

    public void run() throws Exception {
        log.info("═══════════════════════════════════════════════════════");
        log.info("DEMO 5 — Manual Session-per-Thread (Bulk Insert)");
        log.info("═══════════════════════════════════════════════════════");

        int totalProducts = 200;
        int threadCount   = 4;

        log.info("Bulk-inserting {} products across {} threads…", totalProducts, threadCount);
        log.info("Each thread manages its own EntityManager and flushes every 50 rows.");

        long before = System.currentTimeMillis();
        sessionPerThreadService.bulkInsertProducts(totalProducts, threadCount);
        long elapsed = System.currentTimeMillis() - before;

        long total = sessionPerThreadService.countAllProducts();
        log.info("Bulk insert took {} ms — total products in DB: {}", elapsed, total);
        log.info("Demo 5 complete.\n");
    }
}
