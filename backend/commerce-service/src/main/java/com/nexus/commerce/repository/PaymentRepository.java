package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Mọi thao tác payment được service tuần tự hóa bằng khóa Order; index DB chặn trùng attempt và provider transaction. */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByOrderIdAndProvider(UUID orderId, String provider);
    boolean existsByProviderAndProviderRefAndStatus(String provider, String reference, String status);
}
