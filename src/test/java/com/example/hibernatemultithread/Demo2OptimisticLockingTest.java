package com.example.hibernatemultithread;

import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.ProductRepository;
import com.example.hibernatemultithread.service.OptimisticLockingService;
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
 * Tests for Demo 2 — Optimistic Locking with Retry
 */
@SpringBootTest
class Demo2OptimisticLockingTest {

    @Autowired OptimisticLockingService optimisticLockingService;
    @Autowired ProductRepository        productRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("Single decrementStock reduces stock by the exact amount")
    void decrementStock_singleThread() {
        Product product = productRepository.save(new Product("Test Item", new BigDecimal("9.99"), 100));

        Product updated = optimisticLockingService.decrementStock(product.getId(), 10);

        assertThat(updated.getStock()).isEqualTo(90);
        assertThat(updated.getVersion()).isGreaterThan(product.getVersion());
    }

    @Test
    @DisplayName("decrementStock throws IllegalStateException when stock is insufficient")
    void decrementStock_insufficientStock_throws() {
        Product product = productRepository.save(new Product("Scarce Item", new BigDecimal("9.99"), 5));

        assertThatThrownBy(() -> optimisticLockingService.decrementStock(product.getId(), 10))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Insufficient stock");
    }

    @Test
    @DisplayName("Concurrent decrements from multiple threads produce no lost updates")
    void decrementStock_concurrent_noLostUpdates() throws Exception {
        int initialStock = 500;
        int threadCount  = 10;
        int decrPerThread = 5;

        Product product = productRepository.save(
            new Product("Concurrent Item", new BigDecimal("19.99"), initialStock));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    startGate.await();
                    optimisticLockingService.decrementStock(product.getId(), decrPerThread);
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

        Product finalProduct = productRepository.findById(product.getId()).orElseThrow();
        int expectedStock = initialStock - (success.get() * decrPerThread);

        assertThat(failure.get()).as("No thread should fail with enough retries").isEqualTo(0);
        assertThat(finalProduct.getStock())
            .as("No lost updates — stock must match exactly")
            .isEqualTo(expectedStock);
    }

    @Test
    @DisplayName("incrementStock increases stock by the correct amount")
    void incrementStock_works() {
        Product product = productRepository.save(new Product("Restocked Item", new BigDecimal("9.99"), 10));

        Product updated = optimisticLockingService.incrementStock(product.getId(), 50);

        assertThat(updated.getStock()).isEqualTo(60);
    }
}
