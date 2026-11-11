package com.nexus.user.dto;

import java.util.UUID;

/**
 * Snapshot thông tin liên hệ tối thiểu phục vụ tra cứu nội bộ giữa các microservice.
 * Không chứa mật khẩu, giấy tờ tùy thân, vai trò hoặc thông tin nhạy cảm khác.
 */
public record UserContactResponse(
        UUID id,
        String email,
        String fullName
) {
}
