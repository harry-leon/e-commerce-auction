package com.nexus.fulfillment.service;

import com.nexus.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Đóng đường tạo shipment legacy khi worker claim/preflight bật hoặc vận hành yêu cầu chế độ controlled trên mọi instance.
 * Không gọi Commerce/carrier/DB. Không cho override required=false mở đường legacy trong lúc worker đang bật.
 */
@Component
public class ClaimedDispatchIngressPolicy {
    private final boolean required;

    public ClaimedDispatchIngressPolicy(@Value("${app.shipping.dispatch-worker-enabled:false}") boolean workerEnabled,
            @Value("${app.shipping.claimed-dispatch-required:false}") boolean required) {
        this.required = workerEnabled || required;
    }

    /** Legacy create/OrderPaid phải dừng trước repository hoặc carrier; API GET/prepare/recovery và worker riêng không bị chặn. */
    public void requireLegacyAllowed() {
        if (required) {
            throw new ApiException("CLAIMED_DISPATCH_REQUIRED", HttpStatus.CONFLICT,
                    "Tạo vận đơn cần kiện đã đăng ký và worker claim/preflight; dùng API tra cứu cho vận đơn đã có", null);
        }
    }
}
