package com.nexus.commerce.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Cấu hình merchant sandbox từ môi trường; thiếu credential thì chỉ vô hiệu hóa VNPAY, COD vẫn chạy. */
@Configuration
@ConfigurationProperties(prefix = "app.payment.vnpay")
@Getter
@Setter
public class VnpayProperties {
    /** Merchant API mặc định tắt; chỉ bật sau khi quyền refund/query sandbox được xác minh. Không tự bật worker. */
    private boolean merchantApiEnabled;
    /** Worker cần bật riêng và từng yêu cầu có duyệt dispatch; không gửi hàng loạt yêu cầu V13 cũ. */
    private boolean refundWorkerEnabled;
    /** IP egress merchant đã khai báo với provider; không suy đoán từ header request của người dùng. */
    private String merchantSourceIp = "";
    private String tmnCode = "";
    private String hashSecret = "";
    private String returnUrl = "";
    private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
}
