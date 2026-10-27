package com.example.webhook.repo;

import com.example.webhook.domain.Endpoint;   // <- this one
import org.springframework.data.jpa.repository.JpaRepository;

public interface EndpointRepository extends JpaRepository<Endpoint, Long> {}