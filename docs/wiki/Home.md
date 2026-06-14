# Spring + Hibernate Multithreading — Wiki Home

Welcome to the wiki for the **Spring + Hibernate Multithreading Samples** project.  
This repository demonstrates five production-grade patterns for safe concurrent database access using **Java 21**, **Spring Boot 3.2**, and **Hibernate 6**.

---

## Pages

| Page | What it covers |
|------|----------------|
| [Getting Started](Getting-Started) | Build, run, and navigate the code |
| [Architecture](Architecture) | Package layout, entity model, thread-pool design |
| [Pattern 1 — @Async + @Transactional](Pattern-1-Async-Transactional) | Fire-and-forget tasks with independent transactions |
| [Pattern 2 — Optimistic Locking](Pattern-2-Optimistic-Locking) | `@Version`-based conflict detection with retry |
| [Pattern 3 — Pessimistic Locking](Pattern-3-Pessimistic-Locking) | `SELECT … FOR UPDATE` and deadlock prevention |
| [Pattern 4 — Parallel Batch Processing](Pattern-4-Parallel-Batch-Processing) | Independent per-batch transactions across a thread pool |
| [Pattern 5 — Session Per Thread](Pattern-5-Session-Per-Thread) | Manual `EntityManager` lifecycle for bulk inserts |
| [Thread Safety Best Practices](Thread-Safety-Best-Practices) | Rules, pitfalls, and a quick-reference decision table |

---

## Quick summary

```
┌─────────────────────────────────────────────────────────────────┐
│  Pattern 1  │  @Async + @Transactional — pool threads, own tx   │
│  Pattern 2  │  @Version optimistic locking + exponential retry  │
│  Pattern 3  │  SELECT FOR UPDATE + canonical lock ordering       │
│  Pattern 4  │  REQUIRES_NEW batches + Spring proxy fix          │
│  Pattern 5  │  EntityManager-per-thread + flush/clear cycle     │
└─────────────────────────────────────────────────────────────────┘
```

## Technology stack

| Library | Version |
|---------|---------|
| Java | 21 |
| Spring Boot | 3.2.5 |
| Hibernate | 6.4.x |
| HikariCP | 5.x |
| H2 (in-memory) | 2.x |
| JUnit 5 / AssertJ | via Spring Boot Test |
