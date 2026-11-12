package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.Carrier;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository truy vấn thông tin hãng vận chuyển đối tác (GHN, GHTK, ...).
 */
public interface CarrierRepository extends JpaRepository<Carrier, UUID> {

    Optional<Carrier> findByCodeIgnoreCase(String code);

    List<Carrier> findByIsActiveTrue();

    List<Carrier> findByCodeInAndIsActiveTrue(Collection<String> codes);
}
