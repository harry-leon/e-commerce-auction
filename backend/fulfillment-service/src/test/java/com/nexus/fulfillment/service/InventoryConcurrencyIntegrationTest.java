package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Kiểm thử tích hợp Concurrency Control & Chống bán vượt tồn kho (Overselling Prevention).
 *
 * Tình huống nghiệp vụ kiểm chứng:
 * - SLA xử lý nhanh & tính nhất quán cao (SRS §5.1, §5.3).
 * - Bán flash-sale: 10 luồng mua hàng đồng thời tranh chấp 5 sản phẩm tồn kho duy nhất.
 * - Đảm bảo nguyên tử (Atomic): chính xác 5 luồng thành công, 5 luồng thất bại với INVENTORY_INSUFFICIENT.
 * - Tuyệt đối không xảy ra hiện tượng oversell (tổng reserved_qty không bao giờ vượt total_qty).
 * - Kiểm tra cơ chế tự động giải phóng hoàn kho (Auto-release) sau khi hết hạn giữ hàng.
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryConcurrencyIntegrationTest {

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
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean
    private CatalogClient catalogClient;

    private UUID sellerId;
    private UUID skuId;
    private Warehouse warehouse;

    @BeforeEach
    void setUp() {
// Mỗi test dùng warehouse/SKU riêng vì sổ cái không được xóa hay làm mất tham chiếu.

        sellerId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        sellerId.toString(),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_SELLER"))
                )
        );
        when(catalogClient.findSkuById(any(UUID.class))).thenAnswer(inv -> Optional.of(
                new CatalogClient.SkuOwnershipInfo(
                        inv.getArgument(0),
                        UUID.randomUUID(),
                        "Concurrency Product",
                        new BigDecimal("100000"),
                        new BigDecimal("90000"),
                        sellerId,
                        true
                )
        ));

        warehouse = new Warehouse(
                UUID.randomUUID(),
                "WH_" + UUID.randomUUID().toString().substring(0, 8),
                "Kho trung tâm Test",
                "TP. HCM",
                WarehouseType.STANDARD,
                true
        );
        warehouseRepository.save(warehouse);

        // Nhập kho 5 sản phẩm
        inventoryService.inboundStock(new com.nexus.fulfillment.dto.request.StockInboundRequest(
                skuId,
                warehouse.getId(),
                5,
                UUID.randomUUID()
        ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Kiểm soát đồng thời: 10 luồng cùng tranh mua 5 sản phẩm tồn kho -> Đúng 5 thành công, 5 thất bại, 0 oversell")
    void testConcurrentReservations_NoOversell() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final UUID orderId = UUID.randomUUID();
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // Chờ đồng loạt xuất phát cùng một thời điểm

                    CreateReservationRequest request = new CreateReservationRequest(
                            ReservationRefType.ORDER,
                            orderId,
                            List.of(new ReservationItemRequest(skuId, 1, warehouse.getId())),
                            30
                    );

                    ReservationResponse res = inventoryService.reserveStock(request);
                    if (res != null && res.status() == ReservationStatus.PENDING) {
                        successCount.incrementAndGet();
                    }
                } catch (BusinessException be) {
                    if (ErrorCode.INVENTORY_INSUFFICIENT.name().equals(be.getCode())) {
                        insufficientCount.incrementAndGet();
                    } else {
                        otherErrorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // Kích hoạt 10 luồng cùng chạy
        doneLatch.await();
        executor.shutdown();

        // Kiểm chứng kết quả kiểm soát đồng thời
        assertEquals(5, successCount.get(), "Chính xác 5 luồng phải đặt giữ kho thành công");
        assertEquals(5, insufficientCount.get(), "Chính xác 5 luồng phải bị từ chối do hết hàng");
        assertEquals(0, otherErrorCount.get(), "Không được có lỗi không mong muốn");

        // Kiểm tra trạng thái tồn kho thực tế trong cơ sở dữ liệu
        InventoryStockResponse stock = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(5, stock.totalQty(), "Tổng tồn vẫn là 5");
        assertEquals(5, stock.reservedQty(), "Đúng 5 sản phẩm bị tạm giữ");
        assertEquals(0, stock.availableQty(), "Tồn khả dụng phải về 0, không được âm");

        // Kiểm tra số lượng phiếu giữ hàng trong DB
        // Chỉ đếm SKU của test này; fixture khác vẫn giữ lịch sử kho bất biến.
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM inventory_reservations r JOIN inventories i ON i.id=r.inventory_id WHERE i.sku_id=?", Integer.class, skuId), "Đúng 5 phiếu giữ hàng được tạo");
    }

    @Test
    @DisplayName("Tự động hoàn kho (Auto-release): Các phiếu quá 30 phút tự động hoàn trả tồn khả dụng")
    void testAutoReleaseExpiredReservations_RestoresStock() {
        UUID orderId = UUID.randomUUID();
        CreateReservationRequest request = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, 3, warehouse.getId())),
                30
        );
        ReservationResponse reservation = inventoryService.reserveStock(request);
        assertNotNull(reservation);

        // Ban đầu khả dụng còn 5 - 3 = 2
        InventoryStockResponse stockBefore = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(2, stockBefore.availableQty());
        assertEquals(3, stockBefore.reservedQty());

        // Giả lập thời gian phiếu đã hết hạn (cập nhật expires_at lùi về quá khứ)
        var reservations = reservationRepository.findAll();
        for (var res : reservations) {
            res.setExpiresAt(Instant.now().minusSeconds(60));
            reservationRepository.save(res);
        }

        // Kích hoạt quét tự động hoàn kho
        int releasedCount = inventoryService.autoReleaseExpiredReservations();
        assertEquals(1, releasedCount, "Đã giải phóng 1 phiếu hết hạn");

        // Tồn khả dụng phải được hoàn trả lại đầy đủ là 5
        InventoryStockResponse stockAfter = inventoryService.getStockBySku(skuId, warehouse.getId());
        assertEquals(5, stockAfter.availableQty(), "Tồn khả dụng phải phục hồi về 5");
        assertEquals(0, stockAfter.reservedQty(), "Số lượng đang giữ phải về 0");
    }

    @Test
    @DisplayName("Nhập kho lần đầu đồng thời: 5 luồng cùng skuId chỉ sinh đúng 1 seller_skus và cộng dồn đủ số lượng")
    void testConcurrentFirstInbound_SingleSellerSkuAndCombinedQuantity() throws InterruptedException {
        UUID raceSkuId = UUID.randomUUID();
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Set<String> observedSkuCodes = ConcurrentHashMap.newKeySet();
        Set<UUID> observedSellerSkuIds = ConcurrentHashMap.newKeySet();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(
                                    sellerId.toString(),
                                    "n/a",
                                    List.of(new SimpleGrantedAuthority("ROLE_SELLER"))
                            )
                    );
                    startLatch.await();
                    InventoryStockResponse res = inventoryService.inboundStock(
                            new com.nexus.fulfillment.dto.request.StockInboundRequest(
                                    raceSkuId,
                                    warehouse.getId(),
                                    3,
                                    UUID.randomUUID()
                            )
                    );
                    if (res.skuCode() != null) {
                        observedSkuCodes.add(res.skuCode());
                    }
                    if (res.sellerSkuId() != null) {
                        observedSellerSkuIds.add(res.sellerSkuId());
                    }
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    errorCount.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "Cả 5 luồng nhập kho đồng thời phải thành công");
        assertEquals(0, errorCount.get(), "Không được thất bại do xung đột unique seller_skus/inventories");
        assertEquals(1, observedSkuCodes.size(), "Tất cả luồng phải dùng chung đúng 1 skuCode FSKU-...");
        assertEquals(1, observedSellerSkuIds.size(), "Tất cả luồng phải gắn đúng 1 sellerSkuId");
        assertTrue(observedSkuCodes.iterator().next().startsWith("FSKU-"), "Mã SKU kho phải đúng tiền tố FSKU-");
        assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM seller_skus WHERE sku_id = ?", Integer.class, raceSkuId),
                "Chỉ được tồn tại đúng 1 dòng seller_skus cho skuId"
        );
        InventoryStockResponse stock = inventoryService.getStockBySku(raceSkuId, warehouse.getId());
        assertEquals(15, stock.totalQty(), "Tổng tồn kho phải cộng dồn đủ 5 * 3 = 15");
    }
}
