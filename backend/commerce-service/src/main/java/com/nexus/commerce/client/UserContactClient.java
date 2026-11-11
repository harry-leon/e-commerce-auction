package com.nexus.commerce.client;

import java.util.Optional;
import java.util.UUID;

/** Cổng tra cứu thông tin liên hệ tối thiểu (email, họ tên) của winner từ User Service để gửi email giao dịch. */
public interface UserContactClient {

    /** Ảnh chụp liên hệ người dùng tối thiểu, không chứa quyền hoặc thông tin nhạy cảm. */
    record UserContact(UUID id, String email, String fullName) {
    }

    /** Trả Optional rỗng nếu thiếu cấu hình hoặc User Service tạm thời lỗi, tránh chặn bàn giao đơn. */
    Optional<UserContact> findContact(UUID userId);
}
