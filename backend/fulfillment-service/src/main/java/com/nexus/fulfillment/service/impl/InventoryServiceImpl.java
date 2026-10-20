package com.nexus.fulfillment.service.impl;

import com.nexus.fulfillment.dto.request.CommitReservationRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckItemRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.response.InventoryCheckItemResult;
import com.nexus.fulfillment.dto.response.InventoryCheckResponse;
import com.nexus.fulfillment.dto.response.ReservationItemResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuInventoryResponse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.exception.InsufficientStockException;
import com.nexus.fulfillment.exception.InvalidReservationStatusException;
import com.nexus.fulfillment.exception.ReservationExpiredException;
import com.nexus.fulfillment.exception.ReservationNotFoundException;
import com.nexus.fulfillment.exception.SkuNotFoundException;
import com.nexus.fulfillment.service.InventoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Triển khai dịch vụ quản lý tồn kho và phiếu giữ hàng phục vụ API Contract & DTOs [KH-S1-1].
 *
 * 1. Chức năng:
 * - Xử lý kiểm tra tồn kho khả dụng, tạo phiếu giữ hàng, commit xuất kho khi thanh toán thành công,
 *   và release hoàn kho khi hủy đơn hoặc hết hạn.
 * 2. Đầu vào và kết quả:
 * - Nhận các DTO Request (InventoryCheckRequest, CreateReservationRequest, Commit/Release...).
 * - Trả về các DTO Response tương ứng bọc dữ liệu trạng thái tồn kho và phiếu giữ hàng.
 * 3. Luồng xử lý:
 * - checkStock: Duyệt từng SKU → kiểm tra tồn khả dụng → trả về kết quả chi tiết từng item và cờ available tổng.
 * - createReservation: Kiểm tra tồn từng item → nếu thiếu thì ném InsufficientStockException (ERR_INSUFFICIENT_STOCK) →
 *   nếu đủ thì trừ tồn khả dụng (tăng reserved) và lưu Reservation trạng thái PENDING.
 * - commitReservation: Kiểm tra phiếu tồn tại → kiểm tra hạn expires_at → nếu hết hạn ném ReservationExpiredException
 *   (ERR_RESERVATION_EXPIRED) → chuyển trạng thái sang CONFIRMED và trừ tổng tồn (OUTBOUND).
 * - releaseReservation: Kiểm tra phiếu → hoàn trả số lượng đã giữ về tồn khả dụng → chuyển trạng thái sang RELEASED.
 * 4. Quy tắc và lý do:
 * - Tuân thủ SRS §3.6.1 & §2.8: Giữ hàng tạm thời có hạn (mặc định 30 phút theo INVENTORY_RESERVATION_TIMEOUT_MINUTES).
 * - Tuyệt đối không cho phép bán vượt tồn (ALLOW_OVERSELL = false, BR-CFG-32).
 * 5. Lỗi và giới hạn:
 * - Bản triển khai này quản lý dữ liệu trong bộ nhớ (in-memory concurrent store) phục vụ hoàn thiện hợp đồng API (API Contract Draft)
 *   và kiểm thử tích hợp DTO. Khi nối DB PostgreSQL, tầng này sẽ gọi JPA Repository trong Transaction (@Transactional).
 */
@Service
public class InventoryServiceImpl implements InventoryService {

    private static final Logger LOG = LoggerFactory.getLogger(InventoryServiceImpl.class);

    /** Thời gian hiệu lực mặc định của phiếu giữ hàng: 30 phút theo cấu hình SRS INVENTORY_RESERVATION_TIMEOUT_MINUTES. */
    private static final Duration DEFAULT_RESERVATION_TIMEOUT = Duration.ofMinutes(30);

    /** Kho hàng mặc định giả lập khi request không truyền warehouseId. */
    private static final UUID DEFAULT_WAREHOUSE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Lưu trữ phiếu giữ hàng trong bộ nhớ cho việc xác thực contract. */
    private final Map<UUID, ReservationRecord> reservationStore = new ConcurrentHashMap<>();

    /** Lưu trữ số lượng tồn kho theo cặp (skuId, warehouseId) trong bộ nhớ. */
    private final Map<String, StockRecord> stockStore = new ConcurrentHashMap<>();

    public InventoryServiceImpl() {
        // Khởi tạo một số dữ liệu mẫu tồn kho phục vụ kiểm thử và chạy thử nghiệm
        seedSampleInventory();
    }

    private void seedSampleInventory() {
        UUID sampleSku1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID sampleSku2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        stockStore.put(key(sampleSku1, DEFAULT_WAREHOUSE_ID), new StockRecord(sampleSku1, DEFAULT_WAREHOUSE_ID, 100, 0, 0));
        stockStore.put(key(sampleSku2, DEFAULT_WAREHOUSE_ID), new StockRecord(sampleSku2, DEFAULT_WAREHOUSE_ID, 50, 0, 0));
    }

    private String key(UUID skuId, UUID warehouseId) {
        return skuId + "_" + (warehouseId != null ? warehouseId : DEFAULT_WAREHOUSE_ID);
    }

    @Override
    public InventoryCheckResponse checkStock(InventoryCheckRequest request) {
        LOG.info("Bắt đầu kiểm tra tồn kho cho {} items", request.items().size());
        List<InventoryCheckItemResult> results = new ArrayList<>();

        for (InventoryCheckItemRequest item : request.items()) {
            UUID warehouseId = item.warehouseId() != null ? item.warehouseId() : DEFAULT_WAREHOUSE_ID;
            StockRecord stock = stockStore.get(key(item.skuId(), warehouseId));

            int availableQty;
            if (stock != null) {
                availableQty = stock.getAvailableQty();
            } else {
                // Nếu SKU chưa được cấu hình trong kho, mặc định coi như không có tồn kho
                availableQty = 0;
            }

            boolean isAvailable = availableQty >= item.quantity();
            results.add(new InventoryCheckItemResult(
                    item.skuId(),
                    warehouseId,
                    item.quantity(),
                    availableQty,
                    isAvailable
            ));
        }

        return InventoryCheckResponse.of(results);
    }

    @Override
    public ReservationResponse createReservation(CreateReservationRequest request) {
        LOG.info("Tạo phiếu giữ hàng cho đơn hàng/ref: {}, số lượng mặt hàng: {}", request.orderId(), request.items().size());

        Instant expiresAt = request.expiresAt() != null
                ? request.expiresAt()
                : Instant.now().plus(DEFAULT_RESERVATION_TIMEOUT);

        List<ReservationItemResponse> itemResponses = new ArrayList<>();
        UUID reservationId = UUID.randomUUID();

        // Bước 1: Kiểm tra tính khả dụng của toàn bộ mặt hàng trước khi giữ hàng
        for (ReservationItemRequest item : request.items()) {
            UUID warehouseId = item.warehouseId() != null ? item.warehouseId() : DEFAULT_WAREHOUSE_ID;
            StockRecord stock = stockStore.get(key(item.skuId(), warehouseId));

            if (stock == null) {
                LOG.warn("Không tìm thấy SKU {} trong kho {}", item.skuId(), warehouseId);
                throw new SkuNotFoundException(item.skuId());
            }

            if (stock.getAvailableQty() < item.quantity()) {
                LOG.warn("Không đủ tồn kho khả dụng cho SKU {}: yêu cầu={}, khả dụng={}",
                        item.skuId(), item.quantity(), stock.getAvailableQty());
                throw new InsufficientStockException(item.skuId(), item.quantity(), stock.getAvailableQty());
            }
        }

        // Bước 2: Tăng reserved_qty và tạo bản ghi giữ hàng
        for (ReservationItemRequest item : request.items()) {
            UUID warehouseId = item.warehouseId() != null ? item.warehouseId() : DEFAULT_WAREHOUSE_ID;
            StockRecord stock = stockStore.get(key(item.skuId(), warehouseId));
            stock.reservedQty += item.quantity();

            UUID reservationItemId = UUID.randomUUID();
            itemResponses.add(new ReservationItemResponse(
                    reservationItemId,
                    item.skuId(),
                    warehouseId,
                    item.quantity()
            ));
        }

        ReservationRefType refType = request.refType() != null ? request.refType() : ReservationRefType.ORDER;
        ReservationRecord record = new ReservationRecord(
                reservationId,
                request.orderId(),
                refType,
                ReservationStatus.PENDING,
                Instant.now(),
                expiresAt,
                itemResponses
        );

        reservationStore.put(reservationId, record);
        LOG.info("Đã tạo thành công phiếu giữ hàng {} ở trạng thái PENDING", reservationId);

        return toResponse(record);
    }

    @Override
    public ReservationResponse getReservation(UUID reservationId) {
        ReservationRecord record = reservationStore.get(reservationId);
        if (record == null) {
            throw new ReservationNotFoundException(reservationId);
        }
        return toResponse(record);
    }

    @Override
    public ReservationResponse commitReservation(UUID reservationId, CommitReservationRequest request) {
        LOG.info("Bắt đầu xác nhận xuất kho (commit) cho phiếu giữ hàng {}", reservationId);
        ReservationRecord record = reservationStore.get(reservationId);
        if (record == null) {
            throw new ReservationNotFoundException(reservationId);
        }

        if (record.status == ReservationStatus.CONFIRMED) {
            // Idempotent: nếu đã xác nhận xuất kho rồi thì trả về thông tin thành công hiện tại
            LOG.info("Phiếu giữ hàng {} đã được commit trước đó (idempotent)", reservationId);
            return toResponse(record);
        }

        // Kiểm tra hạn giữ hàng
        if (record.expiresAt.isBefore(Instant.now())) {
            record.status = ReservationStatus.EXPIRED;
            LOG.warn("Phiếu giữ hàng {} đã hết hạn tại thời điểm {}", reservationId, record.expiresAt);
            throw new ReservationExpiredException(reservationId, record.expiresAt);
        }

        if (record.status != ReservationStatus.PENDING) {
            throw new InvalidReservationStatusException(reservationId, record.status, "COMMIT");
        }

        // Giảm total_qty và giảm reserved_qty (hàng đã xuất ra khỏi kho)
        for (ReservationItemResponse item : record.items) {
            StockRecord stock = stockStore.get(key(item.skuId(), item.warehouseId()));
            if (stock != null) {
                stock.totalQty -= item.quantity();
                stock.reservedQty -= item.quantity();
            }
        }

        record.status = ReservationStatus.CONFIRMED;
        LOG.info("Xác nhận xuất kho thành công cho phiếu giữ hàng {}", reservationId);
        return toResponse(record);
    }

    @Override
    public ReservationResponse releaseReservation(UUID reservationId, ReleaseReservationRequest request) {
        LOG.info("Bắt đầu hủy giữ hàng (release) cho phiếu {}", reservationId);
        ReservationRecord record = reservationStore.get(reservationId);
        if (record == null) {
            throw new ReservationNotFoundException(reservationId);
        }

        if (record.status == ReservationStatus.RELEASED) {
            LOG.info("Phiếu giữ hàng {} đã được release trước đó (idempotent)", reservationId);
            return toResponse(record);
        }

        if (record.status == ReservationStatus.CONFIRMED) {
            throw new InvalidReservationStatusException(reservationId, record.status, "RELEASE");
        }

        // Trả lại tồn khả dụng: giảm reserved_qty
        for (ReservationItemResponse item : record.items) {
            StockRecord stock = stockStore.get(key(item.skuId(), item.warehouseId()));
            if (stock != null) {
                stock.reservedQty = Math.max(0, stock.reservedQty - item.quantity());
            }
        }

        record.status = ReservationStatus.RELEASED;
        LOG.info("Đã hoàn tất giải phóng tồn kho cho phiếu giữ hàng {}", reservationId);
        return toResponse(record);
    }

    @Override
    public SkuInventoryResponse getSkuInventory(UUID skuId, UUID warehouseId) {
        UUID effectiveWarehouseId = warehouseId != null ? warehouseId : DEFAULT_WAREHOUSE_ID;
        StockRecord stock = stockStore.get(key(skuId, effectiveWarehouseId));

        if (stock == null) {
            throw new SkuNotFoundException(skuId);
        }

        return new SkuInventoryResponse(
                stock.skuId,
                stock.warehouseId,
                stock.totalQty,
                stock.reservedQty,
                stock.unavailableQty,
                stock.getAvailableQty()
        );
    }

    private ReservationResponse toResponse(ReservationRecord record) {
        return new ReservationResponse(
                record.reservationId,
                record.orderId,
                record.refType,
                record.status,
                record.createdAt,
                record.expiresAt,
                record.items
        );
    }

    /** Bản ghi trạng thái phiếu giữ hàng trong bộ nhớ. */
    private static class ReservationRecord {
        private final UUID reservationId;
        private final UUID orderId;
        private final ReservationRefType refType;
        private ReservationStatus status;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final List<ReservationItemResponse> items;

        ReservationRecord(UUID reservationId, UUID orderId, ReservationRefType refType,
                          ReservationStatus status, Instant createdAt, Instant expiresAt,
                          List<ReservationItemResponse> items) {
            this.reservationId = reservationId;
            this.orderId = orderId;
            this.refType = refType;
            this.status = status;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.items = items;
        }
    }

    /** Bản ghi số lượng tồn kho theo SKU và kho. */
    private static class StockRecord {
        private final UUID skuId;
        private final UUID warehouseId;
        private int totalQty;
        private int reservedQty;
        private int unavailableQty;

        StockRecord(UUID skuId, UUID warehouseId, int totalQty, int reservedQty, int unavailableQty) {
            this.skuId = skuId;
            this.warehouseId = warehouseId;
            this.totalQty = totalQty;
            this.reservedQty = reservedQty;
            this.unavailableQty = unavailableQty;
        }

        int getAvailableQty() {
            return Math.max(0, totalQty - reservedQty - unavailableQty);
        }
    }
}
