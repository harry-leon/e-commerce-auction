package com.nexus.catalog.repository;

import com.nexus.catalog.entity.CatalogOutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Truy vấn hàng đợi Outbox của Catalog Service theo trạng thái chờ phát và thời gian thử lại. */
public interface CatalogOutboxEventRepository extends JpaRepository<CatalogOutboxEvent, UUID> {
    List<CatalogOutboxEvent> findTop50ByDoneFalseAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant now);
}
