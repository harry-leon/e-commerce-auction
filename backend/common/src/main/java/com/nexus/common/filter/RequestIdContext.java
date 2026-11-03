package com.nexus.common.filter;

import java.util.UUID;

/**
 * Quản lý định danh truy vết Request ID theo luồng thực thi (ThreadLocal).
 * Đảm bảo mọi điểm trong quá trình xử lý request đều có thể truy xuất Request ID
 * đồng bộ với header X-Request-Id của Gateway và Client.
 */
public final class RequestIdContext {

    private static final ThreadLocal<String> CURRENT_REQUEST_ID = new ThreadLocal<>();

    private RequestIdContext() {
        // Utility class không cho phép khởi tạo đối tượng trực tiếp
    }

    /**
     * Lấy Request ID hiện tại của thread. Nếu chưa được gán, tự động sinh mã mới dạng "req_xxxxxxxx".
     */
    public static String get() {
        String id = CURRENT_REQUEST_ID.get();
        if (id == null || id.isBlank()) {
            id = generate();
            CURRENT_REQUEST_ID.set(id);
        }
        return id;
    }

    /**
     * Thiết lập Request ID nhận được từ header X-Request-Id.
     */
    public static void set(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            CURRENT_REQUEST_ID.set(requestId);
        } else {
            CURRENT_REQUEST_ID.set(generate());
        }
    }

    /**
     * Xóa Request ID khỏi ThreadLocal khi kết thúc request để tránh rò rỉ bộ nhớ giữa các luồng tái sử dụng.
     */
    public static void clear() {
        CURRENT_REQUEST_ID.remove();
    }

    /**
     * Sinh requestId ngẫu nhiên dạng "req_xxxxxxxx" theo chuẩn quy định.
     */
    public static String generate() {
        return "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
