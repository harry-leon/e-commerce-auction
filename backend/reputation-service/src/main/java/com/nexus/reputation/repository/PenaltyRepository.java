package com.nexus.reputation.repository;

import com.nexus.reputation.entity.Penalty;
import com.nexus.reputation.enums.PenaltyRefType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Truy xuất biên bản phạt/điều chỉnh uy tín phục vụ kiểm tra idempotent và tra cứu kiểm toán Admin. */
@Repository
public interface PenaltyRepository extends JpaRepository<Penalty, UUID> {

    /** Kiểm tra xem sự kiện phạt (user, refType, refId) đã được ghi nhận trước đó hay chưa. */
    Optional<Penalty> findByUserIdAndRefTypeAndRefId(UUID userId, PenaltyRefType refType, UUID refId);

    /** Lấy danh sách lịch sử phạt của người dùng theo thời gian mới nhất. */
    Page<Penalty> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Danh sách toàn bộ lịch sử phạt/điều chỉnh cho Admin/Support sắp xếp mới nhất. */
    Page<Penalty> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Lọc lịch sử phạt/điều chỉnh theo loại tham chiếu (AUCTION/ORDER/RATING/MANUAL). */
    Page<Penalty> findByRefTypeOrderByCreatedAtDesc(PenaltyRefType refType, Pageable pageable);

    /** Lọc lịch sử phạt/điều chỉnh theo cả userId và refType. */
    Page<Penalty> findByUserIdAndRefTypeOrderByCreatedAtDesc(UUID userId, PenaltyRefType refType, Pageable pageable);
}
