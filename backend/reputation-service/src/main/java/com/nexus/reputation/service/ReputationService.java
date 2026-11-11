package com.nexus.reputation.service;

import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Giao diện nghiệp vụ tra cứu điểm uy tín và xử phạt vi phạm thanh toán đấu giá (KH-S2-3). */
public interface ReputationService {

    /** Lấy điểm uy tín hiện tại và thống kê giao dịch của người dùng (trả mặc định 100 nếu chưa có bản ghi). */
    ReputationSummaryResponse getReputation(UUID userId);

    /** Lấy danh sách lịch sử biên bản phạt uy tín phân trang theo thời gian giảm dần. */
    Page<PenaltyResponse> getPenalties(UUID userId, Pageable pageable);

    /** Áp dụng trừ điểm uy tín idempotent theo khóa (userId, refType, refId) và phát ReputationPenaltyApplied. */
    PenaltyResponse applyPenalty(ApplyPenaltyRequest request);
}
