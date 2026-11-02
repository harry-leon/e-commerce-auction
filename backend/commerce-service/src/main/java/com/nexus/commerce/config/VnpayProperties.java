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
    private String tmnCode = "";
    private String hashSecret = "";
    private String returnUrl = "";
    private String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
}
