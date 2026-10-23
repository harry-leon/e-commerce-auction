package com.nexus.commerce.controller;

import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.request.OrderStatusChangeRequest;
import com.nexus.commerce.dto.response.ApiResponse;
import com.nexus.commerce.dto.response.OrderResponse;
import com.nexus.commerce.dto.response.PageResponse;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller cho các nghiệp vụ quản lý Đơn hàng (Order Management).
 *
 * <p>Base route: /api/v1/orders
 * Tuân thủ đặc tả giao tiếp chuẩn trong `API_SPEC_CART_ORDER.md` §4 và Envelope response của Project Nexus.
 */
@Tag(name = "Order Management", description = "Endpoints phục vụ tạo đơn từ Checkout, tra cứu chi tiết và quản lý lịch sử đơn hàng")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Phân giải ID người dùng từ JWT hoặc X-User-Id do API Gateway chuyển tiếp.
     */
    private UUID resolveUserId(Jwt jwt, String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return UUID.fromString(headerUserId);
        }
        if (jwt != null) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return UUID.fromString(subject);
            }
        }
        throw new IllegalStateException("Không thể xác thực danh tính người dùng hiện tại");
    }

    /**
     * Kiểm tra xem người dùng hiện tại có vai trò quản trị (Admin) hoặc người bán (Seller) không.
     */
    private boolean checkAdminOrSeller(Jwt jwt, String headerRoles) {
        if (headerRoles != null && (headerRoles.contains("ADMIN") || headerRoles.contains("SELLER"))) {
            return true;
        }
        if (jwt != null) {
            Object rolesClaim = jwt.getClaim("roles");
            return rolesClaim != null && rolesClaim.toString().matches(".*(ADMIN|SELLER).*");
        }
        return false;
    }

    @Operation(summary = "Tạo đơn hàng từ Checkout", description = "Tạo đơn hàng mới từ giỏ hàng hiện tại với Idempotency-Key chống trùng lặp")
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> createOrder(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        OrderResponse response = orderService.createOrder(userId, idempotencyKey, request);
        return ApiResponse.ok(response);
    }

    @Operation(summary = "Xem chi tiết đơn hàng", description = "Lấy toàn bộ thông tin đơn hàng, danh sách sản phẩm snapshot và tiến trình lịch sử trạng thái")
    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<OrderResponse> getOrderById(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Roles", required = false) String headerRoles
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        boolean isAdminOrSeller = checkAdminOrSeller(jwt, headerRoles);
        OrderResponse response = orderService.getOrderById(orderId, userId, isAdminOrSeller);
        return ApiResponse.ok(response);
    }

    @Operation(summary = "Lấy lịch sử đơn hàng của tôi", description = "Danh sách đơn hàng của người mua hiện tại có phân trang và lọc theo trạng thái")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<OrderResponse>> getMyOrders(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        PageResponse<OrderResponse> response = orderService.getMyOrders(userId, status, pageable);
        return ApiResponse.ok(response);
    }

    @Operation(summary = "Cập nhật trạng thái đơn hàng", description = "Chuyển đổi trạng thái đơn hàng theo quy tắc State Machine và ghi vết kiểm toán")
    @PatchMapping("/{orderId}/status")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<OrderResponse> changeOrderStatus(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Valid @RequestBody OrderStatusChangeRequest request
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        OrderResponse response = orderService.changeOrderStatus(orderId, request.toStatus(), userId, request.reason());
        return ApiResponse.ok(response);
    }
}
