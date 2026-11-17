package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTO yêu cầu tạo đơn hàng (Checkout Request) từ giỏ hàng hoặc mua ngay.
 * Chức năng: Nhận thông tin địa chỉ giao hàng, người nhận, phương thức thanh toán, hãng vận chuyển và phí vận chuyển thực tế.
 * Đầu vào & kết quả: Nhận payload từ FE, validate ràng buộc dữ liệu cơ bản trước khi chuyển cho CheckoutService.
 * Luồng xử lý: Controller validate bằng Bean Validation -> CheckoutService kiểm tra tồn kho và Catalog snapshot -> tạo Order.
 * Quy tắc: Không tin giá sản phẩm từ client; phí vận chuyển (shippingFee) nếu có phải >= 0 và được dùng để tính estimatedTotal.
 * Lỗi & giới hạn: Vi phạm validate trả về mã 422 Unprocessable Entity.
 */
public record CreateOrderRequest(
        @NotBlank @Size(max = 500) String shippingAddress,
        @NotBlank @Size(max = 255) String recipientName,
        @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String recipientPhone,
        @Size(max = 50) String voucherCode,
        @NotBlank String paymentMethod,
        String sourceType,
        UUID auctionId,
        @Size(min = 1, max = 50) List<@NotNull UUID> selectedItemIds,
        @jakarta.validation.Valid BuyNowRequest buyNow,
        @DecimalMin(value = "0.00", message = "Phí vận chuyển không được âm") BigDecimal shippingFee,
        @Size(max = 50) String carrier
) {
    /** Giữ tương thích caller checkout giỏ hiện có; buyNow chỉ dùng cho mua trực tiếp. */
    public CreateOrderRequest(String address, String name, String phone, String voucher, String method,
            String source, UUID auction, List<UUID> items) {
        this(address, name, phone, voucher, method, source, auction, items, null, null, null);
    }

    /** Constructor hỗ trợ buyNow mà không có shippingFee/carrier. */
    public CreateOrderRequest(String address, String name, String phone, String voucher, String method,
            String source, UUID auction, List<UUID> items, BuyNowRequest buyNow) {
        this(address, name, phone, voucher, method, source, auction, items, buyNow, null, null);
    }
}
