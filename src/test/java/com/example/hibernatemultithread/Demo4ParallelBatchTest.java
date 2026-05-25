package com.example.hibernatemultithread;

import com.example.hibernatemultithread.entity.Order;
import com.example.hibernatemultithread.repository.OrderRepository;
import com.example.hibernatemultithread.service.ParallelBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Demo 4 — Parallel Batch Processing
 */
@SpringBootTest
class Demo4ParallelBatchTest {

    @Autowired ParallelBatchService parallelBatchService;
    @Autowired OrderRepository      orderRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("All 20 orders are created as PENDING")
    void createTestOrders_allPending() {
        List<Order> orders = parallelBatchService.createTestOrders(20);

        assertThat(orders).hasSize(20);
        assertThat(orders).allMatch(o -> o.getStatus() == Order.OrderStatus.PENDING);
    }

    @Test
    @DisplayName("processAllPendingOrders processes all orders — no order left PENDING")
    void processAllPendingOrders_noPendingLeft() throws Exception {
        parallelBatchService.createTestOrders(20);

        parallelBatchService.processAllPendingOrders();

        long remaining = orderRepository.countByStatus(Order.OrderStatus.PENDING);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("Processed orders are annotated with the thread name")
    void processAllPendingOrders_setsThreadName() throws Exception {
        parallelBatchService.createTestOrders(10);

        parallelBatchService.processAllPendingOrders();

        List<Order> processed = orderRepository.findByStatus(Order.OrderStatus.COMPLETED);
        assertThat(processed).allMatch(o ->
            o.getProcessedByThread() != null && o.getProcessedByThread().startsWith("batch-worker-"));
    }

    @Test
    @DisplayName("processAllPendingOrders handles empty queue gracefully")
    void processAllPendingOrders_empty() throws Exception {
        String result = parallelBatchService.processAllPendingOrders();
        assertThat(result).contains("No orders processed");
    }

    @Test
    @DisplayName("COMPLETED + FAILED count equals total order count")
    void processAllPendingOrders_noOrphanedOrders() throws Exception {
        int total = 15;
        parallelBatchService.createTestOrders(total);

        parallelBatchService.processAllPendingOrders();

        long completed = orderRepository.countByStatus(Order.OrderStatus.COMPLETED);
        long failed    = orderRepository.countByStatus(Order.OrderStatus.FAILED);

        assertThat(completed + failed).isEqualTo(total);
    }
}
