package com.example.hibernatemultithread;

import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.ProductRepository;
import com.example.hibernatemultithread.service.AsyncProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Demo 1 — {@code @Async} + {@code @Transactional}
 */
@SpringBootTest
class Demo1AsyncServiceTest {

    @Autowired AsyncProductService asyncProductService;
    @Autowired ProductRepository   productRepository;

    @BeforeEach
    @Transactional
    void setUp() {
        productRepository.deleteAll();
    }

    @Test
    @DisplayName("Async price increase commits correctly for a single product")
    void asyncPriceIncrease_singleProduct() throws Exception {
        // Given
        Product saved = productRepository.save(new Product("Test Widget", new BigDecimal("100.00"), 50));
        BigDecimal expectedPrice = new BigDecimal("110.00");

        // When
        CompletableFuture<Product> future =
            asyncProductService.applyPriceIncreaseAsync(saved.getId(), 10.0);
        Product updated = future.get();   // blocks until the async tx commits

        // Then
        assertThat(updated.getPrice()).isEqualByComparingTo(expectedPrice);
        // Verify via fresh DB read (the async tx should have committed)
        Product fromDb = productRepository.findById(saved.getId()).orElseThrow();
        assertThat(fromDb.getPrice()).isEqualByComparingTo(expectedPrice);
    }

    @Test
    @DisplayName("Multiple concurrent async price updates all commit without losing any")
    void asyncPriceIncrease_concurrent_noLostUpdates() throws Exception {
        // Given — 5 different products so there's no contention between updates
        List<Product> products = productRepository.saveAll(List.of(
            new Product("P1", new BigDecimal("10.00"), 10),
            new Product("P2", new BigDecimal("20.00"), 20),
            new Product("P3", new BigDecimal("30.00"), 30),
            new Product("P4", new BigDecimal("40.00"), 40),
            new Product("P5", new BigDecimal("50.00"), 50)
        ));

        // When
        List<CompletableFuture<Product>> futures = new ArrayList<>();
        for (Product p : products) {
            futures.add(asyncProductService.applyPriceIncreaseAsync(p.getId(), 10.0));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get();

        // Then — every product price should have increased by 10%
        for (Product original : products) {
            Product updated = productRepository.findById(original.getId()).orElseThrow();
            BigDecimal expected = original.getPrice()
                .multiply(new BigDecimal("1.10"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
            assertThat(updated.getPrice())
                .as("Price for %s", original.getName())
                .isEqualByComparingTo(expected);
        }
    }

    @Test
    @DisplayName("calculateDynamicPrice returns 115% of the base price")
    void calculateDynamicPrice_returns115Percent() throws Exception {
        Product saved = productRepository.save(new Product("Dynamic Widget", new BigDecimal("100.00"), 10));

        BigDecimal dynamicPrice = asyncProductService.calculateDynamicPrice(saved.getId()).get();

        assertThat(dynamicPrice).isEqualByComparingTo(new BigDecimal("115.00"));
    }

    @Test
    @DisplayName("createProductAsync persists the product and makes it findable")
    void createProductAsync_persistsProduct() throws Exception {
        Product created = asyncProductService
            .createProductAsync("Async Widget", new BigDecimal("75.00"), 25)
            .get();

        assertThat(created.getId()).isNotNull();
        assertThat(productRepository.findById(created.getId())).isPresent();
    }
}
