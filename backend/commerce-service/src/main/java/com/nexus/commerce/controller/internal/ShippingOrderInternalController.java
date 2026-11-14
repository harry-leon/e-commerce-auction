package com.nexus.commerce.controller.internal;

import com.nexus.commerce.service.ShippingOrderSnapshotService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fulfillment đọc snapshot người nhận từ Commerce qua API; không truy cập DB chéo hoặc tạo vận đơn tại đây. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/commerce/shipping-orders")
public class ShippingOrderInternalController {
    private final ShippingOrderSnapshotService snapshots;

    /** Chỉ service account Fulfillment có privilege riêng được nhận PII; không trả email, token hoặc provider evidence. */
    @GetMapping("/{id}")
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_READ')")
    @Operation(summary = "Fulfillment đọc snapshot đơn hàng phục vụ chuẩn bị giao hàng, không phải lệnh tạo vận đơn")
    public ApiResponse<ShippingOrderSnapshotService.Snapshot> get(@PathVariable UUID id) {
        return ApiResponse.ok(snapshots.get(id));
    }
}
