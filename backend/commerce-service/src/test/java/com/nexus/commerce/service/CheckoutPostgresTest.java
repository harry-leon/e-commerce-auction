package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.config.CheckoutTransactionConfig;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.CreateOrderResponse;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chạy trên PostgreSQL tạm qua NEXUS_TEST_DB_URL: Flyway/Hibernate thật, khóa DB và transaction commit thật.
 * Catalog/kho được mock ở ranh giới HTTP để chủ động gây lỗi sau reserve mà không phụ thuộc môi trường ngoài.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationOutbox.class, CheckoutService.class, CheckoutLock.class, CheckoutTransactionConfig.class, CheckoutPostgresTest.Config.class})
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CheckoutPostgresTest {
    @Autowired private CheckoutService checkout;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private InventoryClient inventory;
    // Các ca transaction COD cô lập cổng payment; suite PaymentPostgresTest kiểm tra payment thật trong DB.
    @MockitoBean private PaymentService payments;
    private UUID buyer;
    private UUID item;
    private UUID cart;

    @TestConfiguration
    static class Config {
        @Bean CommerceProperties commerceProperties() { return new CommerceProperties(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @BeforeEach
    void setup() {
        // Mỗi tình huống dùng buyer/giỏ/SKU riêng; không xóa dữ liệu test khác trong cùng DB tạm.
        buyer = UUID.randomUUID();
        cart = UUID.randomUUID();
        item = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, buyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,2,80)", item, cart, sku);
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("100.00"), null, true, UUID.randomUUID())));
        // Các ca transaction này bắt đầu với kho đủ hàng để đi tới bước ghi đơn/reserve cần kiểm chứng.
        when(inventory.available(sku)).thenReturn(10);
    }

    private CreateOrderRequest request() {
        return new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "COD", null, null, List.of(item));
    }

    /** Hai thread bắt đầu cùng lúc phải nhận cùng orderId và chỉ một lần gọi reserve, kể cả sau khi cart đã xóa. */
    @Test
    void concurrentSameKeyCommitsExactlyOneOrderAndReservation() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return checkout.create(buyer, key, request(), "jwt"); });
            var second = executor.submit(() -> { start.await(); return checkout.create(buyer, key, request(), "jwt"); });
            start.countDown();
            CreateOrderResponse result = first.get(20, TimeUnit.SECONDS);
            assertEquals(result, second.get(20, TimeUnit.SECONDS));
            // Tạo COD chưa xác nhận thu tiền: chỉ estimated_total có giá, official total phải null.
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, result.id()));
            assertEquals(new BigDecimal("200.00"), jdbc.queryForObject("SELECT estimated_total FROM orders WHERE id=?", BigDecimal.class, result.id()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id=?", Integer.class, result.id()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE cart_id=?", Integer.class, cart));
            assertEquals("CONVERTED", jdbc.queryForObject("SELECT status FROM carts WHERE id=?", String.class, cart));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders WHERE buyer_id=?", Integer.class, buyer));
        }
        verify(inventory, times(1)).reserve(any(), any(), any());
        verify(inventory, never()).release(any(), any());
    }

    /** Hai key khác nhau vẫn không thể tiêu thụ cùng item hai lần; request thua đọc giỏ sau commit đầu tiên. */
    @Test
    void concurrentDifferentKeysCannotPurchaseSameCartTwice() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return checkout.create(buyer, UUID.randomUUID().toString(), request(), "jwt"); });
            var second = executor.submit(() -> { start.await(); return checkout.create(buyer, UUID.randomUUID().toString(), request(), "jwt"); });
            start.countDown();
            int success = 0;
            for (var future : List.of(first, second)) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                    success++;
                } catch (ExecutionException error) {
                    assertEquals("CART_NOT_FOUND", ((BusinessException) error.getCause()).getCode());
                }
            }
            assertEquals(1, success);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders WHERE buyer_id=?", Integer.class, buyer));
        }
        verify(inventory, times(1)).reserve(any(), any(), any());
    }

    /** Kho báo thiếu hàng: rollback xóa đơn nháp và snapshot, giỏ nguyên vẹn, callback hoàn kho theo refId. */
    @Test
    void insufficientStockRollsBackOrderAndPreservesCart() {
        doThrow(new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT)).when(inventory).reserve(any(), any(), any());
        assertThrows(BusinessException.class, () -> checkout.create(buyer, UUID.randomUUID().toString(), request(), "jwt"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders WHERE buyer_id=?", Integer.class, buyer));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE id=?", Integer.class, item));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM carts WHERE id=?", String.class, cart));
        verify(inventory).release(any(), any());
    }

    /** Lỗi chỉ nổ khi commit (sau reserve thành công) vẫn phải kích hoạt bù trừ và rollback toàn bộ DB. */
    @Test
    void deferredCommitFailureReleasesStockAndRestoresCart() {
        jdbc.execute("""
                CREATE FUNCTION checkout_test_reject_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'checkout test commit failure'; END; $$
                """);
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER checkout_test_commit_failure AFTER INSERT ON orders
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION checkout_test_reject_commit()
                """);
        try {
            assertThrows(RuntimeException.class, () -> checkout.create(buyer, UUID.randomUUID().toString(), request(), "jwt"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders WHERE buyer_id=?", Integer.class, buyer));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cart_items WHERE id=?", Integer.class, item));
            verify(inventory).reserve(any(), any(), any());
            verify(inventory).release(any(), any());
        } finally {
            jdbc.execute("DROP TRIGGER checkout_test_commit_failure ON orders");
            jdbc.execute("DROP FUNCTION checkout_test_reject_commit()");
        }
    }
}
