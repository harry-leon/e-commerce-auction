package com.nexus.reputation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lớp khởi động Reputation Service.
 * Chịu trách nhiệm quản lý đánh giá người dùng, điểm phạt và điểm uy tín trong hệ sinh thái Nexus.
 *

 */
@SpringBootApplication
public class ReputationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReputationServiceApplication.class, args);
    }
}
