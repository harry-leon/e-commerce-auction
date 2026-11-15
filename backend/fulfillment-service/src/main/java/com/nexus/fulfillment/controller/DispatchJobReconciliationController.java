package com.nexus.fulfillment.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.service.DispatchJobReconciliationService;
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

/** Admin đọc metadata và đóng job đã có shipment xác nhận; không requeue, gọi carrier hoặc thay trạng thái tiền/claim. */
@RestController
@RequestMapping("/api/v1/shipments/dispatch-jobs")
@PreAuthorize("hasRole('ADMIN')")
public class DispatchJobReconciliationController {
    private final DispatchJobReconciliationService jobs;
    public DispatchJobReconciliationController(DispatchJobReconciliationService jobs) { this.jobs = jobs; }

    /** Chỉ trả metadata an toàn để đối soát; không trả lease token hay snapshot người nhận. */
    @GetMapping("/{id}")
    @Operation(summary = "Admin đọc trạng thái job dispatch, 404 nếu không có")
    public ApiResponse<DispatchJobReconciliationService.Job> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        actor(jwt);
        return ApiResponse.ok(jobs.get(id));
    }

    /** 201 tạo audit/200 replay, 409 nếu chưa đủ chứng cứ; actor chỉ từ JWT, không từ body. */
    @PostMapping("/{id}/reconcile")
    @Operation(summary = "Đối soát job REVIEW với shipment/guard COMMITTED, không gửi lại carrier")
    public ResponseEntity<ApiResponse<DispatchJobReconciliationService.Resolution>> reconcile(@PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody DispatchJobReconciliationService.Command body) {
        var result = jobs.reconcile(id, actor(jwt), key, body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(ApiResponse.ok(result.resolution()));
    }

    /** Định danh người đối soát phải lấy từ token đã xác thực, không lấy actor trong JSON. */
    private UUID actor(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
        }
    }
}
