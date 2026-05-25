package com.example.hibernatemultithread.service;

import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * PATTERN 5 — Session-per-Thread with Manual EntityManager Lifecycle
 *
 * <h2>When to use this pattern</h2>
 * <p>Spring's {@code @Transactional} is excellent for the common case but
 * sometimes you need finer control — for example when:
 * <ul>
 *   <li>You manage your own thread pool outside Spring's executor abstraction.</li>
 *   <li>You want to batch Hibernate flush/clear cycles to control memory.</li>
 *   <li>You integrate with legacy code that does not use Spring proxies.</li>
 * </ul>
 *
 * <h2>Thread safety contract</h2>
 * <p>A Hibernate {@link org.hibernate.Session} (and its JPA wrapper
 * {@link EntityManager}) is <strong>NOT thread-safe</strong>.
 * Each thread must open its own session, use it, and close it.
 * This demo allocates one {@link EntityManager} per {@link Runnable} task
 * submitted to the thread pool — never shared, always closed in {@code finally}.
 *
 * <h2>Batch flush/clear</h2>
 * <p>When inserting thousands of rows, the default behaviour (auto-flush on
 * commit) keeps all managed entities in memory. We call
 * {@code em.flush(); em.clear();} every {@value #FLUSH_EVERY} rows to release
 * references and let GC collect them, avoiding {@link OutOfMemoryError}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionPerThreadService {

    private static final int FLUSH_EVERY = 50;  // flush & clear every N rows

    private final EntityManagerFactory emf;
    private final ProductRepository    productRepository; // used for reads only

    /**
     * Bulk-inserts {@code totalProducts} products using a manual
     * session-per-thread approach with periodic flush/clear.
     *
     * <p>The work is split across {@code threadCount} threads; each thread
     * manages its own {@link EntityManager} independently.
     *
     * @param totalProducts total number of products to insert
     * @param threadCount   number of parallel threads (each gets its own session)
     */
    public void bulkInsertProducts(int totalProducts, int threadCount)
            throws InterruptedException, ExecutionException {

        int perThread  = totalProducts / threadCount;
        int remainder  = totalProducts % threadCount;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            r -> {
                Thread t = new Thread(r);
                t.setName("session-thread-" + t.getId());
                return t;
            });

        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            int from  = t * perThread + 1;
            int count = (t == threadCount - 1) ? perThread + remainder : perThread;
            int threadIndex = t;

            futures.add(pool.submit(() -> insertBatch(from, count, threadIndex)));
        }

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);

        // Surface any exceptions from worker threads
        for (Future<?> f : futures) {
            f.get();  // rethrows ExecutionException if the worker threw
        }

        log.info("Bulk insert complete — total products now: {}", productRepository.count());
    }

    /**
     * Inserts {@code count} products starting at index {@code from}.
     *
     * <p>Opens its own {@link EntityManager}, flushes/clears every
     * {@value #FLUSH_EVERY} rows, and closes the session in {@code finally}.
     * This method intentionally does NOT carry a Spring {@code @Transactional}
     * annotation — the transaction lifecycle is managed explicitly.
     */
    private void insertBatch(int from, int count, int threadIndex) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] Inserting products {} to {}", thread, from, from + count - 1);

        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();

            for (int i = 0; i < count; i++) {
                int idx = from + i;
                Product product = new Product(
                    "Bulk-Product-T%d-%d".formatted(threadIndex, idx),
                    BigDecimal.valueOf(10.00 + idx * 0.01).setScale(2, java.math.RoundingMode.HALF_UP),
                    100 + idx
                );
                em.persist(product);

                // ── Periodic flush/clear to keep the first-level cache small ──
                if ((i + 1) % FLUSH_EVERY == 0) {
                    em.flush();   // write to DB while the transaction is still open
                    em.clear();   // detach all managed entities → GC can collect them
                    log.debug("[{}] Flush/clear at row {}", thread, i + 1);
                }
            }

            em.getTransaction().commit();
            log.info("[{}] Committed {} products", thread, count);

        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) {
                em.getTransaction().rollback();
            }
            log.error("[{}] Rollback after error: {}", thread, ex.getMessage());
            throw ex;
        } finally {
            em.close();  // CRITICAL — always close the session; connections leak otherwise
        }
    }

    /**
     * Demonstrates a read-only session-per-thread scan of all products.
     *
     * <p>Uses Spring's {@code @Transactional(readOnly = true)} which tells
     * Hibernate to skip dirty checking at flush time — a meaningful optimisation
     * when loading large result sets for reporting.
     */
    @Transactional(readOnly = true)
    public long countAllProducts() {
        long count = productRepository.count();
        log.info("[{}] Total products in DB: {}", Thread.currentThread().getName(), count);
        return count;
    }
}
