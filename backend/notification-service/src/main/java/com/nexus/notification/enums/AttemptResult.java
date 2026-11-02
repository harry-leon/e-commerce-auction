package com.nexus.notification.enums;

/**
 * Kết quả của từng lần thử gửi thông báo tương ứng với ràng buộc chk_attempt_result trong CSDL.
 *
 * Chức năng:
 * - SUCCESS: Gửi thành công tới người nhận qua SMTP/Provider.
 * - RETRY: Gặp lỗi mạng hoặc sự cố tạm thời, sẽ được thử lại (tối đa NOTIFICATION_RETRY_LIMIT = 3 lần).
 * - FAILURE: Thất bại vĩnh viễn hoặc đã kiệt sức số lần thử lại tối đa.
 */
public enum AttemptResult {
    SUCCESS,
    FAILURE,
    RETRY
}
