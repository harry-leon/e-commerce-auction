package com.nexus.commerce.controller;

import com.nexus.commerce.dto.response.PaymentResponse;
import com.nexus.commerce.dto.response.VnpayIpnResponse;
import com.nexus.commerce.service.PaymentService;
import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Buyer API dùng JWT/envelope; chỉ IPN công khai và dùng chữ ký VNPAY, không mở route cập nhật trạng thái từ browser. */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Sandbox Payments")
@Slf4j
public class PaymentController {
    private final PaymentService payments;
    /** Tạo URL sandbox từ đơn chờ thanh toán đã lưu; amount không nhận từ client. */
    @PostMapping
    @PreAuthorize("hasAuthority('ORDER.CREATE')")
    @Operation(summary = "Khởi tạo hoặc lấy lại URL thanh toán VNPAY sandbox")
    public ApiResponse<PaymentResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartRequest body,
                                               HttpServletRequest request) {
        return ApiResponse.ok(payments.start(buyer(jwt), body.orderId(), request.getRemoteAddr()));
    }

    /** Poll kết quả đã commit bởi IPN; quyền sở hữu được kiểm tra tại service. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Đọc trạng thái thanh toán của buyer hiện tại")
    public ApiResponse<PaymentResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(payments.get(buyer(jwt), id));
    }

    /** IPN GET theo PAY 2.1.0: chặn tham số lặp, không bọc RspCode/Message trong ApiResponse. */
    @GetMapping("/vnpay/ipn")
    @Operation(summary = "Nhận IPN VNPAY, xác minh HMAC trước khi xử lý")
    public VnpayIpnResponse ipn(@RequestParam MultiValueMap<String, String> input) {
        if (input.size() > 40 || input.entrySet().stream().anyMatch(e -> e.getValue().size() != 1
                || e.getKey().length() > 100 || e.getValue().getFirst().length() > 2000)) {
            return new VnpayIpnResponse("97", "Invalid parameters");
        }
        Map<String, String> params = new HashMap<>();
        input.forEach((key, values) -> params.put(key, values.getFirst()));
        try { return payments.ipn(params); }
        catch (RuntimeException error) {
            // Service proxy đã rollback trước khi trả 99; VNPAY có thể retry, không ACK thành công khi DB lỗi.
            log.error("Không thể commit IPN VNPAY; cần retry/đối soát", error);
            return new VnpayIpnResponse("99", "Unable to process callback");
        }
    }

    private UUID buyer(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT không có user UUID hợp lệ", null);
        }
    }
    /** Chỉ chấp nhận orderId, không có trường amount hay trạng thái thanh toán do client khai báo. */
    public record StartRequest(@NotNull UUID orderId) { }
}
