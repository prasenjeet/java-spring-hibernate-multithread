package com.example.hibernatemultithread.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order entity used in the <strong>parallel batch-processing</strong> demo.
 *
 * <p>Thousands of orders arrive concurrently; a fixed thread pool processes them in
 * parallel, each thread operating in its own Spring-managed transaction so that a
 * failure in one batch does not roll back the others.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime placedAt;

    private LocalDateTime processedAt;

    /** Which thread processed this order — useful for observing concurrency. */
    @Column(length = 100)
    private String processedByThread;

    @PrePersist
    void prePersist() {
        placedAt = LocalDateTime.now();
        if (status == null) status = OrderStatus.PENDING;
    }

    public Order(String customerName, String productName, int quantity, BigDecimal totalPrice) {
        this.customerName = customerName;
        this.productName  = productName;
        this.quantity     = quantity;
        this.totalPrice   = totalPrice;
    }

    public enum OrderStatus {
        PENDING, PROCESSING, COMPLETED, FAILED
    }
}
