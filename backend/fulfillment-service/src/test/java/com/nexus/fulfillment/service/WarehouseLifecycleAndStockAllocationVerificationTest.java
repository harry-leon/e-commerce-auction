package com.nexus.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.StockAllocationRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.StockAllocationResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.SellerSku;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.SellerSkuStatus;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.event.ShipmentReturnedEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.SellerSkuRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm thử xác thực vòng đời kho hàng và phân bổ đa kho (SRS §3.6.1, §5.2, §5.3, KH-S3-3).
 *
 * Chức năng:
 * - Kiểm chứng vòng đời đầy đủ của tồn kho qua các giai đoạn:
 *   1. Intake (Nhập kho -> Sổ cái INBOUND, total_qty tăng, available_qty tăng).
 *   2. Reserve (Khóa giữ Checkout -> Sổ cái RESERVE, reserved_qty tăng, available_qty giảm).
 *   3. Pick (Lấy hàng từ kệ -> trạng thái vận đơn PICKING).
 *   4. Pack (Đóng gói kiện hàng -> trạng thái vận đơn PACKED).
 *   5. Ship (Xuất kho bàn giao carrier -> Sổ cái OUTBOUND, total_qty giảm, reserved_qty giảm, reservation CONFIRMED).
 * - Kiểm chứng các kịch bản thất bại:
 *   + Release on failure: Hủy đơn hoặc lỗi thanh toán -> hoàn trả reserved_qty về available_qty, ghi sổ cái RELEASE.
 *   + Return on failure: Hàng giao thất bại hoặc người mua trả hàng -> hoàn kho thực tế, tăng total & available, ghi sổ cái INBOUND (SHIPMENT_RETURN).
 * - Xác thực thuật toán phân bổ đa kho (Stock Allocation):
 *   + Ưu tiên kho mặc định WH_001 (100% tại 1 kho).
 *   + Tự động chia tách khi kho mặc định thiếu hàng (WH_001 + WH_002, tối đa 2 kho).
 *   + Từ chối khi vượt giới hạn MAX_WAREHOUSES_PER_ORDER = 2 kho (INVENTORY_INSUFFICIENT).
 * - Đối soát tính bất biến và tính nhất quán của Sổ cái tồn kho (Immutable Inventory Ledger).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WarehouseLifecycleAndStockAllocationVerificationTest {

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CatalogClient catalogClient;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private WarehouseStockAllocationService allocationService;

    @Autowired
    private WarehouseFulfillmentService warehouseFulfillmentService;

    @Autowired
    private ShippingService shippingService;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private InventoryLedgerRepository ledgerRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private SellerSkuRepository sellerSkuRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    private Warehouse wh001;
    private Warehouse wh002;
    private Warehouse wh003;
    private UUID sellerId;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        sellerId.toString(),
                        "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_SELLER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                )
        );

        Mockito.when(catalogClient.findSkuById(Mockito.any(UUID.class))).thenAnswer(inv -> Optional.of(
                new CatalogClient.SkuOwnershipInfo(
                        inv.getArgument(0),
                        UUID.randomUUID(),
                        "Verification Product",
                        new BigDecimal("200000"),
                        new BigDecimal("180000"),
                        sellerId,
                        true
                )
        ));

        wh001 = warehouseRepository.findByCode("WH_001").orElseGet(() -> warehouseRepository.save(new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000001"),
                "WH_001",
                "Kho trung tam Nexus",
                "TP. Thu Duc, TP.HCM",
                WarehouseType.STANDARD,
                true
        )));

        wh002 = warehouseRepository.findByCode("WH_002").orElseGet(() -> warehouseRepository.save(new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000002"),
                "WH_002",
                "Kho ve tinh Mien Bac",
                "Long Bien, Ha Noi",
                WarehouseType.STANDARD,
                true
        )));

        wh003 = warehouseRepository.findByCode("WH_003").orElseGet(() -> warehouseRepository.save(new Warehouse(
                UUID.fromString("a0000000-0000-0000-0000-000000000003"),
                "WH_003",
                "Kho ve tinh Mien Trung",
                "Hai Chau, Da Nang",
                WarehouseType.STANDARD,
                true
        )));
    }

    @Test
    @DisplayName("Toàn vẹn vòng đời kho: Intake -> Reserve -> Pick -> Pack -> Ship và ghi nhận sổ cái bất biến")
    void fullWarehouseLifecycle_intakeReservePickPackShip_success() {
        UUID skuId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        int initialStock = 15;
        int orderQty = 5;

        // 1. Intake: Nhập kho ban đầu 15 đơn vị
        inventoryService.inboundStock(new StockInboundRequest(
                skuId, wh001.getId(), initialStock, UUID.randomUUID()
        ));

        InventoryStockResponse stockAfterInbound = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockAfterInbound.totalQty()).isEqualTo(initialStock);
        assertThat(stockAfterInbound.availableQty()).isEqualTo(initialStock);
        assertThat(stockAfterInbound.reservedQty()).isZero();

        // Kiểm tra sổ cái ghi nhận INBOUND
        List<InventoryLedger> ledgersAfterInbound = ledgerRepository.findBySkuId(skuId, Pageable.unpaged()).getContent();
        assertThat(ledgersAfterInbound).anyMatch(l -> l.getMovementType() == LedgerMovementType.INBOUND
                && l.getQuantity() == initialStock);

        // 2. Reserve: Khóa giữ kho Checkout 5 đơn vị
        ReservationResponse reservation = inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, orderQty, wh001.getId())),
                30
        ));
        assertThat(reservation.status()).isEqualTo(ReservationStatus.PENDING);

        InventoryStockResponse stockAfterReserve = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockAfterReserve.totalQty()).isEqualTo(initialStock);
        assertThat(stockAfterReserve.availableQty()).isEqualTo(initialStock - orderQty);
        assertThat(stockAfterReserve.reservedQty()).isEqualTo(orderQty);

        // Kiểm tra sổ cái ghi nhận RESERVE
        List<InventoryLedger> ledgersAfterReserve = ledgerRepository.findBySkuId(skuId, Pageable.unpaged()).getContent();
        assertThat(ledgersAfterReserve).anyMatch(l -> l.getMovementType() == LedgerMovementType.RESERVE
                && l.getQuantity() == orderQty);

        // Tạo vận đơn gắn liền với kho xuất WH_001
        ShipmentResponse createdShipment = shippingService.createShipment(new CreateShipmentRequest(
                orderId,
                wh001.getId(),
                "GHN",
                "STANDARD",
                "Nguyen Van A",
                "0912345678",
                "So 10 Pho Hue, Ha Noi",
                1454,
                "21211",
                500,
                BigDecimal.ZERO,
                new BigDecimal("500000"),
                "Kiem hang truoc khi nhan"
        ), "idempotency-ship-" + orderId);

        assertThat(createdShipment.status()).isIn(ShipmentStatus.CREATED, ShipmentStatus.PENDING);

        // 3. Pick: Nhân viên lấy hàng trên kệ
        ShipmentResponse pickedShipment = warehouseFulfillmentService.pick(createdShipment.id());
        assertThat(pickedShipment.status()).isEqualTo(ShipmentStatus.PICKING);

        // 4. Pack: Kiểm đếm và đóng gói kiện hàng
        ShipmentResponse packedShipment = warehouseFulfillmentService.pack(createdShipment.id());
        assertThat(packedShipment.status()).isEqualTo(ShipmentStatus.PACKED);

        // 5. Ship: Xuất kho bàn giao carrier
        ShipmentResponse shippedShipment = warehouseFulfillmentService.ship(createdShipment.id());
        assertThat(shippedShipment.status()).isEqualTo(ShipmentStatus.SHIPPED);

        // Sau khi xuất kho: reserved_qty -> 0, total_qty -> 10, available_qty -> 10
        InventoryStockResponse stockAfterShip = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockAfterShip.totalQty()).isEqualTo(initialStock - orderQty);
        assertThat(stockAfterShip.reservedQty()).isZero();
        assertThat(stockAfterShip.availableQty()).isEqualTo(initialStock - orderQty);

        // Kiểm tra phiếu reservation đã chuyển sang CONFIRMED
        List<InventoryReservation> confirmedReservations = reservationRepository.findByRefTypeAndRefId(
                ReservationRefType.ORDER, orderId
        );
        assertThat(confirmedReservations).hasSize(1);
        assertThat(confirmedReservations.get(0).getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // Kiểm tra sổ cái bất biến có dòng OUTBOUND
        List<InventoryLedger> ledgersAfterShip = ledgerRepository.findBySkuId(skuId, Pageable.unpaged()).getContent();
        assertThat(ledgersAfterShip).anyMatch(l -> l.getMovementType() == LedgerMovementType.OUTBOUND
                && l.getQuantity() == orderQty);
    }

    @Test
    @DisplayName("Vòng đời thất bại 1: Intake -> Reserve -> Release khi hủy đơn/lỗi thanh toán -> hoàn trả available_qty")
    void lifecycleFailure_releaseOnOrderCancelOrPaymentFailure() {
        UUID skuId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        int initialStock = 20;
        int reserveQty = 6;

        // Intake
        inventoryService.inboundStock(new StockInboundRequest(
                skuId, wh001.getId(), initialStock, UUID.randomUUID()
        ));

        // Reserve
        inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, reserveQty, wh001.getId())),
                30
        ));

        InventoryStockResponse stockReserved = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockReserved.availableQty()).isEqualTo(14);
        assertThat(stockReserved.reservedQty()).isEqualTo(6);

        // Release: Hủy đơn hàng hoặc lỗi thanh toán
        inventoryService.releaseByOrder(orderId, "Khách hàng hủy đơn trước khi thanh toán");

        // Sau release: reserved_qty hoàn trả về 0, available_qty phục hồi 20
        InventoryStockResponse stockAfterRelease = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockAfterRelease.totalQty()).isEqualTo(initialStock);
        assertThat(stockAfterRelease.reservedQty()).isZero();
        assertThat(stockAfterRelease.availableQty()).isEqualTo(initialStock);

        // Phiếu reservation đổi sang RELEASED
        List<InventoryReservation> releasedReservations = reservationRepository.findByRefTypeAndRefId(
                ReservationRefType.ORDER, orderId
        );
        assertThat(releasedReservations.get(0).getStatus()).isEqualTo(ReservationStatus.RELEASED);

        // Sổ cái bất biến ghi nhận RELEASE
        List<InventoryLedger> ledgers = ledgerRepository.findBySkuId(skuId, Pageable.unpaged()).getContent();
        assertThat(ledgers).anyMatch(l -> l.getMovementType() == LedgerMovementType.RELEASE
                && l.getQuantity() == reserveQty);
    }

    @Test
    @DisplayName("Vòng đời thất bại 2: Intake -> Reserve -> Pick -> Pack -> Ship -> Return khi giao hàng không thành công -> Hoàn kho vật lý")
    void lifecycleFailure_returnAndRestockOnDeliveryFailure() {
        UUID skuId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        int initialStock = 10;
        int shipQty = 4;

        // Intake
        inventoryService.inboundStock(new StockInboundRequest(
                skuId, wh001.getId(), initialStock, UUID.randomUUID()
        ));

        // Reserve
        inventoryService.reserveStock(new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, shipQty, wh001.getId())),
                30
        ));

        // Tạo vận đơn
        ShipmentResponse shipment = shippingService.createShipment(new CreateShipmentRequest(
                orderId, wh001.getId(), "GHN", "STANDARD", "Le Thi B", "0988776655", "Quan 1, TP.HCM",
                1454, "21211", 400, BigDecimal.ZERO, new BigDecimal("400000"), "Giao gio hanh chinh"
        ), "idempotency-return-" + orderId);

        // Pick -> Pack -> Ship
        warehouseFulfillmentService.pick(shipment.id());
        warehouseFulfillmentService.pack(shipment.id());
        warehouseFulfillmentService.ship(shipment.id());

        // Kiểm tra sau khi ship: total = 6, available = 6
        InventoryStockResponse stockShipped = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockShipped.totalQty()).isEqualTo(6);
        assertThat(stockShipped.availableQty()).isEqualTo(6);

        // Return: Giao hàng thất bại 3 lần, bưu tá chuyển hoàn kiện hàng về kho xuất WH_001
        ShipmentResponse returnedShipment = warehouseFulfillmentService.processReturn(
                shipment.id(), "Khach hang chuyen dia chi, khong the lien lac sau 3 lan giao"
        );

        assertThat(returnedShipment.status()).isEqualTo(ShipmentStatus.RETURNED);

        // Sau khi hoàn kho: total_qty và available_qty phải phục hồi lại đủ 10
        InventoryStockResponse stockAfterReturn = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockAfterReturn.totalQty()).isEqualTo(initialStock);
        assertThat(stockAfterReturn.availableQty()).isEqualTo(initialStock);
        assertThat(stockAfterReturn.reservedQty()).isZero();

        // Kiểm tra sổ cái bất biến ghi nhận dòng nhập hoàn kho (ref_type = SHIPMENT_RETURN)
        List<InventoryLedger> ledgers = ledgerRepository.findBySkuId(skuId, Pageable.unpaged()).getContent();
        assertThat(ledgers).anyMatch(l -> "SHIPMENT_RETURN".equals(l.getRefType())
                && shipment.id().equals(l.getRefId())
                && l.getQuantity() == shipQty);

        // Thử gọi lại processReturn lần 2 -> Idempotent, không tăng trùng tồn kho
        ShipmentResponse duplicateReturn = warehouseFulfillmentService.processReturn(
                shipment.id(), "Replay callback hoan tra"
        );
        assertThat(duplicateReturn.status()).isEqualTo(ShipmentStatus.RETURNED);

        InventoryStockResponse stockAfterReplay = inventoryService.getStockBySku(skuId, wh001.getId());
        assertThat(stockAfterReplay.totalQty()).isEqualTo(initialStock);
        assertThat(stockAfterReplay.availableQty()).isEqualTo(initialStock);
    }

    @Test
    @DisplayName("Xác thực thuật toán phân bổ tồn kho đa kho (Stock Allocation Rules)")
    void stockAllocation_respectsWarehouseAvailabilityAndLimits() {
        UUID orderId = UUID.randomUUID();
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();

        // Tình huống 1: Kho mặc định WH_001 có đủ 100% tồn cho cả 2 SKU -> Phân bổ 1 kho
        inventoryService.inboundStock(new StockInboundRequest(sku1, wh001.getId(), 20, UUID.randomUUID()));
        inventoryService.inboundStock(new StockInboundRequest(sku2, wh001.getId(), 20, UUID.randomUUID()));

        StockAllocationRequest request1 = new StockAllocationRequest(
                orderId,
                List.of(
                        new StockAllocationRequest.AllocationItemRequest(sku1, 5),
                        new StockAllocationRequest.AllocationItemRequest(sku2, 5)
                )
        );
        StockAllocationResponse resp1 = allocationService.computeAllocation(request1);
        assertThat(resp1.totalWarehouses()).isEqualTo(1);
        assertThat(resp1.singleWarehouseFulfilled()).isTrue();
        assertThat(resp1.allocations().get(0).warehouseCode()).isEqualTo("WH_001");

        // Tình huống 2: WH_001 chỉ có sẵn 3 SKU1, nhưng WH_002 có 10 SKU1 -> Phân bổ kết hợp 2 kho
        UUID orderId2 = UUID.randomUUID();
        UUID sku3 = UUID.randomUUID();
        inventoryService.inboundStock(new StockInboundRequest(sku3, wh001.getId(), 3, UUID.randomUUID()));
        inventoryService.inboundStock(new StockInboundRequest(sku3, wh002.getId(), 10, UUID.randomUUID()));

        StockAllocationRequest request2 = new StockAllocationRequest(
                orderId2,
                List.of(new StockAllocationRequest.AllocationItemRequest(sku3, 8))
        );
        StockAllocationResponse resp2 = allocationService.computeAllocation(request2);
        assertThat(resp2.totalWarehouses()).isEqualTo(2);
        assertThat(resp2.singleWarehouseFulfilled()).isFalse();
        assertThat(resp2.allocations()).hasSize(2);
        assertThat(resp2.allocations().get(0).warehouseCode()).isEqualTo("WH_001");
        assertThat(resp2.allocations().get(0).items().get(0).allocatedQuantity()).isEqualTo(3);
        assertThat(resp2.allocations().get(1).warehouseCode()).isEqualTo("WH_002");
        assertThat(resp2.allocations().get(1).items().get(0).allocatedQuantity()).isEqualTo(5);

        // Tình huống 3: Cần đến 3 kho riêng biệt để gom đủ đơn -> vi phạm MAX_WAREHOUSES_PER_ORDER = 2 -> ném INVENTORY_INSUFFICIENT
        UUID orderId3 = UUID.randomUUID();
        UUID skuA = UUID.randomUUID();
        UUID skuB = UUID.randomUUID();
        UUID skuC = UUID.randomUUID();

        // Mỗi kho chỉ có đúng 1 SKU riêng
        inventoryService.inboundStock(new StockInboundRequest(skuA, wh001.getId(), 10, UUID.randomUUID()));
        inventoryService.inboundStock(new StockInboundRequest(skuB, wh002.getId(), 10, UUID.randomUUID()));
        inventoryService.inboundStock(new StockInboundRequest(skuC, wh003.getId(), 10, UUID.randomUUID()));

        StockAllocationRequest request3 = new StockAllocationRequest(
                orderId3,
                List.of(
                        new StockAllocationRequest.AllocationItemRequest(skuA, 1),
                        new StockAllocationRequest.AllocationItemRequest(skuB, 1),
                        new StockAllocationRequest.AllocationItemRequest(skuC, 1)
                )
        );

        assertThatThrownBy(() -> allocationService.computeAllocation(request3))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT.name());
    }
}
