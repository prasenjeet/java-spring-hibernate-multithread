package com.example.hibernatemultithread.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Bank account entity used in the <strong>pessimistic-locking</strong> and
 * <strong>deadlock-prevention</strong> demos.
 *
 * <p>Money transfers between accounts are the classic scenario where we need
 * strict isolation. We acquire database-level locks ({@code SELECT … FOR UPDATE})
 * to prevent concurrent transfers from leaving accounts in inconsistent states,
 * and we always lock in a canonical order (by ID) to prevent deadlocks.
 */
@Entity
@Table(name = "bank_accounts")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountNumber;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    /** Optimistic-lock version kept alongside pessimistic locks as a safety net. */
    @Version
    @Column(nullable = false)
    private Long version;

    public BankAccount(String accountNumber, String owner, BigDecimal balance) {
        this.accountNumber = accountNumber;
        this.owner         = owner;
        this.balance       = balance;
    }

    /** Debit the account; throws {@link IllegalStateException} on insufficient funds. */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                "Insufficient funds in account %s: has %.2f, needs %.2f"
                    .formatted(accountNumber, balance, amount));
        }
        balance = balance.subtract(amount);
    }

    /** Credit the account. */
    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }
}
