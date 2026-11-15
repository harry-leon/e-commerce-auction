package com.nexus.commerce.controller;

import com.nexus.commerce.dto.response.RefundReceipt;
import com.nexus.commerce.service.RefundReceiptService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** API tải biên nhận refund đã được đối soát; giữ DTO riêng không làm thay đổi biên nhận thu gốc. */
@RestController
@RequiredArgsConstructor
public class RefundReceiptController {
    private final RefundReceiptService receipts;

    /** Trả JSON attachment/no-store với tên từ UUID; lỗi giữ ApiError và không trả file thành công giả. */
    @GetMapping(value = "/api/v1/refunds/{id}/receipt", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Operation(summary = "Tải biên nhận refund nội bộ liên kết khoản thu gốc",
            description = "Buyer chủ đơn/Admin; không phải hóa đơn thuế. 404 sai chủ/không tồn tại; 409 thiếu bằng chứng hoặc có mâu thuẫn.")
    public ResponseEntity<RefundReceipt> download(@PathVariable UUID id, Authentication authentication) {
        var receipt = receipts.download(id, authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"refund-receipt-" + id + ".json\"")
                .header("X-Content-Type-Options", "nosniff").body(receipt);
    }
}
