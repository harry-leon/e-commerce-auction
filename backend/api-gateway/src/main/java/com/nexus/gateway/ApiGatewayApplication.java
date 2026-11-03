package com.nexus.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Khởi động Gateway reactive, nạp route và bảo mật JWT từ Spring. */
@SpringBootApplication
public class ApiGatewayApplication {

    /** Constructor cho Spring tạo cấu hình, không dùng như lớp tiện ích. */
    protected ApiGatewayApplication() {
    }

    /**
     * Khởi chạy server và các bean Gateway.
     *
     * @param args tham số cấu hình dòng lệnh của Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
