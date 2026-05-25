package com.example.hibernatemultithread.repository;

import com.example.hibernatemultithread.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Loads the product using <strong>PESSIMISTIC_WRITE</strong> lock.
     *
     * <p>Issues {@code SELECT … FOR UPDATE} so the calling transaction holds
     * an exclusive row-level lock until it commits or rolls back. Other
     * transactions that try to acquire the same lock will block.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithPessimisticLock(@Param("id") Long id);

    /**
     * Loads the product using <strong>PESSIMISTIC_READ</strong> lock.
     *
     * <p>Issues {@code SELECT … FOR SHARE}. Multiple readers can hold this lock
     * simultaneously, but a writer must wait for all readers to finish.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithPessimisticReadLock(@Param("id") Long id);

    Optional<Product> findByName(String name);
}
