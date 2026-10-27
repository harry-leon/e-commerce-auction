package com.nexus.gateway.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kiểm tra mật khẩu mới trước khi Gateway gọi Keycloak; trả tất cả điều kiện thiếu theo error.details.
 * Giữ cùng policy với ChangePasswordRequest/Keycloak: 8–100 ký tự, chữ hoa, số, dấu/ký hiệu Unicode.
 * FE nhận contract để hiển thị từng lỗi; module này không thay đổi code FE.
 * Không áp dụng cho login, không trim hoặc đưa giá trị mật khẩu vào thông báo lỗi.
 */
public final class PasswordPolicy {
    private PasswordPolicy() { }

    /** Tạo lỗi cho field password của đăng ký; request không đạt bị chặn trước mọi thao tác tạo IAM/profile. */
    public static List<Map<String, String>> errors(String password) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (password == null || password.isBlank()) {
            return List.of(error("NOT_BLANK", "Mật khẩu không được để trống"));
        }
        if (password.length() < 8 || password.length() > 100) {
            errors.add(error("SIZE", "Mật khẩu phải từ 8 đến 100 ký tự"));
        }
        if (!password.matches("(?s).*\\p{Lu}.*")) {
            errors.add(error("UPPERCASE_REQUIRED", "Mật khẩu cần ít nhất 1 chữ in hoa"));
        }
        if (!password.matches("(?s).*\\p{Nd}.*")) {
            errors.add(error("DIGIT_REQUIRED", "Mật khẩu cần ít nhất 1 chữ số"));
        }
        if (!password.matches("(?s).*[\\p{P}\\p{S}].*")) {
            errors.add(error("SYMBOL_REQUIRED", "Mật khẩu cần ít nhất 1 ký tự đặc biệt (ví dụ @, !, #)"));
        }
        return errors;
    }

    /** Chi tiết validation chỉ chứa tên trường, mã và hướng dẫn; không chứa dữ liệu nhạy cảm. */
    private static Map<String, String> error(String code, String message) {
        return Map.of("field", "password", "code", code, "message", message);
    }
}
