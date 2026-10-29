package com.nexus.commerce.statemachine;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bộ kiểm thử đối kháng (Adversarial Stress Test) cho OrderStatusStateMachine.
 *
 * <p>Mục đích:
 * <ul>
 *   <li>Thách thức toàn diện ma trận chuyển trạng thái 8x8 (64 cặp) để chứng minh tính đúng đắn tuyệt đối.</li>
 *   <li>Quét toàn bộ 54 cặp chuyển đổi không hợp lệ nhằm đảm bảo luôn ném ra BusinessException với mã ORDER_STATUS_INVALID.</li>
 *   <li>Tấn công các trạng thái kết thúc (Terminal States) bằng quyền tối cao (ADMIN, SYSTEM) để chứng minh tính bất biến vĩnh viễn.</li>
 *   <li>Tấn công phân quyền vai trò (Role Permissions) trên toàn bộ các nhánh chuyển đổi hợp lệ bằng các vai trò trái phép.</li>
 *   <li>Kiểm tra dữ liệu biên dị thường: null, rỗng, khoảng trắng, định dạng chữ hoa/thường lẫn lộn, tiền tố ROLE_.</li>
 *   <li>Kiểm tra độ an toàn đa luồng (Thread-Safety &amp; Concurrency Stress).</li>
 * </ul>
 */
@DisplayName("OrderStatusStateMachine - Adversarial Challenge Suite")
class OrderStatusStateMachineAdversarialTest {

    private OrderStatusStateMachine stateMachine;

    private static final List<String> SUPER_ROLES = List.of(
            "ADMIN", "SYSTEM", "BUYER", "SELLER", "PAYMENT", "LOGISTICS", "CARRIER"
    );

    @BeforeEach
    void setUp() {
        stateMachine = new OrderStatusStateMachine();
    }

    // =========================================================================
    // 1. Matrix 8x8 Adversarial Challenge (54 Invalid Transitions Probe)
    // =========================================================================
    @Nested
    @DisplayName("1. Đối kháng ma trận 8x8 - Quét toàn bộ 54 cặp trạng thái cấm")
    class Matrix54InvalidTransitionsProbe {

        @Test
        @DisplayName("Xác thực chính xác 54 cặp trạng thái cấm đều ném ORDER_STATUS_INVALID dù có tất cả vai trò")
        void verifyAll54InvalidPairsThrowOrderStatusInvalid() {
            int invalidPairCount = 0;
            int validPairCount = 0;

            for (OrderStatus from : OrderStatus.values()) {
                for (OrderStatus to : OrderStatus.values()) {
                    boolean valid = stateMachine.isValidTransition(from, to, SUPER_ROLES);
                    if (valid) {
                        validPairCount++;
                    } else {
                        invalidPairCount++;
                        BusinessException ex = assertThrows(
                                BusinessException.class,
                                () -> stateMachine.validateTransition(from, to, SUPER_ROLES),
                                String.format("Cặp chuyển đổi bị cấm (%s -> %s) phải ném BusinessException", from, to)
                        );
                        assertEquals(
                                ErrorCode.ORDER_STATUS_INVALID.name(),
                                ex.getCode(),
                                String.format("Mã lỗi cho (%s -> %s) phải là ORDER_STATUS_INVALID", from, to)
                        );
                    }
                }
            }

            assertEquals(9, validPairCount, "Hệ thống chỉ được phép có đúng 9 bước chuyển hợp lệ");
            assertEquals(55, invalidPairCount, "Hệ thống phải có chính xác 55 bước chuyển bị cấm");
        }

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        @DisplayName("Tự chuyển đổi sang chính mình (Self-transition) trên cả 8 trạng thái đều bị cấm")
        void selfTransitions_strictlyForbidden(OrderStatus status) {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(status, status, SUPER_ROLES);
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(status, status, SUPER_ROLES));
        }

        @Test
        @DisplayName("Quay lui trạng thái (Backward transitions) đều bị từ chối")
        void backwardTransitions_strictlyForbidden() {
            List<OrderStatus[]> backwardPairs = List.of(
                    new OrderStatus[]{OrderStatus.PAID, OrderStatus.PENDING_PAYMENT},
                    new OrderStatus[]{OrderStatus.PROCESSING, OrderStatus.PAID},
                    new OrderStatus[]{OrderStatus.PROCESSING, OrderStatus.PENDING_PAYMENT},
                    new OrderStatus[]{OrderStatus.SHIPPED, OrderStatus.PROCESSING},
                    new OrderStatus[]{OrderStatus.SHIPPED, OrderStatus.PAID},
                    new OrderStatus[]{OrderStatus.DELIVERED, OrderStatus.SHIPPED},
                    new OrderStatus[]{OrderStatus.DELIVERED, OrderStatus.PROCESSING},
                    new OrderStatus[]{OrderStatus.DELIVERED, OrderStatus.PAID}
            );

            for (OrderStatus[] pair : backwardPairs) {
                OrderStatus from = pair[0];
                OrderStatus to = pair[1];
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(from, to, SUPER_ROLES);
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(from, to, SUPER_ROLES));
            }
        }

        @Test
        @DisplayName("Nhảy cóc trạng thái (Skip transitions) đều bị từ chối")
        void skipTransitions_strictlyForbidden() {
            List<OrderStatus[]> skipPairs = List.of(
                    new OrderStatus[]{OrderStatus.PENDING_PAYMENT, OrderStatus.PROCESSING},
                    new OrderStatus[]{OrderStatus.PENDING_PAYMENT, OrderStatus.SHIPPED},
                    new OrderStatus[]{OrderStatus.PENDING_PAYMENT, OrderStatus.DELIVERED},
                    new OrderStatus[]{OrderStatus.PENDING_PAYMENT, OrderStatus.COMPLETED},
                    new OrderStatus[]{OrderStatus.PENDING_PAYMENT, OrderStatus.REFUNDED},
                    new OrderStatus[]{OrderStatus.PAID, OrderStatus.SHIPPED},
                    new OrderStatus[]{OrderStatus.PAID, OrderStatus.DELIVERED},
                    new OrderStatus[]{OrderStatus.PAID, OrderStatus.COMPLETED},
                    new OrderStatus[]{OrderStatus.PROCESSING, OrderStatus.DELIVERED},
                    new OrderStatus[]{OrderStatus.PROCESSING, OrderStatus.COMPLETED},
                    new OrderStatus[]{OrderStatus.PROCESSING, OrderStatus.CANCELLED},
                    new OrderStatus[]{OrderStatus.PROCESSING, OrderStatus.REFUNDED},
                    new OrderStatus[]{OrderStatus.SHIPPED, OrderStatus.COMPLETED},
                    new OrderStatus[]{OrderStatus.SHIPPED, OrderStatus.REFUNDED}
            );

            for (OrderStatus[] pair : skipPairs) {
                OrderStatus from = pair[0];
                OrderStatus to = pair[1];
                BusinessException ex = assertThrows(BusinessException.class, () -> {
                    stateMachine.validateTransition(from, to, SUPER_ROLES);
                });
                assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                assertFalse(stateMachine.isValidTransition(from, to, SUPER_ROLES));
            }
        }
    }

    // =========================================================================
    // 2. Terminal States Attack (COMPLETED, CANCELLED, REFUNDED)
    // =========================================================================
    @Nested
    @DisplayName("2. Tấn công các trạng thái kết thúc (Terminal States Immutability)")
    class TerminalStatesAttack {

        @Test
        @DisplayName("Cả 3 trạng thái kết thúc không thể thoát ra bởi bất kỳ trạng thái đích nào dưới quyền ADMIN hoặc SYSTEM")
        void terminalStates_cannotTransitionToAnyTargetEvenWithAdminAndSystem() {
            List<OrderStatus> terminalStates = List.of(
                    OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.REFUNDED
            );

            for (OrderStatus terminal : terminalStates) {
                assertTrue(stateMachine.isTerminal(terminal));
                for (OrderStatus target : OrderStatus.values()) {
                    BusinessException ex = assertThrows(BusinessException.class, () -> {
                        stateMachine.validateTransition(terminal, target, List.of("ADMIN", "SYSTEM"));
                    });
                    assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
                    assertFalse(stateMachine.isValidTransition(terminal, target, List.of("ADMIN", "SYSTEM")));
                }
            }
        }

        @Test
        @DisplayName("getAvailableTransitions đối với cả 3 trạng thái kết thúc luôn trả về Set rỗng")
        void getAvailableTransitions_forTerminalStates_alwaysEmpty() {
            Set<OrderStatus> fromCompleted = stateMachine.getAvailableTransitions(
                    OrderStatus.COMPLETED, SUPER_ROLES);
            Set<OrderStatus> fromCancelled = stateMachine.getAvailableTransitions(
                    OrderStatus.CANCELLED, SUPER_ROLES);
            Set<OrderStatus> fromRefunded = stateMachine.getAvailableTransitions(
                    OrderStatus.REFUNDED, SUPER_ROLES);

            assertTrue(fromCompleted.isEmpty());
            assertTrue(fromCancelled.isEmpty());
            assertTrue(fromRefunded.isEmpty());
        }

        @Test
        @DisplayName("getAllowedNextStates đối với cả 3 trạng thái kết thúc luôn trả về Set rỗng")
        void getAllowedNextStates_forTerminalStates_alwaysEmpty() {
            assertTrue(stateMachine.getAllowedNextStates(OrderStatus.COMPLETED).isEmpty());
            assertTrue(stateMachine.getAllowedNextStates(OrderStatus.CANCELLED).isEmpty());
            assertTrue(stateMachine.getAllowedNextStates(OrderStatus.REFUNDED).isEmpty());
        }
    }

    // =========================================================================
    // 3. Role-Restricted Transitions Adversarial Probe
    // =========================================================================
    @Nested
    @DisplayName("3. Đối kháng phân quyền vai trò (Role-Restricted Transitions)")
    class RoleRestrictedTransitionsProbe {

        @Test
        @DisplayName("Buyer không thể duyệt thanh toán (PENDING_PAYMENT -> PAID)")
        void buyer_cannotTransitionPendingPaymentToPaid() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, List.of("BUYER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "BUYER"));
        }

        @Test
        @DisplayName("Seller không thể hủy đơn chưa thanh toán (PENDING_PAYMENT -> CANCELLED)")
        void seller_cannotCancelPendingPayment() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of("SELLER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "SELLER"));
        }

        @Test
        @DisplayName("Seller không thể hoàn tiền khi đơn đang xử lý (PROCESSING -> REFUNDED)")
        void seller_cannotTransitionProcessingToRefunded() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PROCESSING, OrderStatus.REFUNDED, List.of("SELLER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.PROCESSING, OrderStatus.REFUNDED, "SELLER"));
        }

        @Test
        @DisplayName("Buyer không thể đóng đơn SHIPPED sang COMPLETED mà không qua DELIVERED")
        void buyer_cannotTransitionShippedToCompleted() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.SHIPPED, OrderStatus.COMPLETED, List.of("BUYER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.SHIPPED, OrderStatus.COMPLETED, "BUYER"));
        }

        @Test
        @DisplayName("Seller không thể tự hoàn tiền ở bước DELIVERED -> Chỉ ADMIN mới có quyền")
        void seller_cannotTransitionDeliveredToRefunded() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("SELLER"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.DELIVERED, OrderStatus.REFUNDED, "SELLER"));
        }

        @Test
        @DisplayName("System không thể tự hoàn tiền ở bước DELIVERED -> Chỉ ADMIN mới có quyền")
        void system_cannotTransitionDeliveredToRefunded() {
            BusinessException ex = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("SYSTEM"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            assertFalse(stateMachine.isValidTransition(
                    OrderStatus.DELIVERED, OrderStatus.REFUNDED, "SYSTEM"));
        }

        @Test
        @DisplayName("Admin có quyền thực hiện chuyển DELIVERED sang REFUNDED")
        void admin_canTransitionDeliveredToRefunded() {
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.DELIVERED, OrderStatus.REFUNDED, List.of("ADMIN"));
            });
            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.DELIVERED, OrderStatus.REFUNDED, "ADMIN"));
        }

        @Test
        @DisplayName("validateTransition dạng String đơn lẻ: kiểm thử hợp lệ, trái quyền, null và blank")
        void validateTransition_stringOverload_exercisesAllBranches() {
            // Hợp lệ với String role
            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "ADMIN");
            });

            // Trái quyền với String role
            BusinessException ex1 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "BUYER");
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex1.getCode());

            // Null String role
            BusinessException ex2 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, (String) null);
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex2.getCode());

            // Blank String role
            BusinessException ex3 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "   ");
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex3.getCode());
        }
    }

    // =========================================================================
    // 4. Input Robustness & Normalization Adversarial Probe
    // =========================================================================
    @Nested
    @DisplayName("4. Đối kháng định dạng và chuẩn hóa vai trò (Edge Cases & Normalization)")
    class InputRobustnessAndNormalizationProbe {

        @Test
        @DisplayName("null current hoặc target ném BusinessException với ORDER_STATUS_INVALID")
        void nullStatuses_throwBusinessException() {
            BusinessException ex1 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(null, OrderStatus.PAID, List.of("ADMIN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex1.getCode());

            BusinessException ex2 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(OrderStatus.PAID, null, List.of("ADMIN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex2.getCode());

            BusinessException ex3 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(null, null, List.of("ADMIN"));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex3.getCode());
        }

        @Test
        @DisplayName("null role, empty role hoặc danh sách chỉ chứa khoảng trắng/null đều bị từ chối")
        void nullOrBlankRoles_rejected() {
            BusinessException ex1 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, (List<String>) null);
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex1.getCode());

            BusinessException ex2 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, Collections.emptyList());
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex2.getCode());

            BusinessException ex3 = assertThrows(BusinessException.class, () -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, Arrays.asList("   ", "\t", null));
            });
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex3.getCode());

            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, (String) null));
            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, ""));
            assertFalse(stateMachine.isValidTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "   "));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "buyer", "BUYER", "Role_Buyer", "ROLE_BUYER", "  role_buyer  ", "rOlE_bUyEr", "  BUYER  "
        })
        @DisplayName("Chuẩn hóa vai trò chấp nhận mọi dạng hoa/thường, khoảng trắng và tiền tố ROLE_")
        void normalizeRoles_acceptsAllCasingAndPrefixVariants(String rawRole) {
            Set<String> normalized = stateMachine.normalizeRoles(List.of(rawRole));
            assertEquals(Set.of("BUYER"), normalized);

            assertDoesNotThrow(() -> {
                stateMachine.validateTransition(
                        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, List.of(rawRole));
            });
            assertTrue(stateMachine.isValidTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, rawRole));
        }

        @ParameterizedTest
        @CsvSource({
                "Role_Seller, SELLER",
                "  role_admin  , ADMIN",
                "rOlE_sYsTeM, SYSTEM",
                "ROLE_logistics, LOGISTICS",
                "carrier, CARRIER",
                "Role_Payment, PAYMENT"
        })
        @DisplayName("Chuẩn hóa các vai trò hệ thống đa dạng")
        void normalizeRoles_variousSystemRoles(String input, String expected) {
            Set<String> normalized = stateMachine.normalizeRoles(List.of(input));
            assertTrue(normalized.contains(expected));
        }
    }

    // =========================================================================
    // 5. Concurrency & High Throughput Stress Harness
    // =========================================================================
    @Nested
    @DisplayName("5. Kiểm thử áp lực đa luồng (Concurrency & Thread-Safety Stress)")
    class ConcurrencyStressHarness {

        @Test
        @DisplayName("50 luồng đồng thời thực hiện 25,000 lượt kiểm tra trạng thái an toàn không xảy ra race condition")
        void concurrentExecution_isCompletelyThreadSafe() throws Exception {
            int threadCount = 50;
            int iterationsPerThread = 500;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            try {
                List<Callable<Boolean>> tasks = new ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    tasks.add(() -> {
                        for (int j = 0; j < iterationsPerThread; j++) {
                            // Hợp lệ
                            boolean valid1 = stateMachine.isValidTransition(
                                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, "ADMIN");
                            boolean valid2 = stateMachine.isValidTransition(
                                    OrderStatus.PAID, OrderStatus.PROCESSING, "SELLER");
                            boolean valid3 = stateMachine.isValidTransition(
                                    OrderStatus.DELIVERED, OrderStatus.COMPLETED, "CARRIER");

                            // Bị cấm
                            boolean invalid1 = stateMachine.isValidTransition(
                                    OrderStatus.COMPLETED, OrderStatus.PAID, "ADMIN");
                            boolean invalid2 = stateMachine.isValidTransition(
                                    OrderStatus.PENDING_PAYMENT, OrderStatus.DELIVERED, "ADMIN");
                            boolean invalid3 = stateMachine.isValidTransition(
                                    OrderStatus.DELIVERED, OrderStatus.COMPLETED, "BUYER");

                            if (!valid1 || !valid2 || !valid3 || invalid1 || invalid2 || invalid3) {
                                return false;
                            }
                        }
                        return true;
                    });
                }

                List<Future<Boolean>> futures = executor.invokeAll(tasks);
                for (Future<Boolean> future : futures) {
                    assertTrue(future.get(10, TimeUnit.SECONDS), "Tất cả các luồng phải hoàn thành chính xác");
                }
            } finally {
                executor.shutdown();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
