package com.example.hibernatemultithread.demo;

import com.example.hibernatemultithread.entity.BankAccount;
import com.example.hibernatemultithread.service.BankTransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo 3 — Pessimistic Locking + Deadlock Prevention
 *
 * <p>Simulates bidirectional money transfers between two accounts
 * in parallel.  Without canonical lock ordering this would frequently
 * deadlock.  With ascending-ID ordering both threads always lock
 * account 1 first, so one blocks while the other proceeds — no cycle,
 * no deadlock.
 *
 * <h2>What to observe in the logs</h2>
 * <ul>
 *   <li>Thread names alternate as they acquire/release locks.</li>
 *   <li>Sum of both balances after all transfers equals the initial total
 *       (conservation of money — no phantom debits or credits).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Demo3PessimisticLocking {

    private final BankTransferService bankTransferService;

    public void run() throws Exception {
        log.info("═══════════════════════════════════════════════════════");
        log.info("DEMO 3 — Pessimistic Locking + Deadlock Prevention");
        log.info("═══════════════════════════════════════════════════════");

        // Seed two accounts
        BankAccount alice = bankTransferService.createAccount("ACC-001", "Alice", new BigDecimal("10000.00"));
        BankAccount bob   = bankTransferService.createAccount("ACC-002", "Bob",   new BigDecimal("10000.00"));

        BigDecimal initialTotal = new BigDecimal("20000.00");
        log.info("Alice balance: {}, Bob balance: {}, Total: {}",
            bankTransferService.getBalance(alice.getId()),
            bankTransferService.getBalance(bob.getId()),
            initialTotal);

        int transferCount = 20;
        BigDecimal amount = new BigDecimal("100.00");
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(transferCount);

        ExecutorService pool = Executors.newFixedThreadPool(transferCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < transferCount; i++) {
            final boolean aliceToBob = (i % 2 == 0);
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    if (aliceToBob) {
                        bankTransferService.transfer(alice.getId(), bob.getId(), amount);
                    } else {
                        // Reverse direction — the dangerous case without lock ordering
                        bankTransferService.transfer(bob.getId(), alice.getId(), amount);
                    }
                    success.incrementAndGet();
                } catch (Exception e) {
                    log.warn("Transfer failed: {}", e.getMessage());
                    failure.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        log.info("Releasing {} bidirectional transfer threads simultaneously…", transferCount);
        startGate.countDown();
        doneLatch.await();
        pool.shutdown();

        BigDecimal aliceBalance = bankTransferService.getBalance(alice.getId());
        BigDecimal bobBalance   = bankTransferService.getBalance(bob.getId());
        BigDecimal finalTotal   = aliceBalance.add(bobBalance);

        log.info("Results:");
        log.info("  Transfers: {}, Succeeded: {}, Failed: {}", transferCount, success.get(), failure.get());
        log.info("  Alice final balance: {}", aliceBalance);
        log.info("  Bob   final balance: {}", bobBalance);
        log.info("  Total balance (should be {}): {}", initialTotal, finalTotal);

        if (finalTotal.compareTo(initialTotal) == 0) {
            log.info("  ✓ Money conserved — no phantom debits/credits!");
        } else {
            log.warn("  ✗ Balance mismatch — data integrity issue!");
        }

        log.info("Demo 3 complete.\n");
    }
}
