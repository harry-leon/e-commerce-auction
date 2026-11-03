package com.nexus.gateway.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kiểm tra mật khẩu mới trước khi Gateway gọi Keycloak; trả tất cả điều kiện
 * thiếu theo
 * error.details. Giữ cùng policy với ChangePasswordRequest/Keycloak: 8–100 ký
 * tự, chữ hoa, số,
 * dấu/ký hiệu Unicode. FE nhận contract để hiển thị từng lỗi; module này không
 * thay đổi code FE.
 * Không áp dụng cho login, không trim hoặc đưa giá trị mật khẩu vào thông báo
 * lỗi.
 */
public final class PasswordPolicy {
    /** Độ dài tối thiểu thống nhất với IAM. */
    private static final int MIN_LENGTH = 8;

    /** Độ dài tối đa thống nhất với IAM. */
    private static final int MAX_LENGTH = 100;

    private PasswordPolicy() {
    }

    /**
     * Kiểm tra đăng ký trước mọi thao tác tạo IAM/profile.
     *
     * @param password mật khẩu gốc, không trim hoặc đưa vào thông báo lỗi
     * @return các điều kiện thiếu; rỗng khi đạt policy
     */
    public static List<Map<String, String>> errors(final String password) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (password == null || password.isBlank()) {
            return List.of(error("NOT_BLANK", "Mật khẩu không được để trống"));
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            errors.add(error("SIZE", "Mật khẩu phải từ 8 đến 100 ký tự"));
        }
        if (!password.matches("(?s).*\\p{Lu}.*")) {
            errors.add(error("UPPERCASE_REQUIRED",
                "Mật khẩu cần ít nhất 1 chữ in hoa"));
        }
        if (!password.matches("(?s).*\\p{Nd}.*")) {
            errors.add(error("DIGIT_REQUIRED",
                "Mật khẩu cần ít nhất 1 chữ số"));
        }
        if (!password.matches("(?s).*[\\p{P}\\p{S}].*")) {
            errors.add(
                    error(
                            "SYMBOL_REQUIRED",
                            "Mật khẩu cần ít nhất 1 ký tự đặc biệt (ví dụ @, !"
                                + ", #)"));
        }
        return errors;
    }

    /**
     * Tạo chi tiết validation, không chứa giá trị mật khẩu.
     *
     * @param code mã điều kiện chưa đạt
     * @param message hướng dẫn sửa dữ liệu
     * @return field password cùng mã và hướng dẫn cho FE
     */
    private static Map<String, String> error(final String code, final String
        message) {
        return Map.of("field", "password", "code", code, "message", message);
    }
}
