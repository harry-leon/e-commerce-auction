package com.nexus.reputation.repository;

import com.nexus.reputation.entity.Reputation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Truy xuất hồ sơ điểm uy tín với khóa bi quan khi trừ điểm đồng thời. */
@Repository
public interface ReputationRepository extends JpaRepository<Reputation, UUID> {

    /** Khóa dòng uy tín theo userId để tuần tự hóa các lần cộng/trừ điểm song song. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reputation r WHERE r.userId = :userId")
    Optional<Reputation> findByUserIdForUpdate(@Param("userId") UUID userId);
}
