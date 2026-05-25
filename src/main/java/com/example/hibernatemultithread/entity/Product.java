package com.example.hibernatemultithread.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Product entity used across multiple threading demos.
 *
 * <p>The {@code version} field enables <strong>optimistic locking</strong>: Hibernate
 * increments it on every UPDATE and throws {@link jakarta.persistence.OptimisticLockException}
 * when two threads try to commit changes based on the same snapshot simultaneously.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Stock quantity — the hot field in concurrency scenarios. */
    @Column(nullable = false)
    private int stock;

    /** Hibernate's optimistic-lock counter — never set this manually. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Product(String name, BigDecimal price, int stock) {
        this.name  = name;
        this.price = price;
        this.stock = stock;
    }
}
