package com.example.hibernatemultithread.service;

import com.example.hibernatemultithread.entity.AuditLog;
import com.example.hibernatemultithread.entity.BankAccount;
import com.example.hibernatemultithread.repository.AuditLogRepository;
import com.example.hibernatemultithread.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * PATTERN 3 — Pessimistic Locking + Deadlock Prevention
 *
 * <h2>The problem</h2>
 * <p>Thread A wants to transfer money from account 1 → 2.
 *    Thread B wants to transfer money from account 2 → 1.
 * <pre>
 *   Thread A:  LOCK account 1 … (holds) … tries to LOCK account 2  ← blocks
 *   Thread B:  LOCK account 2 … (holds) … tries to LOCK account 1  ← blocks
 * </pre>
 * Both threads are now deadlocked — each waits for the lock held by the other.
 *
 * <h2>The solution — canonical lock ordering</h2>
 * <p>Always acquire locks in ascending ID order, regardless of which account
 * is the source and which is the destination.  Both threads will now attempt
 * to lock account 1 first; one succeeds and the other blocks, but neither
 * can form a cycle.
 *
 * <h2>Isolation level</h2>
 * <p>We use {@link Isolation#READ_COMMITTED} combined with pessimistic write
 * locks ({@code SELECT … FOR UPDATE}).  The lock already serialises access to
 * the affected rows; SERIALIZABLE would add predicate locking on top of that
 * which can produce spurious deadlocks in H2 even when canonical ordering is
 * applied correctly.  In production (PostgreSQL, MySQL, Oracle) SERIALIZABLE
 * is a valid and safer choice — the canonical ordering still prevents circular
 * waits at the row level.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransferService {

    private final BankAccountRepository bankAccountRepository;
    private final AuditLogRepository    auditLogRepository;

    /**
     * Transfers {@code amount} from {@code fromAccountId} to {@code toAccountId}.
     *
     * <p>Locks are acquired in ascending ID order to prevent deadlocks.
     *
     * @throws IllegalStateException if the source account has insufficient funds
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void transfer(Long fromAccountId, Long toAccountId, BigDecimal amount) {
        String thread = Thread.currentThread().getName();
        log.info("[{}] Initiating transfer of {} from account #{} to #{}",
            thread, amount, fromAccountId, toAccountId);

        // ── Canonical lock ordering ────────────────────────────────────────────
        // Always lock the lower ID first to avoid circular wait (deadlock).
        Long firstId  = Math.min(fromAccountId, toAccountId);
        Long secondId = Math.max(fromAccountId, toAccountId);

        log.debug("[{}] Acquiring lock on account #{} (first)…", thread, firstId);
        BankAccount first = bankAccountRepository.findByIdWithLock(firstId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstId));

        log.debug("[{}] Acquiring lock on account #{} (second)…", thread, secondId);
        BankAccount second = bankAccountRepository.findByIdWithLock(secondId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondId));

        // ── Re-map to from/to based on the actual transfer direction ───────────
        BankAccount from = (firstId.equals(fromAccountId)) ? first : second;
        BankAccount to   = (firstId.equals(toAccountId))   ? first : second;

        log.debug("[{}] Balances before transfer — from '{}': {}, to '{}': {}",
            thread, from.getOwner(), from.getBalance(), to.getOwner(), to.getBalance());

        from.debit(amount);   // throws IllegalStateException on insufficient funds
        to.credit(amount);

        bankAccountRepository.save(from);
        bankAccountRepository.save(to);

        // Audit both sides in the same transaction
        auditLogRepository.save(new AuditLog("TRANSFER_DEBIT",  "BankAccount", from.getId(),
            "Debited %s from %s (%s) → %s".formatted(amount, from.getOwner(), from.getAccountNumber(), to.getOwner())));
        auditLogRepository.save(new AuditLog("TRANSFER_CREDIT", "BankAccount", to.getId(),
            "Credited %s to %s (%s) ← %s".formatted(amount, to.getOwner(), to.getAccountNumber(), from.getOwner())));

        log.info("[{}] Transfer complete — from '{}': {} → {}, to '{}': {} → {}",
            thread,
            from.getOwner(), from.getBalance().add(amount), from.getBalance(),
            to.getOwner(), to.getBalance().subtract(amount), to.getBalance());
    }

    /** Creates a new account with the given initial balance. */
    @Transactional
    public BankAccount createAccount(String accountNumber, String owner, BigDecimal initialBalance) {
        BankAccount account = bankAccountRepository.save(
            new BankAccount(accountNumber, owner, initialBalance));
        log.info("Created bank account #{} for '{}' with balance {}",
            account.getId(), owner, initialBalance);
        return account;
    }

    /** Reads current balance (read-only, no locks needed). */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(Long accountId) {
        return bankAccountRepository.findById(accountId)
            .map(BankAccount::getBalance)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }
}
