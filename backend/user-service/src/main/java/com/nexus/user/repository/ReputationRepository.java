package com.nexus.user.repository;

import com.nexus.user.entity.Reputation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu uy tín (Reputation) từ cơ sở dữ liệu.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Repository
public interface ReputationRepository extends JpaRepository<Reputation, UUID> {
}
