package com.example.hibernatemultithread.demo;

import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.ProductRepository;
import com.example.hibernatemultithread.service.AsyncProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Demo 1 — Async + Transactional
 *
 * <p>Fires multiple {@code @Async} price updates concurrently and waits for
 * all of them to finish.  Each call runs on a separate thread from
 * {@code taskExecutor} with its own Spring-managed transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Demo1AsyncService {

    private final AsyncProductService asyncProductService;
    private final ProductRepository   productRepository;

    @Transactional
    public void seedProducts() {
        productRepository.saveAll(List.of(
            new Product("Widget A",  new BigDecimal("10.00"), 100),
            new Product("Widget B",  new BigDecimal("20.00"), 200),
            new Product("Widget C",  new BigDecimal("30.00"), 150),
            new Product("Gadget X",  new BigDecimal("99.99"), 50),
            new Product("Gadget Y",  new BigDecimal("149.99"), 30)
        ));
        log.info("Seeded 5 products");
    }

    public void run() throws Exception {
        log.info("═══════════════════════════════════════════════════════");
        log.info("DEMO 1 — @Async + @Transactional");
        log.info("═══════════════════════════════════════════════════════");

        seedProducts();

        List<Product> all = productRepository.findAll();
        log.info("Launching {} concurrent async price updates…", all.size());

        List<CompletableFuture<Product>> futures = new ArrayList<>();
        for (Product p : all) {
            // Each call is dispatched to a pool thread with its own transaction
            futures.add(asyncProductService.applyPriceIncreaseAsync(p.getId(), 10.0));
        }

        // Also run a few dynamic-price lookups concurrently
        List<CompletableFuture<BigDecimal>> priceFutures = new ArrayList<>();
        for (Product p : all) {
            priceFutures.add(asyncProductService.calculateDynamicPrice(p.getId()));
        }

        // Wait for all updates
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        log.info("All price updates committed.");

        // Wait for all dynamic prices
        CompletableFuture.allOf(priceFutures.toArray(CompletableFuture[]::new)).join();
        log.info("All dynamic prices computed.");

        // Verify in a fresh read
        log.info("Final prices after async updates:");
        productRepository.findAll().forEach(p ->
            log.info("  {} → {}", p.getName(), p.getPrice()));

        log.info("Demo 1 complete.\n");
    }
}
