package com.nexus.commerce.controller;

import com.nexus.commerce.dto.response.RefundCreditNote;
import com.nexus.commerce.service.RefundCreditNoteService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * API phát hành và tra cứu chứng từ điều chỉnh (credit note) cho refund đã xác minh.
 *
 * <p>Chức năng: Admin phát hành chứng từ sau khi refund có biên nhận hợp lệ; Buyer chủ đơn/Admin tải lại để đối soát.
 * Không sửa biên nhận thu gốc, không gọi provider và không thay đổi trạng thái tài chính.
 *
 * <p>Quyền: phát hành chỉ dành cho Admin; đọc cho Buyer chủ đơn hoặc Admin. Actor luôn lấy từ JWT subject,
 * không nhận từ body/query.
 *
 * <p>Lỗi: 401 token thiếu UUID, 400 đầu vào sai, 404 refund/chứng từ không tồn tại hoặc không thuộc quyền,
 * 409 chứng từ đã tồn tại khác nội dung hoặc refund chưa đủ điều kiện.
 */
@RestController
@RequiredArgsConstructor
public class RefundCreditNoteController {
    private final RefundCreditNoteService notes;

    /**
     * Phát hành credit note; lần đầu trả 201, replay cùng actor/key/nội dung trả 200 với bản ghi cũ.
     * Số chứng từ do server sinh từ refundId, không nhận từ client để tránh đánh số tùy ý.
     */
    @PostMapping("/api/v1/refunds/{id}/credit-note")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Phát hành chứng từ điều chỉnh cho refund đã xác minh",
            description = "Chỉ Admin; yêu cầu refund đã có biên nhận hợp lệ. Không sửa biên nhận gốc hoặc gọi provider.")
    public ResponseEntity<ApiResponse<RefundCreditNote>> issue(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Command body, org.springframework.security.core.Authentication authentication) {
        var result = notes.issue(id, actor(jwt), key, body.note(), authentication);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.note()));
    }

    /** Tra cứu credit note đã phát hành; Buyer khác nhận 404 để không dò được refund của người khác. */
    @GetMapping("/api/v1/refunds/{id}/credit-note")
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    @Operation(summary = "Tra cứu chứng từ điều chỉnh của refund",
            description = "Buyer chủ đơn hoặc Admin; 404 khi chưa phát hành hoặc không thuộc quyền.")
    public ApiResponse<RefundCreditNote> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
            org.springframework.security.core.Authentication authentication) {
        return ApiResponse.ok(notes.get(id, authentication));
    }

    /** Ghi chú nội bộ dùng cho audit đối soát; không chứa secret/token/PII. */
    public record Command(@NotBlank @Size(max = 500) String note) { }

    /** JWT phải mang subject UUID; token thiếu định danh không được tạo chứng từ. */
    private UUID actor(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
    }
}
