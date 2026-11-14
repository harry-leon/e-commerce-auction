package com.nexus.commerce.controller.internal;

import com.nexus.commerce.service.ShippingEventInboxService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Nhận HTTP nội bộ từ outbox Fulfillment; ACK 204 chỉ sau transaction inbox/Order đã commit. */
@RestController
@RequiredArgsConstructor
public class ShippingEventInternalController {
    private final ShippingEventInboxService inbox;
    /** JWT phải đúng Fulfillment và privilege riêng; không mở endpoint nhận trực tiếp webhook carrier/browser. */
    @PostMapping("/api/v1/internal/commerce/shipping-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("T(com.nexus.common.security.ServiceIdentity).permits(authentication, 'nexus-fulfillment-service', 'COMMERCE.SHIPPING_EVENTS')")
    @Operation(summary = "Nhận sự kiện shipment từ Fulfillment, chống trùng và cập nhật Order theo trạng thái hợp lệ")
    public void receive(@Valid @RequestBody ShippingEventInboxService.Event event) { inbox.receive(event); }
}
