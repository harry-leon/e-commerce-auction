package com.nexus.commerce.service;

import com.nexus.commerce.dto.response.PaymentReceipt;
import com.nexus.commerce.entity.Payment;
import com.nexus.commerce.entity.PaymentPriceSnapshot;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.PaymentRepository;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc biên nhận từ Payment đã xác minh, kiểm tra chủ đơn rồi kiểm tra toàn vẹn snapshot.
 * Không ghi DB, phát event hay gọi provider; dữ liệu cũ thiếu bằng chứng trả 409 để đối soát.
 * Đây là bản xuất snapshot có ID xác định, chưa có registry phát hành/điều chỉnh chứng từ.
 */
@Service
@RequiredArgsConstructor
public class PaymentReceiptService {
    private final PaymentRepository payments;
    private final OrderRepository orders;

    /**
     * Nhận paymentId và JWT đã xác thực; chỉ Buyer sở hữu đơn hoặc Admin được tải.
     * Đơn đã giao/refund vẫn giữ biên nhận thu gốc; không suy ra số dư có thể hoàn từ biên nhận.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('BUYER', 'ADMIN')")
    public PaymentReceipt download(UUID paymentId, Authentication authentication) {
        UUID actor = actor(authentication);
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        boolean buyer = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_BUYER".equals(a.getAuthority()));
        if (!admin && !buyer) { throw forbidden(); }
        Payment payment = payments.findById(paymentId).orElseThrow(this::notFound);
        var order = orders.findById(payment.getOrderId()).orElseThrow(this::notFound);
        // Dùng cùng 404 cho mã không tồn tại và đơn của người khác, tránh tiết lộ payment qua UUID.
        if (!admin && !actor.equals(order.getBuyerId())) { throw notFound(); }
        var prices = payment.getPriceSnapshot();
        if (!"SUCCESS".equals(payment.getStatus()) || !"VNPAY".equals(payment.getProvider())
                || !"VNPAY".equals(payment.getMethod()) || payment.getCompletedAt() == null
                || payment.getProviderRef() == null || payment.getProviderRef().isBlank()
                || !"00".equals(payment.getResponseCode()) || !"00".equals(payment.getTransactionStatus())
                || !valid(prices) || !money(payment.getAmount()) || payment.getAmount().signum() <= 0
                || payment.getAmount().compareTo(prices.total()) != 0) {
            throw new ApiException("RECEIPT_NOT_READY", HttpStatus.CONFLICT,
                    "Giao dịch chưa xác nhận hoặc thiếu snapshot hợp lệ để xuất biên nhận", null);
        }
        return new PaymentReceipt(1, "INTERNAL_PAYMENT_RECEIPT", "PAY-" + payment.getId(), payment.getId(),
                payment.getOrderId(), payment.getCompletedAt(), "VND", payment.getMethod(), prices);
    }

    /** Fail closed khi thiếu subject UUID; không nhận actor hay quyền Admin từ tham số HTTP. */
    private UUID actor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw unauthorized();
        }
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) { throw unauthorized(); }
    }

    /** Kiểm tra VND nguyên, dòng hàng và tổng; không sửa hoặc tính lại snapshot hỏng bằng giá hiện tại. */
    private boolean valid(PaymentPriceSnapshot prices) {
        if (prices == null || !money(prices.subtotal()) || !money(prices.shippingFee()) || !money(prices.discount())
                || !money(prices.total()) || prices.items().isEmpty()) { return false; }
        BigDecimal subtotal = BigDecimal.ZERO;
        var ids = new java.util.HashSet<UUID>();
        for (var line : prices.items()) {
            if (line.orderItemId() == null || line.skuId() == null || !ids.add(line.orderItemId()) || line.quantity() <= 0
                    || !money(line.unitPrice()) || !money(line.itemTotal())
                    || line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())).compareTo(line.itemTotal()) != 0) { return false; }
            subtotal = subtotal.add(line.itemTotal());
        }
        return subtotal.compareTo(prices.subtotal()) == 0
                && subtotal.add(prices.shippingFee()).subtract(prices.discount()).compareTo(prices.total()) == 0;
    }

    /** VND không âm và không có phần lẻ; không làm tròn một snapshot sai để phát hành biên nhận. */
    private boolean money(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0;
    }

    private ApiException notFound() {
        return new ApiException("RECEIPT_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy biên nhận thuộc quyền truy cập", null);
    }

    private ApiException unauthorized() {
        return new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT thiếu user UUID hợp lệ", null);
    }

    private ApiException forbidden() {
        return new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Chỉ Buyer sở hữu đơn hoặc Admin được tải biên nhận", null);
    }
}
