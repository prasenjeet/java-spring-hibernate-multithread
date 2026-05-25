package com.example.hibernatemultithread.repository;

import com.example.hibernatemultithread.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByStatus(Order.OrderStatus status);

    @Query("SELECT o FROM Order o WHERE o.status = 'PENDING' ORDER BY o.placedAt")
    List<Order> findPendingOrders();

    long countByStatus(Order.OrderStatus status);
}
