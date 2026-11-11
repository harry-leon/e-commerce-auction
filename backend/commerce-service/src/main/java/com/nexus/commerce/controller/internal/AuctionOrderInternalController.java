package com.nexus.commerce.controller.internal;

import com.nexus.commerce.dto.request.AttachOrderReservationsRequest;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.dto.response.AuctionOrderResponse;
import com.nexus.commerce.service.OrderService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller nội bộ tiếp nhận yêu cầu khởi tạo đơn hàng từ Auction Service (SRS §3.5.3).
 *
 * Chức năng:
 * - Cung cấp API nội bộ POST /api/v1/internal/commerce/orders/from-auction.
 * - Được bảo vệ bằng ServiceIdentity (bắt buộc client 'nexus-auction-service' và privilege 'COMMERCE.AUCTION').
 * - Khởi tạo đơn hàng đấu giá chính thức cho người thắng cuộc một cách an toàn và lũy nghiệm (Idempotent).
 *
 * Đầu vào và kết quả:
 * - Đầu vào: CreateAuctionOrderRequest mang thông tin phiên, người thắng, người bán, SKU, giá thắng cuộc.
 * - Kết quả: ApiResponse chứa AuctionOrderResponse với HTTP 201 Created (hoặc 200 nếu replay đơn cũ).
 *
 * Quy tắc và lý do:
 * - Không expose ra ngoài Gateway public, chỉ cho phép giao tiếp service-to-service nội bộ.
 * - Bắt buộc xác thực ServiceIdentity qua Keycloak Service Account Token.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/internal/commerce/orders")
@RequiredArgsConstructor
@Tag(name = "Internal Auction Orders", description = "API nội bộ tạo đơn hàng đấu giá từ Auction Service")
public class AuctionOrderInternalController {

    private final OrderService orderService;

    /**
     * Khởi tạo đơn hàng từ kết quả chốt phiên đấu giá thành công (Settlement Handover).
     *
     * @param request dữ liệu chốt phiên đấu giá
     * @return kết quả thông tin đơn hàng đã tạo
     */
    @PostMapping("/from-auction")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo đơn hàng từ phiên đấu giá", description = "Endpoint nội bộ dành riêng cho Auction Service bàn giao kết quả chốt phiên")
    public ApiResponse<AuctionOrderResponse> createAuctionOrder(@Valid @RequestBody CreateAuctionOrderRequest request) {
        log.info("Tiếp nhận yêu cầu tạo đơn hàng nội bộ cho phiên đấu giá [{}] của winner [{}]",
                request.auctionId(), request.winnerId());
        AuctionOrderResponse response = orderService.createAuctionOrder(request);
        return ApiResponse.ok(response);
    }

    /**
     * Gắn danh sách phiếu giữ kho (reservation IDs) vào đơn hàng sau khi chuyển tiếp kho thành công.
     *
     * Chức năng:
     * - Tiếp nhận reservationIds từ Auction Service sau khi Auction Service đã gọi Fulfillment Service
     *   chuyển tiếp phiếu giữ kho từ AUCTION sang ORDER.
     * - Lưu các reservationIds vào order_reservations để chuẩn bị cho luồng thanh toán VNPAY.
     *
     * Đầu vào và kết quả:
     * - Đầu vào: orderId (trên URL), AttachOrderReservationsRequest mang danh sách UUID phiếu kho.
     * - Kết quả: ApiResponse.ok() với HTTP 200.
     *
     * Quy tắc:
     * - Bảo vệ bởi ServiceIdentity (bắt buộc client 'nexus-auction-service' và privilege 'COMMERCE.AUCTION').
     *
     * @param orderId Mã đơn hàng Commerce cần gắn phiếu kho
     * @param request Payload chứa danh sách reservationIds
     * @return ApiResponse rỗng
     */
    @PostMapping("/{orderId}/reservations")
    @Operation(summary = "Gắn phiếu giữ kho vào đơn hàng", description = "Endpoint nội bộ cho Auction Service gắn reservation IDs sau khi chuyển kho")
    public ApiResponse<Void> attachReservations(
            @PathVariable UUID orderId,
            @Valid @RequestBody AttachOrderReservationsRequest request) {
        log.info("Tiếp nhận yêu cầu gắn [{}] phiếu giữ kho cho đơn hàng [{}]",
                request.reservationIds().size(), orderId);
        orderService.attachReservations(orderId, request.reservationIds());
        return ApiResponse.ok();
    }
}

