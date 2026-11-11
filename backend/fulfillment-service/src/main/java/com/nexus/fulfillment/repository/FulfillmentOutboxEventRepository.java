package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.FulfillmentOutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository hàng đợi Outbox của Fulfillment Service. */
public interface FulfillmentOutboxEventRepository extends JpaRepository<FulfillmentOutboxEvent, UUID> {
    List<FulfillmentOutboxEvent> findTop50ByDoneFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant now);
}
