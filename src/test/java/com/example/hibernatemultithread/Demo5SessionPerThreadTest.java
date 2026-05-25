package com.example.hibernatemultithread;

import com.example.hibernatemultithread.repository.ProductRepository;
import com.example.hibernatemultithread.service.SessionPerThreadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Demo 5 — Manual Session-per-Thread Bulk Insert
 */
@SpringBootTest
class Demo5SessionPerThreadTest {

    @Autowired SessionPerThreadService sessionPerThreadService;
    @Autowired ProductRepository       productRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("bulkInsertProducts inserts exactly the requested number of rows")
    void bulkInsert_exactCount() throws Exception {
        int total   = 100;
        int threads = 4;

        sessionPerThreadService.bulkInsertProducts(total, threads);

        assertThat(productRepository.count()).isEqualTo(total);
    }

    @Test
    @DisplayName("Bulk insert with a single thread inserts all rows")
    void bulkInsert_singleThread() throws Exception {
        sessionPerThreadService.bulkInsertProducts(50, 1);

        assertThat(productRepository.count()).isEqualTo(50);
    }

    @Test
    @DisplayName("Bulk insert with 8 threads still inserts the correct total")
    void bulkInsert_manyThreads() throws Exception {
        int total   = 80;
        int threads = 8;

        sessionPerThreadService.bulkInsertProducts(total, threads);

        assertThat(productRepository.count()).isEqualTo(total);
    }

    @Test
    @DisplayName("countAllProducts returns the same count as the repository")
    void countAllProducts_matchesRepository() throws Exception {
        sessionPerThreadService.bulkInsertProducts(30, 3);

        long countViaService    = sessionPerThreadService.countAllProducts();
        long countViaRepository = productRepository.count();

        assertThat(countViaService).isEqualTo(countViaRepository);
    }
}
