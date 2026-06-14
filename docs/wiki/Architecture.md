# Architecture

## Package layout

```
com.example.hibernatemultithread
├── Application.java               ← @SpringBootApplication entry point
├── config/
│   └── AsyncConfig.java           ← Three named thread pools
├── entity/
│   ├── Product.java               ← @Version → optimistic locking
│   ├── BankAccount.java           ← Pessimistic-lock target
│   ├── Order.java                 ← Batch-processing target
│   └── AuditLog.java             ← Append-only concurrent writes
├── repository/
│   ├── ProductRepository.java     ← @Lock(PESSIMISTIC_WRITE) queries
│   ├── BankAccountRepository.java ← findByIdWithLock()
│   ├── OrderRepository.java       ← findPendingOrders()
│   └── AuditLogRepository.java
├── service/
│   ├── AsyncProductService.java   ← Pattern 1
│   ├── OptimisticLockingService.java ← Pattern 2
│   ├── BankTransferService.java   ← Pattern 3
│   ├── ParallelBatchService.java  ← Pattern 4 (orchestrator)
│   ├── BatchTransactionHelper.java ← Pattern 4 (tx boundary bean)
│   └── SessionPerThreadService.java ← Pattern 5
├── demo/
│   ├── Demo1AsyncService.java
│   ├── Demo2OptimisticLocking.java
│   ├── Demo3PessimisticLocking.java
│   ├── Demo4ParallelBatch.java
│   └── Demo5SessionPerThread.java
└── runner/
    └── DemoRunner.java            ← CommandLineRunner, sequences demos
```

## Entity model

```
┌─────────────────┐   ┌──────────────────┐   ┌────────────────┐
│    Product      │   │   BankAccount    │   │    Order       │
│─────────────────│   │──────────────────│   │────────────────│
│ id              │   │ id               │   │ id             │
│ name            │   │ accountNumber    │   │ customerName   │
│ price           │   │ owner            │   │ productName    │
│ stock           │   │ balance          │   │ quantity       │
│ version ← @Ver. │   │ version ← @Ver.  │   │ totalPrice     │
│ createdAt       │   └──────────────────┘   │ status (enum)  │
│ updatedAt       │                           │ placedAt       │
└─────────────────┘                           │ processedAt    │
                                              │ processedBy-   │
                    ┌──────────────────┐      │   Thread       │
                    │    AuditLog      │      └────────────────┘
                    │──────────────────│
                    │ id               │
                    │ eventType        │
                    │ entityType       │
                    │ entityId         │
                    │ description      │
                    │ threadName       │
                    │ occurredAt       │
                    └──────────────────┘
```

## Thread pools (AsyncConfig)

| Bean name | Core | Max | Queue | Purpose |
|-----------|------|-----|-------|---------|
| `taskExecutor` | 4 | 8 | 50 | General `@Async` methods (Pattern 1) |
| `batchExecutor` | 4 | 4 | 200 | Fixed-size batch pool (Pattern 4) |
| `virtualThreadExecutor` | — | — | — | Java 21 virtual threads (Pattern 1 alt.) |

**Rejection policy:** `CallerRunsPolicy` — the submitting thread runs the task
itself rather than discarding it silently.  This provides natural back-pressure.

## HikariCP connection pool

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
```

Sized to serve both the `taskExecutor` (max 8 threads) and the `batchExecutor`
(4 threads) simultaneously, with headroom for the main thread and test threads.
Virtual threads still respect this limit — they park while waiting for a
connection rather than blocking an OS thread.
