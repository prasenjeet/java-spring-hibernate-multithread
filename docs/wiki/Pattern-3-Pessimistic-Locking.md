# Pattern 3 — Pessimistic Locking + Deadlock Prevention

**File:** `service/BankTransferService.java`  
**Demo:** `demo/Demo3PessimisticLocking.java`  
**Tests:** `Demo3BankTransferTest.java`

---

## The idea

Pessimistic locking acquires a database-level exclusive lock **before** reading
the row.  No other transaction can read or write the locked row until the lock
holder commits or rolls back.

In Hibernate this is expressed as:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM BankAccount a WHERE a.id = :id")
Optional<BankAccount> findByIdWithLock(@Param("id") Long id);
```

Hibernate generates:
```sql
SELECT * FROM bank_accounts WHERE id = ? FOR UPDATE
```

## The deadlock problem

Without ordering, bidirectional transfers between two accounts deadlock:

```
Thread A (transfer 1 → 2):   LOCK account 1  …  waits for account 2
Thread B (transfer 2 → 1):   LOCK account 2  …  waits for account 1
                                 ↑______________________________________↓
                                        circular wait = DEADLOCK
```

## The fix — canonical lock ordering

Always acquire locks in **ascending entity-ID order**, regardless of which
account is the source and which is the destination.

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public void transfer(Long fromId, Long toId, BigDecimal amount) {

    // Step 1 — determine acquisition order
    Long firstId  = Math.min(fromId, toId);
    Long secondId = Math.max(fromId, toId);

    // Step 2 — always lock in ascending ID order
    BankAccount first  = accountRepo.findByIdWithLock(firstId).orElseThrow();
    BankAccount second = accountRepo.findByIdWithLock(secondId).orElseThrow();

    // Step 3 — re-map to logical from/to
    BankAccount from = firstId.equals(fromId) ? first : second;
    BankAccount to   = firstId.equals(toId)   ? first : second;

    from.debit(amount);    // throws if insufficient funds
    to.credit(amount);

    accountRepo.save(from);
    accountRepo.save(to);
}
```

Now both threads try to lock **account 1** first:

```
Thread A (1→2):  LOCK(1) ✓  LOCK(2) ✓  commits
Thread B (2→1):  LOCK(1) …  [blocks until A releases]  LOCK(2) ✓  commits
```

No cycle → no deadlock.

## Money conservation invariant

Because both the debit and the credit happen in one atomic transaction, the
total balance across all accounts never changes:

```
before transfer:  Alice 1000  Bob 1000  total = 2000
after  transfer:  Alice  800  Bob 1200  total = 2000  ✓
```

The test asserts this invariant after 16 concurrent bidirectional transfers:

```java
assertThat(aliceBalance.add(bobBalance)).isEqualByComparingTo(initialTotal);
assertThat(failure.get()).isEqualTo(0);   // no deadlocks
```

## Isolation level note

We use `READ_COMMITTED` (not `SERIALIZABLE`).  `SELECT … FOR UPDATE` already
serialises access at the row level; adding `SERIALIZABLE` on top introduces
predicate locking that causes spurious deadlocks in H2 even with correct
canonical ordering.  In production (PostgreSQL, MySQL, Oracle) `SERIALIZABLE`
is also safe and can be used for an extra layer of protection.

## `PESSIMISTIC_READ` vs `PESSIMISTIC_WRITE`

| Mode | SQL | Semantics |
|------|-----|-----------|
| `PESSIMISTIC_READ` | `SELECT … FOR SHARE` | Multiple readers allowed; blocks writers |
| `PESSIMISTIC_WRITE` | `SELECT … FOR UPDATE` | Exclusive; blocks all readers and writers |

Use `PESSIMISTIC_READ` when you need a consistent snapshot for reporting but
can tolerate concurrent reads.  Use `PESSIMISTIC_WRITE` when you will mutate
the row.
