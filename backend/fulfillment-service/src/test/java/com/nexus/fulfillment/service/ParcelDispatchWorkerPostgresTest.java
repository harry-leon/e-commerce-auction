package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.fulfillment.client.CommerceShippingClient;
import com.nexus.fulfillment.client.DispatchCommerceClient;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Schema PostgreSQL riêng mỗi ca, lease/CAS/commit thật; Commerce và ShippingService là boundary mock.
 * Không gọi carrier. Observer dùng DataSource khác để chứng minh DISPATCHING đã commit trước ShippingService.
 */
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
class ParcelDispatchWorkerPostgresTest {
    private JdbcTemplate root;
    private JdbcTemplate jdbc;
    private JdbcTemplate observer;
    private DataSourceTransactionManager manager;
    private String schema;
    private UUID order;
    private UUID warehouse;
    private UUID parcel;
    private DispatchCommerceClient.Claim claim;
    private DispatchCommerceClient.Authorization authorization;
    private DispatchCommerceClient remote;
    private ShippingService shipping;
    private ParcelDispatchWorker worker;

    /** Tạo toàn bộ Flyway trong schema UUID, ghi kiện bằng service thật; không sửa fixture/DB của service khác. */
    @BeforeEach
    void setup() {
        String url = System.getenv("FULFILLMENT_TEST_DB_URL");
        root = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        schema = "parcel_job_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE SCHEMA " + schema);
        String scoped = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var source = new DriverManagerDataSource(scoped, "postgres", "");
        jdbc = new JdbcTemplate(source);
        observer = new JdbcTemplate(new DriverManagerDataSource(scoped, "postgres", ""));
        manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).schemas(schema).locations("classpath:db/migration").load().migrate();
        order = UUID.randomUUID(); warehouse = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses(id,code,name,address,type,is_active) VALUES (?,?,?,'Fixture','STANDARD',true)",
                warehouse, "J-" + warehouse.toString().substring(0, 20), "Job fixture");
        parcel = new TransactionTemplate(manager).execute(tx -> new ShipmentParcelService(jdbc).register(order, UUID.randomUUID(), UUID.randomUUID(),
                new ShipmentPreparationService.Parcel(warehouse, "GHN", "STANDARD", 725, 12, "WARD")).registration().id());
        remote = org.mockito.Mockito.mock(DispatchCommerceClient.class);
        shipping = org.mockito.Mockito.mock(ShippingService.class);
        worker = new ParcelDispatchWorker(jdbc, remote, shipping, manager, true);
        UUID handoff = UUID.randomUUID();
        claim = new DispatchCommerceClient.Claim(UUID.randomUUID(), order, handoff, parcel, Instant.now());
        authorization = new DispatchCommerceClient.Authorization(claim,
                new CommerceShippingClient.Snapshot(order, "FIXTURE", "PAID", "Fixture", "0900000000", "Fixture address", "VNPAY", true, BigDecimal.TEN, null));
        when(remote.configured()).thenReturn(true);
        when(remote.handoff(order)).thenReturn(new DispatchCommerceClient.Handoff(handoff, order, "WAITING_PARCEL"));
        when(remote.claim(eq(order), eq(handoff), eq(parcel), any())).thenReturn(claim);
        when(remote.preflight(claim)).thenReturn(authorization);
    }

    /** Chỉ DROP schema UUID do ca này tạo; không xóa cluster/table mặc định hoặc dữ liệu nhóm. */
    @AfterEach
    void cleanup() {
        if (root != null && schema != null && schema.matches("parcel_job_[0-9a-f]{32}")) { root.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    /** Mất ACK claim giữ cùng job/key khi retry; dấu DISPATCHING phải nhìn thấy qua kết nối DB khác trước callback tạo shipment. */
    @Test
    void retriesLostClaimAckWithSameKeyAndCommitsBeforeShipping() {
        when(remote.claim(eq(order), eq(claim.handoffId()), eq(parcel), any()))
                .thenThrow(new DispatchCommerceClient.Fault(0)).thenReturn(claim);
        shippingSucceeds();
        worker.run(); assertEquals("WAITING", state()); UUID job = job();
        due(); worker.run();
        assertEquals("COMMITTED", state());
        worker.run();
        verify(remote, times(2)).claim(order, claim.handoffId(), parcel, job);
        verify(shipping, times(1)).createShipment(any(), eq("dispatch-job:" + job));
        assertEquals(claim.id(), jdbc.queryForObject("SELECT claim_id FROM shipment_dispatch_jobs WHERE id=?", UUID.class, job));
    }

    /** Worker mới tiếp quản lease trong khi response cũ đang chờ; token cũ không được đánh dấu DISPATCHING hoặc gửi lại. */
    @Test
    void fencesStaleWorkerAfterLeaseTakeover() {
        shippingSucceeds(); var first = new AtomicBoolean(true);
        when(remote.preflight(claim)).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                jdbc.update("UPDATE shipment_dispatch_jobs SET lease_expires_at=now()-interval '1 second' WHERE order_id=?", order);
                worker.run();
            }
            return authorization;
        });
        worker.run();
        assertEquals("COMMITTED", state());
        verify(shipping, times(1)).createShipment(any(), any());
        verify(remote, times(2)).claim(order, claim.handoffId(), parcel, job());
    }

    /** Crash sau dấu DISPATCHING dù chưa biết carrier đã nhận vẫn không tự gửi lại; lease hết hạn chỉ chuyển REVIEW. */
    @Test
    void crashAfterDispatchMarkerNeverResendsCarrier() {
        when(shipping.createShipment(any(), any())).thenThrow(new AssertionError("fixture crash"));
        assertThrows(AssertionError.class, worker::run);
        assertEquals("DISPATCHING", state());
        worker.run(); assertEquals("DISPATCHING", state());
        jdbc.update("UPDATE shipment_dispatch_jobs SET lease_expires_at=now()-interval '1 second' WHERE order_id=?", order);
        worker.run(); assertEquals("REQUIRES_REVIEW", state());
        verify(shipping, times(1)).createShipment(any(), any());
    }

    /** Hai instance tranh cùng kiện chỉ một job/claim/send; transaction không giữ khóa trong mock HTTP. */
    @Test
    void concurrentWorkersOnlyDispatchOnce() throws Exception {
        shippingSucceeds(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); worker.run(); return true; });
            var b = pool.submit(() -> { gate.await(); worker.run(); return true; });
            gate.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        assertEquals("COMMITTED", state());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_jobs", Integer.class));
        verify(shipping, times(1)).createShipment(any(), any());
    }

    /** Chờ handoff không tiêu ngân sách; tám lỗi HTTP tạm thời dừng REVIEW và không gọi carrier. */
    @Test
    void waitsForHandoffAndBoundsRetryBudget() {
        when(remote.handoff(order)).thenThrow(new DispatchCommerceClient.Fault(404));
        worker.run(); assertEquals("WAITING", state());
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM shipment_dispatch_jobs", Integer.class));
        org.mockito.Mockito.doThrow(new DispatchCommerceClient.Fault(503)).when(remote).handoff(order);
        for (int i = 0; i < 8; i++) { due(); worker.run(); }
        assertEquals("REQUIRES_REVIEW", state());
        assertEquals(8, jdbc.queryForObject("SELECT attempts FROM shipment_dispatch_jobs", Integer.class));
        verifyNoInteractions(shipping);
    }

    /** Preflight phát hiện review muộn phải giữ claimId để đối soát, không tiến tới DISPATCHING. */
    @Test
    void preflightRejectionKeepsClaimAndStopsBeforeCarrier() {
        when(remote.preflight(claim)).thenThrow(new DispatchCommerceClient.Fault(409));
        worker.run(); assertEquals("REQUIRES_REVIEW", state());
        assertEquals(claim.id(), jdbc.queryForObject("SELECT claim_id FROM shipment_dispatch_jobs", UUID.class));
        verifyNoInteractions(shipping);
    }

    /** Default-off và thiếu OAuth không seed job hoặc chạm ShippingService. */
    @Test
    void disabledOrUnconfiguredDoesNotCreateJobs() {
        new ParcelDispatchWorker(jdbc, remote, shipping, manager, false).run();
        when(remote.configured()).thenReturn(false); worker.run();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_jobs", Integer.class));
        verifyNoInteractions(shipping);
    }

    /** Lease claim hết hạn ở lần thứ tám không mở lần gọi thứ chín. */
    @Test
    void expiredClaimLeaseHonorsRetryBudget() {
        when(remote.handoff(order)).thenThrow(new DispatchCommerceClient.Fault(404)); worker.run();
        jdbc.update("UPDATE shipment_dispatch_jobs SET state='CLAIMING',attempts=8,lease_expires_at=now()-interval '1 second'");
        worker.run(); assertEquals("REQUIRES_REVIEW", state());
        verify(remote, times(1)).handoff(order); verifyNoInteractions(shipping);
    }

    /** Boundary mock lưu shipment thật với đúng key, không gọi carrier; observer tách DataSource khỏi transaction worker. */
    private void shippingSucceeds() {
        when(shipping.createShipment(any(), any())).thenAnswer(invocation -> {
            CreateShipmentRequest request = invocation.getArgument(0); String key = invocation.getArgument(1);
            assertEquals("DISPATCHING", observer.queryForObject("SELECT state FROM shipment_dispatch_jobs WHERE order_id=?", String.class, order));
            assertEquals(claim.id(), observer.queryForObject("SELECT claim_id FROM shipment_dispatch_jobs WHERE order_id=?", UUID.class, order));
            assertEquals(725, request.weightGrams()); assertEquals(BigDecimal.ZERO, request.codAmount());
            UUID id = UUID.randomUUID(); String tracking = "FIXTURE-" + id;
            jdbc.update("INSERT INTO shipments(id,order_id,warehouse_id,carrier_id,carrier_code,status,tracking_no,idempotency_key) "
                    + "VALUES (?,?,?,(SELECT id FROM carriers WHERE code='GHN'),'GHN','CREATED',?,?)", id, order, warehouse, tracking, key);
            return new ShipmentResponse(id, order, warehouse, "FIXTURE", "GHN", "STANDARD", BigDecimal.ZERO, false, tracking,
                    ShipmentStatus.CREATED, null, null, null, null, null, Instant.now(), Instant.now());
        });
    }

    private UUID job() { return jdbc.queryForObject("SELECT id FROM shipment_dispatch_jobs WHERE order_id=?", UUID.class, order); }
    private String state() { return jdbc.queryForObject("SELECT state FROM shipment_dispatch_jobs WHERE order_id=?", String.class, order); }
    private void due() { jdbc.update("UPDATE shipment_dispatch_jobs SET next_attempt_at=now() WHERE order_id=?", order); }
}
