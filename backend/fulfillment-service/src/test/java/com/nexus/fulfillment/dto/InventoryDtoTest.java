package com.nexus.fulfillment.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.fulfillment.dto.request.CommitReservationRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckItemRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.response.ApiResponse;
import com.nexus.fulfillment.dto.response.ErrorDetail;
import com.nexus.fulfillment.dto.response.InventoryCheckItemResult;
import com.nexus.fulfillment.dto.response.InventoryCheckResponse;
import com.nexus.fulfillment.dto.response.MetaInfo;
import com.nexus.fulfillment.dto.response.ReservationItemResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuInventoryResponse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Kiểm thử đơn vị cho toàn bộ DTO Request & Response của Fulfillment Service.
 *
 * Chức năng: Đảm bảo các DTO record khởi tạo đúng, hỗ trợ đầy đủ factory methods,
 * kiểm tra các trường dữ liệu và tuân thủ quy chuẩn Envelope Pattern ApiResponse<T>.
 */
class InventoryDtoTest {

    @Test
    @DisplayName("Kiểm tra factory methods của ApiResponse")
    void testApiResponseFactoryMethods() {
        ApiResponse<String> okData = ApiResponse.ok("Hello");
        assertTrue(okData.success());
        assertEquals("Hello", okData.data());
        assertNull(okData.error());
        assertNotNull(okData.meta());

        ApiResponse<Void> okVoid = ApiResponse.ok();
        assertTrue(okVoid.success());
        assertNull(okVoid.data());

        ApiResponse<String> errSimple = ApiResponse.error("ERR_CODE", "Error message");
        assertFalse(errSimple.success());
        assertEquals("ERR_CODE", errSimple.error().code());
        assertEquals("Error message", errSimple.error().message());

        ApiResponse<String> errDetail = ApiResponse.error("ERR_CODE", "Error message", List.of("detail 1"));
        assertFalse(errDetail.success());
        assertNotNull(errDetail.error().details());

        PageImpl<String> page = new PageImpl<>(List.of("item1", "item2"), PageRequest.of(0, 10), 2);
        ApiResponse<List<String>> okPage = ApiResponse.ok(page);
        assertTrue(okPage.success());
        assertEquals(2, okPage.data().size());
        assertEquals(2L, okPage.meta().total());

        ApiResponse<String> okCustomPage = ApiResponse.ok("data", 1, 20, 100L);
        assertTrue(okCustomPage.success());
        assertEquals(1, okCustomPage.meta().page());
        assertEquals(20, okCustomPage.meta().pageSize());
        assertEquals(100L, okCustomPage.meta().total());
    }

    @Test
    @DisplayName("Kiểm tra MetaInfo factory methods")
    void testMetaInfoFactoryMethods() {
        MetaInfo meta = MetaInfo.of();
        assertEquals("v1", meta.version());
        assertNotNull(meta.timestamp());

        MetaInfo metaWithReq = MetaInfo.of("req-123");
        assertEquals("req-123", metaWithReq.requestId());

        MetaInfo metaPaged = MetaInfo.ofPagination(1, 10, 50L, "req-456");
        assertEquals(1, metaPaged.page());
        assertEquals(10, metaPaged.pageSize());
        assertEquals(50L, metaPaged.total());
        assertEquals("req-456", metaPaged.requestId());
    }

    @Test
    @DisplayName("Kiểm tra ErrorDetail factory methods")
    void testErrorDetailFactoryMethods() {
        ErrorDetail err1 = ErrorDetail.of("C1", "M1");
        assertEquals("C1", err1.code());
        assertEquals("M1", err1.message());
        assertNull(err1.details());

        ErrorDetail err2 = ErrorDetail.of("C2", "M2", "Details");
        assertEquals("Details", err2.details());
    }

    @Test
    @DisplayName("Kiểm tra DTOs của Inventory Check")
    void testInventoryCheckDtos() {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();

        InventoryCheckItemRequest itemReq = new InventoryCheckItemRequest(skuId, whId, 5);
        assertEquals(skuId, itemReq.skuId());
        assertEquals(whId, itemReq.warehouseId());
        assertEquals(5, itemReq.quantity());

        InventoryCheckRequest req = new InventoryCheckRequest(List.of(itemReq));
        assertEquals(1, req.items().size());

        InventoryCheckItemResult itemRes = new InventoryCheckItemResult(skuId, whId, 5, 10, true);
        assertTrue(itemRes.available());
        assertEquals(10, itemRes.availableQuantity());

        InventoryCheckResponse res = InventoryCheckResponse.of(List.of(itemRes));
        assertTrue(res.available());
        assertEquals(1, res.items().size());

        InventoryCheckResponse emptyRes = InventoryCheckResponse.of(null);
        assertFalse(emptyRes.available());
        assertTrue(emptyRes.items().isEmpty());
    }

    @Test
    @DisplayName("Kiểm tra DTOs của Reservation")
    void testReservationDtos() {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        Instant now = Instant.now();

        ReservationItemRequest itemReq = new ReservationItemRequest(skuId, whId, 2);
        CreateReservationRequest createReq = new CreateReservationRequest(
                orderId,
                null, // Should default to ORDER
                now,
                List.of(itemReq)
        );
        assertEquals(ReservationRefType.ORDER, createReq.refType());
        assertEquals(orderId, createReq.orderId());

        ReservationItemResponse itemRes = new ReservationItemResponse(UUID.randomUUID(), skuId, whId, 2);
        ReservationResponse res = new ReservationResponse(
                resId,
                orderId,
                ReservationRefType.ORDER,
                ReservationStatus.PENDING,
                now,
                now.plusSeconds(1800),
                List.of(itemRes)
        );
        assertEquals(resId, res.reservationId());
        assertEquals(ReservationStatus.PENDING, res.status());

        CommitReservationRequest commitReq = new CommitReservationRequest("PAYMENT_SUCCESS");
        assertEquals("PAYMENT_SUCCESS", commitReq.reason());

        ReleaseReservationRequest releaseReq = new ReleaseReservationRequest("CANCELLED");
        assertEquals("CANCELLED", releaseReq.reason());
    }

    @Test
    @DisplayName("Kiểm tra SkuInventoryResponse DTO")
    void testSkuInventoryResponse() {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        SkuInventoryResponse res = new SkuInventoryResponse(skuId, whId, 100, 20, 10, 70);

        assertEquals(skuId, res.skuId());
        assertEquals(whId, res.warehouseId());
        assertEquals(100, res.totalQuantity());
        assertEquals(20, res.reservedQuantity());
        assertEquals(10, res.unavailableQuantity());
        assertEquals(70, res.availableQuantity());
    }
}
