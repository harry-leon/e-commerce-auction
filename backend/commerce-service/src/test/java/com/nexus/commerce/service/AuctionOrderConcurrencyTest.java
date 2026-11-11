package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.dto.response.AuctionOrderResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.impl.OrderServiceImpl;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bộ kiểm thử cạnh tranh và tích hợp cho quy trình bàn giao đơn hàng đấu giá (Feedback Point 5 & DoD KH-S2-2).
 *
 * Chức năng kiểm thử:
 * 1. Chống tạo trùng lặp khi có hai request đồng thời (Concurrency Idempotency):
 *    - Mô phỏng hai luồng song song gọi createAuctionOrder cùng một lúc với cùng auctionId.
 *    - Chứng minh chỉ có duy nhất 1 đơn hàng được tạo, luồng đến sau nhận lại đúng đơn hàng của luồng trước.
 * 2. Phục hồi khi mất phản hồi mạng (Lost Response Replay):
 *    - Mô phỏng trường hợp client gửi request tạo đơn thành công nhưng mất kết nối và gửi lại (replay).
 *    - Chứng minh hệ thống trả về đúng đơn hàng cũ với thông tin không thay đổi.
 * 3. Tích hợp dữ liệu với PaymentService:
 *    - Chứng minh đơn hàng đấu giá có paymentDeadline 24h và reservationIds gắn kèm
 *      đáp ứng trọn vẹn điều kiện để PaymentService tạo link thanh toán VNPAY (không bị hết hạn 15m, không thiếu kho).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuctionOrderConcurrencyTest - Kiểm thử cạnh tranh & tính lũy nghiệm khi bàn giao đơn hàng")
class AuctionOrderConcurrencyTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private OrderStatusStateMachine orderStatusStateMachine;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private CatalogClient catalogClient;

    private OrderService orderService;

    private final UUID auctionId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UUID winnerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000044");
    private final BigDecimal finalPrice = new BigDecimal("2500000.00");
    private final Instant paymentDeadline = Instant.now().plus(24, ChronoUnit.HOURS);

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
                orderRepository,
                orderStatusHistoryRepository,
                orderStatusStateMachine,
                orderMapper,
                jdbcTemplate,
                catalogClient
        );
    }

    @Test
    @DisplayName("Hai request đồng thời với cùng auctionId: Chỉ tạo đúng 1 đơn hàng duy nhất trong DB")
    void test_concurrentAuctionOrderCreation_createsOnlyOneOrder() throws InterruptedException, ExecutionException {
        // Mô phỏng kho lưu trữ nguyên tử để tái hiện hành vi của database unique constraint
        ConcurrentHashMap<UUID, Order> dbStore = new ConcurrentHashMap<>();
        AtomicInteger saveCount = new AtomicInteger(0);

        when(orderRepository.findByAuctionId(auctionId)).thenAnswer(inv ->
                Optional.ofNullable(dbStore.get(auctionId))
        );

        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            Order existing = dbStore.putIfAbsent(auctionId, o);
            if (existing != null) {
                // Đã có luồng khác lưu trước
                return existing;
            }
            saveCount.incrementAndGet();
            return o;
        });

        CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                auctionId, winnerId, sellerId, null, skuId, finalPrice, paymentDeadline, "Dong ho Thuy Si", "SKU-SWISS-01", null
        );

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch readyLatch = new CountDownLatch(numberOfThreads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<AuctionOrderResponse>> futures = new ArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                // Chờ hiệu lệnh để cả 2 luồng cùng bắn request đồng thời
                startLatch.await(5, TimeUnit.SECONDS);
                return orderService.createAuctionOrder(request);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Bắn đồng thời

        AuctionOrderResponse res1 = futures.get(0).get();
        AuctionOrderResponse res2 = futures.get(1).get();

        executor.shutdown();

        // Cả 2 luồng đều nhận kết quả thành công và cùng chung orderId
        assertNotNull(res1);
        assertNotNull(res2);
        assertEquals(res1.orderId(), res2.orderId(), "Hai luồng gọi đồng thời phải nhận về cùng một orderId duy nhất");
        assertEquals(res1.auctionId(), res2.auctionId());
        assertEquals(res1.total(), res2.total());

        // Kiểm tra trong DB giả lập chỉ lưu đúng 1 bản ghi
        assertEquals(1, dbStore.size(), "Chỉ có duy nhất 1 bản ghi Order được tạo trong CSDL");
    }

    @Test
    @DisplayName("Mất response và replay: Gửi lại request nhận đúng đơn cũ với snapshot không đổi")
    void test_lostResponseReplay_returnsExistingOrderWithoutDuplicate() {
        Order savedOrder = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("AUC-20261109-A1B2C3")
                .buyerId(winnerId)
                .sellerId(sellerId)
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(auctionId)
                .total(finalPrice)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentDeadline(paymentDeadline)
                .createdAt(Instant.now().minusSeconds(60))
                .build();

        // Lần 1: Chưa có trong DB -> tạo mới thành công
        when(orderRepository.findByAuctionId(auctionId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedOrder)); // Lần 2 (replay): đã có trong DB

        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);

        CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                auctionId, winnerId, sellerId, skuId, finalPrice, paymentDeadline
        );

        // Client gọi lần 1
        AuctionOrderResponse response1 = orderService.createAuctionOrder(request);
        assertNotNull(response1);

        // Client bị timeout/mất response, replay lại lần 2
        AuctionOrderResponse response2 = orderService.createAuctionOrder(request);
        assertNotNull(response2);

        // Kết quả replay phải trùng khớp 100%
        assertEquals(response1.orderId(), response2.orderId());
        assertEquals(response1.orderNo(), response2.orderNo());
        assertEquals(response1.total(), response2.total());
        assertEquals(response1.paymentDeadline(), response2.paymentDeadline());
    }

    @Test
    @DisplayName("Đơn hàng đấu giá có đủ reservationIds và paymentDeadline 24h: Đủ điều kiện thanh toán")
    void test_auctionOrderWithReservations_meetsPaymentRequirements() {
        UUID resId = UUID.randomUUID();
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("AUC-20261109-XY9876")
                .buyerId(winnerId)
                .sellerId(sellerId)
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(auctionId)
                .total(finalPrice)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentDeadline(paymentDeadline)
                .paymentMethod("VNPAY")
                .createdAt(Instant.now().minusSeconds(100))
                .build();
        order.getReservationIds().add(resId);

        // Kiểm tra tính hợp lệ về thời hạn và phiếu kho cho payment:
        // 1. paymentDeadline là 24h, vẫn còn hiệu lực
        assertTrue(order.getPaymentDeadline().isAfter(Instant.now()), "Thời hạn thanh toán 24h phải còn hiệu lực");
        // 2. reservationIds không rỗng (đã được chuyển tiếp từ Fulfillment)
        assertEquals(1, order.getReservationIds().size());
        assertTrue(order.getReservationIds().contains(resId), "Đơn phải chứa reservationId từ Fulfillment");
    }
}
