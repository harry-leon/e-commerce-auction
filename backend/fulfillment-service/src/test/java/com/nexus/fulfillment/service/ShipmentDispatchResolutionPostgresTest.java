package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovery chạy repository/Flyway/PostgreSQL thật, không gọi adapter hoặc query carrier; bằng chứng chỉ là fixture. */
@DataJpaTest(showSql = false, properties = {"spring.datasource.url=${FULFILLMENT_TEST_DB_URL}", "spring.datasource.username=postgres",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ShipmentDispatchResolutionService.class, FulfillmentEventOutboxService.class, ShipmentDispatchResolutionPostgresTest.Config.class})
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShipmentDispatchResolutionPostgresTest {
    @Autowired private ShipmentDispatchResolutionService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper dispatchObjectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    /** Lost ACK được khôi phục local một lần; replay key không thêm shipment/outbox/audit, đổi nội dung bị conflict. */
    @Test
    void restoresShipmentAndOutboxAtomicallyWithoutCallingCarrier() throws Exception {
        UUID attempt = fixture();
        UUID actor = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var command = command("RECOVERY-" + attempt);
        var result = service.resolve(attempt, actor, key, command);
        assertEquals(result, service.resolve(attempt, actor, key, command));
        assertEquals("COMMITTED", state(attempt));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM shipments WHERE id=?", String.class, result.shipmentId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_resolutions WHERE attempt_id=?", Integer.class, attempt));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fulfillment_event_outbox WHERE aggregate_id=?", Integer.class, result.shipmentId()));
        assertThrows(ApiException.class, () -> service.resolve(attempt, actor, key, command("OTHER")));
        assertThrows(ApiException.class, () -> service.resolve(attempt, actor, UUID.randomUUID(), command));
    }

    /** Nếu transaction quản trị rollback, shipment/history/outbox/audit và state đều không được lưu nửa chừng. */
    @Test
    void rollbackLeavesAttemptUnknownAndDoesNotLeakShipmentOrAudit() throws Exception {
        UUID attempt = fixture();
        UUID[] shipment = new UUID[1];
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            shipment[0] = service.resolve(attempt, UUID.randomUUID(), UUID.randomUUID(), command("ROLLBACK-" + attempt)).shipmentId();
            tx.setRollbackOnly();
        });
        assertEquals("UNKNOWN", state(attempt));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shipments WHERE id=?", Integer.class, shipment[0]));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_resolutions WHERE attempt_id=?", Integer.class, attempt));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM fulfillment_event_outbox WHERE aggregate_id=?", Integer.class, shipment[0]));
    }

    /** Hai Admin cùng chốt chỉ một người tạo shipment/audit; người còn lại phải đối chiếu terminal thay vì ghi đè. */
    @Test
    void concurrentAdminsCannotCreateTwoRecoveredShipments() throws Exception {
        UUID attempt = fixture();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> resolve = () -> {
                start.await();
                try { service.resolve(attempt, UUID.randomUUID(), UUID.randomUUID(), command("CONCURRENT-" + attempt)); return true; }
                catch (ApiException conflict) { return false; }
            };
            var first = pool.submit(resolve); var second = pool.submit(resolve);
            start.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_resolutions WHERE attempt_id=?", Integer.class, attempt));
    }

    /** Tracking đã được guard nhận không được thay bằng tracking khác do Admin nhập nhầm. */
    @Test
    void rejectsTrackingContradictionAndMissingEvidence() throws Exception {
        UUID attempt = fixture();
        jdbc.update("UPDATE shipment_dispatch_attempts SET tracking_number='ACK-ORIGINAL' WHERE id=?", attempt);
        assertThrows(ApiException.class, () -> service.resolve(attempt, UUID.randomUUID(), UUID.randomUUID(), command("WRONG")));
        assertThrows(ApiException.class, () -> service.resolve(attempt, UUID.randomUUID(), UUID.randomUUID(),
                new ShipmentDispatchResolutionService.Command("ACK-ORIGINAL", BigDecimal.ZERO, " ", "Note")));
        assertEquals("UNKNOWN", state(attempt));
    }

    /** Snapshot/warehouse do test tạo riêng, không dùng hoặc thay thông tin người nhận thực. */
    private UUID fixture() throws Exception {
        UUID attempt = UUID.randomUUID(); UUID order = UUID.randomUUID(); UUID warehouse = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses(id,code,name,address) VALUES (?,?,?,?)", warehouse, "R-" + warehouse.toString().substring(0, 12), "Test", "Test");
        var snapshot = new CarrierCreateShipmentRequest(order, warehouse, "Kho test", "Người nhận test", "0900000000", "Địa chỉ test",
                12, "WARD", 725, BigDecimal.ZERO, new BigDecimal("50000"), "STANDARD", "Fixture");
        jdbc.update("INSERT INTO shipment_dispatch_attempts(id,order_id,carrier_code,request_payload,status) VALUES (?,?,?,?,'UNKNOWN')",
                attempt, order, "GHN", new ObjectMapper().writeValueAsString(snapshot));
        jdbc.update("INSERT INTO shipment_dispatch_locks(order_id) VALUES (?)", order);
        return attempt;
    }
    private String state(UUID id) { return jdbc.queryForObject("SELECT status FROM shipment_dispatch_attempts WHERE id=?", String.class, id); }
    private ShipmentDispatchResolutionService.Command command(String tracking) {
        return new ShipmentDispatchResolutionService.Command(tracking, new BigDecimal("25000"), "CASE-TEST", "Đã đối soát carrier fixture");
    }
}
