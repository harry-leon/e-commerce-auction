package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
import com.nexus.fulfillment.dto.request.BatchStockRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.CreateWarehouseRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryReconciliationResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.WarehouseResponse;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Kiểm thử tích hợp H2/JPA thật cho vòng đời kho hàng, nhập kho/điều chỉnh idempotent,
 * truy vấn Criteria sổ cái (searchLedgerFiltered), điều phối thất bại/hết hạn thanh toán
 * và đối soát số dư tồn kho khớp 100% với sổ cái bất biến.
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryLifecycleAndReconciliationE2ETest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InventoryLedgerRepository ledgerRepository;

    @MockitoBean
    private CatalogClient catalogClient;

    @BeforeEach
    void stubCatalogOwnership() {
        // Giả lập phản hồi Catalog khi nội bộ nhập kho để đăng ký seller_skus và FSKU code lần đầu.
        UUID defaultSellerId = UUID.randomUUID();
        when(catalogClient.findSkuById(any(UUID.class))).thenAnswer(inv -> Optional.of(
                new CatalogClient.SkuOwnershipInfo(
                        inv.getArgument(0),
                        UUID.randomUUID(),
                        "E2E Lifecycle Product",
                        new BigDecimal("100000"),
                        new BigDecimal("90000"),
                        defaultSellerId,
                        true
                )
        ));
    }

    @AfterEach
    void cleanupSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Chuỗi nghiệp vụ đầy đủ khớp sổ cái, không âm kho, không xuất hai lần và xử lý đúng thanh toán lỗi/hết hạn")
    void fullLifecycle_MatchesLedger_NoNegative_NoDoubleOutbound() {
        Warehouse warehouse = warehouseRepository.save(new Warehouse(
                UUID.randomUUID(),
                "WH_E2E_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                "Kho E2E",
                "Quận 7, TP.HCM",
                WarehouseType.STANDARD,
                true
        ));
        UUID skuId = UUID.randomUUID();
        UUID skuEmpty = UUID.randomUUID();

        // 1. Nhập kho 20 sản phẩm + gửi lặp lại cùng Idempotency-Key -> chỉ cộng 1 lần thành 20
        String inboundKey = "e2e-inb-" + UUID.randomUUID();
        StockInboundRequest inboundReq = new StockInboundRequest(skuId, null, warehouse.getId(), 20, null, "Nhap dau ky", null);
        InventoryStockResponse afterInb1 = inventoryService.inboundStock(inboundReq, inboundKey);
        InventoryStockResponse afterInb2 = inventoryService.inboundStock(inboundReq, inboundKey);
        assertEquals(20, afterInb1.totalQty());
        assertEquals(20, afterInb2.totalQty());
        assertEquals(20, afterInb2.availableQty());

        // 2. Đơn A giữ 4 + retry reserve -> vẫn chỉ giữ 4 (available = 16)
        UUID orderA = UUID.randomUUID();
        CreateReservationRequest reserveOrderA = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderA,
                List.of(new ReservationItemRequest(skuId, 4, warehouse.getId())),
                30,
                null
        );
        ReservationResponse resA1 = inventoryService.reserveStock(reserveOrderA);
        ReservationResponse resA2 = inventoryService.reserveStock(reserveOrderA);
        assertEquals(ReservationStatus.PENDING, resA1.status());
        assertEquals(resA1.items().get(0).reservationId(), resA2.items().get(0).reservationId());

        // Thanh toán thành công đơn A -> commit lặp 2 lần + release sau commit -> chỉ xuất 1 lần (total=16, reserved=0)
        inventoryService.commitByOrder(orderA);
        inventoryService.commitByOrder(orderA);
        inventoryService.releaseByOrder(orderA, "LATE_CANCEL_IGNORED");
        InventoryStockResponse afterOrderA = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(16, afterOrderA.totalQty());
        assertEquals(0, afterOrderA.reservedQty());
        assertEquals(16, afterOrderA.availableQty());

        // 3. Đơn B giữ 5 rồi thanh toán thất bại -> release lặp 2 lần trả đủ về available=16
        UUID orderB = UUID.randomUUID();
        inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderB,
                List.of(new ReservationItemRequest(skuId, 5, warehouse.getId())),
                30,
                null
        ));
        assertEquals(11, inventoryService.getStockBySku(skuId, warehouse.getId()).availableQty());
        inventoryService.releaseByOrder(orderB, "PAYMENT_FAILED");
        inventoryService.releaseByOrder(orderB, "PAYMENT_FAILED_RETRY");
        InventoryStockResponse afterOrderB = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(16, afterOrderB.totalQty());
        assertEquals(0, afterOrderB.reservedQty());
        assertEquals(16, afterOrderB.availableQty());

        // 4. Đơn C giữ 3 với hạn đã quá -> sweeper tự hoàn kho -> commit muộn bị từ chối INVENTORY_RESERVATION_EXPIRED
        UUID orderC = UUID.randomUUID();
        inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderC,
                List.of(new ReservationItemRequest(skuId, 3, warehouse.getId())),
                null,
                Instant.now().minusSeconds(10)
        ));
        int swept = inventoryService.autoReleaseExpiredReservations();
        assertTrue(swept >= 1);
        BusinessException lateCommit = assertThrows(BusinessException.class,
                () -> inventoryService.commitByOrder(orderC));
        assertEquals(ErrorCode.INVENTORY_RESERVATION_EXPIRED.name(), lateCommit.getCode());

        // 5. Điều chỉnh kiểm kê về targetTotalQty=14 + replay theo refId -> không trừ lặp, chặn điều chỉnh âm
        UUID auditRef = UUID.randomUUID();
        StockAdjustmentRequest adjustReq = new StockAdjustmentRequest(
                skuId, null, warehouse.getId(), null, 14, null, "Kiem ke hao hut", auditRef, null);
        InventoryStockResponse afterAdj1 = inventoryService.adjustStock(adjustReq);
        InventoryStockResponse afterAdj2 = inventoryService.adjustStock(adjustReq);
        assertEquals(14, afterAdj1.totalQty());
        assertEquals(14, afterAdj2.totalQty());

        assertThrows(BusinessException.class, () -> inventoryService.adjustStock(
                new StockAdjustmentRequest(skuId, null, warehouse.getId(), -20, null, null, "Am kho", null, null)));

        // 6. Đọc batch stock gồm 1 SKU có hàng + 1 SKU chưa nhập
        List<InventoryStockResponse> batch = inventoryService.getBatchStock(
                new BatchStockRequest(List.of(skuId, skuEmpty), warehouse.getId()));
        assertEquals(2, batch.size());
        assertEquals(14, batch.get(0).totalQty());
        assertEquals(0, batch.get(1).totalQty());

        // 7. Truy vấn sổ cái Criteria (searchLedgerFiltered) với JPA thật + H2
        Page<InventoryLedgerResponse> inboundLedger = inventoryService.getLedger(
                skuId, null, warehouse.getId(), LedgerMovementType.INBOUND, "INBOUND",
                null, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60), PageRequest.of(0, 10));
        assertEquals(1, inboundLedger.getTotalElements());
        assertEquals(warehouse.getCode(), inboundLedger.getContent().get(0).warehouseCode());

        long outboundCount = ledgerRepository.findBySkuId(skuId, PageRequest.of(0, 50)).getContent().stream()
                .filter(entry -> entry.getMovementType() == LedgerMovementType.OUTBOUND && orderA.equals(entry.getRefId()))
                .count();
        assertEquals(1L, outboundCount);

        // 8. Đối soát tồn kho khớp 100% với sổ cái
        List<InventoryReconciliationResponse> reconciled = inventoryService.reconcileStock(skuId, warehouse.getId());
        assertEquals(1, reconciled.size());
        InventoryReconciliationResponse rec = reconciled.get(0);
        assertEquals(14, rec.totalQty());
        assertEquals(0, rec.reservedQty());
        assertEquals(14, rec.ledgerTotalQty());
        assertEquals(0, rec.ledgerReservedQty());
        assertEquals(0, rec.activeReservationQty());
        assertTrue(rec.nonNegative());
        assertTrue(rec.matched());
    }

    @Test
    @DisplayName("Quản trị kho qua WarehouseService phân trang, lọc từ khóa và chặn Seller tạo kho")
    void warehouseService_CrudAndSellerRestriction() {
        String code = "WH_API_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        TestingAuthenticationToken admin = new TestingAuthenticationToken(UUID.randomUUID().toString(), "N/A", "ROLE_ADMIN");
        admin.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(admin);

        WarehouseResponse created = warehouseService.createWarehouse(
                new CreateWarehouseRequest(code.toLowerCase(), "Kho Da Nang", "Hai Chau, Da Nang", WarehouseType.STANDARD, true));
        assertEquals(code, created.code());
        assertTrue(created.active());

        WarehouseResponse updated = warehouseService.updateWarehouse(
                created.id(),
                new com.nexus.fulfillment.dto.request.UpdateWarehouseRequest("Kho Da Nang Moi", "Lien Chieu, Da Nang", WarehouseType.RETURN, false));
        assertEquals("Kho Da Nang Moi", updated.name());
        assertEquals(WarehouseType.RETURN, updated.type());
        assertFalse(updated.active());

        Page<WarehouseResponse> found = warehouseService.listWarehouses(false, WarehouseType.RETURN, code, PageRequest.of(0, 10));
        assertEquals(1, found.getTotalElements());
        assertEquals(code, warehouseService.getWarehouseByCode(code).code());

        TestingAuthenticationToken seller = new TestingAuthenticationToken(UUID.randomUUID().toString(), "N/A", "ROLE_SELLER");
        seller.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(seller);

        BusinessException forbidden = assertThrows(BusinessException.class, () -> warehouseService.createWarehouse(
                new CreateWarehouseRequest("WH_UNAUTH", "Kho Seller", "HCM", WarehouseType.STANDARD, true)));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), forbidden.getCode());
    }
}
