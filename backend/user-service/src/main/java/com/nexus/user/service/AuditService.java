package com.nexus.user.service;

import com.nexus.user.entity.AuditLog;
import com.nexus.user.repository.AuditLogRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service ghi nhận và xử lý nhật ký kiểm toán (Audit Log) cho các hoạt động IAM.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    /**
     * Khởi tạo AuditService với AuditLogRepository.
     *
     * @param auditLogRepository Repository lưu trữ bản ghi nhật ký
     */
    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Ghi nhận một sự kiện kiểm toán mới vào hệ thống.
     *
     * @param userId      Mã người dùng liên quan (nếu có)
     * @param action      Mã hành động (ví dụ: LOGIN, REGISTER, RESET_PASSWORD)
     * @param description Mô tả chi tiết hành vi
     * @param ipAddress   Địa chỉ IP thực hiện yêu cầu
     * @param userAgent   Trình duyệt hoặc client thực hiện
     * @param status      Kết quả hành động (SUCCESS, FAILED)
     */
    public void record(UUID userId, String action, String description,
                       String ipAddress, String userAgent, String status) {
        try {
            AuditLog auditLog = new AuditLog(
                    UUID.randomUUID(),
                    userId,
                    action,
                    description,
                    ipAddress,
                    userAgent,
                    status
            );
            auditLogRepository.save(auditLog);
            log.info("[AUDIT] Action: {} | User: {} | Status: {} | IP: {}", action, userId, status, ipAddress);
        } catch (Exception e) {
            log.error("Không thể lưu bản ghi audit log: {}", e.getMessage(), e);
        }
    }
}
