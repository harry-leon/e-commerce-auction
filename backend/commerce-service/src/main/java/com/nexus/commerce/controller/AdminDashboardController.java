package com.nexus.commerce.controller;

import com.nexus.commerce.dto.response.AdminDashboardSummaryResponse;
import com.nexus.commerce.service.AdminDashboardService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint báo cáo Admin đọc tổng hợp từ Commerce DB; quyền Admin được kiểm tra tại service đích. */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminDashboardService dashboard;

    /** GMV là tổng total của đơn PAID đến COMPLETED; dữ liệu ngày dùng UTC và bao gồm 7 ngày gần nhất. */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Thống kê tổng hợp đơn hàng cho Admin")
    public ApiResponse<AdminDashboardSummaryResponse> summary() {
        return ApiResponse.ok(dashboard.summary());
    }
}
