package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.service.ShipmentPreparationService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Preview nội bộ quản trị để đối chiếu kiện trước dispatch; request này không tạo vận đơn hoặc reserve kho. */
@RestController
@RequestMapping("/api/v1/shipments/preparation")
public class ShipmentPreparationController {
    private final ShipmentPreparationService preparation;

    public ShipmentPreparationController(ShipmentPreparationService preparation) { this.preparation = preparation; }

    /** Chỉ Admin đọc PII snapshot/chuẩn bị kiện; buyer không được chọn kho/cân nặng để điều khiển lệnh carrier. */
    @PostMapping("/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Preview dữ liệu tạo shipment VNPAY từ snapshot Commerce và thông tin kiện thực, chưa dispatch")
    public ApiResponse<CreateShipmentRequest> prepare(@PathVariable UUID orderId, @RequestBody ShipmentPreparationService.Parcel parcel) {
        return ApiResponse.ok(preparation.prepare(orderId, parcel));
    }
}
