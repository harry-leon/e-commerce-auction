package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexus.common.exception.ApiException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL/Flyway thật bảo vệ kiện/audit bất biến, race nhiều Admin và rollback; không gọi Commerce/carrier. */
@DataJpaTest(showSql = false, properties = {"spring.datasource.url=${FULFILLMENT_TEST_DB_URL}", "spring.datasource.username=postgres",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ShipmentParcelService.class)
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShipmentParcelPostgresTest {
    @Autowired private ShipmentParcelService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID order;
    private UUID warehouse;
    private UUID actor;
    private UUID key;

    /** Mỗi ca dùng UUID/kho riêng; giữ carrier GHN đã seed bởi migration, không sửa dữ liệu của test khác. */
    @BeforeEach
    void setup() {
        order = UUID.randomUUID(); warehouse = UUID.randomUUID(); actor = UUID.randomUUID(); key = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses(id,code,name,address,type,is_active) VALUES (?,?,?,'Fixture','STANDARD',true)",
                warehouse, "P-" + warehouse.toString().substring(0, 20), "Parcel fixture");
    }

    /** Cùng khai báo chuẩn hóa giữ id/audit; replay vẫn trả bản cũ nếu kho đã ngưng hoạt động, không giả là dispatch-ready. */
    @Test
    void replaysCanonicalDeclarationWithoutChangingAudit() {
        var first = service.register(order, actor, key, parcel());
        assertEquals(true, first.created());
        jdbc.update("UPDATE warehouses SET is_active=false WHERE id=?", warehouse);
        var replay = service.register(order, actor, key, new ShipmentPreparationService.Parcel(warehouse, " ghn ", " standard ", 725, 12, " WARD "));
        assertEquals(false, replay.created());
        assertEquals(first.registration(), replay.registration());
        assertEquals(first.registration(), service.get(order));
        assertEquals(1, count());
        assertEquals(409, assertThrows(ApiException.class, () -> service.register(UUID.randomUUID(), actor, key, parcel())).getHttpStatus().value());
        assertEquals(409, assertThrows(ApiException.class, () -> service.register(order, actor, key,
                new ShipmentPreparationService.Parcel(warehouse, "GHN", "STANDARD", 726, 12, "WARD"))).getHttpStatus().value());
    }

    /** Hai request cùng key trả cùng row; hai key/actor khác nhau cho cùng Order chỉ một request được tạo. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRegistrationsNeverOverwriteParcel(boolean sameKey) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> first = () -> { gate.await(); return service.register(order, actor, key, parcel()).created(); };
            java.util.concurrent.Callable<Boolean> second = () -> {
                gate.await();
                try { return service.register(order, sameKey ? actor : UUID.randomUUID(), sameKey ? key : UUID.randomUUID(), parcel()).created(); }
                catch (ApiException error) { if (sameKey) { throw error; } assertEquals(409, error.getHttpStatus().value()); return false; }
            };
            var a = pool.submit(first); var b = pool.submit(second); gate.countDown();
            // Với hai key, first cũng có thể thua: xử lý outcome ở cả hai nhánh bên dưới.
            boolean left;
            try { left = a.get(10, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException error) {
                if (sameKey) { throw error; }
                assertEquals(409, ((ApiException) error.getCause()).getHttpStatus().value()); left = false;
            }
            assertEquals(1, (left ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, count());
    }

    /** Outer transaction rollback phải xóa cả kiện/audit, không để key giữ một lần ghi chưa commit. */
    @Test
    void rollbackDoesNotReserveKeyOrOrder() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            service.register(order, actor, key, parcel()); tx.setRollbackOnly();
        });
        assertEquals(0, count());
        assertEquals(true, service.register(order, actor, key, parcel()).created());
    }

    /** Dữ liệu thiếu/kho tắt và dấu dispatch UNKNOWN không được tạo kiện hoặc bị coi như có thể gửi lại carrier. */
    @Test
    void validatesResourcesAndRejectsExistingDispatch() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(order)).getHttpStatus().value());
        assertEquals(400, assertThrows(ApiException.class, () -> service.register(order, actor, key, null)).getHttpStatus().value());
        assertEquals(400, assertThrows(ApiException.class, () -> service.register(order, actor, key,
                new ShipmentPreparationService.Parcel(warehouse, "GHN", "STANDARD", 0, 12, "WARD"))).getHttpStatus().value());
        jdbc.update("UPDATE warehouses SET is_active=false WHERE id=?", warehouse);
        assertEquals(409, assertThrows(ApiException.class, () -> service.register(order, actor, key, parcel())).getHttpStatus().value());
        jdbc.update("UPDATE warehouses SET is_active=true WHERE id=?", warehouse);
        jdbc.update("INSERT INTO shipment_dispatch_attempts(id,order_id,carrier_code,request_payload,status) VALUES (?,?,'GHN','{}','UNKNOWN')",
                UUID.randomUUID(), order);
        assertEquals(409, assertThrows(ApiException.class, () -> service.register(order, actor, key, parcel())).getHttpStatus().value());
        assertEquals(0, count());
    }

    private ShipmentPreparationService.Parcel parcel() {
        return new ShipmentPreparationService.Parcel(warehouse, "GHN", "STANDARD", 725, 12, "WARD");
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM shipment_parcels WHERE order_id=?", Integer.class, order); }
}
