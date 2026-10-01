package com.nexus.auction.api;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoint mẫu. Xóa khi bắt đầu viết nghiệp vụ thật. */
@RestController
@RequestMapping("/api/v1")
public class PingController {

    private final String serviceName;

    public PingController(@Value("${spring.application.name}") String serviceName) {
        this.serviceName = serviceName;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("service", serviceName, "status", "UP", "time", Instant.now().toString());
    }

    @GetMapping("/whoami")
    public Map<String, String> whoami(Principal principal) {
        return Map.of("subject", principal.getName());
    }
}
