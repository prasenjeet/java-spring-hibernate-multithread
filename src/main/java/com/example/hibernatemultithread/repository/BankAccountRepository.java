package com.example.hibernatemultithread.repository;

import com.example.hibernatemultithread.entity.BankAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    Optional<BankAccount> findByAccountNumber(String accountNumber);

    /**
     * Acquires a pessimistic write-lock on the account row.
     *
     * <p>Critical for money-transfer operations: we need to be certain that
     * no other transaction can read or modify the row until we commit, so that
     * the balance we read is still accurate when we write back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BankAccount a WHERE a.id = :id")
    Optional<BankAccount> findByIdWithLock(@Param("id") Long id);
}
