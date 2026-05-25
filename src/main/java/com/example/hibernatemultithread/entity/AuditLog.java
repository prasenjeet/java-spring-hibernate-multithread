package com.example.hibernatemultithread.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Immutable audit log written from multiple threads concurrently.
 *
 * <p>Demonstrates that plain {@code INSERT}-only entities work well under high
 * concurrency because there are no conflicting UPDATEs or optimistic-lock
 * collisions — each thread writes its own rows.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String entityType;

    private Long entityId;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private String threadName;

    @Column(nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @PrePersist
    void prePersist() {
        occurredAt  = LocalDateTime.now();
        threadName  = Thread.currentThread().getName();
    }

    public AuditLog(String eventType, String entityType, Long entityId, String description) {
        this.eventType   = eventType;
        this.entityType  = entityType;
        this.entityId    = entityId;
        this.description = description;
    }
}
