# Pattern 5 — Session-per-Thread (Manual EntityManager)

**File:** `service/SessionPerThreadService.java`  
**Demo:** `demo/Demo5SessionPerThread.java`  
**Tests:** `Demo5SessionPerThreadTest.java`

---

## The idea

Spring's `@Transactional` is the right choice in most cases, but sometimes you
need **direct control over the Hibernate session lifecycle** — for example:

- ETL pipelines inserting millions of rows
- Data migrations where you need fine-grained flush/clear cycles
- Legacy code that doesn't use Spring proxies
- Custom thread pools outside Spring's executor abstraction

In this pattern each thread opens its own `EntityManager`, uses it exclusively,
and **always closes it in a `finally` block**.

## Thread safety contract

> A Hibernate `Session` (and its JPA wrapper `EntityManager`) is **NOT
> thread-safe**.  Never share an `EntityManager` between threads.

Each thread must:
1. Open its own `EntityManager` via `EntityManagerFactory.createEntityManager()`.
2. Use it exclusively within that thread.
3. Close it in `finally` — regardless of success or failure.

## Code walkthrough

```java
@Service
public class SessionPerThreadService {

    private final EntityManagerFactory emf;

    public void bulkInsertProducts(int total, int threadCount) throws ... {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount,
            r -> new Thread(r, "session-thread-" + ...));

        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> insertBatch(from, count));
        }
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.MINUTES);
    }

    private void insertBatch(int from, int count) {
        EntityManager em = emf.createEntityManager();   // one per thread
        try {
            em.getTransaction().begin();

            for (int i = 0; i < count; i++) {
                em.persist(new Product(...));

                if ((i + 1) % FLUSH_EVERY == 0) {
                    em.flush();   // write buffered INSERTs to DB
                    em.clear();   // detach all managed entities → GC can collect
                }
            }

            em.getTransaction().commit();

        } catch (RuntimeException ex) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw ex;
        } finally {
            em.close();   // CRITICAL — always close; JDBC connection leaks otherwise
        }
    }
}
```

## Why `flush()` + `clear()`?

Hibernate's first-level cache (the persistence context) keeps a reference to
every managed entity.  Without periodic clearing:

```
After 10,000 inserts:
  └─ em has 10,000 Product references in its first-level cache
  └─ dirty-checking on flush scans all 10,000 entities
  └─ heap grows linearly → eventually OutOfMemoryError
```

With periodic flush/clear every `FLUSH_EVERY = 50` rows:

```
Flush at row 50:   write 50 INSERTs to DB, clear cache → back to 0 entities
Flush at row 100:  write 50 INSERTs to DB, clear cache → back to 0 entities
...
Memory usage stays bounded regardless of total insert count.
```

## Read-only session optimisation

For reporting / scanning large tables, mark the Spring transaction
`readOnly = true`:

```java
@Transactional(readOnly = true)
public long countAllProducts() {
    return productRepository.count();
}
```

With `readOnly = true` Hibernate:
- Skips dirty checking at flush time (nothing to write).
- May disable the second-level cache writes.
- Allows the JDBC driver to apply read-only connection optimisations.

## Demo observation

```
Bulk-inserting 200 products across 4 threads…
[session-thread-1] Inserting products 1 to 50
[session-thread-2] Inserting products 51 to 100
[session-thread-3] Inserting products 101 to 150
[session-thread-4] Inserting products 151 to 200
[session-thread-1] Flush/clear at row 50
...
[session-thread-1] Committed 50 products
...
Bulk insert took ~120 ms — total products in DB: 200
```
