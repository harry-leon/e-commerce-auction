package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.AuctionTimeoutSyncClient;
import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.commerce.client.NotificationDelivery;
import com.nexus.commerce.client.UserContactClient;
import com.nexus.commerce.client.VnpayGateway;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.config.CheckoutTransactionConfig;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.config.VnpayProperties;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.VnpayIpnResponse;
import com.nexus.commerce.repository.CheckoutLock;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** Checkout→Payment→IPN chạy transaction/Flyway thật; chỉ API Catalog/kho được mock, không cần merchant thật cho test. */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=OFF", "app.payment.vnpay.tmn-code=TESTONLY",
        "app.payment.vnpay.hash-secret=unit-test-only-not-a-credential",
        "app.payment.vnpay.return-url=https://example.test/payment-result"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RefundNotificationReplayService.class, RefundResolutionService.class, RefundRequestService.class, PaymentPricingService.class, AuctionPaymentExpiryWorker.class, AuctionPaymentReminderWorker.class,
        AuctionTimeoutSideEffectWorker.class, NotificationWorker.class, NotificationOutbox.class, CheckoutService.class,
        PaymentService.class, PaymentInventoryWorker.class, VnpayGateway.class, CheckoutLock.class,
        CheckoutTransactionConfig.class, PaymentPostgresTest.Config.class,
        com.nexus.commerce.service.impl.OrderServiceImpl.class,
        com.nexus.commerce.dto.mapper.OrderMapper.class, com.nexus.commerce.statemachine.OrderStatusStateMachine.class})
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentPostgresTest {
    @Autowired private RefundRequestService refunds;
    @Autowired private RefundResolutionService resolutions;
    @Autowired private RefundNotificationReplayService notificationReplays;

    /** Replay chỉ reset dead letter; retry cùng key giữ audit cũ kể cả worker đã giao, khác nội dung bị 409. */
    @Test
    void notificationReplayIsAuditedAndIdempotent() {
        UUID refund = newRefundRequest();
        UUID event = jdbc.queryForObject("SELECT id FROM refund_notification_outbox WHERE refund_id=?", UUID.class, refund);
        String payload = jdbc.queryForObject("SELECT payload::text FROM refund_notification_outbox WHERE id=?", String.class, event);
        UUID actor = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        assertThrows(com.nexus.common.exception.ApiException.class, () -> notificationReplays.replay(event, actor, key, "Đã sửa OAuth"));
        jdbc.update("UPDATE refund_notification_outbox SET status='DEAD_LETTER',attempts=8 WHERE id=?", event);
        UUID audit = notificationReplays.replay(event, actor, key, "Đã sửa OAuth");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM refund_notification_outbox WHERE id=?", String.class, event));
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM refund_notification_outbox WHERE id=?", Integer.class, event));
        assertEquals(payload, jdbc.queryForObject("SELECT payload::text FROM refund_notification_outbox WHERE id=?", String.class, event));
        jdbc.update("UPDATE refund_notification_outbox SET status='DELIVERED' WHERE id=?", event);
        assertEquals(audit, notificationReplays.replay(event, actor, key, "Đã sửa OAuth"));
        assertThrows(com.nexus.common.exception.ApiException.class, () -> notificationReplays.replay(event, actor, key, "Khác nội dung"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_notification_replays WHERE event_id=?", Integer.class, event));
        assertEquals("REQUESTED", refundState(refund));
    }

    /** Rollback transaction quản trị phải giữ dead letter và không để lại audit nửa chừng. */
    @Test
    void notificationReplayRollbackPreservesDeadLetter() {
        UUID refund = newRefundRequest();
        UUID event = jdbc.queryForObject("SELECT id FROM refund_notification_outbox WHERE refund_id=?", UUID.class, refund);
        jdbc.update("UPDATE refund_notification_outbox SET status='DEAD_LETTER',attempts=8 WHERE id=?", event);
        new org.springframework.transaction.support.TransactionTemplate(refundTransactionManager).executeWithoutResult(tx -> {
            notificationReplays.replay(event, UUID.randomUUID(), UUID.randomUUID(), "Đã kiểm tra");
            tx.setRollbackOnly();
        });
        assertEquals("DEAD_LETTER", jdbc.queryForObject("SELECT status FROM refund_notification_outbox WHERE id=?", String.class, event));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_notification_replays WHERE event_id=?", Integer.class, event));
    }

    /** Hai Admin replay cùng event chỉ được reset một lần; loser nhận conflict, không tạo audit giả. */
    @Test
    void concurrentNotificationReplaysResetOnlyOnce() throws Exception {
        UUID refund = newRefundRequest();
        UUID event = jdbc.queryForObject("SELECT id FROM refund_notification_outbox WHERE refund_id=?", UUID.class, refund);
        jdbc.update("UPDATE refund_notification_outbox SET status='DEAD_LETTER',attempts=8 WHERE id=?", event);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                start.await();
                try {
                    notificationReplays.replay(event, UUID.randomUUID(), UUID.randomUUID(), "Đã kiểm tra OAuth");
                    return true;
                } catch (com.nexus.common.exception.ApiException conflict) { return false; }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            start.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_notification_replays WHERE event_id=?", Integer.class, event));
    }

    /** Snapshot gắn refundId riêng; UPDATE cùng state không tạo tin trùng, rollback không rò sự kiện. */
    @Test
    void refundNotificationSnapshotIsAtomicAndDeduplicated() throws Exception {
        UUID refund = newRefundRequest();
        var snapshot = jdbc.queryForObject("SELECT payload::text FROM refund_notification_outbox WHERE refund_id=?", String.class, refund);
        var event = new ObjectMapper().readTree(snapshot);
        assertEquals(refund.toString(), event.get("refundId").asText());
        assertEquals("REFUND_REQUESTED", event.get("type").asText());
        assertEquals(50000, event.get("amount").asInt());
        assertEquals(8, event.size());
        jdbc.update("UPDATE refund_requests SET status=status WHERE id=?", refund);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_notification_outbox WHERE refund_id=?", Integer.class, refund));
        new org.springframework.transaction.support.TransactionTemplate(refundTransactionManager).executeWithoutResult(tx -> {
            jdbc.update("UPDATE refund_requests SET status='UNKNOWN' WHERE id=?", refund);
            tx.setRollbackOnly();
        });
        assertEquals("REQUESTED", refundState(refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_notification_outbox WHERE refund_id=?", Integer.class, refund));
    }

    /** Mất ACK gửi lại đúng eventId/payload; tám lỗi đưa dead letter, không ảnh hưởng trạng thái refund. */
    @Test
    void refundNotificationRetriesSameSnapshotAndStopsAfterEightErrors() {
        UUID refund = newRefundRequest();
        UUID event = jdbc.queryForObject("SELECT id FROM refund_notification_outbox WHERE refund_id=?", UUID.class, refund);
        var remote = org.mockito.Mockito.mock(NotificationDelivery.class);
        when(remote.configured()).thenReturn(true);
        doThrow(new IllegalStateException("must-not-store-sensitive-message")).when(remote).sendInbox(any());
        var worker = new RefundNotificationWorker(jdbc, remote, refundTransactionManager);
        for (int i = 0; i < 8; i++) {
            jdbc.update("UPDATE refund_notification_outbox SET next_attempt_at=now()-interval '1 second' WHERE id=?", event);
            worker.deliver(event);
        }
        worker.deliver(event);
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(8)).sendInbox(any());
        assertEquals("DEAD_LETTER", jdbc.queryForObject("SELECT status FROM refund_notification_outbox WHERE id=?", String.class, event));
        assertEquals("IllegalStateException", jdbc.queryForObject("SELECT last_error_type FROM refund_notification_outbox WHERE id=?", String.class, event));
        assertEquals("REQUESTED", refundState(refund));
    }

    /** Sau mất ACK, lần giao sau phải giữ nguyên payload/id; backoff ngăn gửi lại ngay trong cùng tick. */
    @Test
    void refundNotificationLostAckRetriesIdenticalPayload() {
        UUID refund = newRefundRequest();
        UUID event = jdbc.queryForObject("SELECT id FROM refund_notification_outbox WHERE refund_id=?", UUID.class, refund);
        var remote = org.mockito.Mockito.mock(NotificationDelivery.class);
        when(remote.configured()).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("lost ACK")).doNothing().when(remote).sendInbox(any());
        var worker = new RefundNotificationWorker(jdbc, remote, refundTransactionManager);
        worker.deliver(event);
        worker.deliver(event);
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(1)).sendInbox(any());
        jdbc.update("UPDATE refund_notification_outbox SET next_attempt_at=now()-interval '1 second' WHERE id=?", event);
        worker.deliver(event);
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(2)).sendInbox(captor.capture());
        assertEquals(captor.getAllValues().getFirst(), captor.getAllValues().getLast());
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM refund_notification_outbox WHERE id=?", String.class, event));
    }

    /** Worker bỏ qua row đang được instance khác giao và không gửi lại sau ACK; OAuth thiếu giữ backlog. */
    @Test
    void concurrentRefundNotificationWorkersDeliverOnce() throws Exception {
        UUID refund = newRefundRequest();
        UUID event = jdbc.queryForObject("SELECT id FROM refund_notification_outbox WHERE refund_id=?", UUID.class, refund);
        var remote = org.mockito.Mockito.mock(NotificationDelivery.class);
        var worker = new RefundNotificationWorker(jdbc, remote, refundTransactionManager);
        worker.deliver(event);
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.never()).sendInbox(any());
        when(remote.configured()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test timeout"); }
            return null;
        }).when(remote).sendInbox(any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> worker.deliver(event));
            try {
                assertEquals(true, entered.await(5, TimeUnit.SECONDS));
                pool.submit(() -> worker.deliver(event)).get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        worker.deliver(event);
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(1)).sendInbox(any());
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM refund_notification_outbox WHERE id=?", String.class, event));
    }

    /** SUCCEEDED thủ công cần khớp snapshot và receipt; replay trả cùng audit, không ghi đè terminal hoặc nhân bản receipt. */
    @Test
    void manualRefundSuccessIsAuditedAndIdempotent() {
        UUID refund = unknownRefundForResolution();
        UUID actor = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var command = manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, refundProviderRef(), "CASE-M1");
        var decision = resolutions.resolve(refund, actor, key, command);
        assertEquals(true, decision.created());
        assertEquals(decision.response(), resolutions.resolve(refund, actor, key, command).response());
        assertEquals(false, resolutions.resolve(refund, actor, key, command).created());
        assertEquals("SUCCEEDED", refundState(refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_manual_resolutions WHERE refund_id=?", Integer.class, refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_provider_receipts WHERE refund_id=?", Integer.class, refund));
        assertThrows(com.nexus.common.exception.ApiException.class, () -> resolutions.resolve(refund, actor, UUID.randomUUID(),
                manualCommand(com.nexus.commerce.enums.RefundStatus.FAILED, null, "CASE-M1-OTHER")));
        assertThrows(com.nexus.common.exception.ApiException.class, () -> resolutions.resolve(refund, actor, key,
                manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, command.providerRefundReference(), "DIFFERENT-EVIDENCE")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders WHERE id=? AND status='REFUNDED'", Integer.class, orderId));
    }

    /** FAILED có bằng chứng giải phóng hạn mức cho yêu cầu mới, nhưng không gửi lại attempt cũ. */
    @Test
    void manualFailureReleasesOnlyRequestAllocation() {
        UUID refund = unknownRefundForResolution();
        resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(),
                manualCommand(com.nexus.commerce.enums.RefundStatus.FAILED, null, "CASE-M2"));
        assertEquals("FAILED", refundState(refund));
        var next = refunds.create(UUID.randomUUID(), paymentId, UUID.randomUUID(), "Yêu cầu mới sau đối soát");
        assertEquals(com.nexus.commerce.enums.RefundStatus.REQUESTED, next.response().status());
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        assertEquals(false, new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager).dispatch(refund));
        org.mockito.Mockito.verifyNoInteractions(remote);
    }

    /** Hai kết luận trái ngược chạy cùng lúc: chỉ một audit được commit, bên sau nhận conflict thay vì sửa terminal. */
    @Test
    void concurrentManualDecisionsHaveOneWinner() throws Exception {
        UUID refund = unknownRefundForResolution();
        var succeeded = manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, refundProviderRef(), "CASE-M3-OK");
        var failed = manualCommand(com.nexus.commerce.enums.RefundStatus.FAILED, null, "CASE-M3-NO");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> success = () -> {
                gate.await();
                try { resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(), succeeded); return true; }
                catch (com.nexus.common.exception.ApiException error) { assertEquals("REFUND_RESOLUTION_CONFLICT", error.getCode()); return false; }
            };
            java.util.concurrent.Callable<Boolean> failure = () -> {
                gate.await();
                try { resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(), failed); return true; }
                catch (com.nexus.common.exception.ApiException error) { assertEquals("REFUND_RESOLUTION_CONFLICT", error.getCode()); return false; }
            };
            var a = pool.submit(success);
            var b = pool.submit(failure);
            gate.countDown();
            assertEquals(1, (a.get(20, TimeUnit.SECONDS) ? 1 : 0) + (b.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_manual_resolutions WHERE refund_id=?", Integer.class, refund));
        assertEquals(refundState(refund), jdbc.queryForObject("SELECT decision FROM refund_manual_resolutions WHERE refund_id=?", String.class, refund));
    }

    /** Lỗi lưu audit phải rollback cả receipt và trạng thái; không có thành công tài chính thiếu bằng chứng. */
    @Test
    void manualResolutionAuditFailureRollsBackReceiptAndStatus() {
        UUID refund = unknownRefundForResolution();
        var command = manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, refundProviderRef(), "CASE-M4");
        jdbc.execute("CREATE FUNCTION resolution_fail_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        jdbc.execute("CREATE TRIGGER resolution_fail_fixture BEFORE INSERT ON refund_manual_resolutions FOR EACH ROW WHEN "
                + "(NEW.refund_id='" + refund + "'::uuid) EXECUTE FUNCTION resolution_fail_fixture()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(), command));
        } finally {
            jdbc.execute("DROP TRIGGER resolution_fail_fixture ON refund_manual_resolutions");
            jdbc.execute("DROP FUNCTION resolution_fail_fixture()");
        }
        assertEquals("UNKNOWN", refundState(refund));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_provider_receipts WHERE refund_id=?", Integer.class, refund));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_manual_resolutions WHERE refund_id=?", Integer.class, refund));
    }

    /** Không dùng cùng provider refund receipt để xác nhận hai payment khác nhau, dù hai Admin dùng key khác. */
    @Test
    void manualRefundReceiptCannotBeReusedAcrossPayments() {
        UUID first = unknownRefundForResolution();
        String providerRef = refundProviderRef();
        resolutions.resolve(first, UUID.randomUUID(), UUID.randomUUID(),
                manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, providerRef, "CASE-M5-A"));
        setup();
        UUID second = unknownRefundForResolution();
        assertThrows(com.nexus.common.exception.ApiException.class, () -> resolutions.resolve(second, UUID.randomUUID(), UUID.randomUUID(),
                manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, providerRef, "CASE-M5-B")));
        assertEquals("UNKNOWN", refundState(second));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_manual_resolutions WHERE refund_id=?", Integer.class, second));
    }

    /** Thiếu bằng chứng hoặc sai amount/ref không được sửa refund, kể cả Admin gửi quyết định thành công. */
    @Test
    void manualRefundRequiresMatchingSnapshotAndEvidence() {
        UUID refund = unknownRefundForResolution();
        var valid = manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, refundProviderRef(), "CASE-M6");
        var invalidAmount = new RefundResolutionService.Command(valid.decision(), valid.amount().add(BigDecimal.ONE), valid.currency(),
                valid.providerPaymentReference(), valid.providerRefundReference(), valid.evidenceReference(), valid.note());
        var missingEvidence = new RefundResolutionService.Command(valid.decision(), valid.amount(), valid.currency(),
                valid.providerPaymentReference(), valid.providerRefundReference(), " ", valid.note());
        assertThrows(com.nexus.common.exception.ApiException.class,
                () -> resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(), invalidAmount));
        assertThrows(com.nexus.common.exception.ApiException.class,
                () -> resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(), missingEvidence));
        assertEquals("UNKNOWN", refundState(refund));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_manual_resolutions WHERE refund_id=?", Integer.class, refund));
    }

    /** Response cũ về sau manual FAILED phải mở REVIEW và hold, không cho lệnh REQUESTED mới tiếp tục gửi. */
    @Test
    void lateProviderResultAfterManualFailureHoldsPaymentAndQueuedRefund() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã kiểm tra CASE-M7");
        UUID[] queued = new UUID[1];
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        when(remote.exchange(any(), any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE refund_attempts SET lease_expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE refund_id=?", refund);
            dispatcher.recoverExpired();
            resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(),
                    manualCommand(com.nexus.commerce.enums.RefundStatus.FAILED, null, "CASE-M7-FAILED"));
            queued[0] = refunds.create(UUID.randomUUID(), paymentId, UUID.randomUUID(), "Sau xác nhận thất bại").response().refundId();
            refunds.approve(queued[0], UUID.randomUUID(), "Đã duyệt CASE-M7-B");
            return new com.nexus.commerce.client.VnpayMerchantClient.Outcome("00", "00", "02", refundProviderRef());
        });
        dispatcher.dispatch(refund);
        assertEquals("REQUIRES_REVIEW", refundState(refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_payment_holds WHERE payment_id=?", Integer.class, paymentId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_result_conflicts WHERE refund_id=?", Integer.class, refund));
        assertEquals(false, dispatcher.dispatch(queued[0]));
        assertEquals("REQUIRES_REVIEW", refundState(queued[0]));
        assertThrows(com.nexus.common.exception.ApiException.class,
                () -> refunds.create(UUID.randomUUID(), paymentId, UUID.randomUUID(), "Không được gửi thêm"));
        assertEquals("FAILED", jdbc.queryForObject("SELECT decision FROM refund_manual_resolutions WHERE refund_id=?", String.class, refund));
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(1)).exchange(any(), any());
    }

    /** Chốt SUCCEEDED thủ công trong lúc chờ response muộn khác receipt: giữ thành công, ghi conflict/hold thay vì hạ trạng thái. */
    @Test
    void lateContradictionCannotOverwriteManualSuccess() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã kiểm tra CASE-M8");
        String manualReceipt = refundProviderRef();
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        when(remote.exchange(any(), any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE refund_attempts SET lease_expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE refund_id=?", refund);
            dispatcher.recoverExpired();
            resolutions.resolve(refund, UUID.randomUUID(), UUID.randomUUID(),
                    manualCommand(com.nexus.commerce.enums.RefundStatus.SUCCEEDED, manualReceipt, "CASE-M8-OK"));
            return new com.nexus.commerce.client.VnpayMerchantClient.Outcome("00", "00", "02", manualReceipt + "1");
        });
        dispatcher.dispatch(refund);
        assertEquals("SUCCEEDED", refundState(refund));
        assertEquals(manualReceipt, jdbc.queryForObject("SELECT provider_ref FROM refund_provider_receipts WHERE refund_id=?", String.class, refund));
        // Cảnh báo xung đột vẫn phát dù quyết định tài chính SUCCEEDED được giữ nguyên.
        assertEquals("REFUND_REQUIRES_REVIEW", jdbc.queryForObject("SELECT payload->>'type' FROM refund_notification_outbox "
                + "WHERE refund_id=? ORDER BY source_version DESC LIMIT 1", String.class, refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_result_conflicts WHERE refund_id=?", Integer.class, refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_payment_holds WHERE payment_id=?", Integer.class, paymentId));
    }

    /** Đưa request qua timeout thật ở ranh giới HTTP mock để test đối soát không dựa vào UPDATE trạng thái giả. */
    private UUID unknownRefundForResolution() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Fixture đã kiểm tra điều kiện refund");
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        when(remote.exchange(any(), any())).thenThrow(new com.nexus.commerce.client.VnpayMerchantClient.UncertainOutcomeException());
        new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager).dispatch(refund);
        assertEquals("UNKNOWN", refundState(refund));
        return refund;
    }

    private RefundResolutionService.Command manualCommand(com.nexus.commerce.enums.RefundStatus decision, String providerRef, String evidence) {
        return new RefundResolutionService.Command(decision,
                jdbc.queryForObject("SELECT amount FROM payments WHERE id=?", BigDecimal.class, paymentId), "VND",
                jdbc.queryForObject("SELECT provider_ref FROM payments WHERE id=?", String.class, paymentId),
                providerRef, evidence, "Operator đã kiểm tra kết quả provider fixture");
    }
    @Autowired private org.springframework.transaction.PlatformTransactionManager refundTransactionManager;

    /** Không duyệt hoặc chỉ bật một flag không được gửi tiền; bật flag không tiêu thụ toàn bộ REQUESTED cũ. */
    @Test
    void refundDispatchNeedsApprovalAndBothFlags() {
        UUID refund = newRefundRequest();
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        var config = refundWorkerConfig();
        var dispatcher = new RefundDispatchWorker(jdbc, remote, config, refundTransactionManager);
        assertEquals(false, dispatcher.dispatch(refund));
        refunds.approve(refund, UUID.randomUUID(), "Đã kiểm tra hủy hàng, case TEST-1");
        config.setRefundWorkerEnabled(false);
        assertEquals(false, dispatcher.dispatch(refund));
        org.mockito.Mockito.verifyNoInteractions(remote);
    }

    /** Hai instance cùng claim chỉ một HTTP mutation, HTTP nhìn thấy attempt đã commit và không còn transaction giữ khóa. */
    @Test
    void concurrentRefundWorkersSendOnceAfterCommitAndDoNotRefundOrderAutomatically() throws Exception {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã xác nhận điều kiện TEST-2");
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        when(remote.exchange(any(), any())).thenAnswer(invocation -> {
            assertEquals(false, org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("PROCESSING", refundState(refund));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=?", Integer.class, refund));
            return new com.nexus.commerce.client.VnpayMerchantClient.Outcome("00", "00", "02", refundProviderRef());
        });
        var firstWorker = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        var secondWorker = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return firstWorker.dispatch(refund); });
            var second = pool.submit(() -> { gate.await(); return secondWorker.dispatch(refund); });
            gate.countDown();
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals("SUCCEEDED", refundState(refund));
        assertEquals(false, firstWorker.dispatch(refund));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM orders WHERE id=? AND status='REFUNDED'", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=? AND jsonb_exists(request_payload,'vnp_SecureHash')",
                Integer.class, refund));
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(1)).exchange(any(), any());
    }

    /** Timeout không gửi lại refund; QueryDr PAY thành công ba lần chỉ đưa vào REVIEW, không tạo refund SUCCEEDED giả. */
    @Test
    void refundTimeoutReconcilesPaymentOnlyThenRequiresReview() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã đối chiếu điều kiện TEST-3");
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        when(remote.exchange(any(), any())).thenThrow(new com.nexus.commerce.client.VnpayMerchantClient.UncertainOutcomeException())
                .thenReturn(new com.nexus.commerce.client.VnpayMerchantClient.Outcome("00", "00", "01", "12345"));
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        assertEquals(true, dispatcher.dispatch(refund));
        assertEquals("UNKNOWN", refundState(refund));
        for (int i = 0; i < 3; i++) {
            assertEquals(false, dispatcher.dispatch(refund));
            jdbc.update("UPDATE refund_requests SET reconcile_after=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?", refund);
            assertEquals(true, dispatcher.reconcile(refund));
        }
        assertEquals("REQUIRES_REVIEW", refundState(refund));
        assertEquals(false, dispatcher.reconcile(refund));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=?", Integer.class, refund));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM refund_reconciliation_checks WHERE refund_id=?", Integer.class, refund));
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(4)).exchange(any(), any());
    }

    /** Mô phỏng JVM chết sau claim bằng Error; worker mới thu hồi lease và tuyệt đối không gửi attempt thứ hai. */
    @Test
    void crashedRefundWorkerRecoversExpiredLeaseWithoutResending() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã kiểm tra TEST-4");
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        when(remote.exchange(any(), any())).thenThrow(new AssertionError("Simulated process crash"));
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        assertThrows(AssertionError.class, () -> dispatcher.dispatch(refund));
        assertEquals("PROCESSING", refundState(refund));
        jdbc.update("UPDATE refund_attempts SET lease_expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE refund_id=?", refund);
        var restarted = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        restarted.recoverExpired();
        restarted.recoverExpired();
        assertEquals("UNKNOWN", refundState(refund));
        assertEquals(false, restarted.dispatch(refund));
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(1)).exchange(any(), any());
    }

    /** Provider đã trả success nhưng finalize DB rollback: attempt không mất, lease recovery giữ tiền UNKNOWN thay vì gửi lại. */
    @Test
    void refundFinalizeRollbackKeepsCommittedAttemptForRecovery() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã kiểm tra TEST-5");
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        when(remote.exchange(any(), any())).thenReturn(new com.nexus.commerce.client.VnpayMerchantClient.Outcome("00", "00", "02", refundProviderRef()));
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        jdbc.execute("CREATE FUNCTION refund_fail_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        jdbc.execute("CREATE TRIGGER refund_fail_fixture BEFORE UPDATE ON refund_attempts FOR EACH ROW WHEN "
                + "(NEW.refund_id='" + refund + "'::uuid AND NEW.status='SUCCEEDED') EXECUTE FUNCTION refund_fail_fixture()");
        try { assertThrows(org.springframework.dao.DataAccessException.class, () -> dispatcher.dispatch(refund)); }
        finally {
            jdbc.execute("DROP TRIGGER refund_fail_fixture ON refund_attempts");
            jdbc.execute("DROP FUNCTION refund_fail_fixture()");
        }
        assertEquals("PROCESSING", refundState(refund));
        jdbc.update("UPDATE refund_attempts SET lease_expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE refund_id=?", refund);
        dispatcher.recoverExpired();
        assertEquals("UNKNOWN", refundState(refund));
        assertEquals(false, dispatcher.dispatch(refund));
        org.mockito.Mockito.verify(remote, org.mockito.Mockito.times(1)).exchange(any(), any());
    }

    /** Payment/IPN fixture dùng DB thật, không gọi VNPAY hoặc dùng credential của nhóm. */
    private UUID newRefundRequest() {
        payments.ipn(callback("00", "00", "5000000"));
        return refunds.create(UUID.randomUUID(), paymentId, UUID.randomUUID(), "Refund worker fixture").response().refundId();
    }

    private String refundState(UUID id) {
        return jdbc.queryForObject("SELECT status FROM refund_requests WHERE id=?", String.class, id);
    }

    /** Chỉ test mới bật worker với HTTP mock; không dùng cấu hình ứng dụng/môi trường thật. */
    private VnpayProperties refundWorkerConfig() {
        var config = new VnpayProperties();
        config.setTmnCode("TESTONLY");
        config.setHashSecret("fixture-only");
        config.setMerchantApiEnabled(true);
        config.setRefundWorkerEnabled(true);
        config.setMerchantSourceIp("127.0.0.1");
        return config;
    }

    private String refundProviderRef() {
        return Long.toString(Math.abs(UUID.randomUUID().getLeastSignificantBits() % 99999999999999L) + 1);
    }

    /** Kết quả trả sau lease expiry không được lặng lẽ ghi đè recovery; lưu bằng chứng và chuyển REVIEW. */
    @Test
    void lateRefundResultAfterRecoveryRequiresReview() {
        UUID refund = newRefundRequest();
        refunds.approve(refund, UUID.randomUUID(), "Đã kiểm tra TEST-6");
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        when(remote.exchange(any(), any())).thenAnswer(invocation -> {
            jdbc.update("UPDATE refund_attempts SET lease_expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE refund_id=?", refund);
            dispatcher.recoverExpired();
            return new com.nexus.commerce.client.VnpayMerchantClient.Outcome("00", "00", "02", refundProviderRef());
        });
        dispatcher.dispatch(refund);
        assertEquals("REQUIRES_REVIEW", refundState(refund));
        assertEquals("00", jdbc.queryForObject("SELECT response_code FROM refund_attempts WHERE refund_id=?", String.class, refund));
        assertEquals(false, dispatcher.dispatch(refund));
    }

    /** Query thứ ba chết trước finalize không được để UNKNOWN vĩnh viễn khi đã hết số lượt cho phép. */
    @Test
    void expiredThirdReconciliationMovesToManualReview() {
        UUID refund = newRefundRequest();
        jdbc.update("UPDATE refund_requests SET status='UNKNOWN',reconcile_count=3,"
                + "reconcile_after=CURRENT_TIMESTAMP-interval '121 seconds' WHERE id=?", refund);
        var remote = org.mockito.Mockito.mock(com.nexus.commerce.client.VnpayMerchantClient.class);
        var dispatcher = new RefundDispatchWorker(jdbc, remote, refundWorkerConfig(), refundTransactionManager);
        dispatcher.recoverExpired();
        assertEquals("REQUIRES_REVIEW", refundState(refund));
        org.mockito.Mockito.verifyNoInteractions(remote);
    }

    /** Hai Admin dùng hai key cho cùng payment chỉ được giữ một hạn mức toàn phần, không phụ thuộc thread thắng. */
    @Test
    void concurrentFullRefundRequestsReserveFundsOnce() throws Exception {
        payments.ipn(callback("00", "00", "5000000"));
        var gate = new CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> request = () -> {
            gate.await();
            try {
                refunds.create(UUID.randomUUID(), paymentId, UUID.randomUUID(), "Hoàn toàn phần");
                return true;
            } catch (com.nexus.common.exception.ApiException error) {
                assertEquals("REFUND_AMOUNT_EXCEEDED", error.getCode());
                return false;
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(request);
            var second = pool.submit(request);
            gate.countDown();
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0) + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_requests WHERE payment_id=?", Integer.class, paymentId));
    }

    /** Refund chỉ tiếp nhận tiền thật đã xác minh, giữ hạn mức dưới khóa DB và replay không INSERT lần hai. */
    @Test
    void refundRequestReplaysAndRejectsConflictingKeyOrSecondFullRefund() {
        payments.ipn(callback("00", "00", "5000000"));
        UUID admin = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var created = refunds.create(admin, paymentId, key, "Khách yêu cầu");
        assertEquals(com.nexus.commerce.enums.RefundStatus.REQUESTED, created.response().status());
        assertEquals(created.response(), refunds.create(admin, paymentId, key, "Khách yêu cầu").response());
        org.junit.jupiter.api.Assertions.assertThrows(com.nexus.common.exception.ApiException.class,
                () -> refunds.create(admin, paymentId, key, "Lý do khác"));
        org.junit.jupiter.api.Assertions.assertThrows(com.nexus.common.exception.ApiException.class,
                () -> refunds.create(admin, paymentId, UUID.randomUUID(), "Hoàn lần hai"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund_requests WHERE payment_id=?", Integer.class, paymentId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(created.response(), refunds.get(created.response().refundId(), buyer, false));
        org.junit.jupiter.api.Assertions.assertThrows(com.nexus.common.exception.ApiException.class,
                () -> refunds.get(created.response().refundId(), UUID.randomUUID(), false));
    }
    @Autowired private CheckoutService checkout;
    @Autowired private OrderService management;
    @Autowired private PaymentService payments;
    @Autowired private PaymentInventoryWorker worker;
    @Autowired private AuctionPaymentExpiryWorker expiry;
    @Autowired private AuctionPaymentReminderWorker reminderWorker;
    @Autowired private AuctionTimeoutSideEffectWorker timeoutSideEffectWorker;
    @Autowired private VnpayGateway gateway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NotificationWorker notificationWorker;
    @MockitoBean private NotificationDelivery notificationDelivery;
    @MockitoBean private CatalogClient catalog;
    @MockitoBean private InventoryClient inventory;
    @MockitoBean private InventoryServiceToken tokens;
    @MockitoBean private AuctionTimeoutSyncClient auctionTimeoutSync;
    @MockitoBean private UserContactClient userContactClient;
    private UUID buyer;
    private UUID orderId;
    private UUID paymentId;
    private UUID reservation;

    /** Nâng fixture checkout thành đơn winner đã handover để kiểm tra transaction IPN/expiry/outbox thật trên PostgreSQL. */
    private void winner(boolean expired) {
        jdbc.update("UPDATE orders SET source_type='AUCTION',auction_id=?,payment_deadline="
                + "(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') + (? * interval '1 hour') WHERE id=?",
                UUID.randomUUID(), expired ? -1 : 1, orderId);
        // Fixture winner dùng ref kho từ handover; REGULAR trước đó giữ bằng paymentId.
        jdbc.update("UPDATE payments SET inventory_ref_id=? WHERE id=?", orderId, paymentId);
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 day' WHERE order_id<>?", orderId);
        when(tokens.get()).thenReturn("service-jwt");
    }

    /** Callback đồng thời chỉ ghi một COMMIT; PAID/history/thông báo chỉ xuất hiện sau worker commit kho thành công. */
    @Test
    void winnerConcurrentIpnsWaitForInventoryThenCompleteExactlyOnce() throws Exception {
        winner(false);
        var callback = callback("00", "00", "5000000");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return payments.ipn(callback).code(); });
            var b = pool.submit(() -> { gate.await(); return payments.ipn(callback).code(); });
            gate.countDown();
            assertEquals(List.of("00", "02"), List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)).stream().sorted().toList());
        }
        assertEquals("PROCESSING", payments.get(buyer, paymentId).status());
        assertEquals("PENDING_PAYMENT", orderState());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
        worker.run();
        worker.run();
        assertEquals("PAID", orderState());
        assertEquals("SUCCESS", payments.get(buyer, paymentId).status());
        verify(inventory).commitOrder(orderId, List.of(reservation), "service-jwt");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=? AND to_status='PAID'", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'", Integer.class, orderId));
    }

    /** Sweeper kho thắng: tiền được ghi SUCCESS nhưng đơn CANCELLED/REVIEW, không phát thông báo đã thanh toán/giao hàng. */
    @Test
    void expiredInventoryNeverCreatesPaidWinner() {
        winner(false);
        payments.ipn(callback("00", "00", "5000000"));
        doThrow(new com.nexus.common.exception.ApiException("INVENTORY_RESERVATION_EXPIRED",
                org.springframework.http.HttpStatus.CONFLICT, "Phiếu đã hết hạn", null))
                .when(inventory).commitOrder(orderId, List.of(reservation), "service-jwt");
        worker.run();
        assertEquals("CANCELLED", orderState());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'", Integer.class, orderId));
        assertEquals("02", payments.ipn(callback("00", "00", "5000000")).code());
    }

    /** HTTP response mất sau commit kho: outbox còn pending, lần retry cùng orderId mới xác nhận PAID, không thu tiền lại. */
    @Test
    void winnerInventoryTimeoutRetriesAndDoesNotReportSuccessEarly() {
        winner(false);
        payments.ipn(callback("00", "00", "5000000"));
        doThrow(new IllegalStateException("lost response")).doNothing()
                .when(inventory).commitOrder(orderId, List.of(reservation), "service-jwt");
        worker.run();
        assertEquals("PROCESSING", payments.get(buyer, paymentId).status());
        assertEquals("PENDING_PAYMENT", orderState());
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        worker.run();
        assertEquals("PAID", orderState());
        verify(inventory, org.mockito.Mockito.times(2)).commitOrder(orderId, List.of(reservation), "service-jwt");
    }

    /** Timeout đơn trước callback: hủy/release chỉ một lần, IPN muộn ghi tiền REVIEW và không tạo COMMIT hồi sinh đơn. */
    @Test
    void winnerDeadlineThenLateIpnKeepsCancellationAndRecordsFunds() {
        winner(true);
        expiry.run();
        expiry.run();
        assertEquals("CANCELLED", orderState());
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        assertEquals("CANCELLED", orderState());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='RELEASE'", Integer.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='AUCTION_PAYMENT_TIMEOUT'", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auction_timeout_tasks WHERE order_id=? AND task_type='REPUTATION_PENALTY' AND score_delta=10", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auction_timeout_tasks WHERE order_id=? AND task_type='AUCTION_DEFAULTED'", Integer.class, orderId));
    }

    /** Nhắc hạn 2h chỉ ghi đúng 1 outbox; lỗi mạng sang Reputation giữ retry backoff cho tới khi hồi phục. */
    @Test
    void reminderAndTimeoutSideEffectsPersistAndRecoverCleanly() {
        UUID auction = UUID.randomUUID();
        var nearDeadline = java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        when(userContactClient.findContact(buyer)).thenReturn(java.util.Optional.of(
                new UserContactClient.UserContact(buyer, "winner@nexus.test", "Winner Postgres")));
        var created = management.createAuctionOrder(new com.nexus.commerce.dto.request.CreateAuctionOrderRequest(
                auction, buyer, UUID.randomUUID(), null, UUID.randomUUID(),
                new BigDecimal("300000"), nearDeadline, "Gốm sứ", "CER-01", List.of(reservation)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='AUCTION_WON'", Integer.class, created.orderId()));

        reminderWorker.run();
        reminderWorker.run();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='AUCTION_PAYMENT_REMINDER'", Integer.class, created.orderId()));

        jdbc.update("UPDATE orders SET payment_deadline=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - interval '5 minutes' WHERE id=?", created.orderId());
        expiry.run();
        when(auctionTimeoutSync.configured()).thenReturn(true);
        doThrow(new IllegalStateException("timeout")).doNothing()
                .when(auctionTimeoutSync).applyTimeoutPenalty(eq(buyer), eq(auction), eq(created.orderId()), eq(10), any());

        timeoutSideEffectWorker.run();
        // Lỗi Reputation Service giữ lại REPUTATION_PENALTY để retry; AUCTION_DEFAULTED vẫn hoàn tất độc lập.
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM auction_timeout_tasks WHERE order_id=? AND task_type='REPUTATION_PENALTY' AND done=true",
                Integer.class, created.orderId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM auction_timeout_tasks WHERE order_id=? AND task_type='AUCTION_DEFAULTED' AND done=true",
                Integer.class, created.orderId()));

        jdbc.update("UPDATE auction_timeout_tasks SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", created.orderId());
        timeoutSideEffectWorker.run();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM auction_timeout_tasks WHERE order_id=? AND done=true", Integer.class, created.orderId()));
    }

    /** IPN và timeout chạy song song phải có một kết quả nhất quán; expiry không hủy đơn có SUCCESS đang chờ kho. */
    @Test
    void winnerExpiryRacesIpnWithoutPaidAndReleaseTogether() throws Exception {
        winner(true);
        var callback = callback("00", "00", "5000000");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return payments.ipn(callback).code(); });
            var b = pool.submit(() -> { gate.await(); expiry.run(); return true; });
            gate.countDown();
            assertEquals("00", a.get(20, TimeUnit.SECONDS));
            b.get(20, TimeUnit.SECONDS);
        }
        worker.run();
        var releases = jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='RELEASE'", Integer.class, orderId);
        if ("PAID".equals(orderState())) {
            assertEquals(0, releases);
            assertEquals("SUCCESS", payments.get(buyer, paymentId).status());
        } else {
            assertEquals("CANCELLED", orderState());
            assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        }
    }

    /** Đọc state bằng connection mới để kiểm tra kết quả commit, không đọc entity còn cache trong test. */
    private String orderState() {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId);
    }

    /** Hai handover thật chạy song song phải nhận cùng một orderId; khóa PostgreSQL bảo vệ read/insert, không giả lập bằng Map. */
    @Test
    void concurrentWinnerHandoverReturnsOnePersistedOrder() throws Exception {
        UUID auction = UUID.randomUUID();
        var deadline = java.time.Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request = new com.nexus.commerce.dto.request.CreateAuctionOrderRequest(auction, buyer, UUID.randomUUID(),
                null, UUID.randomUUID(), new BigDecimal("100000"), deadline, "Winner item", "WINNER-SKU", List.of(reservation));
        var gate = new CountDownLatch(1);
        UUID winnerOrder;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { gate.await(); return management.createAuctionOrder(request); });
            var b = pool.submit(() -> { gate.await(); return management.createAuctionOrder(request); });
            gate.countDown();
            var first = a.get(20, TimeUnit.SECONDS);
            var replay = b.get(20, TimeUnit.SECONDS);
            assertEquals(first.orderId(), replay.orderId());
            assertEquals(deadline, replay.paymentDeadline());
            winnerOrder = first.orderId();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM orders WHERE auction_id=?", Integer.class, auction));
        var payment = payments.start(buyer, winnerOrder, "127.0.0.1");
        assertEquals(deadline, payment.expiresAt());
        assertEquals("PENDING", payment.status());
        assertEquals("VNPAY", jdbc.queryForObject("SELECT payment_method FROM orders WHERE id=?", String.class, winnerOrder));
    }

    @TestConfiguration
    static class Config {
        @Bean CommerceProperties commerceProperties() { return new CommerceProperties(); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean VnpayProperties vnpayProperties() {
            var properties = new VnpayProperties();
            properties.setTmnCode("TESTONLY");
            properties.setHashSecret("unit-test-only-not-a-credential");
            properties.setReturnUrl("https://example.test/payment-result");
            return properties;
        }
    }

    @BeforeEach
    void setup() {
        // Checkout hiện kiểm tra tồn trước reserve; mock phải có hàng để các ca payment/refund đi đến đúng nghiệp vụ.
        when(inventory.available(any())).thenReturn(100);
        // Service account fixture chỉ mở cổng tạo URL; không gửi yêu cầu tới Keycloak thật.
        when(tokens.configured()).thenReturn(true);
        buyer = UUID.randomUUID();
        reservation = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, buyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,2,25000)", item, cart, sku);
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("25000.00"), null, true, UUID.randomUUID())));
        when(inventory.reserve(any(), any(), any())).thenReturn(List.of(reservation));
        var request = new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "VNPAY", null, null, List.of(item));
        String key = UUID.randomUUID().toString();
        var created = checkout.create(buyer, key, request, "buyer-jwt", "127.0.0.1", "buyer@example.test");
        orderId = created.id();
        assertEquals(created, checkout.create(buyer, key, request, "buyer-jwt"));
        when(tokens.get()).thenReturn("service-jwt");
        paymentId = payments.start(buyer, orderId, "127.0.0.1", UUID.randomUUID().toString()).paymentId();
        // Cô lập tác vụ còn lại của test trước vì mỗi ca dùng transaction thật và không rollback fixture.
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 day' WHERE order_id<>?", orderId);
    }

    /** Đọc hết hạn không sửa DB/đơn; IPN tới sau vẫn commit SUCCESS và chỉ enqueue confirm đúng một lần. */
    @Test
    void expiredProjectionKeepsDatabasePendingUntilVerifiedLateIpn() {
        // Schema lưu timestamp UTC không kèm zone; dùng UTC tường minh, không phụ thuộc timezone của JDBC trên máy test.
        jdbc.update("UPDATE payments SET expires_at=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - interval '1 minute' WHERE id=?", paymentId);
        var result = payments.get(buyer, paymentId);
        assertEquals("EXPIRED", result.status());
        org.junit.jupiter.api.Assertions.assertNull(result.paymentUrl());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        var ipn = callback("00", "00", "5000000");
        assertEquals("00", payments.ipn(ipn).code());
        assertEquals("02", payments.ipn(ipn).code());
        assertEquals("PROCESSING", payments.get(buyer, paymentId).status());
        worker.run();
        assertEquals("SUCCESS", payments.get(buyer, paymentId).status());
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
    }

    private Map<String, String> callback(String response, String status, String amount) {
        var params = new HashMap<>(Map.of("vnp_TmnCode", "TESTONLY", "vnp_TxnRef", paymentId.toString(), "vnp_Amount", amount,
                "vnp_ResponseCode", response, "vnp_TransactionStatus", status,
                "vnp_TransactionNo", Long.toString(Math.abs((long) paymentId.hashCode()) + 1)));
        params.put("vnp_SecureHash", gateway.sign(params));
        return params;
    }

    /** Snapshot khóa theo attempt: đổi Catalog giữa hai lần trả tiền chỉ ảnh hưởng attempt mới, không sửa tiền đã gửi cổng. */
    @Test
    void retryRepricesAndFreezesAttemptWhileOfficialPricesStayNullUntilSuccess() {
        assertEquals(false, jdbc.queryForObject("SELECT price_finalized FROM orders WHERE id=?", Boolean.class, orderId));
        org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId));
        UUID oldPayment = paymentId;
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        UUID seller = jdbc.queryForObject("SELECT seller_id FROM orders WHERE id=?", UUID.class, orderId);
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("30000"), null, true, seller)));
        String key = UUID.randomUUID().toString();
        var next = payments.start(buyer, orderId, "127.0.0.1", key);
        paymentId = next.paymentId();
        assertEquals(new BigDecimal("60000.00"), next.amount());
        assertEquals(next.paymentId(), payments.start(buyer, orderId, "127.0.0.1", key).paymentId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(new BigDecimal("50000.00"), payments.get(buyer, oldPayment).amount());
        // Catalog tiếp tục tăng sau khi cấp URL; callback vẫn chốt 30.000 mỗi SKU của attempt, không thành 90.000.
        when(catalog.getSkuDetails(any())).thenReturn(Map.of(sku,
                new SkuInfo(sku, "SKU", "Sản phẩm", null, new BigDecimal("90000"), null, true, seller)));
        assertEquals("00", payments.ipn(callback("00", "00", "6000000")).code());
        assertEquals(new BigDecimal("60000.00"), jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
        assertEquals(new BigDecimal("30000.00"), jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId));
        worker.run();
        assertEquals("PAID", orderState());
        // Release attempt cũ chạy cùng batch vẫn nhắm oldPayment, không thể nhả phiếu mới.
        verify(inventory).release(oldPayment, "service-jwt");
        verify(inventory).commitOrder(paymentId, List.of(reservation), "service-jwt");
    }

    /** Hai request retry song song cùng key phải có đúng một attempt mới, dù cả hai cùng thấy lần trước FAILED. */
    @Test
    void concurrentRetryCreatesExactlyOneNewAttempt() throws Exception {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        String key = UUID.randomUUID().toString();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); return payments.start(buyer, orderId, "ip", key); });
            var second = pool.submit(() -> { gate.await(); return payments.start(buyer, orderId, "ip", key); });
            gate.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS).paymentId(), second.get(20, TimeUnit.SECONDS).paymentId());
        }
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? AND status='PENDING'", Integer.class, orderId));
    }

    /** PENDING hết hạn chưa rõ kết quả không được mở lần thu tiền khác chỉ dựa trên đồng hồ client. */
    @Test
    void expiredPendingCannotBeRetriedAndFailedKeyReplaysOnlyItsOwnAttempt() {
        jdbc.update("UPDATE payments SET expires_at=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - interval '1 minute' WHERE id=?", paymentId);
        assertThrows(com.nexus.common.exception.ApiException.class, () ->
                payments.start(buyer, orderId, "ip", UUID.randomUUID().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
    }

    /** Thu tiền muộn từ FAILED được lưu REVIEW; không ghi đè snapshot/tổng của attempt mới hoặc tự giao hai lần. */
    @Test
    void lateFundsFromFailedAttemptRequireReviewAndBlockNewCharges() {
        var late = callback("00", "00", "5000000");
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        UUID previous = paymentId;
        String nextKey = UUID.randomUUID().toString();
        var next = payments.start(buyer, orderId, "ip", nextKey);
        paymentId = next.paymentId();
        assertEquals("00", payments.ipn(late).code());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, previous).status());
        // Không chỉ chặn key mới: URL đã cấp của attempt B phải bị ẩn trên cả GET và replay key B.
        var pending = payments.get(buyer, paymentId);
        assertEquals("REQUIRES_REVIEW", pending.status());
        org.junit.jupiter.api.Assertions.assertNull(pending.paymentUrl());
        var replay = payments.start(buyer, orderId, "ip", nextKey);
        assertEquals("REQUIRES_REVIEW", replay.status());
        org.junit.jupiter.api.Assertions.assertNull(replay.paymentUrl());
        org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
        assertThrows(com.nexus.common.exception.ApiException.class, () ->
                payments.start(buyer, orderId, "ip", UUID.randomUUID().toString()));
        // Attempt mới đã được cấp URL vẫn có thể thu tiền; callback cũng phải đối soát thay vì chốt đè.
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals("REQUIRES_REVIEW", payments.get(buyer, paymentId).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COMMIT'", Integer.class, orderId));
    }

    /** Reserve timeout sau khi Fulfillment có thể đã giữ hàng: rollback không lưu attempt và bù trừ đúng ref mới. */
    @Test
    void reservationFailureRollsBackAttemptAndReleasesOnlyItsReference() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        doThrow(new IllegalStateException("lost reserve response")).when(inventory).reserve(any(), any(), any());
        assertThrows(IllegalStateException.class, () ->
                payments.start(buyer, orderId, "ip", UUID.randomUUID().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, orderId));
        verify(inventory).release(org.mockito.ArgumentMatchers.argThat(ref -> !ref.equals(paymentId)), org.mockito.ArgumentMatchers.isNull());
    }

    /** COD ghi nhận tiền một lần, chỉ chuyển PAID sau xác nhận toàn bộ kho; trạng thái chờ không được xử lý giao hàng. */
    @Test
    void codConfirmationFinalizesEstimatedPricesAndCreatesInventoryTasksOnce() {
        UUID codBuyer = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, codBuyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,1,25000)", item, cart, sku);
        var created = checkout.create(codBuyer, UUID.randomUUID().toString(),
                new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "COD", null, null, List.of(item)), "buyer-jwt");
        assertEquals(false, created.priceFinalized());
        org.junit.jupiter.api.Assertions.assertNull(created.total());
        var confirm = new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(com.nexus.commerce.enums.OrderStatus.PAID, "Đã xác nhận thu tiền COD");
        var paid = management.updateOrderStatus(created.id(), confirm, UUID.randomUUID(), List.of("ROLE_ADMIN"));
        management.updateOrderStatus(created.id(), confirm, UUID.randomUUID(), List.of("ROLE_ADMIN"));
        assertEquals(true, paid.priceFinalized());
        assertEquals(new BigDecimal("25000.00"), paid.total());
        assertEquals("PENDING_PAYMENT", paid.status().name());
        assertEquals("PENDING", paid.fulfillmentStatus());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='COD_COMMIT'", Integer.class, created.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=?", Integer.class, created.id()));
        assertThrows(com.nexus.common.exception.BusinessException.class, () -> management.updateOrderStatus(created.id(),
                new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(com.nexus.commerce.enums.OrderStatus.PROCESSING, "Đóng gói"),
                UUID.randomUUID(), List.of("ROLE_ADMIN")));
        worker.run();
        worker.run();
        var confirmed = management.getOrderDetail(created.id(), codBuyer, List.of("ROLE_BUYER"));
        assertEquals("PAID", confirmed.status().name());
        assertEquals("CONFIRMED", confirmed.fulfillmentStatus());
        verify(inventory).commitOrder(created.id(), List.of(reservation), "service-jwt");
    }

    /** Fixture COD riêng trong cùng schema; dùng SKU/seller Catalog thật của test, chỉ HTTP kho được mock. */
    private UUID createCodOrder() {
        UUID codBuyer = UUID.randomUUID(), cart = UUID.randomUUID(), item = UUID.randomUUID();
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, codBuyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,1,25000)", item, cart, sku);
        return checkout.create(codBuyer, UUID.randomUUID().toString(), new CreateOrderRequest(
                "123 Lê Lợi", "Người nhận", "0901234567", null, "COD", null, null, List.of(item)), "buyer-jwt").id();
    }

    /** Xác nhận COD qua cùng API admin mà UI đang dùng; khóa Order và quyền được service kiểm tra như production. */
    private com.nexus.commerce.dto.response.OrderDetailResponse confirmCod(UUID id) {
        return management.updateOrderStatus(id, new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(
                com.nexus.commerce.enums.OrderStatus.PAID, "Đã thu COD"), UUID.randomUUID(), List.of("ROLE_ADMIN"));
    }

    /** Hồi quy P2: hết hạn/mất phiếu là lỗi vĩnh viễn, đóng task và lưu REVIEW thay vì tiếp tục PAID hoặc retry vô hạn. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"INVENTORY_RESERVATION_EXPIRED", "INVENTORY_RESERVATION_NOT_FOUND"})
    void codPermanentInventoryFailurePreservesFundsAndRequiresReview(String code) {
        UUID cod = createCodOrder();
        doThrow(new com.nexus.common.exception.ApiException(code, org.springframework.http.HttpStatus.CONFLICT, "Kho không thể xuất", null))
                .when(inventory).commitOrder(cod, List.of(reservation), "service-jwt");
        confirmCod(cod);
        worker.run();
        worker.run();
        var detail = management.getOrderDetail(cod, UUID.randomUUID(), List.of("ROLE_ADMIN"));
        assertEquals("CANCELLED", detail.status().name());
        assertEquals("REVIEW", detail.fulfillmentStatus());
        assertEquals(true, detail.priceFinalized());
        assertEquals(new BigDecimal("25000.00"), detail.total());
        // Hủy do kho là SYSTEM, không phải buyer; mốc thời gian phải cùng UTC với receipt thu tiền.
        var cancellation = detail.statusHistory().stream().filter(event -> event.toStatus() == com.nexus.commerce.enums.OrderStatus.CANCELLED)
                .findFirst().orElseThrow();
        assertEquals("SYSTEM", cancellation.actor());
        org.junit.jupiter.api.Assertions.assertTrue(java.time.Duration.between(cancellation.changedAt(), java.time.Instant.now())
                .abs().compareTo(java.time.Duration.ofMinutes(1)) < 0);
        assertEquals(true, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=? AND action='COD_COMMIT'", Boolean.class, cod));
        assertEquals("REVIEW", confirmCod(cod).fulfillmentStatus());
        assertThrows(com.nexus.common.exception.BusinessException.class, () -> management.updateOrderStatus(cod,
                new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(com.nexus.commerce.enums.OrderStatus.PROCESSING, "Không được giao"),
                UUID.randomUUID(), List.of("ROLE_ADMIN")));
        verify(inventory).commitOrder(cod, List.of(reservation), "service-jwt");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=? AND to_status='PAID'", Integer.class, cod));
    }

    /** Timeout sau commit kho chưa chứng minh thất bại: giữ chờ, chặn hủy/giao và retry cùng ref, không ghi thêm lần thu tiền. */
    @Test
    void codInventoryTimeoutStaysPendingThenRetriesExactlySameReference() {
        UUID cod = createCodOrder();
        doThrow(new IllegalStateException("lost commit ACK")).doNothing().when(inventory).commitOrder(cod, List.of(reservation), "service-jwt");
        confirmCod(cod);
        worker.run();
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, cod));
        assertEquals("PENDING", jdbc.queryForObject("SELECT fulfillment_status FROM orders WHERE id=?", String.class, cod));
        assertThrows(com.nexus.common.exception.BusinessException.class, () -> management.cancelOrder(cod,
                new com.nexus.commerce.dto.request.CancelOrderRequest("Hủy khi đã thu tiền"), UUID.randomUUID(), List.of("ROLE_ADMIN")));
        confirmCod(cod);
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", cod);
        worker.run();
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, cod));
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT fulfillment_status FROM orders WHERE id=?", String.class, cod));
        verify(inventory, org.mockito.Mockito.times(2)).commitOrder(cod, List.of(reservation), "service-jwt");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=? AND reason LIKE '%COD_FUNDS_RECEIVED_PENDING_INVENTORY'", Integer.class, cod));
    }

    /** Lỗi DB tại commit xác nhận COD phải rollback cả giá, cờ thu tiền, lịch sử và task; không để receipt nửa vời. */
    @Test
    void codReceiptCommitFailureRollsBackPricesAndTaskTogether() {
        UUID cod = createCodOrder();
        jdbc.execute("CREATE FUNCTION cod_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'cod commit test'; END; $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER cod_test_failure AFTER UPDATE ON orders DEFERRABLE INITIALLY DEFERRED "
                + "FOR EACH ROW EXECUTE FUNCTION cod_test_reject()");
        try {
            assertThrows(RuntimeException.class, () -> confirmCod(cod));
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, cod));
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT fulfillment_status FROM orders WHERE id=?", String.class, cod));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, cod));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, cod));
        } finally {
            jdbc.execute("DROP TRIGGER cod_test_failure ON orders");
            jdbc.execute("DROP FUNCTION cod_test_reject()");
        }
        assertEquals("PENDING", confirmCod(cod).fulfillmentStatus());
    }

    /** Hai callback song song chỉ một lần đổi trạng thái, ghi history và enqueue confirm; worker chạy lặp không gọi kho lại. */
    @Test
    void concurrentSuccessCommitsExactlyOnceAndWorkerConfirmsStock() throws Exception {
        var callback = callback("00", "00", "5000000");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return payments.ipn(callback); });
            var second = executor.submit(() -> { start.await(); return payments.ipn(callback); });
            start.countDown();
            List<String> codes = List.of(first.get(20, TimeUnit.SECONDS).code(), second.get(20, TimeUnit.SECONDS).code()).stream().sorted().toList();
            assertEquals(List.of("00", "02"), codes);
        }
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        // Cô lập outbox của ca này vì các test khác có thể để lại tác vụ cố ý chưa xử lý trong DB tạm.
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(tokens.configured()).thenReturn(true);
        when(tokens.get()).thenReturn("service-jwt");
        worker.run();
        worker.run();
        verify(inventory).commitOrder(paymentId, List.of(reservation), "service-jwt");
        assertEquals("PAID", orderState());
        assertEquals(true, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
    }

    /** Callback thất bại hủy đơn; worker lỗi mạng vẫn giữ tác vụ, tăng attempts và retry release sau đó. */
    @Test
    void failureCommitsReleaseOutboxAndRetriesTransientErrors() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(tokens.configured()).thenReturn(true);
        when(tokens.get()).thenReturn("service-jwt");
        doThrow(new IllegalStateException("network")).doNothing().when(inventory).release(paymentId, "service-jwt");
        worker.run();
        assertEquals(false, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        jdbc.update("UPDATE payment_inventory_tasks SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        worker.run();
        assertEquals(true, jdbc.queryForObject("SELECT done FROM payment_inventory_tasks WHERE order_id=?", Boolean.class, orderId));
    }

    /** IPN sai chữ ký/số tiền không tạo history/outbox; payment và order phải giữ nguyên trạng thái pending. */
    @Test
    void invalidWebhookCannotChangeDatabase() {
        var tampered = callback("00", "00", "5000000");
        tampered.put("vnp_Amount", "1");
        assertEquals("97", payments.ipn(tampered).code());
        assertEquals("04", payments.ipn(callback("00", "00", "1")).code());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=?", Integer.class, orderId));
    }

    /** Lỗi Notification xảy ra sau commit không đổi PAID/SUCCESS; retry giữ UUID/payload và không tạo thêm sự kiện. */
    @Test
    void notificationFailureCannotUndoPaymentAndRetriesSameSnapshot() {
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        worker.run();
        assertEquals("02", payments.ipn(callback("00", "00", "5000000")).code());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=?", Integer.class, orderId));
        jdbc.update("UPDATE commerce_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 hour' WHERE order_id<>?", orderId);
        when(notificationDelivery.configured()).thenReturn(true);
        doThrow(new IllegalStateException("network")).when(notificationDelivery).send(any());
        notificationWorker.run();
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND NOT done AND attempts=1", Integer.class, orderId));
        org.mockito.Mockito.doNothing().when(notificationDelivery).send(any());
        jdbc.update("UPDATE commerce_notification_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE order_id=?", orderId);
        notificationWorker.run();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND done AND attempts=2", Integer.class, orderId));
        var events = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(notificationDelivery, org.mockito.Mockito.times(4)).send(events.capture());
        assertEquals(new java.util.HashSet<>(events.getAllValues().subList(0, 2)),
                new java.util.HashSet<>(events.getAllValues().subList(2, 4)));
        assertEquals(buyer, events.getAllValues().get(0).get("userId"));
    }

    /** Thiếu OAuth giữ backlog pending; IPN thất bại tạo đúng thông báo thất bại thay vì thành công. */
    @Test
    void disabledDeliveryKeepsEventsAndFailureUsesCorrectType() {
        assertEquals("00", payments.ipn(callback("24", "02", "5000000")).code());
        when(notificationDelivery.configured()).thenReturn(false);
        notificationWorker.run();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_FAILED' AND NOT done", Integer.class, orderId));
        verify(notificationDelivery, org.mockito.Mockito.never()).send(any());
    }

    /** Constraint lỗi đúng lúc commit phải rollback cả trạng thái lẫn history/outbox; IPN retry sau đó vẫn xử lý được. */
    @Test
    void databaseFailureRollsBackFinancialStateAndOutboxTogether() {
        jdbc.execute("CREATE FUNCTION payment_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test commit failure'; END; $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER payment_test_failure AFTER UPDATE ON payments DEFERRABLE INITIALLY DEFERRED "
                + "FOR EACH ROW EXECUTE FUNCTION payment_test_reject()");
        try {
            assertThrows(RuntimeException.class, () -> payments.ipn(callback("00", "00", "5000000")));
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
            assertEquals("PENDING_PAYMENT", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
            // Deferred commit failure phải rollback cả giá chính thức của Order và OrderItem, không chỉ Payment.status.
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT total FROM orders WHERE id=?", BigDecimal.class, orderId));
            org.junit.jupiter.api.Assertions.assertNull(jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id=?", BigDecimal.class, orderId));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=?", Integer.class, orderId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM commerce_notification_outbox WHERE order_id=?", Integer.class, orderId));
        } finally {
            jdbc.execute("DROP TRIGGER payment_test_failure ON payments");
            jdbc.execute("DROP FUNCTION payment_test_reject()");
        }
        VnpayIpnResponse result = payments.ipn(callback("00", "00", "5000000"));
        assertEquals("00", result.code());
    }

    /** Checkout/IPN, quyền sở hữu, state machine và history chạy service/DB thật, không mock OrderService. */
    @Test
    void paidOrderCompletesRealLifecycleAndKeepsEmailSnapshot() {
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        worker.run();
        UUID seller = jdbc.queryForObject("SELECT seller_id FROM orders WHERE id=?", UUID.class, orderId);
        assertThrows(com.nexus.common.exception.BusinessException.class, () ->
                management.getOrderDetail(orderId, UUID.randomUUID(), List.of("ROLE_BUYER")));
        for (var status : List.of(com.nexus.commerce.enums.OrderStatus.PROCESSING,
                com.nexus.commerce.enums.OrderStatus.SHIPPED, com.nexus.commerce.enums.OrderStatus.DELIVERED)) {
            assertEquals(status, management.updateOrderStatus(orderId,
                    new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(status, "Cập nhật giao hàng"),
                    seller, List.of("ROLE_SELLER")).status());
        }
        management.updateOrderStatus(orderId, new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(
                com.nexus.commerce.enums.OrderStatus.COMPLETED, "Hoàn tất giao hàng"), UUID.randomUUID(), List.of("ROLE_ADMIN"));
        assertEquals("COMPLETED", management.getOrderDetail(orderId, buyer, List.of("ROLE_BUYER")).status().name());
        assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, orderId));
        assertEquals("buyer@example.test", jdbc.queryForObject(
                "SELECT email_payload->>'recipientEmail' FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'",
                String.class, orderId));
        assertEquals("Sản phẩm", jdbc.queryForObject(
                "SELECT email_payload->'items'->0->>'productName' FROM commerce_notification_outbox WHERE order_id=? AND event_type='PAYMENT_SUCCESS'",
                String.class, orderId));
    }

    /** Hai request cùng chuyển PAID→PROCESSING phải đọc lại sau row lock, chỉ ghi history một lần. */
    @Test
    void concurrentStatusUpdatesAreSerialized() throws Exception {
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        worker.run();
        UUID seller = jdbc.queryForObject("SELECT seller_id FROM orders WHERE id=?", UUID.class, orderId);
        var request = new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(
                com.nexus.commerce.enums.OrderStatus.PROCESSING, "Bắt đầu đóng gói");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { gate.await(); return management.updateOrderStatus(orderId, request, seller, List.of("ROLE_SELLER")); });
            var second = executor.submit(() -> { gate.await(); return management.updateOrderStatus(orderId, request, seller, List.of("ROLE_SELLER")); });
            gate.countDown();
            assertEquals("PROCESSING", first.get(20, TimeUnit.SECONDS).status().name());
            assertEquals("PROCESSING", second.get(20, TimeUnit.SECONDS).status().name());
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id=? AND from_status='PAID' AND to_status='PROCESSING'", Integer.class, orderId));
    }

    /** Chưa có refund/cancel provider: từ chối hủy VNPAY đang chờ hoặc PAID, không làm sai dữ liệu tài chính. */
    @Test
    void cancellationCannotFakeRefundOrRaceAnActiveVnpayPayment() {
        var request = new com.nexus.commerce.dto.request.CancelOrderRequest("Người mua yêu cầu hủy");
        assertThrows(com.nexus.common.exception.BusinessException.class,
                () -> management.cancelOrder(orderId, request, buyer, List.of("ROLE_BUYER")));
        assertEquals("00", payments.ipn(callback("00", "00", "5000000")).code());
        worker.run();
        assertThrows(com.nexus.common.exception.BusinessException.class,
                () -> management.cancelOrder(orderId, request, buyer, List.of("ROLE_BUYER")));
        assertThrows(com.nexus.common.exception.BusinessException.class, () -> management.updateOrderStatus(orderId,
                new com.nexus.commerce.dto.request.UpdateOrderStatusRequest(com.nexus.commerce.enums.OrderStatus.REFUNDED, "Hoàn tiền"),
                UUID.randomUUID(), List.of("ROLE_ADMIN")));
        assertEquals("PAID", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, paymentId));
    }

    /** Hủy COD thật phải ghi tác vụ release trong cùng transaction; gọi lặp không tạo history/tác vụ trùng. */
    @Test
    void cancellingCodPersistsAnIdempotentInventoryRelease() {
        UUID codBuyer = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sku = jdbc.queryForObject("SELECT sku_id FROM order_items WHERE order_id=?", UUID.class, orderId);
        jdbc.update("INSERT INTO carts(id,user_id,status,expires_at) VALUES (?,?,'ACTIVE',CURRENT_TIMESTAMP + interval '1 hour')", cart, codBuyer);
        jdbc.update("INSERT INTO cart_items(id,cart_id,sku_id,quantity,unit_price) VALUES (?,?,?,1,25000)", item, cart, sku);
        var request = new CreateOrderRequest("123 Lê Lợi", "Người nhận", "0901234567", null, "COD", null, null, List.of(item));
        UUID codOrder = checkout.create(codBuyer, UUID.randomUUID().toString(), request, "buyer-jwt").id();
        var cancel = new com.nexus.commerce.dto.request.CancelOrderRequest("Không còn nhu cầu mua");
        management.cancelOrder(codOrder, cancel, codBuyer, List.of("ROLE_BUYER"));
        management.cancelOrder(codOrder, cancel, codBuyer, List.of("ROLE_BUYER"));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, codOrder));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM order_status_history WHERE order_id=?", Integer.class, codOrder));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment_inventory_tasks WHERE order_id=? AND action='RELEASE'", Integer.class, codOrder));
        when(tokens.get()).thenReturn("service-jwt");
        worker.run();
        verify(inventory).release(codOrder, "service-jwt");
    }
}
