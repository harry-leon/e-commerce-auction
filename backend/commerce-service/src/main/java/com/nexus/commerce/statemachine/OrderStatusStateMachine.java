package com.nexus.commerce.statemachine;

import com.nexus.commerce.enums.OrderStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Máy trạng thái đơn hàng (Order State Machine) quản lý và kiểm soát toàn bộ vòng đời đơn hàng.
 *
 * <p>Chức năng:
 * <ul>
 *   <li>Thực thi quy trình chuyển đổi trạng thái đơn hàng theo đúng ma trận nghiệp vụ (SRS §3.4.3, API_SPEC §6).</li>
 *   <li>Kiểm tra phân quyền vai trò (RBAC/IAM) cho từng bước chuyển đổi cụ thể (BUYER, SELLER, ADMIN, SYSTEM...).</li>
 *   <li>Đảm bảo tính bất biến của các trạng thái kết thúc (Terminal States: COMPLETED, CANCELLED, REFUNDED).</li>
 *   <li>Ném ra BusinessException với mã lỗi ORDER_STATUS_INVALID khi phát hiện vi phạm.</li>
 * </ul>
 *
 * <p>Đầu vào &amp; Kết quả:
 * <ul>
 *   <li>Đầu vào: Trạng thái hiện tại (current), trạng thái đích (target), danh sách vai trò người thực hiện (roles).</li>
 *   <li>Kết quả: Hợp lệ thì kết thúc bình thường (void / boolean); nếu sai phạm thì ném BusinessException kèm thông điệp tiếng Việt.</li>
 * </ul>
 *
 * <p>Quy tắc &amp; Rationale:
 * <ul>
 *   <li>Tuân thủ ràng buộc cơ sở dữ liệu chk_order_status trong Flyway V2__init_schema.sql.</li>
 *   <li>Chuẩn hóa tiền tố ROLE_ để tương thích liền mạch với Spring Security Authorities và Keycloak Claims.</li>
 * </ul>
 */
@Component
public class OrderStatusStateMachine {

    /**
     * Tập hợp các vai trò chuẩn trong hệ thống.
     */
    public static final String ROLE_BUYER = "BUYER";
    public static final String ROLE_SELLER = "SELLER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SYSTEM = "SYSTEM";
    public static final String ROLE_PAYMENT = "PAYMENT";
    public static final String ROLE_LOGISTICS = "LOGISTICS";
    public static final String ROLE_CARRIER = "CARRIER";

    /**
     * Tập hợp các trạng thái kết thúc không thể chuyển tiếp (Terminal States).
     */
    private static final Set<OrderStatus> TERMINAL_STATES = Collections.unmodifiableSet(
            EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED, OrderStatus.REFUNDED)
    );

    /**
     * Ma trận chuyển đổi trạng thái: CurrentStatus -> Map(TargetStatus -> Set of Authorized Roles).
     */
    private static final Map<OrderStatus, Map<OrderStatus, Set<String>>> TRANSITIONS;

    static {
        Map<OrderStatus, Map<OrderStatus, Set<String>>> matrix = new EnumMap<>(OrderStatus.class);

        // 1. PENDING_PAYMENT
        Map<OrderStatus, Set<String>> fromPendingPayment = new EnumMap<>(OrderStatus.class);
        fromPendingPayment.put(OrderStatus.PAID, Set.of(ROLE_SYSTEM, ROLE_PAYMENT, ROLE_ADMIN));
        fromPendingPayment.put(OrderStatus.CANCELLED, Set.of(ROLE_BUYER, ROLE_ADMIN, ROLE_SYSTEM));
        matrix.put(OrderStatus.PENDING_PAYMENT, Collections.unmodifiableMap(fromPendingPayment));

        // 2. PAID
        Map<OrderStatus, Set<String>> fromPaid = new EnumMap<>(OrderStatus.class);
        fromPaid.put(OrderStatus.PROCESSING, Set.of(ROLE_SELLER, ROLE_ADMIN));
        fromPaid.put(OrderStatus.REFUNDED, Set.of(ROLE_ADMIN, ROLE_SYSTEM, ROLE_SELLER, ROLE_BUYER));
        matrix.put(OrderStatus.PAID, Collections.unmodifiableMap(fromPaid));

        // 3. PROCESSING
        Map<OrderStatus, Set<String>> fromProcessing = new EnumMap<>(OrderStatus.class);
        fromProcessing.put(OrderStatus.SHIPPED, Set.of(ROLE_SELLER, ROLE_ADMIN, ROLE_SYSTEM));
        matrix.put(OrderStatus.PROCESSING, Collections.unmodifiableMap(fromProcessing));

        // 4. SHIPPED
        Map<OrderStatus, Set<String>> fromShipped = new EnumMap<>(OrderStatus.class);
        fromShipped.put(OrderStatus.DELIVERED, Set.of(ROLE_SELLER, ROLE_SYSTEM, ROLE_LOGISTICS, ROLE_CARRIER, ROLE_ADMIN));
        fromShipped.put(OrderStatus.CANCELLED, Set.of(ROLE_SYSTEM, ROLE_CARRIER, ROLE_ADMIN));
        matrix.put(OrderStatus.SHIPPED, Collections.unmodifiableMap(fromShipped));

        // 5. DELIVERED: Chỉ Shipper/Carrier, Logistics, Admin hoặc Hệ thống xác nhận hoàn tất giao hàng (COMPLETED)
        Map<OrderStatus, Set<String>> fromDelivered = new EnumMap<>(OrderStatus.class);
        fromDelivered.put(OrderStatus.COMPLETED, Set.of(ROLE_CARRIER, ROLE_LOGISTICS, ROLE_ADMIN, ROLE_SYSTEM));
        fromDelivered.put(OrderStatus.REFUNDED, Set.of(ROLE_ADMIN));
        matrix.put(OrderStatus.DELIVERED, Collections.unmodifiableMap(fromDelivered));

        // 6, 7, 8. TERMINAL STATES: Không có bất kỳ nhánh chuyển trạng thái nào
        matrix.put(OrderStatus.COMPLETED, Collections.emptyMap());
        matrix.put(OrderStatus.CANCELLED, Collections.emptyMap());
        matrix.put(OrderStatus.REFUNDED, Collections.emptyMap());

        TRANSITIONS = Collections.unmodifiableMap(matrix);
    }

    /**
     * Kiểm tra trạng thái có phải trạng thái kết thúc (Terminal State) hay không.
     *
     * @param status Trạng thái cần kiểm tra
     * @return true nếu là COMPLETED, CANCELLED hoặc REFUNDED
     */
    public boolean isTerminal(OrderStatus status) {
        if (status == null) {
            return false;
        }
        return TERMINAL_STATES.contains(status);
    }

    /**
     * Lấy tập hợp các trạng thái kết thúc.
     *
     * @return Set các OrderStatus kết thúc
     */
    public Set<OrderStatus> getTerminalStates() {
        return TERMINAL_STATES;
    }

    /**
     * Xác thực tính hợp lệ của việc chuyển đổi trạng thái đơn hàng theo vai trò người dùng.
     *
     * <p>Luồng xử lý:
     * <ol>
     *   <li>Kiểm tra null đối với trạng thái hiện tại, trạng thái đích và danh sách vai trò.</li>
     *   <li>Kiểm tra nếu trạng thái hiện tại là trạng thái kết thúc -> ném lỗi bất biến.</li>
     *   <li>Kiểm tra nếu current == target -> ném lỗi vi phạm chuyển trạng thái.</li>
     *   <li>Tra cứu ma trận TRANSITIONS xem cặp (current -> target) có tồn tại hay không.</li>
     *   <li>Chuẩn hóa danh sách roles đầu vào và so khớp với tập hợp vai trò được cấp phép.</li>
     * </ol>
     *
     * @param current Trạng thái hiện tại của đơn hàng
     * @param target Trạng thái đích muốn chuyển sang
     * @param roles Tập hợp các vai trò của người thực hiện
     * @throws BusinessException mã ORDER_STATUS_INVALID khi vi phạm quy tắc
     */
    public void validateTransition(OrderStatus current, OrderStatus target, Collection<String> roles) {
        if (current == null || target == null) {
            throw new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    "Trạng thái hiện tại và trạng thái đích của đơn hàng không được để trống"
            );
        }

        if (isTerminal(current)) {
            throw new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    String.format("Đơn hàng đã ở trạng thái kết thúc (%s), không thể thay đổi trạng thái", current)
            );
        }

        if (current == target) {
            throw new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    String.format("Không thể chuyển đổi đơn hàng sang cùng trạng thái hiện tại (%s)", current)
            );
        }

        Map<OrderStatus, Set<String>> allowedTargets = TRANSITIONS.get(current);
        if (allowedTargets == null || !allowedTargets.containsKey(target)) {
            throw new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    String.format("Không thể chuyển trạng thái đơn hàng từ %s sang %s", current, target)
            );
        }

        if (roles == null || roles.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    "Danh sách vai trò của người thực hiện không được để trống"
            );
        }

        Set<String> normalizedRoles = normalizeRoles(roles);
        Set<String> authorizedRoles = allowedTargets.get(target);

        boolean hasPermission = normalizedRoles.stream().anyMatch(authorizedRoles::contains);
        if (!hasPermission) {
            throw new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    String.format(
                            "Vai trò người dùng %s không có quyền chuyển trạng thái đơn hàng từ %s sang %s",
                            normalizedRoles, current, target
                    )
            );
        }
    }

    /**
     * Quá tải tiện ích nhận một vai trò đơn lẻ dạng String.
     *
     * @param current Trạng thái hiện tại
     * @param target Trạng thái đích
     * @param role Vai trò của người thực hiện
     */
    public void validateTransition(OrderStatus current, OrderStatus target, String role) {
        if (role == null || role.isBlank()) {
            validateTransition(current, target, Collections.emptyList());
        } else {
            validateTransition(current, target, List.of(role));
        }
    }

    /**
     * Kiểm tra xem việc chuyển đổi trạng thái có hợp lệ hay không (không ném ngoại lệ).
     *
     * @param current Trạng thái hiện tại
     * @param target Trạng thái đích
     * @param roles Danh sách vai trò của người thực hiện
     * @return true nếu chuyển đổi được phép, ngược lại false
     */
    public boolean isValidTransition(OrderStatus current, OrderStatus target, Collection<String> roles) {
        try {
            validateTransition(current, target, roles);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * Quá tải tiện ích kiểm tra xem việc chuyển đổi trạng thái có hợp lệ với 1 vai trò hay không.
     *
     * @param current Trạng thái hiện tại
     * @param target Trạng thái đích
     * @param role Vai trò người thực hiện
     * @return true nếu hợp lệ, ngược lại false
     */
    public boolean isValidTransition(OrderStatus current, OrderStatus target, String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        return isValidTransition(current, target, List.of(role));
    }

    /**
     * Lấy danh sách các trạng thái tiếp theo hợp lệ dựa trên trạng thái hiện tại và vai trò người dùng.
     * Phục vụ hiển thị danh sách nút thao tác trên giao diện người dùng (ví dụ: nút Hủy đơn, Chuẩn bị hàng).
     *
     * @param current Trạng thái hiện tại
     * @param roles Danh sách vai trò người dùng
     * @return Set các OrderStatus hợp lệ mà người dùng có thể thao tác
     */
    public Set<OrderStatus> getAvailableTransitions(OrderStatus current, Collection<String> roles) {
        if (current == null || roles == null || roles.isEmpty() || isTerminal(current)) {
            return Collections.emptySet();
        }

        Map<OrderStatus, Set<String>> allowedTargets = TRANSITIONS.get(current);
        if (allowedTargets == null || allowedTargets.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> normalizedRoles = normalizeRoles(roles);
        Set<OrderStatus> result = EnumSet.noneOf(OrderStatus.class);

        for (Map.Entry<OrderStatus, Set<String>> entry : allowedTargets.entrySet()) {
            Set<String> authorizedRoles = entry.getValue();
            if (normalizedRoles.stream().anyMatch(authorizedRoles::contains)) {
                result.add(entry.getKey());
            }
        }

        return Collections.unmodifiableSet(result);
    }

    /**
     * Lấy tất cả các trạng thái đích hợp lệ từ trạng thái hiện tại (không phụ thuộc vai trò).
     *
     * @param current Trạng thái hiện tại
     * @return Set tất cả trạng thái đích theo đồ thị trạng thái
     */
    public Set<OrderStatus> getAllowedNextStates(OrderStatus current) {
        if (current == null || isTerminal(current)) {
            return Collections.emptySet();
        }
        Map<OrderStatus, Set<String>> targets = TRANSITIONS.get(current);
        if (targets == null || targets.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(targets.keySet());
    }

    /**
     * Chuẩn hóa danh sách vai trò đầu vào:
     * <ul>
     *   <li>Cắt bỏ khoảng trắng.</li>
     *   <li>Chuyển sang in hoa.</li>
     *   <li>Loại bỏ tiền tố "ROLE_" nếu có (ví dụ: "ROLE_BUYER" -> "BUYER").</li>
     * </ul>
     *
     * @param roles Danh sách vai trò ban đầu
     * @return Tập hợp các vai trò đã được chuẩn hóa
     */
    public Set<String> normalizeRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                String clean = role.trim().toUpperCase(Locale.ROOT);
                if (clean.startsWith("ROLE_")) {
                    clean = clean.substring(5);
                }
                normalized.add(clean);
            }
        }
        return normalized;
    }
}
