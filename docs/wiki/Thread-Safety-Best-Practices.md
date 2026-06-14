# Thread Safety Best Practices

A concise reference card for safe concurrent use of Spring and Hibernate.

---

## Golden rules

1. **One session per thread, always.**  
   `EntityManager` is not thread-safe.  Never share it.  Spring's
   `@Transactional` handles this automatically; with manual EMs, allocate in
   the thread and close in `finally`.

2. **Never pass detached entities across thread boundaries.**  
   Pass entity IDs or plain DTOs.  Reload inside the target thread's session.

3. **Return `CompletableFuture<T>` from `@Async` methods.**  
   Lets callers join, chain, and catch exceptions.  A `void` async method
   swallows exceptions silently.

4. **Use bounded thread pools.**  
   `SimpleAsyncTaskExecutor` creates a new thread per task — unbounded.
   Always define a `ThreadPoolTaskExecutor` with a size based on your workload.

5. **Acquire multiple locks in a canonical order.**  
   For any set of rows that a transaction might lock, always lock them in the
   same order (e.g., ascending primary key).  This eliminates circular waits
   and prevents deadlocks.

6. **Prefer optimistic locking for low-contention reads, pessimistic for
   high-contention writes.**  
   Optimistic: no DB lock held, but retry logic is required.  
   Pessimistic: DB lock held, simpler logic, but potential for lock waits.

7. **Each retry of an optimistic-lock conflict needs its own transaction.**  
   The retry must read a fresh snapshot.  If the retry shares the same
   transaction as the failed attempt, it will read the stale snapshot again.

8. **Fix Spring AOP self-invocation by extracting to a separate bean.**  
   `@Async` and `@Transactional` are implemented as AOP proxies that intercept
   calls from outside the bean.  `this.method()` bypasses the proxy — the
   annotation is silently ignored.

9. **Always close `EntityManager` in `finally`.**  
   An unclosed EM leaks its JDBC connection, silently exhausting the pool.

10. **Flush and clear periodically during bulk inserts.**  
    Call `em.flush(); em.clear()` every N rows to keep the first-level cache
    bounded and prevent `OutOfMemoryError`.

---

## Decision table — which locking pattern?

| Situation | Recommended pattern |
|-----------|--------------------|
| Concurrent reads, rare writes (catalogue, settings) | **Optimistic** (`@Version`) |
| Frequent concurrent writes to the same row | **Pessimistic** (`FOR UPDATE`) |
| Money / inventory — must not lose any update | **Pessimistic** |
| Locking multiple rows in one transaction | **Pessimistic + canonical ordering** |
| Insert-only workload (audit log, events) | **Neither** — no conflicts possible |
| Large ETL / bulk load | **Session-per-thread + flush/clear** |
| Fan-out parallel processing, independent results | **`@Async` + `@Transactional`** |
| Large list to process with partial-failure tolerance | **Parallel batch + `REQUIRES_NEW`** |

---

## Common mistakes

### Sharing an EntityManager between threads
```java
// ❌ BROKEN
@Service
public class BadService {
    @PersistenceContext
    private EntityManager em;   // Spring injects a proxy; the raw session is NOT thread-safe

    public void doWork() {
        Executors.newFixedThreadPool(4).submit(() -> em.persist(entity)); // race condition
    }
}
```
Fix: use Spring's `@Transactional` (Spring opens a session per thread) or
allocate your own `EntityManager` per thread.

### Passing a detached entity to another thread
```java
// ❌ BROKEN
List<Product> products = productRepository.findAll();   // session closes after method
new Thread(() -> products.get(0).getOrders()).start();   // LazyInitializationException
```
Fix: pass `List<Long> ids` and reload in the target thread.

### Self-invocation killing @Transactional
```java
// ❌ BROKEN
@Service
public class OrderService {
    public void processAll() {
        processOne();   // this.processOne() — bypasses proxy
    }

    @Transactional(propagation = REQUIRES_NEW)   // ignored!
    public void processOne() { ... }
}
```
Fix: extract `processOne` into a separate Spring bean.

### Using @Async without a bounded pool
```java
// ❌ BROKEN — SimpleAsyncTaskExecutor (Spring default) creates unlimited threads
@Async
public void doWork() { ... }   // called 10,000 times → 10,000 threads → OOM
```
Fix: define a `ThreadPoolTaskExecutor` bean and reference it: `@Async("taskExecutor")`.

### Forgetting to close EntityManager
```java
// ❌ BROKEN
EntityManager em = emf.createEntityManager();
em.getTransaction().begin();
em.persist(entity);
em.getTransaction().commit();
// em.close() missing → JDBC connection leaked forever
```
Fix: always wrap in try/finally.

---

## HikariCP sizing formula

For IO-bound (JDBC) work:

```
pool_size = number_of_cores × (1 + average_wait_time / average_compute_time)
```

Example: 4-core machine, queries average 50 ms wait, 10 ms compute:
```
pool_size = 4 × (1 + 50/10) = 4 × 6 = 24
```

Set `spring.datasource.hikari.maximum-pool-size` accordingly, and ensure your
thread pool does not exceed this (otherwise threads queue up waiting for
connections).
