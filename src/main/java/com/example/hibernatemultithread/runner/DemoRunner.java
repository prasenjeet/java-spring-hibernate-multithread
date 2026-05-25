package com.example.hibernatemultithread.runner;

import com.example.hibernatemultithread.demo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Entry point that runs all five demos sequentially when the application starts.
 *
 * <p>Each demo is self-contained: it seeds its own data and logs its own
 * results.  Run the application and watch the console output to see all
 * five multithreading patterns in action.
 *
 * <h2>Demo summary</h2>
 * <ol>
 *   <li><b>Async + Transactional</b> — fire-and-forget price updates via
 *       {@code @Async} methods, each with its own {@code @Transactional}
 *       context on a pool thread.</li>
 *   <li><b>Optimistic Locking</b> — 10 threads race to decrement the same
 *       product's stock; {@code @Version} detects conflicts and an
 *       exponential-backoff retry loop ensures no lost updates.</li>
 *   <li><b>Pessimistic Locking + Deadlock Prevention</b> — bidirectional
 *       bank transfers with {@code SELECT … FOR UPDATE} and canonical
 *       ascending-ID lock ordering.</li>
 *   <li><b>Parallel Batch Processing</b> — 20 orders processed in parallel
 *       mini-batches, each batch in an independent {@code REQUIRES_NEW}
 *       transaction.</li>
 *   <li><b>Session-per-Thread</b> — manual {@link jakarta.persistence.EntityManager}
 *       lifecycle with periodic flush/clear for memory-efficient bulk inserts.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoRunner implements CommandLineRunner {

    private final Demo1AsyncService      demo1;
    private final Demo2OptimisticLocking demo2;
    private final Demo3PessimisticLocking demo3;
    private final Demo4ParallelBatch     demo4;
    private final Demo5SessionPerThread  demo5;

    @Override
    public void run(String... args) throws Exception {
        log.info("╔═══════════════════════════════════════════════════════╗");
        log.info("║  Spring + Hibernate Multithreading Samples             ║");
        log.info("║  5 patterns for safe concurrent database access        ║");
        log.info("╚═══════════════════════════════════════════════════════╝");
        log.info("");

        demo1.run();
        demo2.run();
        demo3.run();
        demo4.run();
        demo5.run();

        log.info("╔═══════════════════════════════════════════════════════╗");
        log.info("║  All demos finished successfully!                      ║");
        log.info("╚═══════════════════════════════════════════════════════╝");
    }
}
