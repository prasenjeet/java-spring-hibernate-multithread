# Pattern 2 — Optimistic Locking with Retry

**File:** `service/OptimisticLockingService.java`  
**Demo:** `demo/Demo2OptimisticLocking.java`  
**Tests:** `Demo2OptimisticLockingTest.java`

---

## The idea

Optimistic locking assumes conflicts are **rare**.  Rather than holding a
database lock while reading and writing, it detects conflicts at commit time
by comparing a version counter.

```
Thread A reads product (version=5) ──────────────────────────────┐
Thread B reads product (version=5) ──────────────┐               │
                                                  │               │
Thread B commits UPDATE ... WHERE version=5  ←── ┘  (ok, v→6)  │
                                                                  │
Thread A tries UPDATE ... WHERE version=5  ←─────────────────── ┘
  └─ 0 rows matched → OptimisticLockException!  (A retries)
```

## Enabling optimistic locking

Add a single `@Version` field to the entity — Hibernate manages everything else:

```java
@Entity
public class Product {
    @Version
    private Long version;   // Hibernate increments on every UPDATE
}
```

Hibernate generates:
```sql
UPDATE products SET price=?, stock=?, version=6 WHERE id=? AND version=5
```

If another transaction already bumped the version to 6, this UPDATE matches 0
rows and Hibernate throws `OptimisticLockException` (wrapped by Spring as
`ObjectOptimisticLockingFailureException`).

## Retry strategy

```java
public Product decrementStock(Long productId, int quantity) {
    int attempt = 0;
    while (attempt < MAX_RETRIES) {           // MAX_RETRIES = 5
        try {
            return tryDecrementStock(productId, quantity);  // own tx
        } catch (ObjectOptimisticLockingFailureException ex) {
            attempt++;
            if (attempt >= MAX_RETRIES) throw new IllegalStateException(...);
            sleepWithBackoff(attempt);        // exponential + jitter
        }
    }
}

@Transactional                                // each attempt is its own tx
public Product tryDecrementStock(Long productId, int qty) {
    Product p = productRepository.findById(productId).orElseThrow();
    p.setStock(p.getStock() - qty);
    return productRepository.save(p);
}
```

Key points:
- Each retry calls `tryDecrementStock` (a separate `@Transactional` method on
  the same bean is fine here because the outer `decrementStock` is **not**
  `@Transactional` — calls come from outside the bean through the proxy).
- Exponential back-off with jitter: `delay = BASE * 2^(attempt-1) + random`.
- Jitter prevents the "thundering herd" where all retrying threads wake up
  simultaneously.

## Optimistic vs pessimistic — when to choose

| Criterion | Optimistic | Pessimistic |
|-----------|-----------|-------------|
| Contention level | Low (mostly reads) | High (many writers) |
| Lock held | None (read and write without DB lock) | DB row lock for the duration of the tx |
| Throughput | Higher — no lock waits | Lower — writers queue up |
| Complexity | Retry logic needed | Deadlock-prevention logic needed |
| Use case | Product catalogue, settings | Bank transfers, inventory at checkout |

## Demo observation

10 threads simultaneously decrement the same product's stock by 5 units.  The
`@Version` field ensures no update is silently lost.  At the end:

```
Initial stock:  1000
Threads × decrement:  10 × 5 = 50
Expected final stock: 950
Actual stock:   950   ✓ No lost updates
Version (# committed updates): 10
```
