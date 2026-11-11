package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.AuctionTimeoutSyncClient;
import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.UserContactClient;
import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.impl.OrderServiceImpl;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Kiểm thử đơn vị cho vòng đời thông báo winner đấu giá (AUCTION_WON), nhắc hạn trước 2 giờ,
 * xử lý quá hạn 24h (nhả kho + phạt 10 điểm uy tín + đồng bộ DEFAULTED) và khôi phục khi mất mạng.
 */
class AuctionWinnerNotificationAndTimeoutTest {

    private OrderRepository orderRepository;
    private OrderStatusHistoryRepository historyRepository;
    private OrderStatusStateMachine stateMachine;
    private OrderMapper orderMapper;
    private JdbcTemplate jdbc;
    private NotificationOutbox notificationOutbox;
    private UserContactClient userContactClient;
    private PlatformTransactionManager txManager;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        historyRepository = mock(OrderStatusHistoryRepository.class);
        stateMachine = mock(OrderStatusStateMachine.class);
        orderMapper = mock(OrderMapper.class);
        jdbc = mock(JdbcTemplate.class);
        notificationOutbox = mock(NotificationOutbox.class);
        userContactClient = mock(UserContactClient.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
    }

    /** Tạo đơn winner lấy email từ UserContactClient, ghi AUCTION_WON lần đầu và bỏ qua khi replay. */
    @Test
    void createAuctionOrderEnrichesWinnerContactAndEnqueuesNotificationOnce() {
        UUID auctionId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(24, ChronoUnit.HOURS);

        when(userContactClient.findContact(winnerId))
                .thenReturn(Optional.of(new UserContactClient.UserContact(winnerId, "winner@nexus.test", "Nguyễn Winner")));
        when(orderRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderServiceImpl service = new OrderServiceImpl(
                orderRepository, historyRepository, stateMachine, orderMapper,
                jdbc, mock(CatalogClient.class), notificationOutbox, userContactClient);

        CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                auctionId, winnerId, sellerId, null, skuId, new BigDecimal("1800000"),
                deadline, "Máy ảnh cổ điển", "CAM-01", List.of(UUID.randomUUID()));

        service.createAuctionOrder(request);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        // Bảo đảm Order (và các bản ghi con) được flush xuống DB trước khi NotificationOutbox chạy JDBC INSERT tham chiếu FK order_id.
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orderRepository, notificationOutbox);
        inOrder.verify(orderRepository).save(any(Order.class));
        inOrder.verify(orderRepository).flush();
        inOrder.verify(notificationOutbox).enqueue(orderCaptor.capture(), eq("AUCTION_WON"));
        Order captured = orderCaptor.getValue();
        assertEquals("winner@nexus.test", captured.getBuyerEmail());
        assertEquals("Nguyễn Winner", captured.getRecipientName());
        assertEquals(deadline, captured.getPaymentDeadline());

        // Replay cùng auctionId trả về đơn cũ, không phát lại AUCTION_WON.
        when(orderRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(captured));
        service.createAuctionOrder(request);
        verify(notificationOutbox, times(1)).enqueue(any(Order.class), eq("AUCTION_WON"));
    }

    /** NotificationOutbox đóng gói snapshot kèm paymentLink /order-detail?id=..., paymentDeadline và penaltyScore. */
    @Test
    void notificationOutboxCapturesAuctionPaymentLinkDeadlineAndPenaltySnapshot() {
        NotificationOutbox outbox = new NotificationOutbox(jdbc, "https://shop.nexus.vn/", 10);
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        Instant deadline = Instant.parse("2026-11-12T10:00:00Z");

        Order order = Order.builder()
                .id(orderId)
                .orderNo("AUC-20261111-ABC123")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .buyerEmail("winner@nexus.test")
                .recipientName("Winner")
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(auctionId)
                .paymentMethod("VNPAY")
                .paymentDeadline(deadline)
                .total(new BigDecimal("2500000.00"))
                .build();
        order.addItem(OrderItem.builder()
                .id(UUID.randomUUID())
                .skuId(UUID.randomUUID())
                .productName("Đồng hồ cổ")
                .skuCode("WATCH-1")
                .quantity(1)
                .unitPrice(new BigDecimal("2500000.00"))
                .build());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        outbox.enqueue(order, "AUCTION_PAYMENT_REMINDER");
        verify(jdbc).update(contains("commerce_notification_outbox"),
                any(UUID.class), eq(orderId), eq(buyerId), eq("AUCTION_PAYMENT_REMINDER"),
                eq("AUC-20261111-ABC123"), eq(new BigDecimal("2500000.00")), jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("https://shop.nexus.vn/order-detail?id=" + orderId));
        assertTrue(json.contains(deadline.toString()));
        assertTrue(json.contains("\"penaltyScore\":10"));
    }

    /** Đơn thắng đấu giá chưa thu tiền (priceFinalized = false) dùng estimatedTotal/estimatedUnitPrice cho outbox. */
    @Test
    void notificationOutboxUsesEstimatedPricesWhenAuctionOrderIsNotFinalized() {
        NotificationOutbox outbox = new NotificationOutbox(jdbc, "https://shop.nexus.vn/", 10);
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Instant deadline = Instant.parse("2026-11-12T10:00:00Z");

        Order order = Order.builder()
                .id(orderId)
                .orderNo("AUC-EST-1")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .buyerEmail("winner@nexus.test")
                .recipientName("Winner")
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(UUID.randomUUID())
                .paymentMethod("AUCTION")
                .paymentDeadline(deadline)
                .priceFinalized(false)
                .estimatedTotal(new BigDecimal("1950000.00"))
                .total(null)
                .build();
        order.addItem(OrderItem.builder()
                .id(UUID.randomUUID())
                .skuId(UUID.randomUUID())
                .productName("Tác phẩm gốm")
                .skuCode("POT-1")
                .quantity(1)
                .estimatedUnitPrice(new BigDecimal("1950000.00"))
                .unitPrice(null)
                .build());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        outbox.enqueue(order, "AUCTION_WON");
        verify(jdbc).update(contains("commerce_notification_outbox"),
                any(UUID.class), eq(orderId), eq(buyerId), eq("AUCTION_WON"),
                eq("AUC-EST-1"), eq(new BigDecimal("1950000.00")), jsonCaptor.capture());

        String json = jsonCaptor.getValue();
        assertTrue(json.contains("\"priceFinalized\":false"));
        assertTrue(json.contains("1950000.00"));
        assertTrue(json.contains("https://shop.nexus.vn/order-detail?id=" + orderId));
    }

    /** Worker nhắc hạn chỉ gửi AUCTION_PAYMENT_REMINDER cho đơn chưa thanh toán nằm trong cửa sổ trước hạn 2 giờ. */
    @Test
    void reminderWorkerEnqueuesReminderWithinTwoHourWindow() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("AUC-REM-1")
                .buyerId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentDeadline(Instant.now().plus(90, ChronoUnit.MINUTES))
                .total(new BigDecimal("1000000"))
                .build();

        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(120))).thenReturn(List.of(orderId));
        when(orderRepository.findForPaymentWithItems(orderId)).thenReturn(Optional.of(order));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(orderId))).thenReturn(0);

        AuctionPaymentReminderWorker reminderWorker = new AuctionPaymentReminderWorker(
                jdbc, orderRepository, txManager, notificationOutbox, 120);
        reminderWorker.run();

        verify(notificationOutbox).enqueue(order, "AUCTION_PAYMENT_REMINDER");
    }

    /** Quá hạn 24h hủy đơn, ghi tác vụ RELEASE kho, ghi outbox phạt 10 điểm + đồng bộ DEFAULTED + thông báo. */
    @Test
    void expiryWorkerEnqueuesReleaseTimeoutNotificationPenaltyAndAuctionSyncAtomically() {
        UUID orderId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("AUC-EXP-1")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(auctionId)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentDeadline(Instant.now().minus(5, ChronoUnit.MINUTES))
                .total(new BigDecimal("1000000"))
                .build();

        when(jdbc.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of(orderId));
        when(orderRepository.findForPaymentWithItems(orderId)).thenReturn(Optional.of(order));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(orderId))).thenReturn(0);

        AuctionPaymentExpiryWorker worker = new AuctionPaymentExpiryWorker(
                jdbc, orderRepository, txManager, notificationOutbox, 10);
        worker.run();

        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        verify(jdbc).update(contains("payment_inventory_tasks"), any(UUID.class), eq(orderId), eq(orderId));
        verify(jdbc).update(contains("REPUTATION_PENALTY"),
                any(UUID.class), eq(orderId), eq(buyerId), eq(auctionId), eq(10), anyString());
        verify(jdbc).update(contains("AUCTION_DEFAULTED"),
                any(UUID.class), eq(orderId), eq(buyerId), eq(auctionId), eq(10), anyString());
        verify(notificationOutbox).enqueue(order, "AUCTION_PAYMENT_TIMEOUT");
    }

    /** Mô phỏng lỗi mạng tạm thời sang Reputation/Auction: giữ outbox retry backoff và hồi phục ở lần kế tiếp. */
    @Test
    void timeoutSideEffectWorkerRetriesOnNetworkFailureThenCompletes() {
        AuctionTimeoutSyncClient syncClient = mock(AuctionTimeoutSyncClient.class);
        when(syncClient.configured()).thenReturn(true);

        UUID taskId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "id", taskId,
                "order_id", orderId,
                "user_id", userId,
                "auction_id", auctionId,
                "task_type", "REPUTATION_PENALTY",
                "score_delta", 10,
                "reason", "Quá hạn thanh toán 24h"
        );
        when(jdbc.queryForList(contains("auction_timeout_tasks")))
                .thenReturn(List.of(row), List.of(), List.of(row), List.of());

        doThrow(new IllegalStateException("Network timeout calling reputation-service"))
                .doNothing()
                .when(syncClient).applyTimeoutPenalty(userId, auctionId, orderId, 10, "Quá hạn thanh toán 24h");

        AuctionTimeoutSideEffectWorker worker = new AuctionTimeoutSideEffectWorker(jdbc, syncClient, txManager);
        worker.run();
        verify(jdbc).update(contains("attempts=attempts+1, next_attempt_at"), eq(taskId));
        verify(jdbc, never()).update(contains("SET done=true"), eq(taskId));

        worker.run();
        verify(jdbc).update(contains("SET done=true,attempts=attempts+1"), eq(taskId));
        assertNotNull(taskId);
    }
}
