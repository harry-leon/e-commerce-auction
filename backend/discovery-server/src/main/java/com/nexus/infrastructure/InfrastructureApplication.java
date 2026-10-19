package com.nexus.infrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;
/** Khởi động Eureka Server: nhận đăng ký/heartbeat và trả registry để Gateway tìm instance của từng service. */
@SpringBootApplication
@EnableEurekaServer
public class InfrastructureApplication {
    /** Entry point Spring Boot; annotation Enable ở class kích hoạt chức năng hạ tầng tương ứng. */
    public static void main(String[] args) { SpringApplication.run(InfrastructureApplication.class, args); }
}
