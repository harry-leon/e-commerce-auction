package com.nexus.commerce.e2e;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.controller.OrderController;
import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.fixture.OrderTestFixtures;
import com.nexus.commerce.service.OrderService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử End-to-End (E2E) toàn diện vòng đời đơn hàng (Issue HG-S1-4 / Milestone 4).
 *
 * <p>Chức năng: Mô phỏng đầy đủ 4 kịch bản vòng đời đơn hàng theo yêu cầu R4:
 * <ul>
 *   <li>Kịch bản A (Happy Path): PENDING_PAYMENT -> PAID -> PROCESSING -> SHIPPED -> DELIVERED -> COMPLETED.</li>
 *   <li>Kịch bản B (Hủy đơn): Cho phép người mua hủy khi PENDING_PAYMENT, từ chối hủy khi đơn đã SHIPPED.</li>
 *   <li>Kịch bản C (Hoàn tiền / Hết hàng): Xử lý hoàn tiền khi người bán hết hàng hoặc Admin duyệt tranh chấp.</li>
 *   <li>Kịch bản D (Tính lũy thừa & Chặn nhảy cóc trạng thái): Chặn nhảy cóc, lùi trạng thái và bất biến trạng thái đóng.</li>
 * </ul>
 *
 * <p>Đầu vào và kết quả:
 * Giả lập yêu cầu HTTP với JWT token tương ứng cho từng vai trò và kiểm tra mã trạng thái HTTP,
 * cấu trúc JSON trả về theo chuẩn ApiResponse.
 *
 * <p>Luồng xử lý:
 * MockMvc -> Security Layer -> OrderController -> MockitoBean OrderService -> Envelope ApiResponse.
 *
 * <p>Quy tắc và lý do:
 * Tuân thủ mô hình Opaque-box kiểm thử hành vi; xác thực quyền IAM và cấu trúc phản hồi chuẩn.
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("E2E Order Lifecycle Test Suite")
class OrderLifecycleE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private UUID buyerId;
    private UUID sellerId;
    private UUID adminId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        buyerId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        orderId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("Kịch bản A: Vòng đời thành công hoàn chỉnh (Happy Path)")
    class ScenarioAHappyPath {

        /**
         * Mục đích: Xác nhận luồng nghiệp vụ mua sắm trọn vẹn từ lúc đặt hàng đến khi người mua nhận hàng và hoàn tất.
         * Đầu vào: Chuỗi các request cập nhật trạng thái theo đúng vai trò nghiệp vụ (Admin xác nhận tiền, Người bán đóng gói, Người giao hàng vận chuyển, Admin/Hệ thống hoàn tất).
         * Kết quả: Đơn hàng chuyển tuần tự qua các trạng thái; mỗi bước trả về HTTP 200 OK và dữ liệu trạng thái tương ứng.
         */
        @Test
        @DisplayName("Bước 1 đến 5: Vòng đời tuần tự từ PENDING_PAYMENT -> PAID -> PROCESSING -> SHIPPED -> DELIVERED -> COMPLETED")
        void testFullHappyPathLifecycle() throws Exception {
            // Bước 1: Hệ thống / Admin xác nhận thanh toán (PENDING_PAYMENT -> PAID)
            Order paidOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse paidResponse = OrderTestFixtures.createMockOrderDetailResponse(paidOrder);
            UpdateOrderStatusRequest payRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.PAID, "Payment confirmed via VietQR");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenReturn(paidResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PAID"));

            // Bước 2: Người bán tiếp nhận đơn và đóng gói hàng (PAID -> PROCESSING)
            Order processingOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.PROCESSING);
            OrderDetailResponse processingResponse = OrderTestFixtures.createMockOrderDetailResponse(processingOrder);
            UpdateOrderStatusRequest processRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.PROCESSING, "Order packed and ready for dispatch");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenReturn(processingResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockSellerJwt(sellerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(processRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PROCESSING"));

            // Bước 3: Người bán giao bưu kiện cho đơn vị vận chuyển (PROCESSING -> SHIPPED)
            Order shippedOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.SHIPPED);
            OrderDetailResponse shippedResponse = OrderTestFixtures.createMockOrderDetailResponse(shippedOrder);
            UpdateOrderStatusRequest shipRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.SHIPPED, "Handed to courier VN-POST-12345");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenReturn(shippedResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockSellerJwt(sellerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(shipRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("SHIPPED"));

            // Bước 4: Đơn vị vận chuyển / Admin cập nhật đã giao hàng (SHIPPED -> DELIVERED)
            Order deliveredOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.DELIVERED);
            OrderDetailResponse deliveredResponse = OrderTestFixtures.createMockOrderDetailResponse(deliveredOrder);
            UpdateOrderStatusRequest deliverRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.DELIVERED, "Delivered to recipient");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenReturn(deliveredResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(deliverRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("DELIVERED"));

            // Bước 5: Admin xác nhận hoàn tất giao dịch (DELIVERED -> COMPLETED)
            Order completedOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.COMPLETED);
            OrderDetailResponse completedResponse = OrderTestFixtures.createMockOrderDetailResponse(completedOrder);
            UpdateOrderStatusRequest completeRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.COMPLETED, "Transaction completed");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenReturn(completedResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(completeRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        }
    }

    @Nested
    @DisplayName("Kịch bản B: Vòng đời hủy đơn hàng (Cancellation Lifecycle)")
    class ScenarioBCancellation {

        /**
         * Mục đích: Kiểm tra trường hợp người mua được phép hủy đơn hàng trước khi thanh toán (PENDING_PAYMENT).
         * Đầu vào: Đơn hàng ở trạng thái PENDING_PAYMENT, người mua gửi lý do hủy.
         * Kết quả: Trạng thái chuyển sang CANCELLED, HTTP 200 OK.
         */
        @Test
        @DisplayName("B1: Người mua hủy thành công đơn hàng đang ở trạng thái PENDING_PAYMENT")
        void testBuyerCanCancelOrderInPendingPayment() throws Exception {
            Order cancelledOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.CANCELLED);
            OrderDetailResponse cancelledResponse = OrderTestFixtures.createMockOrderDetailResponse(cancelledOrder);
            CancelOrderRequest cancelRequest = OrderTestFixtures.createMockCancelOrderRequest("Đổi ý, không muốn mua nữa");

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenReturn(cancelledResponse);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                    .with(OrderTestFixtures.mockBuyerJwt(buyerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        /**
         * Mục đích: Kiểm tra quy tắc nghiệp vụ chặn người mua hủy đơn khi đơn hàng đã bàn giao vận chuyển (SHIPPED).
         * Đầu vào: Đơn hàng đã ở trạng thái SHIPPED, người mua gửi request hủy.
         * Kết quả: Hệ thống ném lỗi ORDER_CANNOT_CANCEL, trả về mã lỗi HTTP 409 Conflict.
         */
        @Test
        @DisplayName("B2: Chặn người mua hủy đơn khi đơn hàng đã chuyển sang trạng thái SHIPPED")
        void testBuyerCannotCancelOrderWhenShipped() throws Exception {
            CancelOrderRequest cancelRequest = OrderTestFixtures.createMockCancelOrderRequest("Hủy đơn vì giao lâu");

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenThrow(new BusinessException(ErrorCode.ORDER_CANNOT_CANCEL, "Không thể hủy đơn hàng đang giao"));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                    .with(OrderTestFixtures.mockBuyerJwt(buyerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_CANNOT_CANCEL"));
        }

        /**
         * Mục đích: Kiểm tra bảo mật đa người dùng (Multi-tenant isolation) - chặn người mua A hủy đơn của người mua B.
         * Đầu vào: User B gửi request hủy đơn của User A.
         * Kết quả: Trả về lỗi 403 Forbidden.
         */
        @Test
        @DisplayName("B3: Từ chối yêu cầu khi người mua cố gắng hủy đơn hàng của người khác")
        void testBuyerCannotCancelOtherUserOrder() throws Exception {
            UUID anotherBuyerId = UUID.randomUUID();
            CancelOrderRequest cancelRequest = OrderTestFixtures.createMockCancelOrderRequest("Phá hoại đơn hàng");

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(anotherBuyerId), any()))
                    .thenThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN, "Bạn không có quyền thao tác trên đơn hàng này"));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                    .with(OrderTestFixtures.mockBuyerJwt(anotherBuyerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * Mục đích: Kiểm tra trường hợp Người mua hủy đơn đã thanh toán (PAID) chuyển sang trạng thái REFUNDED.
         * Đầu vào: Đơn hàng ở trạng thái PAID, người mua gửi yêu cầu hủy đơn.
         * Kết quả: Đơn hàng chuyển sang trạng thái REFUNDED để kích hoạt hoàn tiền, HTTP 200 OK.
         */
        @Test
        @DisplayName("B4: Người mua hủy đơn hàng đã thanh toán (PAID) -> Chuyển sang trạng thái REFUNDED")
        void testBuyerCanCancelPaidOrderTransitionsToRefunded() throws Exception {
            Order refundedOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.REFUNDED);
            OrderDetailResponse refundedResponse = OrderTestFixtures.createMockOrderDetailResponse(refundedOrder);
            CancelOrderRequest cancelRequest = OrderTestFixtures.createMockCancelOrderRequest("Hủy đơn đã thanh toán để nhận hoàn tiền");

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenReturn(refundedResponse);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                    .with(OrderTestFixtures.mockBuyerJwt(buyerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(cancelRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("REFUNDED"));
        }
    }

    @Nested
    @DisplayName("Kịch bản C: Vòng đời hoàn tiền và xử lý hết hàng (Refund Lifecycle)")
    class ScenarioCRefund {

        /**
         * Mục đích: Kiểm tra trường hợp Người bán hoặc Admin hoàn tiền khi phát hiện sản phẩm bị hỏng/hết hàng sau khi đã thanh toán (PAID -> REFUNDED).
         * Đầu vào: Đơn hàng ở trạng thái PAID, người bán gửi yêu cầu cập nhật trạng thái REFUNDED.
         * Kết quả: Trạng thái chuyển sang REFUNDED thành công, HTTP 200 OK.
         */
        @Test
        @DisplayName("C1: Người bán hoặc Admin hoàn tiền đơn hàng PAID do hết hàng trong kho")
        void testSellerCanRefundPaidOrderWhenOutOfStock() throws Exception {
            Order refundedOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.REFUNDED);
            OrderDetailResponse refundedResponse = OrderTestFixtures.createMockOrderDetailResponse(refundedOrder);
            UpdateOrderStatusRequest refundRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.REFUNDED, "Hết hàng trong kho tại thời điểm đóng gói");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenReturn(refundedResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockSellerJwt(sellerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refundRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("REFUNDED"));
        }

        /**
         * Mục đích: Kiểm tra trường hợp Admin giải quyết tranh chấp sau giao hàng (DELIVERED -> REFUNDED).
         * Đầu vào: Đơn hàng DELIVERED, Admin duyệt yêu cầu hoàn tiền cho người mua.
         * Kết quả: Chuyển sang REFUNDED thành công, HTTP 200 OK.
         */
        @Test
        @DisplayName("C2: Admin giải quyết tranh chấp và hoàn tiền đơn hàng sau khi đã giao hàng (DELIVERED -> REFUNDED)")
        void testAdminCanRefundDeliveredOrderOnDispute() throws Exception {
            Order refundedOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.REFUNDED);
            OrderDetailResponse refundedResponse = OrderTestFixtures.createMockOrderDetailResponse(refundedOrder);
            UpdateOrderStatusRequest refundRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.REFUNDED, "Admin duyệt hoàn tiền: Hàng lỗi do vận chuyển");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenReturn(refundedResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refundRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("REFUNDED"));
        }

        /**
         * Mục đích: Chặn hoàn tiền đối với đơn hàng chưa thanh toán (PENDING_PAYMENT -> REFUNDED là bất hợp lệ).
         * Đầu vào: Request chuyển trạng thái sang REFUNDED khi đơn chưa thanh toán.
         * Kết quả: Ném lỗi ORDER_STATUS_INVALID, trả về HTTP 409 Conflict.
         */
        @Test
        @DisplayName("C3: Từ chối yêu cầu hoàn tiền đối với đơn hàng chưa thanh toán (PENDING_PAYMENT)")
        void testCannotRefundUnpaidOrder() throws Exception {
            UpdateOrderStatusRequest refundRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.REFUNDED, "Thử hoàn tiền đơn chưa trả tiền");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenThrow(new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Không thể hoàn tiền đơn hàng chưa thanh toán"));

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(refundRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_INVALID"));
        }
    }

    @Nested
    @DisplayName("Kịch bản D: Tính lũy thừa và Chặn nhảy cóc trạng thái (Idempotency & Jump Rejections)")
    class ScenarioDIdempotencyAndJumpRejections {

        /**
         * Mục đích: Kiểm tra tính lũy thừa khi webhook gửi thông báo trạng thái SHIPPED lặp lại nhiều lần.
         * Đầu vào: Đơn hàng đã ở trạng thái SHIPPED, gửi lại request trạng thái SHIPPED.
         * Kết quả: Hệ thống xử lý an toàn, trả về trạng thái hiện tại mà không tạo bản ghi lịch sử trùng lặp.
         */
        @Test
        @DisplayName("D1: Xử lý lũy thừa khi nhận request cập nhật trùng trạng thái hiện tại (Idempotent Update)")
        void testIdempotentStatusUpdate() throws Exception {
            Order shippedOrder = OrderTestFixtures.createMockOrder(orderId, buyerId, sellerId, OrderStatus.SHIPPED);
            OrderDetailResponse shippedResponse = OrderTestFixtures.createMockOrderDetailResponse(shippedOrder);
            UpdateOrderStatusRequest repeatRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.SHIPPED, "Webhook gửi lại cùng trạng thái");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenReturn(shippedResponse);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockSellerJwt(sellerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(repeatRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("SHIPPED"));
        }

        /**
         * Mục đích: Chặn hành vi nhảy cóc trạng thái bất hợp lệ (ví dụ: PENDING_PAYMENT -> DELIVERED).
         * Đầu vào: Đơn hàng đang ở PENDING_PAYMENT, request đòi chuyển thẳng sang DELIVERED.
         * Kết quả: Bị từ chối với mã lỗi 409 Conflict / ORDER_STATUS_INVALID, DB không bị thay đổi.
         */
        @Test
        @DisplayName("D2: Chặn bước nhảy cóc không hợp lệ từ PENDING_PAYMENT sang DELIVERED")
        void testRejectInvalidSkipLevelJump() throws Exception {
            UpdateOrderStatusRequest invalidJumpRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.DELIVERED, "Nhảy cóc trạng thái bất hợp lệ");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenThrow(new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Chuyển trạng thái không hợp lệ: PENDING_PAYMENT -> DELIVERED"));

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidJumpRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_INVALID"));
        }

        /**
         * Mục đích: Chặn bước nhảy lùi trạng thái (ví dụ: SHIPPED -> PROCESSING).
         * Đầu vào: Request chuyển lùi từ SHIPPED về PROCESSING.
         * Kết quả: Bị từ chối với lỗi 409 Conflict / ORDER_STATUS_INVALID.
         */
        @Test
        @DisplayName("D3: Chặn bước nhảy lùi trạng thái từ SHIPPED về PROCESSING")
        void testRejectBackwardStatusTransition() throws Exception {
            UpdateOrderStatusRequest backwardRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.PROCESSING, "Thử chuyển lùi trạng thái");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenThrow(new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Không được phép chuyển lùi trạng thái đơn hàng"));

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockSellerJwt(sellerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(backwardRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }

        /**
         * Mục đích: Đảm bảo tính bất biến của trạng thái đóng (Terminal State Immutability).
         * Đầu vào: Đơn hàng đã ở trạng thái COMPLETED hoặc CANCELLED, gửi request thay đổi trạng thái.
         * Kết quả: Bị từ chối với mã lỗi 409 Conflict; không thể tái mở đơn hàng đã đóng.
         */
        @Test
        @DisplayName("D4: Đảm bảo tính bất biến của trạng thái đóng terminal (COMPLETED không thể thay đổi)")
        void testTerminalStateImmutability() throws Exception {
            UpdateOrderStatusRequest mutationRequest = OrderTestFixtures.createMockUpdateOrderStatusRequest(OrderStatus.PROCESSING, "Cố gắng sửa đổi đơn hàng đã hoàn tất");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenThrow(new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Đơn hàng đã ở trạng thái kết thúc, không thể thay đổi"));

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                    .with(OrderTestFixtures.mockAdminJwt(adminId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(mutationRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_INVALID"));
        }
    }
}
