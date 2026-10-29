package com.nexus.commerce.statemachine;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bộ kiểm thử đơn vị toàn diện cho OrderStatusStateMachine.
 *
 * <p>Mục tiêu kiểm thử:
 * <ol>
 *   <li>Xác thực tính đúng đắn của 10 bước chuyển đổi trạng thái hợp lệ theo từng vai trò (Actors/Roles).</li>
 *   <li>Đảm bảo 3 trạng thái kết thúc (COMPLETED, CANCELLED, REFUNDED) là hoàn toàn bất biến.</li>
 *   <li>Chặn đứng 100% các bước nhảy cóc, quay lui trạng thái sai quy trình.</li>
 *   <li>Chặn đứng các hành động chuyển đổi trái thẩm quyền vai trò (Role Permission Violation).</li>
 *   <li>Kiểm thử chuẩn hóa tiền tố ROLE_ và xử lý dữ liệu đầu vào bất thường (null/empty).</li>
 * </ol>
 */
@DisplayName("OrderStatusStateMachine - Unit Test Suite")
class OrderStatusStateMachineTest {

    private OrderStatusStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStatusStateMachine();
    }

    // =========================================================================
    // 1. Valid Transitions Tests (Tất cả 10 bước chuyển hợp lệ)
    // =========================================================================
    @Nested
    @DisplayName("1. Kiểm thử các bước chuyển đổi trạng thái hợp lệ")
    class ValidTransitionsTest {

        @Test
        @DisplayName("PENDING_PAYMENT -> PAID: Cho phép SYSTEM, PAYMENT, ADMIN")
        void pendingPaymentToPaid_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("SYSTEM"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("ROLE_SYSTEM"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("PAYMENT"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("ROLE_PAYMENT"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("ADMIN"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("ROLE_ADMIN"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "SYSTEM"));
        }

        @Test
        @DisplayName("PENDING_PAYMENT -> CANCELLED: Cho phép BUYER, ADMIN, SYSTEM")
        void pendingPaymentToCancelled_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of("BUYER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of("ROLE_BUYER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of("ADMIN"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of("SYSTEM"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "BUYER"));
        }

        @Test
        @DisplayName("PAID -> PROCESSING: Cho phép SELLER, ADMIN")
        void paidToProcessing_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.PROCESSING, List.of("SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.PROCESSING, List.of("ROLE_SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.PROCESSING, List.of("ADMIN"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PAID, OrderStatus.PROCESSING, "SELLER"));
        }

        @Test
        @DisplayName("PAID -> CANCELLED: Tuyệt đối bị cấm (khi đơn đã thanh toán chỉ được phép chuyển sang REFUNDED)")
        void paidToCancelled_prohibited_throwsException() {
            assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.CANCELLED, List.of("BUYER"));
            });
            assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.CANCELLED, List.of("ADMIN"));
            });

            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.PAID, OrderStatus.CANCELLED, "BUYER"));
        }

        @Test
        @DisplayName("PAID -> REFUNDED: Cho phép ADMIN, SYSTEM, SELLER, BUYER (hết hàng/yêu cầu hủy sau thanh toán)")
        void paidToRefunded_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.REFUNDED, List.of("ADMIN"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.REFUNDED, List.of("SYSTEM"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.REFUNDED, List.of("SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.REFUNDED, List.of("ROLE_SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.REFUNDED, List.of("BUYER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.REFUNDED, List.of("ROLE_BUYER"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PAID, OrderStatus.REFUNDED, "ADMIN"));
        }

        @Test
        @DisplayName("PROCESSING -> SHIPPED: Cho phép SELLER, ADMIN, SYSTEM")
        void processingToShipped_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PROCESSING, OrderStatus.SHIPPED, List.of("SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PROCESSING, OrderStatus.SHIPPED, List.of("ROLE_SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PROCESSING, OrderStatus.SHIPPED, List.of("ADMIN"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PROCESSING, OrderStatus.SHIPPED, List.of("SYSTEM"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PROCESSING, OrderStatus.SHIPPED, "SELLER"));
        }

        @Test
        @DisplayName("SHIPPED -> DELIVERED: Cho phép SELLER, SYSTEM, LOGISTICS, CARRIER, ADMIN")
        void shippedToDelivered_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.DELIVERED, List.of("SELLER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.DELIVERED, List.of("SYSTEM"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.DELIVERED, List.of("LOGISTICS"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.DELIVERED, List.of("ROLE_LOGISTICS"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.DELIVERED, List.of("CARRIER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.DELIVERED, List.of("ADMIN"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.SHIPPED, OrderStatus.DELIVERED, "LOGISTICS"));
        }

        @Test
        @DisplayName("SHIPPED -> CANCELLED: Cho phép SYSTEM, CARRIER, ADMIN (giao hàng thất bại)")
        void shippedToCancelled_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.CANCELLED, List.of("SYSTEM"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.CANCELLED, List.of("CARRIER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.CANCELLED, List.of("ADMIN"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.SHIPPED, OrderStatus.CANCELLED, "SYSTEM"));
        }

        @Test
        @DisplayName("DELIVERED -> COMPLETED: Cho phép CARRIER, LOGISTICS, ADMIN, SYSTEM (chặn BUYER)")
        void deliveredToCompleted_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("CARRIER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("ROLE_CARRIER"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("LOGISTICS"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("ROLE_LOGISTICS"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("SYSTEM"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("ADMIN"));
            });

            // Chặn Người mua (BUYER) tự xác nhận hoàn thành
            assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("BUYER"));
            });
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.DELIVERED, OrderStatus.COMPLETED, "BUYER"));

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.DELIVERED, OrderStatus.COMPLETED, "CARRIER"));
        }

        @Test
        @DisplayName("DELIVERED -> REFUNDED: Chỉ cho phép ADMIN (xử lý khiếu nại trả hàng)")
        void deliveredToRefunded_allowedRoles_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("ADMIN"));
            });
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("ROLE_ADMIN"));
            });

            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.DELIVERED, OrderStatus.REFUNDED, "ADMIN"));
        }
    }

    // =========================================================================
    // 2. Terminal States Immutability Tests (Bất biến trạng thái kết thúc)
    // =========================================================================
    @Nested
    @DisplayName("2. Kiểm thử tính bất biến của các trạng thái kết thúc (Terminal States)")
    class TerminalStatesTest {

        @Test
        @DisplayName("isTerminal xác định đúng 3 trạng thái kết thúc")
        void isTerminal_correctlyIdentified() {
            assertTrue(stateMachine.isTerminal(OrderStatus.COMPLETED));
            assertTrue(stateMachine.isTerminal(OrderStatus.CANCELLED));
            assertTrue(stateMachine.isTerminal(OrderStatus.REFUNDED));

            assertFalse(stateMachine.isTerminal(OrderStatus.PENDING_PAYMENT));
            assertFalse(stateMachine.isTerminal(OrderStatus.PAID));
            assertFalse(stateMachine.isTerminal(OrderStatus.PROCESSING));
            assertFalse(stateMachine.isTerminal(OrderStatus.SHIPPED));
            assertFalse(stateMachine.isTerminal(OrderStatus.DELIVERED));
            assertFalse(stateMachine.isTerminal(null));
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("COMPLETED không thể chuyển sang bất kỳ trạng thái nào kể cả bởi ADMIN")
        void completed_cannotTransitionToAnyState(OrderStatus target) {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.COMPLETED, target, List.of("ADMIN", "SYSTEM"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.COMPLETED, target, "ADMIN"));
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("CANCELLED không thể chuyển sang bất kỳ trạng thái nào kể cả bởi ADMIN")
        void cancelled_cannotTransitionToAnyState(OrderStatus target) {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.CANCELLED, target, List.of("ADMIN", "SYSTEM"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.CANCELLED, target, "ADMIN"));
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("REFUNDED không thể chuyển sang bất kỳ trạng thái nào kể cả bởi ADMIN")
        void refunded_cannotTransitionToAnyState(OrderStatus target) {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.REFUNDED, target, List.of("ADMIN", "SYSTEM"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.REFUNDED, target, "ADMIN"));
        }
    }

    // =========================================================================
    // 3. Illegal Transition Jumps & Backward Transitions (Nhảy cóc và quay lui)
    // =========================================================================
    @Nested
    @DisplayName("3. Kiểm thử các bước nhảy cóc và chuyển ngược vi phạm quy trình")
    class IllegalJumpsTest {

        @Test
        @DisplayName("PENDING_PAYMENT nhảy cóc sang PROCESSING, SHIPPED, DELIVERED, COMPLETED, REFUNDED -> Bị từ chối")
        void pendingPayment_illegalJumps_rejected() {
            List<OrderStatus> illegalTargets = List.of(
                    OrderStatus.PROCESSING,
                    OrderStatus.SHIPPED,
                    OrderStatus.DELIVERED,
                    OrderStatus.COMPLETED,
                    OrderStatus.REFUNDED
            );

            for (OrderStatus target : illegalTargets) {
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(OrderStatus.PENDING_PAYMENT, target, List.of("ADMIN"));
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, target, "ADMIN"));
            }
        }

        @Test
        @DisplayName("PAID nhảy cóc hoặc quay lui sai quy trình -> Bị từ chối")
        void paid_illegalJumps_rejected() {
            List<OrderStatus> illegalTargets = List.of(
                    OrderStatus.PENDING_PAYMENT,
                    OrderStatus.SHIPPED,
                    OrderStatus.DELIVERED,
                    OrderStatus.COMPLETED
            );

            for (OrderStatus target : illegalTargets) {
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(OrderStatus.PAID, target, List.of("ADMIN"));
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(OrderStatus.PAID, target, "ADMIN"));
            }
        }

        @Test
        @DisplayName("PROCESSING nhảy cóc hoặc quay lui sai quy trình -> Bị từ chối")
        void processing_illegalJumps_rejected() {
            List<OrderStatus> illegalTargets = List.of(
                    OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAID,
                    OrderStatus.DELIVERED,
                    OrderStatus.COMPLETED,
                    OrderStatus.CANCELLED,
                    OrderStatus.REFUNDED
            );

            for (OrderStatus target : illegalTargets) {
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(OrderStatus.PROCESSING, target, List.of("ADMIN"));
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(OrderStatus.PROCESSING, target, "ADMIN"));
            }
        }

        @Test
        @DisplayName("SHIPPED nhảy cóc hoặc quay lui sai quy trình -> Bị từ chối")
        void shipped_illegalJumps_rejected() {
            List<OrderStatus> illegalTargets = List.of(
                    OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAID,
                    OrderStatus.PROCESSING,
                    OrderStatus.COMPLETED,
                    OrderStatus.REFUNDED
            );

            for (OrderStatus target : illegalTargets) {
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(OrderStatus.SHIPPED, target, List.of("ADMIN"));
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(OrderStatus.SHIPPED, target, "ADMIN"));
            }
        }

        @Test
        @DisplayName("DELIVERED quay lui hoặc hủy không hợp lệ -> Bị từ chối")
        void delivered_illegalJumps_rejected() {
            List<OrderStatus> illegalTargets = List.of(
                    OrderStatus.PENDING_PAYMENT,
                    OrderStatus.PAID,
                    OrderStatus.PROCESSING,
                    OrderStatus.SHIPPED,
                    OrderStatus.CANCELLED
            );

            for (OrderStatus target : illegalTargets) {
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(OrderStatus.DELIVERED, target, List.of("ADMIN"));
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(OrderStatus.DELIVERED, target, "ADMIN"));
            }
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("Chuyển đổi sang cùng một trạng thái (current == target) -> Bị từ chối")
        void sameStateTransition_rejected(OrderStatus status) {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(status, status, List.of("ADMIN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(status, status, "ADMIN"));
        }
    }

    // =========================================================================
    // 4. Role Authorization Failures (Vi phạm phân quyền vai trò)
    // =========================================================================
    @Nested
    @DisplayName("4. Kiểm thử vi phạm phân quyền vai trò (Role Permission Violation)")
    class RoleAuthorizationFailuresTest {

        @Test
        @DisplayName("BUYER không được phép duyệt thanh toán (PENDING_PAYMENT -> PAID)")
        void buyer_cannotTransitionToPaid() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("BUYER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertTrue(ex.getMessage().contains("không có quyền"));
            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "BUYER"));
        }

        @Test
        @DisplayName("SELLER không được phép hủy đơn chưa thanh toán (PENDING_PAYMENT -> CANCELLED)")
        void seller_cannotCancelUnpaidOrder() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of("SELLER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "SELLER"));
        }

        @Test
        @DisplayName("BUYER không được phép chuyển trạng thái sang PROCESSING")
        void buyer_cannotTransitionToProcessing() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PAID, OrderStatus.PROCESSING, List.of("BUYER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.PAID, OrderStatus.PROCESSING, "BUYER"));
        }

        @Test
        @DisplayName("BUYER không được phép chuyển trạng thái sang SHIPPED")
        void buyer_cannotTransitionToShipped() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PROCESSING, OrderStatus.SHIPPED, List.of("BUYER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.PROCESSING, OrderStatus.SHIPPED, "BUYER"));
        }

        @Test
        @DisplayName("SELLER không được phép tự đóng đơn hoàn tất (DELIVERED -> COMPLETED)")
        void seller_cannotTransitionToCompleted() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.DELIVERED, OrderStatus.COMPLETED, List.of("SELLER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.DELIVERED, OrderStatus.COMPLETED, "SELLER"));
        }

        @Test
        @DisplayName("SELLER không được phép tự duyệt khiếu nại hoàn tiền (DELIVERED -> REFUNDED)")
        void seller_cannotTransitionDeliveredToRefunded() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("SELLER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.DELIVERED, OrderStatus.REFUNDED, "SELLER"));
        }

        @Test
        @DisplayName("BUYER không được phép tự duyệt khiếu nại hoàn tiền (DELIVERED -> REFUNDED)")
        void buyer_cannotTransitionDeliveredToRefunded() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("BUYER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.DELIVERED, OrderStatus.REFUNDED, "BUYER"));
        }

        @Test
        @DisplayName("Vai trò không hợp lệ hoặc không xác định bị từ chối")
        void unknownRole_rejected() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PAID, OrderStatus.PROCESSING, List.of("GUEST", "UNKNOWN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.PAID, OrderStatus.PROCESSING, "GUEST"));
        }

        @Test
        @DisplayName("Người dùng có nhiều vai trò, trong đó có ít nhất một vai trò hợp lệ -> Cho phép chuyển đổi")
        void multipleRoles_oneValid_success() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PAID, OrderStatus.PROCESSING, List.of("ROLE_BUYER", "ROLE_ADMIN"));
            });
            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PAID, OrderStatus.PROCESSING, List.of("BUYER", "ADMIN")));
        }
    }

    // =========================================================================
    // 5. Input Validation & Edge Cases (Dữ liệu đầu vào bất thường)
    // =========================================================================
    @Nested
    @DisplayName("5. Kiểm thử dữ liệu đầu vào bất thường và trường hợp biên")
    class InputValidationEdgeCasesTest {

        @Test
        @DisplayName("current là null -> Ném BusinessException")
        void nullCurrentStatus_throwsException() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(null, OrderStatus.PAID, List.of("ADMIN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(null, OrderStatus.PAID, "ADMIN"));
        }

        @Test
        @DisplayName("target là null -> Ném BusinessException")
        void nullTargetStatus_throwsException() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PAID, null, List.of("ADMIN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(OrderStatus.PAID, null, "ADMIN"));
        }

        @Test
        @DisplayName("roles là null hoặc rỗng -> Ném BusinessException")
        void nullOrEmptyRoles_throwsException() {
            BusinessException ex1 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, (List<String>) null);
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex1.getCode());

            BusinessException ex2 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, Collections.emptyList());
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex2.getCode());

            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, (String) null));
            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "   "));
        }

        @ParameterizedTest
        @ValueSource(strings = {"admin", "ADMIN", "ROLE_ADMIN", "  ROLE_admin  ", "Role_Admin"})
        @DisplayName("normalizeRole xử lý đúng chữ thường, hoa, khoảng trắng và tiền tố ROLE_")
        void normalizeRole_handlesFormattingVariants(String rawRole) {
            Set<String> normalized = stateMachine.normalizeRoles(List.of(rawRole));
            assertTrue(normalized.contains("ADMIN"));
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of(rawRole));
            });
        }

        @Test
        @DisplayName("normalizeRoles với danh sách null hoặc rỗng trả về Set rỗng an toàn")
        void normalizeRoles_nullOrEmpty_returnsEmptySet() {
            assertTrue(stateMachine.normalizeRoles(null).isEmpty());
            assertTrue(stateMachine.normalizeRoles(Collections.emptyList()).isEmpty());
        }
    }

    // =========================================================================
    // 6. Helpers: Available Transitions & Allowed Next States
    // =========================================================================
    @Nested
    @DisplayName("6. Kiểm thử các hàm hỗ trợ tra cứu trạng thái tiếp theo")
    class HelpersTest {

        @Test
        @DisplayName("getAvailableTransitions cho BUYER ở PENDING_PAYMENT trả về CANCELLED")
        void getAvailableTransitions_buyer_pendingPayment() {
            Set<OrderStatus> available = stateMachine.getAvailableTransitions(
                    OrderStatus.PENDING_PAYMENT, List.of("BUYER"));
            assertEquals(Set.of(OrderStatus.CANCELLED), available);
        }

        @Test
        @DisplayName("getAvailableTransitions cho SYSTEM ở PENDING_PAYMENT trả về PAID và CANCELLED")
        void getAvailableTransitions_system_pendingPayment() {
            Set<OrderStatus> available = stateMachine.getAvailableTransitions(
                    OrderStatus.PENDING_PAYMENT, List.of("SYSTEM"));
            assertEquals(Set.of(OrderStatus.PAID, OrderStatus.CANCELLED), available);
        }

        @Test
        @DisplayName("getAvailableTransitions cho SELLER ở PAID trả về PROCESSING và REFUNDED")
        void getAvailableTransitions_seller_paid() {
            Set<OrderStatus> available = stateMachine.getAvailableTransitions(
                    OrderStatus.PAID, List.of("SELLER"));
            assertEquals(Set.of(OrderStatus.PROCESSING, OrderStatus.REFUNDED), available);
        }

        @Test
        @DisplayName("getAvailableTransitions cho trạng thái kết thúc trả về Set rỗng")
        void getAvailableTransitions_terminal_returnsEmpty() {
            assertTrue(stateMachine.getAvailableTransitions(OrderStatus.COMPLETED, List.of("ADMIN")).isEmpty());
            assertTrue(stateMachine.getAvailableTransitions(OrderStatus.CANCELLED, List.of("ADMIN")).isEmpty());
            assertTrue(stateMachine.getAvailableTransitions(OrderStatus.REFUNDED, List.of("ADMIN")).isEmpty());
        }

        @Test
        @DisplayName("getAllowedNextStates trả về tất cả nhánh chuyển hợp lệ trên đồ thị")
        void getAllowedNextStates_verifiesGraphBranches() {
            assertEquals(Set.of(OrderStatus.PAID, OrderStatus.CANCELLED),
                    stateMachine.getAllowedNextStates(OrderStatus.PENDING_PAYMENT));
            assertEquals(Set.of(OrderStatus.PROCESSING, OrderStatus.REFUNDED),
                    stateMachine.getAllowedNextStates(OrderStatus.PAID));
            assertEquals(Set.of(OrderStatus.SHIPPED),
                    stateMachine.getAllowedNextStates(OrderStatus.PROCESSING));
            assertEquals(Set.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
                    stateMachine.getAllowedNextStates(OrderStatus.SHIPPED));
            assertEquals(Set.of(OrderStatus.COMPLETED, OrderStatus.REFUNDED),
                    stateMachine.getAllowedNextStates(OrderStatus.DELIVERED));
            assertTrue(stateMachine.getAllowedNextStates(OrderStatus.COMPLETED).isEmpty());
            assertTrue(stateMachine.getAllowedNextStates(OrderStatus.CANCELLED).isEmpty());
            assertTrue(stateMachine.getAllowedNextStates(OrderStatus.REFUNDED).isEmpty());
        }

        @Test
        @DisplayName("getTerminalStates trả về đúng 3 trạng thái")
        void getTerminalStates_containsExpectedThreeStates() {
            Set<OrderStatus> terminals = stateMachine.getTerminalStates();
            assertNotNull(terminals);
            assertEquals(3, terminals.size());
            assertTrue(terminals.contains(OrderStatus.COMPLETED));
            assertTrue(terminals.contains(OrderStatus.CANCELLED));
            assertTrue(terminals.contains(OrderStatus.REFUNDED));
        }
    }

    // =========================================================================
    // 7. Exhaustive Combinatorial Matrix Verification (8 x 8 = 64 cặp trạng thái)
    // =========================================================================
    @Nested
    @DisplayName("7. Kiểm thử vét cạn ma trận toàn bộ 64 cặp trạng thái")
    class ExhaustiveMatrixTest {

        @Test
        @DisplayName("Chỉ chính xác 9 cặp trạng thái là hợp lệ trên đồ thị, 55 cặp còn lại tuyệt đối không thể chuyển đổi")
        void matrixExhaustive_exactlyNineValidTransitionsExist() {
            int validCount = 0;
            int invalidCount = 0;

            OrderStatus[] allStatuses = OrderStatus.values();
            assertEquals(8, allStatuses.length, "Phải có chính xác 8 trạng thái theo schema");

            List<String> superRoles = List.of(
                    "ADMIN", "SYSTEM", "BUYER", "SELLER", "PAYMENT", "CARRIER", "LOGISTICS");

            for (OrderStatus from : allStatuses) {
                for (OrderStatus to : allStatuses) {
                    boolean isValid = stateMachine.isValidTransition(from, to, superRoles);
                    if (isValid) {
                        validCount++;
                    } else {
                        invalidCount++;
                    }
                }
            }

            assertEquals(9, validCount, "Tổng số cặp chuyển đổi hợp lệ trong hệ thống phải chính xác là 9");
            assertEquals(55, invalidCount, "Tổng số cặp chuyển đổi bị cấm trong hệ thống phải chính xác là 55");
        }
    }
}
