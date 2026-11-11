package com.nexus.fulfillment.service;

import com.nexus.fulfillment.entity.SellerSku;
import com.nexus.fulfillment.enums.SellerSkuStatus;
import com.nexus.fulfillment.repository.SellerSkuRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dịch vụ đăng ký bản ghi seller_skus trong transaction độc lập (REQUIRES_NEW) khi nhập kho lần đầu.
 * Mục đích: nếu hai luồng đồng thời cùng nhập kho lần đầu cho một skuId, vi phạm ràng buộc UNIQUE(sku_id)
 * chỉ làm rollback transaction con này mà không làm hỏng transaction ngoài của luồng còn lại.
 */
@Service
public class SellerSkuRegistrar {

    private final SellerSkuRepository sellerSkuRepository;

    public SellerSkuRegistrar(SellerSkuRepository sellerSkuRepository) {
        this.sellerSkuRepository = sellerSkuRepository;
    }

    /** Kết quả đăng ký: entity SellerSku và cờ đánh dấu đây có phải lần đầu cấp phát skuCode hay không. */
    public record RegistrationResult(SellerSku sellerSku, boolean newlyCreated) {
    }

    /**
     * Cố gắng tạo seller_skus mới với mã FSKU-XXXXXXXX từ sequence PostgreSQL nếu skuId chưa tồn tại.
     * Giao cho caller bắt DataIntegrityViolationException để đọc lại bản ghi vừa được luồng thắng tạo.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegistrationResult registerIfAbsent(UUID skuId, UUID sellerId) {
        var existing = sellerSkuRepository.findBySkuId(skuId);
        if (existing.isPresent()) {
            return new RegistrationResult(existing.get(), false);
        }
        long seqVal = sellerSkuRepository.nextSkuCodeSequenceValue();
        String generatedSkuCode = String.format("FSKU-%08d", seqVal);
        SellerSku created = new SellerSku(
                UUID.randomUUID(),
                sellerId,
                skuId,
                generatedSkuCode,
                SellerSkuStatus.ACTIVE);
        SellerSku saved = sellerSkuRepository.saveAndFlush(created);
        return new RegistrationResult(saved, true);
    }
}
