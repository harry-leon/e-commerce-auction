package com.nexus.fulfillment.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.service.ShipmentParcelService;
import com.nexus.fulfillment.service.ShipmentPreparationService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin lưu/đọc kiện có audit; độc lập preview/dispatch, actor chỉ lấy từ JWT đã xác thực. */
@RestController
@RequestMapping("/api/v1/shipments/preparation/{orderId}/parcels")
@PreAuthorize("hasRole('ADMIN')")
public class ShipmentParcelController {
    private final ShipmentParcelService parcels;
    public ShipmentParcelController(ShipmentParcelService parcels) { this.parcels = parcels; }

    /** Mới trả 201, replay trả 200; key UUID bắt buộc. Không gửi carrier hoặc nhận quyền từ body. */
    @PostMapping
    @Operation(summary = "Admin lưu kiện thực có audit/idempotency, chưa dispatch",
            description = "400 dữ liệu/key sai; 401 subject sai; 403 sai role; 409 Order/key/kho/carrier không phù hợp.")
    public ResponseEntity<ApiResponse<ShipmentParcelService.Registration>> register(@PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ShipmentPreparationService.Parcel body) {
        var result = parcels.register(orderId, actor(jwt), key, body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.ok(result.registration()));
    }

    /** Tra cứu khai báo, không xác minh Order/tài chính hoặc ghép PII từ Commerce trong request này. */
    @GetMapping
    @Operation(summary = "Admin đọc kiện đã khai báo; 404 nếu chưa có, chưa phải quyền dispatch")
    public ApiResponse<ShipmentParcelService.Registration> get(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        actor(jwt);
        return ApiResponse.ok(parcels.get(orderId));
    }

    /** Thiếu subject UUID phải dừng trước truy vấn DB để không ghi actor rỗng hoặc actor từ client. */
    private UUID actor(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
    }
}
