package com.example.webhook.repo;

import com.example.webhook.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// repo/DeliveryAttemptRepository.java
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {
    List<DeliveryAttempt> findByEventIdOrderById(Long eventId);
}