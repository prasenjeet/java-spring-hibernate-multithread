package com.example.hibernatemultithread;

import com.example.hibernatemultithread.entity.BankAccount;
import com.example.hibernatemultithread.repository.BankAccountRepository;
import com.example.hibernatemultithread.service.BankTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for Demo 3 — Pessimistic Locking + Deadlock Prevention
 */
@SpringBootTest
class Demo3BankTransferTest {

    @Autowired BankTransferService     bankTransferService;
    @Autowired BankAccountRepository   bankAccountRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        bankAccountRepository.deleteAll();
    }

    @Test
    @DisplayName("Simple transfer moves funds correctly")
    void transfer_simple() {
        BankAccount alice = bankTransferService.createAccount("T001", "Alice", new BigDecimal("1000.00"));
        BankAccount bob   = bankTransferService.createAccount("T002", "Bob",   new BigDecimal("500.00"));

        bankTransferService.transfer(alice.getId(), bob.getId(), new BigDecimal("200.00"));

        assertThat(bankTransferService.getBalance(alice.getId())).isEqualByComparingTo("800.00");
        assertThat(bankTransferService.getBalance(bob.getId())).isEqualByComparingTo("700.00");
    }

    @Test
    @DisplayName("Transfer fails with insufficient funds and balances remain unchanged")
    void transfer_insufficientFunds_rollsBack() {
        BankAccount alice = bankTransferService.createAccount("T003", "Alice", new BigDecimal("100.00"));
        BankAccount bob   = bankTransferService.createAccount("T004", "Bob",   new BigDecimal("500.00"));

        assertThatThrownBy(() ->
            bankTransferService.transfer(alice.getId(), bob.getId(), new BigDecimal("500.00"))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("Insufficient funds");

        // Both balances unchanged after rollback
        assertThat(bankTransferService.getBalance(alice.getId())).isEqualByComparingTo("100.00");
        assertThat(bankTransferService.getBalance(bob.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Concurrent bidirectional transfers conserve total balance (no deadlock)")
    void transfer_concurrent_noDeadlock_balanceConserved() throws Exception {
        BankAccount alice = bankTransferService.createAccount("T005", "Alice", new BigDecimal("10000.00"));
        BankAccount bob   = bankTransferService.createAccount("T006", "Bob",   new BigDecimal("10000.00"));
        BigDecimal initialTotal = new BigDecimal("20000.00");

        int threadCount = 16;
        BigDecimal amount = new BigDecimal("50.00");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final boolean aliceToBob = (i % 2 == 0);
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    if (aliceToBob) {
                        bankTransferService.transfer(alice.getId(), bob.getId(), amount);
                    } else {
                        bankTransferService.transfer(bob.getId(), alice.getId(), amount);
                    }
                    success.incrementAndGet();
                } catch (Exception e) {
                    failure.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startGate.countDown();
        doneLatch.await();
        pool.shutdown();

        BigDecimal finalTotal = bankTransferService.getBalance(alice.getId())
            .add(bankTransferService.getBalance(bob.getId()));

        // Bidirectional transfers of equal amount cancel out, so balances reset
        assertThat(finalTotal)
            .as("Money must be conserved — no phantom debits or credits")
            .isEqualByComparingTo(initialTotal);

        assertThat(failure.get())
            .as("No deadlocks — all transfers should succeed")
            .isEqualTo(0);
    }
}
