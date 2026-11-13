package com.nexus.fulfillment.dto.response;

/**
 * Thông tin hãng vận chuyển khả dụng trong hệ thống.
 */
public record CarrierInfoResponse(
        String code,
        String name,
        boolean active,
        boolean isDefault
) {
}
