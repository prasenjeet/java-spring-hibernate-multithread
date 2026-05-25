package com.example.hibernatemultithread.service;

import com.example.hibernatemultithread.entity.Product;
import com.example.hibernatemultithread.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PATTERN 2 — Optimistic Locking with Automatic Retry
 *
 * <h2>How it works</h2>
 * <p>The {@link Product#getVersion()} field is annotated with {@code @Version}.
 * Hibernate adds a {@code WHERE version = ?} predicate to every UPDATE. If
 * another transaction has already incremented the version the update matches
 * 0 rows and Hibernate throws {@link jakarta.persistence.OptimisticLockException}.
 *
 * <h2>When to use optimistic vs pessimistic locking</h2>
 * <table border="1">
 *   <tr><th>Scenario</th><th>Preferred</th></tr>
 *   <tr><td>Low contention, reads dominate</td><td>Optimistic</td></tr>
 *   <tr><td>High contention, writes dominate</td><td>Pessimistic</td></tr>
 *   <tr><td>Money / inventory — can't lose updates</td><td>Pessimistic</td></tr>
 * </table>
 *
 * <h2>Retry strategy</h2>
 * <p>We use a simple exponential back-off with jitter.  In production you
 * would use a library like Resilience4j's {@code @Retry}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimisticLockingService {

    private static final int    MAX_RETRIES    = 5;
    private static final long   BASE_DELAY_MS  = 50;

    private final ProductRepository productRepository;

    /**
     * Decrements product stock by {@code quantity} units using optimistic
     * locking.  Retries up to {@value MAX_RETRIES} times on version conflicts.
     *
     * <p>Each attempt runs in its own transaction so we read a fresh snapshot
     * with the latest version number before retrying.
     *
     * @param productId  product to destock
     * @param quantity   units to remove
     * @return the saved product after successful decrement
     * @throws IllegalStateException    if stock would go negative
     * @throws IllegalArgumentException if max retries are exhausted
     */
    public Product decrementStock(Long productId, int quantity) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                return tryDecrementStock(productId, quantity);
            } catch (ObjectOptimisticLockingFailureException ex) {
                attempt++;
                log.warn("[{}] Optimistic lock conflict on product #{}, attempt {}/{}",
                    Thread.currentThread().getName(), productId, attempt, MAX_RETRIES);
                if (attempt >= MAX_RETRIES) {
                    throw new IllegalStateException(
                        "Could not update product #%d after %d attempts".formatted(productId, MAX_RETRIES), ex);
                }
                sleepWithBackoff(attempt);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    /**
     * Single attempt — runs inside its own transaction so that a retry sees
     * a fresh snapshot from the database.
     */
    @Transactional
    public Product tryDecrementStock(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (product.getStock() < quantity) {
            throw new IllegalStateException(
                "Insufficient stock for product '%s': has %d, needs %d"
                    .formatted(product.getName(), product.getStock(), quantity));
        }

        product.setStock(product.getStock() - quantity);
        Product saved = productRepository.save(product);

        log.info("[{}] Stock decremented for '{}': {} → {} (version {})",
            Thread.currentThread().getName(), saved.getName(),
            saved.getStock() + quantity, saved.getStock(), saved.getVersion());
        return saved;
    }

    /**
     * Increments stock (e.g. restocking / return) with the same retry logic.
     */
    public Product incrementStock(Long productId, int quantity) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                return tryIncrementStock(productId, quantity);
            } catch (ObjectOptimisticLockingFailureException ex) {
                attempt++;
                log.warn("[{}] Optimistic lock conflict (increment) on product #{}, attempt {}/{}",
                    Thread.currentThread().getName(), productId, attempt, MAX_RETRIES);
                if (attempt >= MAX_RETRIES) {
                    throw new IllegalStateException(
                        "Could not restock product #%d after %d attempts".formatted(productId, MAX_RETRIES), ex);
                }
                sleepWithBackoff(attempt);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    @Transactional
    public Product tryIncrementStock(Long productId, int quantity) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        product.setStock(product.getStock() + quantity);
        return productRepository.save(product);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void sleepWithBackoff(int attempt) {
        long jitter = (long) (Math.random() * BASE_DELAY_MS);
        long delay  = (BASE_DELAY_MS * (1L << (attempt - 1))) + jitter;  // exponential + jitter
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
