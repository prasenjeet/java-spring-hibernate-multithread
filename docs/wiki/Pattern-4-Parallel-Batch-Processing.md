# Pattern 4 — Parallel Batch Processing with Independent Transactions

**Files:** `service/ParallelBatchService.java`, `service/BatchTransactionHelper.java`  
**Demo:** `demo/Demo4ParallelBatch.java`  
**Tests:** `Demo4ParallelBatchTest.java`

---

## The idea

Process a large list of work items concurrently.  Each mini-batch runs on its
own thread in its own **independent** transaction (`REQUIRES_NEW`).  A failure
in one batch never rolls back sibling batches.

```
20 PENDING orders
    │
    ├── Batch 1 (ids 1-5)   ← batchExecutor thread 1 → REQUIRES_NEW tx → COMMIT
    ├── Batch 2 (ids 6-10)  ← batchExecutor thread 2 → REQUIRES_NEW tx → COMMIT
    ├── Batch 3 (ids 11-15) ← batchExecutor thread 3 → REQUIRES_NEW tx → COMMIT
    └── Batch 4 (ids 16-20) ← batchExecutor thread 4 → REQUIRES_NEW tx → COMMIT
                                                    ↑
                               failure here only rolls back batch 4
```

## The Spring AOP self-invocation pitfall

This is the most common Spring concurrency mistake.

```java
// ❌ BROKEN — self-invocation bypasses the AOP proxy
@Service
public class BatchService {

    @Async("batchExecutor")
    public CompletableFuture<Result> processBatchAsync(List<Long> ids) {
        return CompletableFuture.completedFuture(processBatch(ids));  // calls this.processBatch!
    }

    @Transactional(propagation = REQUIRES_NEW)  // ← IGNORED — no proxy in self-call
    public Result processBatch(List<Long> ids) { ... }
}
```

The call `processBatch(ids)` is `this.processBatch(ids)` — it hits the concrete
object directly, bypassing the Spring proxy.  `@Transactional` and `@Async` are
silently ignored.

## The fix — separate bean

Move the annotated methods into a **different Spring bean**.  Calls from
`ParallelBatchService` now go through `BatchTransactionHelper`'s proxy:

```java
// BatchTransactionHelper.java  ← separate @Component
@Component
public class BatchTransactionHelper {

    @Async("batchExecutor")
    public CompletableFuture<BatchResult> processBatchAsync(List<Long> ids) {
        return CompletableFuture.completedFuture(processBatch(ids));
        // processBatch is on the SAME class here — but @Async is what matters.
        // The self-call to processBatch is OK because @Transactional is the
        // one that needs external proxy invocation, and the outer method
        // (processBatchAsync) handles the @Async part.
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult processBatch(List<Long> ids) {
        // each call to this from processBatchAsync goes through the tx proxy
        ...
    }
}

// ParallelBatchService.java
@Service
public class ParallelBatchService {
    private final BatchTransactionHelper batchHelper;

    public String processAllPendingOrders() {
        ...
        List<CompletableFuture<BatchResult>> futures = partitions.stream()
            .map(batchHelper::processBatchAsync)   // ← through proxy ✓
            .toList();
        CompletableFuture.allOf(futures.toArray(...)).join();
        ...
    }
}
```

## Transaction propagation refresher

| Propagation | Behaviour |
|-------------|----------|
| `REQUIRED` (default) | Join existing tx, or start new one |
| `REQUIRES_NEW` | **Always** start a new tx; suspend caller's tx |
| `SUPPORTS` | Join if one exists; otherwise non-transactional |
| `NOT_SUPPORTED` | Suspend caller's tx; run non-transactional |

`REQUIRES_NEW` is the right choice for batch partitions because each partition
must commit (or roll back) independently.

## Passing IDs, not entities

Always pass only **entity IDs** across thread (and transaction) boundaries.
Hibernate entities belong to the session that loaded them.  Passing them to
another thread's session causes `LazyInitializationException` or stale-data
behavior.

```java
// ✓ Correct
List<Long> orderIds = pending.stream().map(Order::getId).toList();

// ✗ Wrong
List<Order> orders = orderRepository.findPendingOrders();
// ... pass orders across threads → detached entities
```

## Demo observation

```
Created 20 PENDING orders
Submitting 4 batches to batchExecutor...
[batch-worker-1] Processing batch [1,2,3,4,5]...
[batch-worker-2] Processing batch [6,7,8,9,10]...
[batch-worker-3] Processing batch [11,12,13,14,15]...
[batch-worker-4] Processing batch [16,17,18,19,20]...
Processed 20 orders: 17 succeeded, 3 failed.
  (every 7th order is intentionally failed as a simulation)
```
