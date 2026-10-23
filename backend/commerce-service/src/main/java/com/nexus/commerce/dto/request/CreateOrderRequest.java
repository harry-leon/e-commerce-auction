package com.nexus.commerce.dto.request;

import com.nexus.commerce.enums.OrderSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * DTO yêu cầu tạo đơn hàng từ màn hình Checkout (POST /api/v1/orders).
 *
 * <p>Quy chuẩn theo `API_SPEC_CART_ORDER.md` §4.2.
 *
 * @param shippingAddress Địa chỉ giao hàng chi tiết
 * @param recipientName   Họ và tên người nhận hàng
 * @param recipientPhone  Số điện thoại liên hệ nhận hàng
 * @param voucherCode     Mã giảm giá voucher (nếu có)
 * @param paymentMethod   Phương thức thanh toán đã chọn (VNPAY, COD...)
 * @param sourceType      Nguồn phát sinh đơn hàng (REGULAR, AUCTION)
 * @param auctionId       Mã định danh phiên đấu giá (bắt buộc nếu sourceType là AUCTION)
 * @param selectedItemIds Danh sách itemId trong giỏ hàng muốn thanh toán (nếu null/rỗng thì mua toàn bộ giỏ)
 */
@Schema(description = "Yêu cầu đặt đơn hàng từ Checkout")
public record CreateOrderRequest(
        @NotBlank(message = "Địa chỉ nhận hàng không được để trống")
        @Size(max = 500, message = "Địa chỉ giao hàng không được vượt quá 500 ký tự")
        @Schema(description = "Địa chỉ nhận hàng chi tiết", example = "Số 123 Lê Lợi, Bến Nghé, Quận 1, TP. Hồ Chí Minh")
        String shippingAddress,

        @Size(max = 255, message = "Tên người nhận không được vượt quá 255 ký tự")
        @Schema(description = "Họ và tên người nhận hàng", example = "Nguyễn Văn A")
        String recipientName,

        @Size(max = 20, message = "Số điện thoại không được vượt quá 20 ký tự")
        @Schema(description = "Số điện thoại người nhận", example = "0901234567")
        String recipientPhone,

        @Schema(description = "Mã voucher giảm giá (nếu có)", example = "NEXUS2026")
        String voucherCode,

        @Schema(description = "Phương thức thanh toán", example = "VNPAY")
        String paymentMethod,

        @Schema(description = "Nguồn gốc đơn hàng", defaultValue = "REGULAR")
        OrderSourceType sourceType,

        @Schema(description = "Mã phiên đấu giá (nếu là đơn đấu giá)")
        UUID auctionId,

        @Schema(description = "Danh sách ID dòng giỏ hàng được chọn để đặt hàng")
        List<UUID> selectedItemIds
) {
    public CreateOrderRequest {
        if (sourceType == null) {
            sourceType = OrderSourceType.REGULAR;
        }
    }
}
