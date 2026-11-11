package com.nexus.commerce.service;

import com.nexus.commerce.entity.PaymentPriceSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Bảo vệ giá tin cậy: đọc giá live cho đơn thường, giữ settlement đấu giá và chỉ chốt từ snapshot đã thu tiền. */
@ExtendWith(MockitoExtension.class)
class PaymentPricingServiceTest {
    @Mock private CatalogClient catalog;
    private PaymentPricingService pricing;
    private CommerceProperties config;
    private Order order;
    private OrderItem item;

    @BeforeEach
    void setup() {
        config = new CommerceProperties();
        pricing = new PaymentPricingService(catalog, config);
        order = Order.builder().sellerId(UUID.randomUUID()).priceFinalized(false).total(null)
                .estimatedSubtotal(new BigDecimal("300000.00")).estimatedShippingFee(new BigDecimal("0.00"))
                .estimatedDiscount(new BigDecimal("0.00")).estimatedTotal(new BigDecimal("300000.00")).build();
        item = OrderItem.builder().skuId(UUID.randomUUID()).quantity(2).estimatedUnitPrice(new BigDecimal("150000.00")).build();
        order.addItem(item);
    }

    private SkuInfo sku(BigDecimal price, Boolean active, UUID seller) {
        return new SkuInfo(item.getSkuId(), "AO-M", "Áo thun", null, price, null, active, seller);
    }

    /** Giá tăng sau Create Order phải được tính tại attempt, nhưng chưa ghi giá chính thức lên OrderItem. */
    @Test
    void regularUsesLatestCatalogAndConfiguredShippingWithoutFinalizingOrder() {
        config.getOrder().setShippingFee(new BigDecimal("25000"));
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(new BigDecimal("160000"), true, order.getSellerId())));
        var snapshot = pricing.quote(order);
        assertEquals(new BigDecimal("320000.00"), snapshot.subtotal());
        assertEquals(new BigDecimal("345000.00"), snapshot.total());
        assertNull(item.getUnitPrice());
        assertNull(order.getTotal());
        assertFalse(order.isPriceFinalized());
    }

    /** SKU ngừng bán, không tồn tại hoặc đổi chủ shop không được tiếp tục thu tiền từ đơn cũ. */
    @Test
    void unavailableOrWrongSellerCannotBePriced() {
        for (var response : List.of(Map.<UUID, SkuInfo>of(),
                Map.of(item.getSkuId(), sku(BigDecimal.TEN, false, order.getSellerId())),
                Map.of(item.getSkuId(), sku(BigDecimal.TEN, true, UUID.randomUUID())))) {
            when(catalog.getSkuDetails(any())).thenReturn(response);
            assertThrows(ApiException.class, () -> pricing.quote(order));
        }
    }

    /** Lỗi dữ liệu Catalog/số lượng/giới hạn số tiền phải dừng trước tạo URL hoặc reserve. */
    @Test
    void rejectsInvalidMoneyQuantityAndOverflow() {
        for (BigDecimal value : new BigDecimal[] {null, new BigDecimal("-1"), new BigDecimal("9999999999999.99")}) {
            when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(value, true, order.getSellerId())));
            assertThrows(ApiException.class, () -> pricing.quote(order));
        }
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(BigDecimal.TEN, true, order.getSellerId())));
        item.setQuantity(0);
        assertThrows(ApiException.class, () -> pricing.quote(order));
        item.setQuantity(null);
        assertThrows(ApiException.class, () -> pricing.quote(order));
        order.getItems().clear();
        assertThrows(ApiException.class, () -> pricing.quote(order));
    }

    /** Giá thắng đấu giá không bị giá bán thường hoặc thay đổi phí ship cấu hình ghi đè. */
    @Test
    void auctionPreservesSettlementWithoutReadingCatalog() {
        order.setSourceType(OrderSourceType.AUCTION);
        config.getOrder().setShippingFee(new BigDecimal("99000"));
        var snapshot = pricing.quote(order);
        assertEquals(new BigDecimal("300000.00"), snapshot.total());
        verifyNoInteractions(catalog);
    }

    /** Callback chốt đúng ảnh chụp attempt, kể cả dự kiến trên đơn đã khác; không đọc Catalog khi chốt. */
    @Test
    void finalizationUsesFrozenSnapshotAndSetsOfficialPricesTogether() {
        var snapshot = PaymentPricingService.estimated(order);
        item.setEstimatedUnitPrice(new BigDecimal("999999"));
        PaymentPricingService.finalizePrices(order, snapshot);
        assertEquals(new BigDecimal("150000.00"), item.getUnitPrice());
        assertEquals(new BigDecimal("300000.00"), order.getTotal());
        assertTrue(order.isPriceFinalized());
        verifyNoInteractions(catalog);
    }

    /** Snapshot sai ID/SKU/số lượng/tổng bị từ chối; transaction IPN chịu trách nhiệm rollback mọi mutation. */
    @Test
    void corruptSnapshotsCannotFinalizePrices() {
        var source = PaymentPricingService.estimated(order);
        assertThrows(ApiException.class, () -> PaymentPricingService.finalizePrices(order, null));
        var badLine = new PaymentPriceSnapshot.Line(item.getId(), UUID.randomUUID(), 2, BigDecimal.TEN, new BigDecimal("20"));
        assertThrows(ApiException.class, () -> PaymentPricingService.finalizePrices(order,
                new PaymentPriceSnapshot(source.subtotal(), source.shippingFee(), source.discount(), source.total(), List.of(badLine))));
        assertThrows(ApiException.class, () -> PaymentPricingService.finalizePrices(order,
                new PaymentPriceSnapshot(source.subtotal(), source.shippingFee(), source.discount(), BigDecimal.ONE, source.items())));
        assertFalse(order.isPriceFinalized());
    }
}
