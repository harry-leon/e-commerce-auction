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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/**
 * PostgreSQL/Flyway/transaction và manual-resolution service thật; claimId/provider evidence chỉ là fixture.
 * Không gọi Commerce/carrier hoặc suy ra sandbox đã đạt từ các bản ghi mô phỏng.
 */
@DataJpaTest(showSql = false, properties = {"spring.datasource.url=${FULFILLMENT_TEST_DB_URL}", "spring.datasource.username=postgres",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DispatchJobReconciliationService.class, ShipmentParcelService.class, ShipmentDispatchResolutionService.class,
        FulfillmentEventOutboxService.class, DispatchJobReconciliationPostgresTest.Config.class})
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DispatchJobReconciliationPostgresTest {
    @Autowired private DispatchJobReconciliationService jobs;
    @Autowired private ShipmentParcelService parcels;
    @Autowired private ShipmentDispatchResolutionService recovery;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID job;
    private UUID order;
    private UUID attempt;
    private UUID claim;
    private UUID warehouse;
    private String payload;

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper dispatchObjectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    /** Kiện ghi qua service trước dấu dispatch; snapshot/job REVIEW mô phỏng crash sau claim, không tạo quyền tài chính thật. */
    @BeforeEach
    void setup() throws Exception {
        job = UUID.randomUUID(); order = UUID.randomUUID(); attempt = UUID.randomUUID(); claim = UUID.randomUUID(); warehouse = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses(id,code,name,address,type,is_active) VALUES (?,?,?,'Fixture','STANDARD',true)",
                warehouse, "R-" + warehouse.toString().substring(0, 20), "Reconcile fixture");
        var parcel = parcels.register(order, UUID.randomUUID(), UUID.randomUUID(),
                new ShipmentPreparationService.Parcel(warehouse, "GHN", "STANDARD", 725, 12, "WARD")).registration();
        payload = new ObjectMapper().writeValueAsString(new CarrierCreateShipmentRequest(order, warehouse, "Fixture",
                "Recipient fixture", "0900000000", "Address fixture", 12, "WARD", 725, BigDecimal.ZERO, new BigDecimal("50000"), "STANDARD", "Fixture"));
        jdbc.update("INSERT INTO shipment_dispatch_attempts(id,order_id,carrier_code,request_payload,status) VALUES (?,?,'GHN',?,'UNKNOWN')", attempt, order, payload);
        jdbc.update("INSERT INTO shipment_dispatch_locks(order_id) VALUES (?)", order);
        jdbc.update("INSERT INTO shipment_dispatch_jobs(id,parcel_id,order_id,claim_id,state) VALUES (?,?,?,?,'REQUIRES_REVIEW')",
                job, parcel.id(), order, claim);
    }

    /** Khôi phục shipment thật qua service rồi đóng job/audit một lần; không phát lại outbox hoặc sửa claim/guard payload. */
    @Test
    void reconcilesRecoveredShipmentAndReplaysAudit() {
        UUID shipment = recovered(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID();
        var first = jobs.reconcile(job, actor, key, command());
        assertEquals(true, first.created()); assertEquals(shipment, first.resolution().shipmentId());
        assertEquals(false, jobs.reconcile(job, actor, key, command()).created());
        assertEquals(first.resolution(), jobs.reconcile(job, actor, key, command()).resolution());
        assertEquals("COMMITTED", jobs.get(job).state()); assertEquals(claim, jobs.get(job).claimId());
        assertEquals(1, auditCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fulfillment_event_outbox WHERE aggregate_id=?", Integer.class, shipment));
        assertEquals(payload, jdbc.queryForObject("SELECT request_payload FROM shipment_dispatch_attempts WHERE id=?", String.class, attempt));
        assertThrows(ApiException.class, () -> jobs.reconcile(job, actor, key, new DispatchJobReconciliationService.Command("OTHER", "Other")));
        assertThrows(ApiException.class, () -> jobs.reconcile(job, actor, UUID.randomUUID(), command()));
    }

    /** Hai Admin/key hoặc cùng key chỉ một audit; loser khác key nhận 409, cùng key nhận replay. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentReconciliationCannotCreateTwoAudits(boolean sameKey) throws Exception {
        recovered(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> action = () -> {
                gate.await();
                try { return jobs.reconcile(job, sameKey ? actor : UUID.randomUUID(), sameKey ? key : UUID.randomUUID(), command()).created(); }
                catch (ApiException error) { if (sameKey) { throw error; } assertEquals(409, error.getHttpStatus().value()); return false; }
            };
            var first = pool.submit(action); var second = pool.submit(action); gate.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, auditCount()); assertEquals("COMMITTED", jobs.get(job).state());
    }

    /** Rollback phải giữ REVIEW và không giữ audit/key giả; retry cùng key sau rollback vẫn tạo được quyết định. */
    @Test
    void auditAndJobStateRollbackTogether() {
        recovered(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID();
        new TransactionTemplate(manager).executeWithoutResult(tx -> { jobs.reconcile(job, actor, key, command()); tx.setRollbackOnly(); });
        assertEquals("REQUIRES_REVIEW", jobs.get(job).state()); assertEquals(0, auditCount());
        assertEquals(true, jobs.reconcile(job, actor, key, command()).created());
    }

    /** Guard UNKNOWN, snapshot lệch kiện hoặc recipient bị đổi phải chặn trước audit, không fallback dữ liệu hiện tại. */
    @Test
    void rejectsUnconfirmedOrMismatchedEvidence() {
        UUID shipment = recovered();
        jdbc.update("UPDATE shipment_dispatch_attempts SET status='UNKNOWN' WHERE id=?", attempt); conflict();
        jdbc.update("UPDATE shipment_dispatch_attempts SET status='COMMITTED',request_payload=? WHERE id=?", payload.replace("725", "726"), attempt); conflict();
        jdbc.update("UPDATE shipment_dispatch_attempts SET request_payload=? WHERE id=?", payload, attempt);
        jdbc.update("UPDATE shipments SET recipient_phone='OTHER' WHERE id=?", shipment); conflict();
        assertEquals(0, auditCount()); assertEquals("REQUIRES_REVIEW", jobs.get(job).state());
    }

    /** Shipment key recovery cần audit nguồn; mô phỏng ACK local đúng job key để kiểm tra nhánh không dùng manual audit. */
    @Test
    void requiresOwnJobKeyOrMatchingManualAudit() {
        UUID shipment = recovered();
        // Fault injection riêng fixture, không phải hướng dẫn xóa audit/key trong vận hành.
        jdbc.update("DELETE FROM shipment_dispatch_resolutions WHERE attempt_id=?", attempt); conflict();
        jdbc.update("UPDATE shipments SET idempotency_key=? WHERE id=?", "dispatch-job:" + job, shipment);
        assertEquals(shipment, jobs.reconcile(job, UUID.randomUUID(), UUID.randomUUID(), command()).resolution().shipmentId());
    }

    /** Job còn chạy/thiếu claim không được đóng, kể cả đã có shipment; input thiếu và job lạ trả đúng lỗi. */
    @Test
    void rejectsRunningOrUnclaimedJobsAndInvalidInput() {
        recovered();
        jdbc.update("UPDATE shipment_dispatch_jobs SET state='DISPATCHING' WHERE id=?", job); conflict();
        jdbc.update("UPDATE shipment_dispatch_jobs SET state='REQUIRES_REVIEW',claim_id=NULL WHERE id=?", job); conflict();
        assertEquals(400, assertThrows(ApiException.class, () -> jobs.reconcile(job, UUID.randomUUID(), UUID.randomUUID(), null)).getHttpStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> jobs.get(UUID.randomUUID())).getHttpStatus().value());
        assertEquals(0, auditCount());
    }

    /** Lifecycle lock đang do transaction khác giữ phải chờ, không chỉ dựa vào tuổi REVIEW để bỏ qua request đang chạy. */
    @Test
    void waitsForLifecycleBeforeClosingJob() throws Exception {
        recovered(); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holding = pool.submit(() -> {
                new TransactionTemplate(manager).executeWithoutResult(tx -> {
                    jdbc.queryForList("SELECT order_id FROM shipment_dispatch_locks WHERE order_id=? FOR UPDATE", order);
                    locked.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
                });
            });
            locked.await(5, TimeUnit.SECONDS);
            var closing = pool.submit(() -> jobs.reconcile(job, UUID.randomUUID(), UUID.randomUUID(), command()));
            try { assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(200, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            holding.get(5, TimeUnit.SECONDS); assertEquals(true, closing.get(5, TimeUnit.SECONDS).created());
        }
    }

    private UUID recovered() {
        return recovery.resolve(attempt, UUID.randomUUID(), UUID.randomUUID(),
                new ShipmentDispatchResolutionService.Command("FIXTURE-" + attempt, BigDecimal.TEN, "CASE-FIXTURE", "Đã đối soát fixture")).shipmentId();
    }
    private DispatchJobReconciliationService.Command command() { return new DispatchJobReconciliationService.Command("CASE-JOB", "Đối chiếu shipment fixture"); }
    private void conflict() { assertEquals(409, assertThrows(ApiException.class, () -> jobs.reconcile(job, UUID.randomUUID(), UUID.randomUUID(), command())).getHttpStatus().value()); }
    private int auditCount() { return jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_job_reconciliations WHERE job_id=?", Integer.class, job); }
}
