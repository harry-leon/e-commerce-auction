package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.client.CommerceShippingClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Chuẩn bị kiện không được lấy PII/giá từ browser, đoán cân nặng hoặc cho đơn chưa trả tiền đi giao. */
class ShipmentPreparationServiceTest {
    private final CommerceShippingClient client = mock(CommerceShippingClient.class);
    private final ShipmentPreparationService service = new ShipmentPreparationService(client);
    private final UUID order = UUID.randomUUID();
    private final ShipmentPreparationService.Parcel parcel = new ShipmentPreparationService.Parcel(UUID.randomUUID(), "GHN", "STANDARD", 725, 12, "WARD");

    /** Cân nặng/định tuyến lấy từ kiện thực, tên/địa chỉ/tổng lấy Commerce; VNPAY không thu COD lần nữa. */
    @Test
    void combinesAuthoritativeOrderWithActualParcelWithoutDispatch() {
        when(client.get(order)).thenReturn(snapshot("PAID", "VNPAY", true, "Người nhận"));
        var result = service.prepare(order, parcel);
        assertEquals("Người nhận", result.recipientName());
        assertEquals(725, result.weightGrams());
        assertEquals(new BigDecimal("50000"), result.orderTotal());
        assertEquals(BigDecimal.ZERO, result.codAmount());
        assertEquals(parcel.warehouseId(), result.warehouseId());
    }

    /** Các trạng thái chưa thu/đã kết thúc không được biến thành request carrier; COD cần contract riêng. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PENDING_PAYMENT", "CANCELLED", "SHIPPED", "DELIVERED"})
    void rejectsOrdersOutsidePreparationStates(String status) {
        when(client.get(order)).thenReturn(snapshot(status, "VNPAY", true, "Người nhận"));
        assertThrows(ApiException.class, () -> service.prepare(order, parcel));
    }

    /** Thiếu kiện dừng trước HTTP; đơn legacy thiếu người nhận/giá tạm và COD đều phải chặn. */
    @Test
    void rejectsIncompleteParcelAndUnreadyOrder() {
        assertThrows(ApiException.class, () -> service.prepare(order, null));
        verifyNoInteractions(client);
        when(client.get(order)).thenReturn(snapshot("PAID", "COD", true, "Người nhận"));
        assertThrows(ApiException.class, () -> service.prepare(order, parcel));
        when(client.get(order)).thenReturn(snapshot("PAID", "VNPAY", false, "Người nhận"));
        assertThrows(ApiException.class, () -> service.prepare(order, parcel));
        when(client.get(order)).thenReturn(snapshot("PAID", "VNPAY", true, null));
        assertThrows(ApiException.class, () -> service.prepare(order, parcel));
    }

    /** Fixture mô phỏng response Commerce, không merchant/carrier thật. */
    private CommerceShippingClient.Snapshot snapshot(String status, String method, boolean finalized, String name) {
        return new CommerceShippingClient.Snapshot(order, "ORD-TEST", status, name, "0900000000", "Địa chỉ test", method,
                finalized, new BigDecimal("50000"), null);
    }
}
