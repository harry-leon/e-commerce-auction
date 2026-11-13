package com.nexus.reputation.repository;

import com.nexus.reputation.entity.Rating;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Kho lưu trữ đánh giá giao dịch trên bảng ratings.
 * Chỉ tính điểm trung bình và danh sách công khai trên các bản ghi không bị gắn cờ khiếu nại (isDisputed = false).
 */
public interface RatingRepository extends JpaRepository<Rating, UUID> {

    boolean existsByOrderIdAndRaterId(UUID orderId, UUID raterId);

    Page<Rating> findByRateeIdAndDisputedFalseOrderByCreatedAtDesc(UUID rateeId, Pageable pageable);

    List<Rating> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    long countByRateeIdAndDisputedFalse(UUID rateeId);

    @Query("SELECT COALESCE(AVG(r.score), 0.0) FROM Rating r WHERE r.rateeId = :rateeId AND r.disputed = false")
    Double findAverageScoreByRateeId(@Param("rateeId") UUID rateeId);

    Page<Rating> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Rating> findByDisputedTrueOrderByCreatedAtDesc(Pageable pageable);

    Page<Rating> findByRateeIdOrderByCreatedAtDesc(UUID rateeId, Pageable pageable);

    Page<Rating> findByRateeIdAndDisputedTrueOrderByCreatedAtDesc(UUID rateeId, Pageable pageable);
}
