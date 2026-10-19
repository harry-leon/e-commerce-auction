package com.nexus.infrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;
/** Khởi động Config Server: đọc cấu hình native từ classpath và cung cấp cho client theo application/profile. */
@SpringBootApplication
@EnableConfigServer
public class InfrastructureApplication {
    /** Entry point Spring Boot; annotation Enable ở class kích hoạt chức năng hạ tầng tương ứng. */
    public static void main(String[] args) { SpringApplication.run(InfrastructureApplication.class, args); }
}
