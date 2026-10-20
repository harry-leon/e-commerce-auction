package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.fulfillment.dto.request.CommitReservationRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckItemRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.response.InventoryCheckResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuInventoryResponse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.exception.InsufficientStockException;
import com.nexus.fulfillment.exception.InvalidReservationStatusException;
import com.nexus.fulfillment.exception.ReservationExpiredException;
import com.nexus.fulfillment.exception.ReservationNotFoundException;
import com.nexus.fulfillment.exception.SkuNotFoundException;
import com.nexus.fulfillment.service.impl.InventoryServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử đơn vị cho InventoryServiceImpl.
 *
 * Chức năng: Kiểm chứng logic nghiệp vụ kiểm tra tồn, tạo phiếu giữ hàng, commit xuất kho,
 * release hoàn kho và các ngoại lệ InsufficientStockException, ReservationExpiredException, SkuNotFoundException.
 */
class InventoryServiceTest {

    private InventoryServiceImpl service;
    private final UUID seededSku1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID defaultWarehouse = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new InventoryServiceImpl();
    }

    @Test
    @DisplayName("Kiểm tra tồn kho cho SKU có sẵn trả về available=true")
    void checkStockSeededSkuAvailable() {
        var request = new InventoryCheckRequest(List.of(
                new InventoryCheckItemRequest(seededSku1, defaultWarehouse, 5)
        ));

        InventoryCheckResponse res = service.checkStock(request);
        assertTrue(res.available());
        assertEquals(1, res.items().size());
        assertTrue(res.items().get(0).available());
        assertEquals(100, res.items().get(0).availableQuantity());
    }

    @Test
    @DisplayName("Kiểm tra tồn kho vượt quá số lượng hiện có trả về available=false")
    void checkStockExceedingQuantityReturnsNotAvailable() {
        var request = new InventoryCheckRequest(List.of(
                new InventoryCheckItemRequest(seededSku1, defaultWarehouse, 150)
        ));

        InventoryCheckResponse res = service.checkStock(request);
        assertFalse(res.available());
        assertFalse(res.items().get(0).available());
    }

    @Test
    @DisplayName("Tạo phiếu giữ hàng thành công chuyển trạng thái PENDING và giảm tồn khả dụng")
    void createReservationSuccess() {
        UUID orderId = UUID.randomUUID();
        var request = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                Instant.now().plusSeconds(1800),
                List.of(new ReservationItemRequest(seededSku1, defaultWarehouse, 10))
        );

        ReservationResponse res = service.createReservation(request);
        assertNotNull(res.reservationId());
        assertEquals(ReservationStatus.PENDING, res.status());
        assertEquals(orderId, res.orderId());

        SkuInventoryResponse stock = service.getSkuInventory(seededSku1, defaultWarehouse);
        assertEquals(90, stock.availableQuantity());
        assertEquals(10, stock.reservedQuantity());
    }

    @Test
    @DisplayName("Tạo phiếu giữ hàng khi tồn không đủ ném InsufficientStockException")
    void createReservationInsufficientStockThrows() {
        UUID orderId = UUID.randomUUID();
        var request = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                null,
                List.of(new ReservationItemRequest(seededSku1, defaultWarehouse, 200))
        );

        assertThrows(InsufficientStockException.class, () -> service.createReservation(request));
    }

    @Test
    @DisplayName("Tạo phiếu giữ hàng với SKU không tồn tại ném SkuNotFoundException")
    void createReservationUnknownSkuThrows() {
        UUID orderId = UUID.randomUUID();
        UUID unknownSku = UUID.randomUUID();
        var request = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                null,
                List.of(new ReservationItemRequest(unknownSku, defaultWarehouse, 1))
        );

        assertThrows(SkuNotFoundException.class, () -> service.createReservation(request));
    }

    @Test
    @DisplayName("Commit phiếu giữ hàng thành công cập nhật trạng thái CONFIRMED và trừ tổng tồn kho")
    void commitReservationSuccess() {
        UUID orderId = UUID.randomUUID();
        var createReq = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                Instant.now().plusSeconds(1800),
                List.of(new ReservationItemRequest(seededSku1, defaultWarehouse, 10))
        );
        ReservationResponse created = service.createReservation(createReq);

        ReservationResponse committed = service.commitReservation(
                created.reservationId(),
                new CommitReservationRequest("PAYMENT_SUCCESS")
        );

        assertEquals(ReservationStatus.CONFIRMED, committed.status());
        SkuInventoryResponse stock = service.getSkuInventory(seededSku1, defaultWarehouse);
        assertEquals(90, stock.totalQuantity());
        assertEquals(0, stock.reservedQuantity());
        assertEquals(90, stock.availableQuantity());
    }

    @Test
    @DisplayName("Commit phiếu giữ hàng đã hết hạn ném ReservationExpiredException")
    void commitReservationExpiredThrows() {
        UUID orderId = UUID.randomUUID();
        var createReq = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                Instant.now().minusSeconds(10), // Đã hết hạn
                List.of(new ReservationItemRequest(seededSku1, defaultWarehouse, 5))
        );
        ReservationResponse created = service.createReservation(createReq);

        assertThrows(ReservationExpiredException.class, () -> service.commitReservation(
                created.reservationId(),
                new CommitReservationRequest("PAYMENT_SUCCESS")
        ));
    }

    @Test
    @DisplayName("Release phiếu giữ hàng hoàn trả lại số lượng tồn khả dụng")
    void releaseReservationSuccess() {
        UUID orderId = UUID.randomUUID();
        var createReq = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                Instant.now().plusSeconds(1800),
                List.of(new ReservationItemRequest(seededSku1, defaultWarehouse, 10))
        );
        ReservationResponse created = service.createReservation(createReq);

        ReservationResponse released = service.releaseReservation(
                created.reservationId(),
                new ReleaseReservationRequest("ORDER_CANCELLED")
        );

        assertEquals(ReservationStatus.RELEASED, released.status());
        SkuInventoryResponse stock = service.getSkuInventory(seededSku1, defaultWarehouse);
        assertEquals(100, stock.availableQuantity());
        assertEquals(0, stock.reservedQuantity());
    }

    @Test
    @DisplayName("Release phiếu đã CONFIRMED ném InvalidReservationStatusException")
    void releaseConfirmedReservationThrows() {
        UUID orderId = UUID.randomUUID();
        var createReq = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                Instant.now().plusSeconds(1800),
                List.of(new ReservationItemRequest(seededSku1, defaultWarehouse, 10))
        );
        ReservationResponse created = service.createReservation(createReq);
        service.commitReservation(created.reservationId(), new CommitReservationRequest(null));

        assertThrows(InvalidReservationStatusException.class, () -> service.releaseReservation(
                created.reservationId(),
                new ReleaseReservationRequest(null)
        ));
    }

    @Test
    @DisplayName("Tra cứu phiếu không tồn tại ném ReservationNotFoundException")
    void getReservationNotFoundThrows() {
        assertThrows(ReservationNotFoundException.class, () -> service.getReservation(UUID.randomUUID()));
    }
}
