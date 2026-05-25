package com.example.hibernatemultithread.service;

import com.example.hibernatemultithread.entity.AuditLog;
import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.AuditLogRepository;
import com.example.hibernatemultithread.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * PATTERN 1 — {@code @Async} + {@code @Transactional}
 *
 * <h2>Key rules</h2>
 * <ol>
 *   <li>Spring's {@code @Transactional} binds one JDBC connection to the
 *       <em>current thread</em> for the life of the method. When {@code @Async}
 *       offloads the call to a different thread, Spring opens a <em>new</em>
 *       transaction on that thread — completely independent of the caller's
 *       transaction.</li>
 *   <li>Return {@link CompletableFuture} so callers can {@code .join()} and
 *       observe success/failure, and so Spring can propagate exceptions
 *       correctly.</li>
 *   <li>Do <em>not</em> pass detached Hibernate entities across thread
 *       boundaries. Pass only IDs or plain DTOs and reload inside the
 *       {@code @Async} method where a live session exists.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncProductService {

    private final ProductRepository  productRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * Applies a price increase to a product asynchronously.
     *
     * <p>This method runs on a thread from the {@code taskExecutor} pool.
     * Spring opens a fresh transaction bound to that thread, loads the product
     * inside it, updates the price, and commits — all without touching the
     * caller's transaction (if any).
     *
     * @param productId  ID of the product to update
     * @param pctIncrease  price increase as a percentage (e.g. 10 = +10 %)
     * @return a future that resolves to the updated product once the tx commits
     */
    @Async("taskExecutor")
    @Transactional
    public CompletableFuture<Product> applyPriceIncreaseAsync(Long productId, double pctIncrease) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] Applying {}% price increase to product #{}", thread, pctIncrease, productId);

        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        BigDecimal multiplier = BigDecimal.valueOf(1 + pctIncrease / 100.0);
        BigDecimal oldPrice   = product.getPrice();
        product.setPrice(oldPrice.multiply(multiplier).setScale(2, java.math.RoundingMode.HALF_UP));

        // Audit log written in the same transaction as the product update.
        // If the product save fails, both roll back together.
        auditLogRepository.save(new AuditLog(
            "PRICE_UPDATE", "Product", productId,
            "Price changed from %s to %s (+%.1f%%) by %s"
                .formatted(oldPrice, product.getPrice(), pctIncrease, thread)
        ));

        Product saved = productRepository.save(product);
        log.info("[{}] Price update committed for product #{}: {} → {}",
            thread, productId, oldPrice, saved.getPrice());
        return CompletableFuture.completedFuture(saved);
    }

    /**
     * Simulates a long-running async computation (e.g. external pricing API call).
     *
     * <p>Demonstrates that the {@code @Async} thread is not blocked at the caller
     * site — the caller gets the {@link CompletableFuture} immediately and can
     * do other work while waiting.
     */
    @Async("taskExecutor")
    @Transactional(readOnly = true)
    public CompletableFuture<BigDecimal> calculateDynamicPrice(Long productId) throws InterruptedException {
        String thread = Thread.currentThread().getName();
        log.info("[{}] Calculating dynamic price for product #{}...", thread, productId);

        // Simulate slow external call (pricing API, ML model, etc.)
        Thread.sleep(200);

        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        // Simple rule: "dynamic price" = base price × 1.15
        BigDecimal dynamicPrice = product.getPrice()
            .multiply(BigDecimal.valueOf(1.15))
            .setScale(2, java.math.RoundingMode.HALF_UP);

        log.info("[{}] Dynamic price for #{}: {}", thread, productId, dynamicPrice);
        return CompletableFuture.completedFuture(dynamicPrice);
    }

    /**
     * Saves a brand-new product inside an async transaction.
     */
    @Async("taskExecutor")
    @Transactional
    public CompletableFuture<Product> createProductAsync(String name, BigDecimal price, int stock) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] Creating product '{}' asynchronously", thread, name);
        Product product = productRepository.save(new Product(name, price, stock));
        log.info("[{}] Product '{}' created with id={}", thread, name, product.getId());
        return CompletableFuture.completedFuture(product);
    }
}
