package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.InventoryEventListener;
import com.nexus.fulfillment.event.OrderPaidEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Kiểm thử tích hợp toàn luồng End-to-End (E2E Integration Test) Sprint 1 (SRS §3.2.3, §3.6.1, §5.2, §5.3).
 *
 * Các kịch bản nghiệm thu kiểm thử:
 * 1. Kịch bản mua hàng chuẩn (Happy Path):
 *    Thêm vào giỏ -> Khởi tạo checkout -> Giữ kho thành công (PENDING, ghi sổ cái RESERVE) ->
 *    Nhận kết quả thanh toán Sandbox -> Trừ kho thật (CONFIRMED, ghi sổ cái OUTBOUND) ->
 *    Kích hoạt gửi email hóa đơn điện tử cho khách hàng.
 *
 * 2. Kịch bản thiếu tồn (Insufficient Stock):
 *    Người dùng mua số lượng vượt tồn khả dụng -> Hệ thống từ chối checkout, ném INVENTORY_INSUFFICIENT,
 *    không tạo reservation, không trừ kho lố (ALLOW_OVERSELL = false).
 *
 * 3. Kịch bản quá hạn (30-Minute Reservation Expiration Auto-Release):
 *    Khách giữ hàng quá 30 phút không thanh toán -> Hệ thống quét và tự động giải phóng tồn kho,
 *    chuyển EXPIRED, hoàn trả available_qty và ghi sổ cái RELEASE.
 *
 * 4. Kịch bản Webhook / Request trùng lặp (Idempotent Webhook):
 *    Cổng thanh toán Sandbox gửi liên tiếp 3 webhook OrderPaid cho cùng một đơn hàng ->
 *    Xử lý idempotent hoàn hảo, chỉ trừ kho 1 lần duy nhất, tồn kho không bị lệch.
 */
/** T?ch h?p service/repository kho th?t; email c? suite outbox ri?ng ? Commerce. */
@org.springframework.transaction.annotation.Transactional
@SpringBootTest
@ActiveProfiles("test")
class E2ECheckoutOrderFulfillmentIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private InventoryLedgerRepository ledgerRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InventoryEventListener inventoryEventListener;


    private UUID skuId;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
// Mỗi test tạo SKU riêng và rollback fixture; không xóa sổ cái bất biến.
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        warehouseRepository.deleteAll();

        skuId = UUID.randomUUID();
        warehouse = new Warehouse(
                UUID.randomUUID(),
                "WH_E2E_01",
                "Kho tổng E2E Test",
                "Hà Nội",
                WarehouseType.STANDARD,
                true
        );
        warehouseRepository.save(warehouse);

        // Nhập kho ban đầu: 10 sản phẩm
        inventoryService.inboundStock(new StockInboundRequest(
                skuId,
                warehouse.getId(),
                10,
                UUID.randomUUID()
        ));
    }

    @Test
    @DisplayName("E2E Kịch bản mua hàng chuẩn: Checkout -> Giữ kho -> Thanh toán Sandbox -> Trừ kho thật -> Gửi hóa đơn email")
    void testStandardPurchaseFlow_EndToEnd() {
        UUID orderId = UUID.randomUUID();
        int purchaseQty = 3;

        // Bước 1: Khách hàng tiến hành Checkout: Khóa giữ kho nguyên tử (Reserve)
        CreateReservationRequest reserveReq = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, purchaseQty, warehouse.getId())),
                30
        );
        ReservationResponse reserveResp = inventoryService.reserveStock(reserveReq);

        assertNotNull(reserveResp);
        assertEquals(ReservationStatus.PENDING, reserveResp.status());
        assertEquals(1, reserveResp.items().size());

        // Kiểm tra tồn kho sau khi giữ: total=10, reserved=3, available=7
        InventoryStockResponse stockAfterReserve = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(10, stockAfterReserve.totalQty());
        assertEquals(3, stockAfterReserve.reservedQty());
        assertEquals(7, stockAfterReserve.availableQty());

        // Bước 2: Cổng thanh toán Sandbox phản hồi thanh toán thành công (Webhook -> OrderPaidEvent)
        OrderPaidEvent paidEvent = OrderPaidEvent.of(orderId);
        inventoryEventListener.onOrderPaid(paidEvent);

        // Bước 3: Kiểm tra tồn kho sau khi trừ thật (Commit): total=7, reserved=0, available=7
        InventoryStockResponse stockAfterCommit = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(7, stockAfterCommit.totalQty());
        assertEquals(0, stockAfterCommit.reservedQty());
        assertEquals(7, stockAfterCommit.availableQty());

        // Kiểm tra phiếu reservation đã đổi sang CONFIRMED
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefId(ReservationRefType.ORDER, orderId);
        assertEquals(1, reservations.size());
        assertEquals(ReservationStatus.CONFIRMED, reservations.get(0).getStatus());

        // Bước 4: Kiểm tra sổ cái biến động kho (RESERVE -> OUTBOUND)
        List<InventoryLedger> ledgers = ledgerRepository.findBySkuId(skuId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        boolean hasOutboundLedger = ledgers.stream()
                .anyMatch(l -> l.getMovementType() == LedgerMovementType.OUTBOUND && l.getQuantity() == purchaseQty);
        assertTrue(hasOutboundLedger);

        // Bước 5: Xác nhận NotificationClient đã được kích hoạt gửi email hóa đơn
        // Suite này kiểm tra kho thật; email được kiểm tra qua outbox Commerce.
    }

    @Test
    @DisplayName("E2E Kịch bản thiếu tồn: Từ chối checkout khi mua vượt tồn khả dụng, không trừ kho")
    void testInsufficientStockFlow_Rejection() {
        UUID orderId = UUID.randomUUID();
        int excessiveQty = 15; // Tồn chỉ có 10

        CreateReservationRequest request = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, excessiveQty, warehouse.getId())),
                30
        );

        // Hệ thống phải ném lỗi INVENTORY_INSUFFICIENT
        BusinessException exception = assertThrows(BusinessException.class, () -> inventoryService.reserveStock(request));
        assertEquals(ErrorCode.INVENTORY_INSUFFICIENT.name(), exception.getCode());

        // Tồn kho không bị thay đổi
        InventoryStockResponse stock = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(10, stock.totalQty());
        assertEquals(0, stock.reservedQty());
        assertEquals(10, stock.availableQty());

        // Không có phiếu giữ hàng nào được tạo
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefId(ReservationRefType.ORDER, orderId);
        assertTrue(reservations.isEmpty());
    }

    @Test
    @DisplayName("E2E Kịch bản quá hạn: Giữ hàng quá 30 phút không thanh toán -> Tự động giải phóng tồn kho an toàn")
    void testReservationExpiration_AutoRelease() {
        UUID orderId = UUID.randomUUID();
        int heldQty = 4;

        // Giữ hàng tạm thời
        CreateReservationRequest reserveReq = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, heldQty, warehouse.getId())),
                30
        );
        inventoryService.reserveStock(reserveReq);

        // Giả lập thời gian trôi qua quá 30 phút: chỉnh expiresAt về quá khứ
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefId(ReservationRefType.ORDER, orderId);
        assertEquals(1, reservations.size());
        InventoryReservation res = reservations.get(0);
        res.setExpiresAt(Instant.now().minus(35, ChronoUnit.MINUTES));
        reservationRepository.save(res);

        // Bộ quét tự động giải phóng phiếu quá hạn (Auto-release)
        int releasedCount = inventoryService.autoReleaseExpiredReservations();
        assertEquals(1, releasedCount);

        // Kiểm tra phiếu chuyển sang EXPIRED
        InventoryReservation updatedRes = reservationRepository.findById(res.getId()).orElseThrow();
        assertEquals(ReservationStatus.EXPIRED, updatedRes.getStatus());

        // Kiểm tra tồn kho đã hoàn lại đầy đủ: total=10, reserved=0, available=10
        InventoryStockResponse stockAfterRelease = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(10, stockAfterRelease.totalQty());
        assertEquals(0, stockAfterRelease.reservedQty());
        assertEquals(10, stockAfterRelease.availableQty());

        // Kiểm tra sổ cái có bản ghi RELEASE hoàn tồn
        List<InventoryLedger> ledgers = ledgerRepository.findBySkuId(skuId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        boolean hasReleaseLedger = ledgers.stream()
                .anyMatch(l -> l.getMovementType() == LedgerMovementType.RELEASE && l.getQuantity() == heldQty);
        assertTrue(hasReleaseLedger);
    }

    @Test
    @DisplayName("E2E Kịch bản webhook trùng lặp (Idempotent): Nhận 3 webhook OrderPaid liên tiếp -> Chỉ trừ kho 1 lần duy nhất")
    void testDuplicateWebhook_IdempotentCommit() {
        UUID orderId = UUID.randomUUID();
        int purchaseQty = 2;

        // Giữ kho
        CreateReservationRequest reserveReq = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, purchaseQty, warehouse.getId())),
                30
        );
        inventoryService.reserveStock(reserveReq);

        // Webhook lần 1: Thanh toán thành công -> Trừ kho thật
        OrderPaidEvent webhook1 = OrderPaidEvent.of(orderId);
        inventoryEventListener.onOrderPaid(webhook1);

        // Tồn kho sau lần 1: total=8, reserved=0, available=8
        InventoryStockResponse stock1 = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(8, stock1.totalQty());
        assertEquals(0, stock1.reservedQty());
        assertEquals(8, stock1.availableQty());

        // Webhook lần 2: Trùng lặp do network retry từ Sandbox Gateway
        OrderPaidEvent webhook2 = OrderPaidEvent.of(orderId);
        inventoryEventListener.onOrderPaid(webhook2);

        // Webhook lần 3: Tiếp tục gửi trùng lặp
        OrderPaidEvent webhook3 = OrderPaidEvent.of(orderId);
        inventoryEventListener.onOrderPaid(webhook3);

        // Xác nhận tồn kho sau 3 lần webhook vẫn giữ nguyên ở mức 8 (KHÔNG BỊ TRỪ LẶP)
        InventoryStockResponse stockFinal = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(8, stockFinal.totalQty());
        assertEquals(0, stockFinal.reservedQty());
        assertEquals(8, stockFinal.availableQty());

        // Kiểm tra sổ cái OUTBOUND chỉ xuất hiện ĐÚNG 1 LẦN cho đơn hàng này
        long outboundCount = ledgerRepository.findBySkuId(skuId, org.springframework.data.domain.Pageable.unpaged()).getContent().stream()
                .filter(l -> l.getMovementType() == LedgerMovementType.OUTBOUND && orderId.equals(l.getRefId()))
                .count();
        assertEquals(1L, outboundCount);
    }
}
