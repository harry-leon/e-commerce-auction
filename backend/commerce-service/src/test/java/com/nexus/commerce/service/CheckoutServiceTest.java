package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.dto.request.CheckoutPreviewRequest;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.entity.CartItem;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.CartStatus;
import com.nexus.commerce.repository.CartItemRepository;
import com.nexus.commerce.repository.CartRepository;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bảo vệ tính giá server, quyền sở hữu giỏ, replay key và bù trừ kho; test PostgreSQL riêng xác minh transaction thật. */
@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {
    @Mock private CartRepository carts;
    @Mock private CartItemRepository items;
    @Mock private OrderRepository orders;
    @Mock private CheckoutLock locks;
    @Mock private CatalogClient catalog;
    @Mock private InventoryClient inventory;
    private CheckoutService service;
    private final CommerceProperties properties = new CommerceProperties();
    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final String key = UUID.randomUUID().toString();
    private Cart cart;
    private CartItem item;

    @BeforeEach
    void setup() {
        service = new CheckoutService(carts, items, orders, locks, catalog, inventory, properties, new ObjectMapper());
        cart = Cart.builder().id(UUID.randomUUID()).userId(buyer).expiresAt(Instant.now().plusSeconds(3600)).build();
        item = CartItem.builder().id(UUID.randomUUID()).cart(cart).skuId(UUID.randomUUID()).quantity(2)
                .unitPrice(new BigDecimal("90.00")).build();
        cart.getItems().add(item);
        lenient().when(carts.findActiveForCheckout(buyer)).thenReturn(Optional.of(cart));
        lenient().when(carts.findByUserIdAndStatus(buyer, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        lenient().when(items.findForCheckout(cart.getId())).thenAnswer(call -> new ArrayList<>(cart.getItems()));
        lenient().when(items.findByCartId(cart.getId())).thenAnswer(call -> new ArrayList<>(cart.getItems()));
        lenient().when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(seller, new BigDecimal("100.125"), true)));
        lenient().when(inventory.available(item.getSkuId())).thenReturn(10);
        lenient().when(orders.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clearSynchronization(); }

    private SkuInfo sku(UUID owner, BigDecimal price, boolean active) {
        return new SkuInfo(item.getSkuId(), "LIVE-SKU", "Tên mới từ Catalog", null, price, null, active, owner);
    }

    private CreateOrderRequest request(List<UUID> selected, String address, String method, String voucher, String source) {
        return new CreateOrderRequest(address, "Người nhận", "0901234567", voucher, method, source, null, selected);
    }

    private CreateOrderRequest request() { return request(null, "123 Lê Lợi", "COD", null, null); }

    private void complete(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }

    /** Đổi giá ở Catalog phải phản ánh vào snapshot; phí ship cấu hình và item không chọn được giữ lại. */
    @Test
    void createsLiveSnapshotAndKeepsUnselectedItems() {
        properties.getOrder().setShippingFee(new BigDecimal("5.00"));
        CartItem other = CartItem.builder().id(UUID.randomUUID()).skuId(UUID.randomUUID()).cart(cart).quantity(1).build();
        cart.getItems().add(other);
        var result = service.create(buyer, key, request(List.of(item.getId()), "123 Lê Lợi", "COD", null, null), "jwt");
        assertEquals(new BigDecimal("200.26"), result.subtotal());
        assertEquals(new BigDecimal("205.26"), result.total());
        assertEquals("COD", result.paymentMethod());
        assertEquals("PENDING_PAYMENT", result.status());
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(saved.capture());
        assertEquals(seller, saved.getValue().getSellerId());
        assertEquals("Tên mới từ Catalog", saved.getValue().getItems().getFirst().getProductName());
        assertEquals("LIVE-SKU", saved.getValue().getItems().getFirst().getSkuCode());
        assertEquals(2, saved.getValue().getItems().getFirst().getQuantity());
        assertEquals(List.of(other), cart.getItems());
        assertEquals(CartStatus.ACTIVE, cart.getStatus());
        verify(inventory).reserve(result.id(), List.of(new InventoryClient.Item(item.getSkuId(), null, 2)), "jwt");
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(inventory, never()).release(any(), any());
    }

    /** Retry cùng key/payload trả đơn cũ ngay cả khi giỏ đã rỗng; khác địa chỉ hoặc buyer trả conflict. */
    @Test
    void replaysBeforeReadingCartAndRejectsChangedPayloadOrBuyer() {
        var first = service.create(buyer, key, request(), "jwt");
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(saved.capture());
        when(orders.findByIdempotencyKey(key)).thenReturn(Optional.of(saved.getValue()));
        assertEquals(first, service.create(buyer, key.toUpperCase(), request(), "jwt"));
        assertEquals(CartStatus.CONVERTED, cart.getStatus());
        assertCode("ORDER_IDEMPOTENCY_CONFLICT", () -> service.create(buyer, key,
                request(null, "Địa chỉ khác", "COD", null, null), "jwt"));
        assertCode("ORDER_IDEMPOTENCY_CONFLICT", () -> service.create(UUID.randomUUID(), key, request(), "jwt"));
    }

    /** Preview cảnh báo đổi giá và hết kho nhưng không reserve hoặc ghi đơn. */
    @Test
    void previewReportsLivePriceAndAvailability() {
        var result = service.preview(buyer, new CheckoutPreviewRequest(null, "123 Lê Lợi", null));
        assertTrue(result.isOrderable());
        assertEquals(new BigDecimal("200.26"), result.totalAmount());
        assertEquals(1, result.warnings().size());
        when(inventory.available(item.getSkuId())).thenReturn(1);
        result = service.preview(buyer, new CheckoutPreviewRequest(List.of(item.getId()), "123 Lê Lợi", null));
        assertFalse(result.isOrderable());
        assertEquals(2, result.warnings().size());
        verify(orders, never()).saveAndFlush(any());
        verify(inventory, never()).reserve(any(), any(), any());
    }

    /** Callback rollback hoàn kho cả khi reserve timeout hoặc API kho báo thiếu hàng; lỗi release giữ nguyên lỗi gốc. */
    @Test
    void rollbackReleasesReservationEvenWhenReserveResponseFails() {
        doThrow(new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT)).when(inventory).reserve(any(), any(), any());
        assertCode("INVENTORY_INSUFFICIENT", () -> service.create(buyer, key, request(), "jwt"));
        doThrow(new IllegalStateException("network")).when(inventory).release(any(), any());
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(inventory).release(any(), any());
        assertFalse(cart.getItems().isEmpty());
    }

    /** Kết quả commit không xác định phải để đối soát; release ngay có thể làm mất hàng của đơn đã commit. */
    @Test
    void unknownCommitDoesNotReleasePossibleCommittedOrder() {
        service.create(buyer, key, request(), "jwt");
        complete(TransactionSynchronization.STATUS_UNKNOWN);
        verify(inventory, never()).release(any(), any());
    }

    /** Chặn lựa chọn ngoài phạm vi ticket và key không phải UUID trước khi ghi đơn hoặc gọi kho. */
    @Test
    void rejectsUnsupportedOptionsAndInvalidKey() {
        assertCode("CHECKOUT_INVALID_IDEMPOTENCY_KEY", () -> service.create(buyer, "1-1-1-1-1", request(), "jwt"));
        assertCode("CHECKOUT_INVALID_IDEMPOTENCY_KEY", () -> service.create(buyer, null, request(), "jwt"));
        assertCode("PAYMENT_METHOD_UNSUPPORTED", () -> service.create(buyer, key, request(null, "A", "VNPAY", null, null), "jwt"));
        assertCode("CHECKOUT_SOURCE_UNSUPPORTED", () -> service.create(buyer, key, request(null, "A", "COD", null, "AUCTION"), "jwt"));
        assertCode("CHECKOUT_VOUCHER_UNSUPPORTED", () -> service.create(buyer, key, request(null, "A", "COD", "SALE", null), "jwt"));
        verify(orders, never()).saveAndFlush(any());
    }

    /** Giỏ hết hạn/rỗng, item trùng hoặc thuộc người khác không được dùng để tạo đơn. */
    @Test
    void rejectsInvalidCartSelectionsAndDailyLimit() {
        assertCode("CART_ITEM_NOT_FOUND", () -> service.create(buyer, key, request(List.of(UUID.randomUUID()), "A", "COD", null, null), "jwt"));
        assertCode("CHECKOUT_INVALID_ITEMS", () -> service.create(buyer, key, request(List.of(item.getId(), item.getId()), "A", "COD", null, null), "jwt"));
        assertCode("CHECKOUT_INVALID_ITEMS", () -> service.create(buyer, key, request(List.of(), "A", "COD", null, null), "jwt"));
        cart.setExpiresAt(Instant.now().minusSeconds(1));
        assertCode("CHECKOUT_EXPIRED", () -> service.create(buyer, key, request(), "jwt"));
        cart.setExpiresAt(null);
        assertCode("CHECKOUT_EXPIRED", () -> service.create(buyer, key, request(), "jwt"));
        cart.setExpiresAt(Instant.now().plusSeconds(100));
        cart.getItems().clear();
        assertCode("CHECKOUT_EMPTY_CART", () -> service.create(buyer, key, request(), "jwt"));
        when(orders.countByBuyerIdAndCreatedAtGreaterThanEqual(any(), any())).thenReturn(10L);
        assertCode("ORDER_DAILY_LIMIT_EXCEEDED", () -> service.create(buyer, key, request(), "jwt"));
        verify(inventory, never()).reserve(any(), any(), any());
    }

    /** Không tin snapshot thiếu giá/seller, SKU ngừng bán, tự mua hoặc quantity sai ngay cả khi dữ liệu DB bị lỗi. */
    @Test
    void rejectsInvalidCatalogAndQuantities() {
        when(catalog.getSkuDetails(any())).thenReturn(Map.of());
        assertCode("CART_SKU_NOT_AVAILABLE", () -> service.create(buyer, key, request(), "jwt"));
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(seller, BigDecimal.ONE, false)));
        assertCode("CART_SKU_NOT_AVAILABLE", () -> service.create(buyer, key, request(), "jwt"));
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(null, BigDecimal.ONE, true)));
        assertCode("CATALOG_INVALID_RESPONSE", () -> service.create(buyer, key, request(), "jwt"));
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(buyer, BigDecimal.ONE, true)));
        assertCode("ORDER_SELLER_CANNOT_BUY_OWN_PRODUCT", () -> service.create(buyer, key, request(), "jwt"));
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(seller, BigDecimal.ONE, true)));
        item.setQuantity(0);
        assertCode("CHECKOUT_INVALID_QUANTITY", () -> service.create(buyer, key, request(), "jwt"));
        item.setQuantity(null);
        assertCode("CHECKOUT_INVALID_QUANTITY", () -> service.create(buyer, key, request(), "jwt"));
        item.setQuantity(2);
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(seller, new BigDecimal("9999999999999.99"), true)));
        assertCode("CHECKOUT_AMOUNT_EXCEEDED", () -> service.create(buyer, key, request(), "jwt"));
    }

    /** Schema Order chỉ có một seller nên chọn nhiều shop phải báo lỗi thay vì gán nhầm người bán. */
    @Test
    void rejectsMultipleShopsAndItemLimit() {
        CartItem other = CartItem.builder().id(UUID.randomUUID()).skuId(UUID.randomUUID()).cart(cart).quantity(1).build();
        cart.getItems().add(other);
        var second = new SkuInfo(other.getSkuId(), "OTHER", "Other", null, BigDecimal.ONE, null, true, UUID.randomUUID());
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(item.getSkuId(), sku(seller, BigDecimal.ONE, true), other.getSkuId(), second));
        assertCode("CHECKOUT_MULTIPLE_SELLERS", () -> service.create(buyer, key, request(), "jwt"));
        properties.getCart().setMaxItemsPerCart(1);
        assertCode("CART_MAX_ITEMS_EXCEEDED", () -> service.create(buyer, key, request(), "jwt"));
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ApiException.class, action).getCode());
    }
}
