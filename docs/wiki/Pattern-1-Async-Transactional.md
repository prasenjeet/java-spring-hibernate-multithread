# Pattern 1 — `@Async` + `@Transactional`

**File:** `service/AsyncProductService.java`  
**Demo:** `demo/Demo1AsyncService.java`  
**Tests:** `Demo1AsyncServiceTest.java`

---

## The idea

Spring's `@Async` offloads a method call to a thread-pool thread and returns
immediately.  When you also apply `@Transactional`, Spring opens a **new
transaction bound to that pool thread** — completely independent of any
transaction the caller may already hold.

```
Caller thread (main)                Pool thread (async-worker-2)
─────────────────────               ─────────────────────────────────
calls applyPriceIncreaseAsync()  →  opens transaction T2
gets CompletableFuture back          loads product
continues doing other work           updates price
...                                  writes audit log (same T2)
future.get() ← blocks when needed   T2 commits
                                     returns saved product
```

## Rules

| Rule | Why |
|------|-----|
| Return `CompletableFuture<T>` | Callers can join, chain, and catch exceptions |
| Pass IDs, not entities | Entities become **detached** once the caller's session closes; the pool thread needs a live session to reload them |
| Use a bounded pool | `SimpleAsyncTaskExecutor` (Spring's default) creates unbounded threads → OOM under load |
| Don't call `@Async` methods on `this` | Self-invocation bypasses the AOP proxy — the method runs synchronously |

## Code walkthrough

```java
// AsyncConfig.java — bounded pool
@Bean(name = "taskExecutor")
public Executor taskExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(4);
    ex.setMaxPoolSize(8);
    ex.setQueueCapacity(50);
    ex.setThreadNamePrefix("async-worker-");
    ex.setRejectedExecutionHandler(new CallerRunsPolicy());
    ex.initialize();
    return ex;
}
```

```java
// AsyncProductService.java
@Async("taskExecutor")
@Transactional                       // ← new tx on the pool thread
public CompletableFuture<Product> applyPriceIncreaseAsync(
        Long productId, double pctIncrease) {

    // Reload by ID inside this thread's session
    Product p = productRepository.findById(productId).orElseThrow();

    BigDecimal multiplier = BigDecimal.valueOf(1 + pctIncrease / 100.0);
    p.setPrice(p.getPrice().multiply(multiplier));

    // Audit log in the same transaction — both commit or both roll back
    auditLogRepository.save(new AuditLog("PRICE_UPDATE", "Product", productId, ...));

    return CompletableFuture.completedFuture(productRepository.save(p));
}
```

```java
// Caller site
List<CompletableFuture<Product>> futures = products.stream()
    .map(p -> asyncProductService.applyPriceIncreaseAsync(p.getId(), 10.0))
    .toList();

// Wait for all pool threads to commit
CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
```

## Java 21 virtual threads

`AsyncConfig` also exposes a `virtualThreadExecutor` bean backed by
`Executors.newVirtualThreadPerTaskExecutor()`.  Virtual threads are ideal for
IO-bound work (JDBC calls block while the JVM parks the virtual thread).
HikariCP still caps the number of open connections — virtual threads don't
bypass the pool.

## When to use this pattern

- Fire-and-forget notifications, price recalculations, or enrichment tasks
- Parallel fan-out where each subtask is independent
- Any place where you want the caller to remain responsive while background work proceeds
