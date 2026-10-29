package com.nexus.commerce.controller;

import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.service.OrderService;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller quản lý toàn bộ vòng đời và thao tác nghiệp vụ đơn hàng (Issue HG-S1-4 / SRS §3.4.3).
 *
 * <p>Chức năng:
 * <ul>
 *   <li>Cung cấp API tra cứu lịch sử mua hàng của Người mua có phân trang, bộ lọc trạng thái và khoảng thời gian.</li>
 *   <li>Cung cấp cơ chế điều hướng linh hoạt cho tham số role=seller sang danh sách đơn bán.</li>
 *   <li>Cung cấp API xem chi tiết đơn hàng kèm kiểm soát quyền sở hữu đa đối tượng (Buyer, Seller, Admin).</li>
 *   <li>Cung cấp API quản lý danh sách đơn bán dành riêng cho Người bán được bảo vệ bằng vai trò ROLE_SELLER.</li>
 *   <li>Cung cấp API cập nhật trạng thái đơn hàng (Seller/Admin) tuân thủ nghiêm ngặt máy trạng thái OrderStatusStateMachine.</li>
 *   <li>Cung cấp API hủy đơn an toàn dành cho Người mua và Quản trị viên kèm ghi vết kiểm toán.</li>
 * </ul>
 *
 * <p>Đầu vào và kết quả:
 * <ul>
 *   <li>Đầu vào: Token JWT xác thực (Bearer), các tham số query phân trang/bộ lọc, path variable orderId và payload JSON.</li>
 *   <li>Kết quả: Đóng gói toàn bộ phản hồi vào Envelope chuẩn {@code ApiResponse<T>} theo Quy chuẩn Nexus API Response (v2).</li>
 * </ul>
 *
 * <p>Luồng xử lý:
 * <ol>
 *   <li>Trích xuất định danh người dùng UUID từ trường subject (sub) của JWT đã xác thực.</li>
 *   <li>Trích xuất danh sách vai trò người dùng từ Authentication Authorities và JWT claims.</li>
 *   <li>Thực thi kiểm tra phân quyền (RBAC) và xác thực tính hợp lệ của tham số đầu vào.</li>
 *   <li>Ủy quyền xử lý xuống tầng nghiệp vụ {@link OrderService}.</li>
 *   <li>Đóng gói kết quả vào ApiResponse trả về cho client.</li>
 * </ol>
 *
 * <p>Quy tắc và lý do:
 * <ul>
 *   <li>Tuân thủ nguyên tắc cách ly dữ liệu người dùng (Tenant Isolation): không cho phép truy cập chéo dữ liệu đơn của nhau.</li>
 *   <li>Mặc định sắp xếp đơn hàng mới nhất lên đầu danh sách theo trường createdAt DESC.</li>
 *   <li>Xử lý idempotent an toàn khi có yêu cầu chuyển đổi trạng thái trùng lặp từ mạng hoặc webhook.</li>
 * </ul>
 *
 * <p>Lỗi và giới hạn:
 * <ul>
 *   <li>Trả về HTTP 401 khi chưa xác thực hoặc token không hợp lệ.</li>
 *   <li>Trả về HTTP 403 khi truy cập tài nguyên hoặc thực hiện hành động ngoài phạm vi phân quyền của vai trò.</li>
 *   <li>Trả về HTTP 404 khi không tìm thấy đơn hàng tương ứng với orderId.</li>
 *   <li>Trả về HTTP 409 khi chuyển đổi trạng thái vi phạm quy tắc máy trạng thái hoặc cố hủy đơn không hợp lệ.</li>
 *   <li>Trả về HTTP 422 khi dữ liệu phân trang hoặc nội dung lý do vi phạm ràng buộc validation.</li>
 * </ul>
 */
@Tag(name = "Order Management", description = "Quản lý đơn hàng và vòng đời trạng thái đơn")
@Validated
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * Tra cứu danh sách đơn hàng có phân trang cho Người mua, Người bán (role=seller) hoặc Quản trị viên (role=admin).
     *
     * <p>Chức năng: Lấy danh sách tóm tắt đơn hàng có phân trang, hỗ trợ lọc theo trạng thái và mốc thời gian tạo.
     *
     * <p>Đầu vào và kết quả:
     * Nhận tham số phân trang page, size, sortBy, sortDirection, bộ lọc status, khoảng thời gian startDate, endDate
     * và tham số điều hướng role ('buyer', 'seller', 'admin').
     * Trả về ApiResponse chuẩn Envelope v2 bọc danh sách List&lt;OrderSummaryResponse&gt; trong trường data và thông tin phân trang tại meta.pagination.
     *
     * <p>Luồng xử lý:
     * Xác thực user -&gt; kiểm tra khoảng ngày hợp lệ -&gt;
     * Nếu role=admin: kiểm tra quyền ADMIN và gọi getAdminOrders -&gt;
     * Nếu role=seller: kiểm tra quyền SELLER/ADMIN và gọi getSellerOrders -&gt;
     * Ngược lại mặc định gọi getBuyerOrders cho người mua hiện tại.
     *
     * <p>Quy tắc và lý do:
     * Tuân thủ Envelope v2 (data là Array, meta.pagination chứa page/size/totalElements).
     * Chỉ người dùng có vai trò tương ứng mới được phép xem đơn theo vai trò đó.
     *
     * <p>Lỗi và giới hạn:
     * Trả về HTTP 400 nếu startDate sau endDate hoặc role không hợp lệ. Trả về HTTP 403 nếu quyền không đủ.
     */
    @Operation(
            summary = "Lấy danh sách đơn hàng có phân trang",
            description = "Truy vấn danh sách đơn hàng có phân trang cho người mua, người bán (role=seller) hoặc quản trị viên (role=admin)"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Lấy danh sách thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Tham số truy vấn không hợp lệ"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực danh tính"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền truy cập danh sách theo vai trò yêu cầu"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Tham số phân trang vi phạm ràng buộc")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<OrderSummaryResponse>> getOrders(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @Parameter(description = "Lọc theo trạng thái đơn hàng")
            @RequestParam(value = "status", required = false) OrderStatus status,
            @Parameter(description = "Mốc thời gian bắt đầu (ISO-8601 UTC)")
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "Mốc thời gian kết thúc (ISO-8601 UTC)")
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @Parameter(description = "Tham số tùy chọn điều hướng vai trò: 'buyer', 'seller', hoặc 'admin'")
            @RequestParam(value = "role", required = false) String role,
            @Parameter(description = "Chỉ số trang (0-based)")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "Số trang không được nhỏ hơn 0") int page,
            @Parameter(description = "Số lượng bản ghi trên một trang (1 - 100)")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "Kích thước trang phải từ 1 đến 100")
            @Max(value = 100, message = "Kích thước trang phải từ 1 đến 100") int size,
            @Parameter(description = "Trường sắp xếp dữ liệu (mặc định createdAt)")
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Hướng sắp xếp: ASC hoặc DESC (mặc định DESC)")
            @RequestParam(value = "sortDirection", defaultValue = "DESC") String sortDirection
    ) {
        UUID currentUserId = resolveUserId(jwt);
        List<String> userRoles = extractRoles(authentication, jwt);

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM_VALUE,
                    "startDate không được sau endDate"
            );
        }

        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String validSortBy = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, validSortBy));

        if (role != null && "admin".equalsIgnoreCase(role.trim())) {
            boolean isAdmin = hasAnyRole(userRoles, OrderStatusStateMachine.ROLE_ADMIN);
            if (!isAdmin) {
                throw new BusinessException(
                        ErrorCode.AUTH_FORBIDDEN,
                        "Bạn không có quyền truy cập danh sách đơn hàng quản trị viên"
                );
            }
            Page<OrderSummaryResponse> adminOrders = orderService.getAdminOrders(
                    status, startDate, endDate, pageable
            );
            return ApiResponse.ok(adminOrders);
        }

        if (role != null && "seller".equalsIgnoreCase(role.trim())) {
            boolean isSellerOrAdmin = hasAnyRole(
                    userRoles,
                    OrderStatusStateMachine.ROLE_SELLER,
                    OrderStatusStateMachine.ROLE_ADMIN
            );
            if (!isSellerOrAdmin) {
                throw new BusinessException(
                        ErrorCode.AUTH_FORBIDDEN,
                        "Bạn không có quyền truy cập danh sách đơn hàng người bán"
                );
            }
            Page<OrderSummaryResponse> sellerOrders = orderService.getSellerOrders(
                    currentUserId, status, startDate, endDate, pageable
            );
            return ApiResponse.ok(sellerOrders);
        }

        if (role != null && !role.isBlank() && !"buyer".equalsIgnoreCase(role.trim())) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM_VALUE,
                    "Tham số role chỉ chấp nhận giá trị 'buyer', 'seller' hoặc 'admin'"
            );
        }

        Page<OrderSummaryResponse> buyerOrders = orderService.getBuyerOrders(
                currentUserId, status, startDate, endDate, pageable
        );
        return ApiResponse.ok(buyerOrders);
    }

    /**
     * Lấy danh sách đơn hàng thuộc quyền quản lý của Người bán.
     *
     * <p>Chức năng: Lấy danh sách các đơn hàng có sản phẩm thuộc gian hàng của Người bán đang đăng nhập.
     *
     * <p>Đầu vào và kết quả:
     * Nhận tham số phân trang page, size, sortBy, sortDirection và bộ lọc trạng thái, ngày tháng.
     * Trả về ApiResponse Envelope v2 bọc List&lt;OrderSummaryResponse&gt; trong data và phân trang trong meta.pagination.
     *
     * <p>Luồng xử lý:
     * Spring Security kiểm tra role SELLER/ADMIN qua @PreAuthorize -&gt; kiểm tra khoảng ngày hợp lệ -&gt;
     * gọi getSellerOrders scoped theo sellerId -&gt; đóng gói vào ApiResponse.ok(page).
     *
     * <p>Quy tắc và lý do:
     * Chỉ tài khoản có vai trò SELLER hoặc ADMIN mới được phép gọi endpoint này; người mua thuần túy nhận HTTP 403.
     * Khoảng ngày startDate không được sau endDate.
     *
     * <p>Lỗi và giới hạn:
     * Trả về HTTP 400 nếu startDate sau endDate. Trả về HTTP 403 nếu người dùng không có vai trò SELLER hoặc ADMIN.
     */
    @Operation(
            summary = "Lấy danh sách đơn hàng của người bán",
            description = "Truy vấn danh sách đơn hàng thuộc gian hàng của người bán đang đăng nhập với phân trang và bộ lọc"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Lấy danh sách đơn bán thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Tham số bộ lọc không hợp lệ"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực danh tính"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền Người bán hoặc Quản trị viên"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Tham số phân trang vi phạm ràng buộc")
    })
    @GetMapping("/seller")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    public ApiResponse<List<OrderSummaryResponse>> getSellerOrders(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Lọc theo trạng thái đơn hàng")
            @RequestParam(value = "status", required = false) OrderStatus status,
            @Parameter(description = "Mốc thời gian bắt đầu (ISO-8601 UTC)")
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "Mốc thời gian kết thúc (ISO-8601 UTC)")
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @Parameter(description = "Chỉ số trang (0-based)")
            @RequestParam(value = "page", defaultValue = "0")
            @Min(value = 0, message = "Số trang không được nhỏ hơn 0") int page,
            @Parameter(description = "Số lượng bản ghi trên một trang (1 - 100)")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "Kích thước trang phải từ 1 đến 100")
            @Max(value = 100, message = "Kích thước trang phải từ 1 đến 100") int size,
            @Parameter(description = "Trường sắp xếp dữ liệu (mặc định createdAt)")
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @Parameter(description = "Hướng sắp xếp: ASC hoặc DESC (mặc định DESC)")
            @RequestParam(value = "sortDirection", defaultValue = "DESC") String sortDirection
    ) {
        UUID sellerId = resolveUserId(jwt);

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM_VALUE,
                    "startDate không được sau endDate"
            );
        }

        Sort.Direction direction = "ASC".equalsIgnoreCase(sortDirection) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String validSortBy = (sortBy != null && !sortBy.isBlank()) ? sortBy : "createdAt";
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, validSortBy));

        Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                sellerId, status, startDate, endDate, pageable
        );
        return ApiResponse.ok(response);
    }

    /**
     * Xem thông tin chi tiết một đơn hàng kèm kiểm soát quyền sở hữu đa đối tượng.
     *
     * <p>Chức năng: Cung cấp đầy đủ thông tin đơn hàng, mặt hàng snapshot và dòng thời gian chuyển trạng thái.
     *
     * <p>Đầu vào và kết quả: Nhận orderId từ đường dẫn URL, trả về OrderDetailResponse bọc trong ApiResponse.
     *
     * <p>Luồng xử lý:
     * Trích xuất userId và userRoles từ Security Context -> OrderService xác thực quyền sở hữu
     * (Buyer sở hữu đơn, Seller sở hữu gian hàng hoặc Admin) -> trả về chi tiết đơn hàng.
     *
     * <p>Quy tắc và lý do:
     * Chặn truy cập chéo giữa các người mua hoặc người bán không sở hữu đơn hàng (HTTP 403 AUTH_FORBIDDEN).
     *
     * <p>Lỗi và giới hạn:
     * Trả về HTTP 401 nếu chưa đăng nhập, HTTP 403 nếu không sở hữu đơn, HTTP 404 nếu không tìm thấy đơn hàng.
     */
    @Operation(
            summary = "Xem chi tiết đơn hàng",
            description = "Lấy toàn bộ dữ liệu đơn hàng bao gồm snapshot sản phẩm, địa chỉ và dòng thời gian trạng thái"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Lấy chi tiết đơn hàng thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Mã định danh orderId sai định dạng UUID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực danh tính"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền xem đơn hàng này"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy đơn hàng")
    })
    @GetMapping("/{orderId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<OrderDetailResponse> getOrderDetail(
            @Parameter(description = "Mã định danh duy nhất của đơn hàng (UUID)")
            @PathVariable UUID orderId,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        UUID currentUserId = resolveUserId(jwt);
        List<String> userRoles = extractRoles(authentication, jwt);
        OrderDetailResponse response = orderService.getOrderDetail(orderId, currentUserId, userRoles);
        return ApiResponse.ok(response);
    }

    /**
     * Cập nhật trạng thái đơn hàng (Dành cho Người bán và Quản trị viên).
     *
     * <p>Chức năng: Thực thi bước chuyển trạng thái đơn hàng với xác thực máy trạng thái và ghi audit log.
     *
     * <p>Đầu vào và kết quả:
     * Nhận orderId và request chứa status đích cùng lý do -> trả về OrderDetailResponse sau khi cập nhật.
     *
     * <p>Luồng xử lý:
     * Kiểm tra role SELLER/ADMIN qua @PreAuthorize -> OrderService xác thực quyền sở hữu gian hàng,
     * tính lũy kế idempotent, kiểm tra chuyển trạng thái hợp lệ và lưu lịch sử kiểm toán.
     *
     * <p>Quy tắc và lý do:
     * Chặn các bước chuyển nhảy cóc, thụt lùi hoặc chuyển tiếp từ trạng thái kết thúc (Terminal State).
     *
     * <p>Lỗi và giới hạn:
     * Trả về HTTP 403 nếu Người bán cố cập nhật đơn gian hàng khác. Trả về HTTP 409 nếu bước chuyển sai ma trận.
     */
    @Operation(
            summary = "Cập nhật trạng thái đơn hàng",
            description = "Thực hiện chuyển đổi trạng thái đơn hàng bởi Người bán hoặc Quản trị viên theo máy trạng thái"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cập nhật trạng thái thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Dữ liệu yêu cầu không hợp lệ"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực danh tính"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền cập nhật đơn hàng của gian hàng khác"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy đơn hàng"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Chuyển trạng thái bất hợp pháp theo quy tắc máy trạng thái"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Dữ liệu validation không hợp lệ")
    })
    @PatchMapping("/{orderId}/status")
    @PreAuthorize("hasAnyRole('SELLER', 'ADMIN')")
    public ApiResponse<OrderDetailResponse> updateOrderStatus(
            @Parameter(description = "Mã định danh duy nhất của đơn hàng (UUID)")
            @PathVariable UUID orderId,
            @Valid @RequestBody UpdateOrderStatusRequest request,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        UUID currentUserId = resolveUserId(jwt);
        List<String> userRoles = extractRoles(authentication, jwt);
        OrderDetailResponse response = orderService.updateOrderStatus(orderId, request, currentUserId, userRoles);
        return ApiResponse.ok(response);
    }

    /**
     * Hủy đơn hàng an toàn theo yêu cầu của Người mua hoặc can thiệp của Quản trị viên.
     *
     * <p>Chức năng: Chuyển trạng thái đơn sang CANCELLED và ghi nhận lý do hủy đơn vào lịch sử kiểm toán.
     *
     * <p>Đầu vào và kết quả:
     * Nhận orderId và request chứa lý do hủy (tùy chọn). Trả về OrderDetailResponse sau khi hủy.
     *
     * <p>Luồng xử lý:
     * Xác thực người dùng có quyền BUYER hoặc ADMIN qua @PreAuthorize -> chuẩn hóa request rỗng ->
     * OrderService xác thực quyền sở hữu và trạng thái được phép hủy -> cập nhật trạng thái đơn và ghi audit log.
     *
     * <p>Quy tắc và lý do:
     * Chỉ cho phép hủy khi đơn ở PENDING_PAYMENT hoặc PAID. Từ chối hủy khi đơn đã sang PROCESSING hoặc SHIPPED (HTTP 409).
     * Nếu không truyền body, tự động gán lý do mặc định: "Người dùng yêu cầu hủy đơn".
     *
     * <p>Lỗi và giới hạn:
     * Trả về HTTP 403 nếu người dùng không phải chủ đơn hoặc Admin. Trả về HTTP 409 nếu đơn hàng không thể hủy.
     */
    @Operation(
            summary = "Hủy đơn hàng",
            description = "Người mua sở hữu đơn hoặc Quản trị viên thực hiện hủy đơn hàng trong giai đoạn hợp lệ"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Hủy đơn hàng thành công"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Mã định danh orderId sai định dạng"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Chưa xác thực danh tính"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Không có quyền hủy đơn hàng của người khác"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không tìm thấy đơn hàng"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Đơn hàng đang giao hoặc đã hoàn tất, không thể hủy"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Lý do hủy đơn không hợp lệ")
    })
    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    public ApiResponse<OrderDetailResponse> cancelOrder(
            @Parameter(description = "Mã định danh duy nhất của đơn hàng (UUID)")
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) CancelOrderRequest request,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication
    ) {
        UUID currentUserId = resolveUserId(jwt);
        List<String> userRoles = extractRoles(authentication, jwt);

        CancelOrderRequest safeRequest = (request != null)
                ? request
                : CancelOrderRequest.builder().reason("Người dùng yêu cầu hủy đơn").build();

        OrderDetailResponse response = orderService.cancelOrder(orderId, safeRequest, currentUserId, userRoles);
        return ApiResponse.ok(response);
    }

    // =========================================================================
    // Các phương thức tiện ích bảo mật nội bộ (Security Helper Methods)
    // =========================================================================

    /**
     * Lấy UUID người dùng từ trường subject của token JWT đã xác thực.
     *
     * @param jwt Token JWT đã giải mã bởi Resource Server
     * @return UUID định danh người dùng
     */
    private UUID resolveUserId(Jwt jwt) {
        if (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
            try {
                return UUID.fromString(jwt.getSubject());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(
                        ErrorCode.AUTH_UNAUTHORIZED,
                        "Định danh người dùng trong token không đúng định dạng UUID"
                );
            }
        }
        throw new BusinessException(
                ErrorCode.AUTH_UNAUTHORIZED,
                "Không thể xác định User ID từ token JWT đã xác thực"
        );
    }

    /**
     * Trích xuất danh sách các vai trò chuẩn hóa của người dùng từ Authentication và JWT claims.
     *
     * @param authentication Đối tượng xác thực Spring Security
     * @param jwt Token JWT đã xác thực
     * @return Danh sách các chuỗi vai trò của người dùng
     */
    private List<String> extractRoles(Authentication authentication, Jwt jwt) {
        if (authentication != null && authentication.getAuthorities() != null && !authentication.getAuthorities().isEmpty()) {
            List<String> authorities = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a != null && !a.isBlank())
                    .toList();
            if (!authorities.isEmpty()) {
                return authorities;
            }
        }

        if (jwt != null) {
            List<String> roles = new ArrayList<>();
            Object rolesClaim = jwt.getClaim("roles");
            collectRoleStrings(roles, rolesClaim);

            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null) {
                collectRoleStrings(roles, realmAccess.get("roles"));
            }
            if (!roles.isEmpty()) {
                return roles;
            }
        }

        return Collections.emptyList();
    }

    /**
     * Thu thập danh sách chuỗi vai trò từ đối tượng claim linh hoạt.
     *
     * @param target Danh sách lưu kết quả
     * @param claim Đối tượng claim từ token JWT
     */
    private void collectRoleStrings(List<String> target, Object claim) {
        if (claim instanceof Collection<?> coll) {
            for (Object obj : coll) {
                if (obj instanceof String roleStr && !roleStr.isBlank()) {
                    target.add(roleStr.trim());
                }
            }
        }
    }

    /**
     * Kiểm tra xem danh sách vai trò người dùng có chứa ít nhất một trong các vai trò mục tiêu hay không.
     *
     * @param userRoles Danh sách vai trò hiện tại của người dùng
     * @param targetRoles Danh sách vai trò cần kiểm tra
     * @return true nếu người dùng sở hữu ít nhất một vai trò hợp lệ
     */
    private boolean hasAnyRole(List<String> userRoles, String... targetRoles) {
        if (userRoles == null || userRoles.isEmpty() || targetRoles == null || targetRoles.length == 0) {
            return false;
        }
        Set<String> cleanTargets = Arrays.stream(targetRoles)
                .map(this::normalizeRole)
                .collect(Collectors.toSet());

        return userRoles.stream()
                .map(this::normalizeRole)
                .anyMatch(cleanTargets::contains);
    }

    /**
     * Chuẩn hóa tên vai trò bằng cách loại bỏ tiền tố ROLE_ và chuyển về chữ in hoa.
     *
     * @param role Chuỗi vai trò đầu vào
     * @return Tên vai trò đã chuẩn hóa
     */
    private String normalizeRole(String role) {
        if (role == null) {
            return "";
        }
        String trimmed = role.trim().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("ROLE_")) {
            return trimmed.substring(5);
        }
        return trimmed;
    }
}
