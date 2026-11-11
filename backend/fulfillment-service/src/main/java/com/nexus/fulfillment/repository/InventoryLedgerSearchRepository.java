package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.enums.LedgerMovementType;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện mở rộng tra cứu Sổ cái tồn kho động theo nhiều tiêu chí (SKU, phạm vi seller, kho, loại biến động, thời gian).
 * Tách thành fragment riêng để giữ InventoryLedgerRepository kế thừa Repository bất biến (không expose DELETE/UPDATE).
 */
public interface InventoryLedgerSearchRepository {

    /**
     * Bộ lọc truy vấn lịch sử biến động kho.
     * Nếu restrictToSkuIds = true và skuIds rỗng thì trả về trang rỗng để chặn rò rỉ dữ liệu ngoài seller.
     */
    record LedgerFilter(
            UUID skuId,
            Collection<UUID> skuIds,
            boolean restrictToSkuIds,
            UUID warehouseId,
            LedgerMovementType movementType,
            String refType,
            UUID refId,
            Instant fromTime,
            Instant toTime
    ) {
    }

    /**
     * Truy vấn sổ cái có phân trang theo điều kiện động bằng Criteria API.
     */
    Page<InventoryLedger> searchLedgerFiltered(LedgerFilter filter, Pageable pageable);
}
