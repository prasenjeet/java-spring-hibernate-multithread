# Spring + Hibernate Multithreading Samples

A runnable Java 21 / Spring Boot 3 / Hibernate 6 project demonstrating **five
real-world patterns** for safe concurrent database access.  Each pattern lives in
its own service class with rich Javadoc, and every pattern has its own JUnit 5
test suite.

---

## Quick start

```bash
# Requires Java 21 and Maven 3.8+
mvn spring-boot:run        # runs all 5 demos and prints results to the console
mvn test                   # runs all 20 unit / integration tests
```

After start-up, the H2 web console is available at
<http://localhost:8080/h2-console>
(JDBC URL: `jdbc:h2:mem:multithreaddb`, user `sa`, no password).

---

## Project structure

```
src/main/java/com/example/hibernatemultithread/
├── Application.java                  ← Spring Boot entry point
├── config/
│   └── AsyncConfig.java              ← Thread-pool definitions
├── entity/
│   ├── Product.java                  ← @Version field → optimistic locking
│   ├── BankAccount.java              ← Pessimistic locking target
│   ├── Order.java                    ← Batch processing target
│   └── AuditLog.java                 ← Append-only concurrent writes
├── repository/                       ← Spring Data JPA with custom @Lock queries
├── service/
│   ├── AsyncProductService.java      ← Pattern 1
│   ├── OptimisticLockingService.java ← Pattern 2
│   ├── BankTransferService.java      ← Pattern 3
│   ├── ParallelBatchService.java     ← Pattern 4 (orchestrator)
│   ├── BatchTransactionHelper.java   ← Pattern 4 (transaction boundary)
│   └── SessionPerThreadService.java  ← Pattern 5
├── demo/                             ← Demo orchestrators (run by DemoRunner)
└── runner/DemoRunner.java            ← CommandLineRunner that runs all demos
```

---

## The five patterns

### Pattern 1 — `@Async` + `@Transactional`  
**File:** `AsyncProductService.java`

`@Async` offloads a method call to a pool thread.  When combined with
`@Transactional`, Spring opens a **new transaction bound to that thread**,
independent of the caller.

Key rules:
- Return `CompletableFuture<T>` so callers can observe success/failure.
- Never pass **detached Hibernate entities** across thread boundaries —
  pass IDs or DTOs and reload inside the async method.
- Use a **bounded `ThreadPoolTaskExecutor`** instead of
  `SimpleAsyncTaskExecutor` (which creates unbounded threads).

```java
@Async("taskExecutor")
@Transactional
public CompletableFuture<Product> applyPriceIncreaseAsync(Long id, double pct) {
    Product p = productRepository.findById(id).orElseThrow();
    p.setPrice(p.getPrice().multiply(BigDecimal.valueOf(1 + pct / 100)));
    return CompletableFuture.completedFuture(productRepository.save(p));
}
```

---

### Pattern 2 — Optimistic Locking with Exponential-Backoff Retry  
**File:** `OptimisticLockingService.java`

Hibernate's `@Version` field adds a `WHERE version = ?` predicate to every
`UPDATE`.  If two transactions commit changes based on the same snapshot,
the second one gets an `OptimisticLockException`.  A retry loop with
exponential back-off + jitter ensures eventual success without blocking DB
connections.

```java
@Entity
public class Product {
    @Version private Long version;  // Hibernate manages this automatically
}
```

Best for: **low-contention** scenarios where reads vastly outnumber writes.

---

### Pattern 3 — Pessimistic Locking + Deadlock Prevention  
**File:** `BankTransferService.java`

`SELECT … FOR UPDATE` (Hibernate's `PESSIMISTIC_WRITE`) gives one transaction
exclusive access to a row until it commits.

**Deadlock prevention via canonical ordering:**  
Always acquire locks in ascending entity-ID order, regardless of transfer
direction.  This eliminates circular waits.

```
Thread A: account 1 → account 2   acquires lock(1) first, then lock(2)
Thread B: account 2 → account 1   also acquires lock(1) first → blocks → no deadlock
```

```java
Long firstId  = Math.min(fromId, toId);
Long secondId = Math.max(fromId, toId);
// Always lock firstId before secondId
```

---

### Pattern 4 — Parallel Batch Processing with Independent Transactions  
**Files:** `ParallelBatchService.java` + `BatchTransactionHelper.java`

Partitions a large work list into mini-batches; each batch runs on a
`batchExecutor` thread in its own `REQUIRES_NEW` transaction.  A failure in
one batch does not roll back sibling batches.

**Critical lesson — Spring AOP self-invocation:**  
`@Transactional` and `@Async` work through AOP proxies that intercept calls
from **outside** the bean.  A method calling another method on `this` bypasses
the proxy and annotations are silently ignored.

**Fix:** extract the transactional/async methods into a **separate Spring
bean** so every call goes through a proxy.

```
ParallelBatchService          BatchTransactionHelper (separate bean)
  processAllPendingOrders()  →  processBatchAsync()   ← @Async applies ✓
                                  processBatch()        ← @Transactional applies ✓
```

---

### Pattern 5 — Session-per-Thread (Manual EntityManager)  
**File:** `SessionPerThreadService.java`

Bypasses Spring's transaction abstraction for full control over the Hibernate
session lifecycle.  Each thread opens its own `EntityManager`, uses it, and
closes it in `finally`.

Includes periodic `em.flush(); em.clear()` every N rows to keep the
first-level cache small during large bulk inserts — essential for avoiding
`OutOfMemoryError`.

```java
EntityManager em = emf.createEntityManager();
try {
    em.getTransaction().begin();
    for (int i = 0; i < count; i++) {
        em.persist(entity);
        if ((i + 1) % FLUSH_EVERY == 0) {
            em.flush();   // write to DB
            em.clear();   // detach all → GC can collect
        }
    }
    em.getTransaction().commit();
} finally {
    em.close();  // ALWAYS close — connections leak otherwise
}
```

---

## Key takeaways

| Topic | Rule |
|-------|------|
| `@Async` + `@Transactional` | Each async thread gets its own transaction; never share entities across threads |
| Optimistic vs pessimistic | Optimistic for low contention; pessimistic for financial/inventory writes |
| Deadlock prevention | Always acquire multiple locks in a canonical (e.g. ascending ID) order |
| Spring AOP self-invocation | `@Async` / `@Transactional` are ignored on `this.method()` calls — use a separate bean |
| Session thread safety | One `EntityManager` per thread, always closed in `finally` |
| Connection pool sizing | `pool_size ≈ cores × (1 + wait/compute)`; virtual threads still respect pool limits |

---

## Technology stack

| Library | Version |
|---------|---------|
| Java | 21 |
| Spring Boot | 3.2.5 |
| Hibernate | 6.4.x (via Spring Boot) |
| HikariCP | 5.x (via Spring Boot) |
| H2 Database | 2.x |
| Lombok | latest |
| JUnit 5 / AssertJ | via Spring Boot Test |
