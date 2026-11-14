package com.nexus.commerce.controller;

import com.nexus.commerce.dto.response.PaymentReceipt;
import com.nexus.commerce.service.PaymentReceiptService;
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

/** API tải JSON biên nhận nội bộ; service kiểm tra JWT/ownership và bằng chứng thu tiền trước khi xuất. */
@RestController
@RequiredArgsConstructor
public class PaymentReceiptController {
    private final PaymentReceiptService receipts;

    /**
     * Trả file JSON riêng tư, không cache; tên file chỉ dùng UUID đã parse để tránh chèn header.
     * Thành công trả document trực tiếp; lỗi giữ ApiError chung (401/403/404/409), không tạo chứng từ mới.
     */
    @GetMapping(value = "/api/v1/payments/{id}/receipt", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Operation(summary = "Tải biên nhận nội bộ của Payment VNPAY SUCCESS",
            description = "Buyer sở hữu đơn/Admin; JSON snapshot, không phải hóa đơn thuế. 404 sai chủ đơn/không tồn tại; 409 chưa đủ bằng chứng.")
    public ResponseEntity<PaymentReceipt> download(@PathVariable UUID id, Authentication authentication) {
        var receipt = receipts.download(id, authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payment-receipt-" + id + ".json\"")
                .header("X-Content-Type-Options", "nosniff").body(receipt);
    }
}
