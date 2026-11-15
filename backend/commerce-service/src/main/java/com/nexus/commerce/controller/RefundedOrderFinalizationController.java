package com.nexus.commerce.controller;

import com.nexus.commerce.service.RefundedOrderFinalizationService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Thao tác Admin riêng để chốt trạng thái Order sau refund đã xác minh; không mở quyền hủy đơn đã giao. */
@RestController
public class RefundedOrderFinalizationController {
    private final RefundedOrderFinalizationService finalizations;
    public RefundedOrderFinalizationController(RefundedOrderFinalizationService finalizations) { this.finalizations = finalizations; }

    /** Key UUID bắt buộc; amount/status/actor lấy từ DB/JWT, 201 mới/200 replay; flag tắt trả 503. */
    @PostMapping("/api/v1/refunds/{id}/finalize-order")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Chốt Order REFUNDED sau hoàn toàn phần đã xác minh, trước dispatch",
            description = "Mặc định tắt; 409 thiếu bằng chứng/hold/claim/vận đơn, không hoàn kho hoặc gọi provider")
    public ResponseEntity<ApiResponse<RefundedOrderFinalizationService.Decision>> finalizeOrder(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") UUID key, @RequestBody Command body, Authentication authentication) {
        var result = finalizations.finalizeRefund(id, key, body.note(), authentication);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.ok(result.decision()));
    }
    /** Ghi chú audit nội bộ; không gửi secret/token hoặc PII vào ghi chú. */
    public record Command(String note) { }
}
