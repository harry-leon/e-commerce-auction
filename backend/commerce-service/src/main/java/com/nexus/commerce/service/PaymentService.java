package com.nexus.commerce.service;

import com.nexus.commerce.client.VnpayGateway;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.commerce.config.VnpayProperties;
import com.nexus.commerce.dto.response.PaymentResponse;
import com.nexus.commerce.dto.response.VnpayIpnResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.entity.Payment;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.PaymentRepository;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo một attempt cho đơn VNPAY và xử lý IPN đã ký. IPN khóa theo TxnRef rồi Order để tuần tự hóa callback cùng payment.
 * Payment, Order, history và outbox kho/thông báo commit cùng transaction; browser return không cập nhật tài chính.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final VnpayGateway gateway;
    private final VnpayProperties config;
    private final CheckoutLock locks;
    private final JdbcTemplate jdbc;
    private final InventoryServiceToken inventoryTokens;
    private final NotificationOutbox notifications;

    /** Kiểm tra cấu hình trước reserve checkout để thiếu merchant không tạo reservation thừa. */
    public void requireConfigured() {
        gateway.requireConfigured();
        // Không nhận thanh toán mới khi chưa thể giao tác vụ kho sau IPN; callback cũ vẫn được ghi nhận khi worker thiếu config.
        if (!inventoryTokens.configured()) {
            throw new ApiException("PAYMENT_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình service account hoàn tất tồn kho", null);
        }
    }

    /** Checkout replay giữ nguyên URL đã cấp dù đã hết hạn/terminal; tạo attempt chỉ khi đơn mới chưa có Payment. */
    @Transactional
    public String checkoutUrl(Order order, String ip) {
        return payments.findByOrderIdAndProvider(order.getId(), "VNPAY").map(Payment::getPaymentUrl)
                .orElseGet(() -> start(order.getBuyerId(), order.getId(), ip).paymentUrl());
    }

    /** Tạo/replay URL cho buyer sở hữu đơn còn chờ thanh toán; total chỉ lấy DB, không nhận amount từ request. */
    @Transactional
    public PaymentResponse start(UUID buyer, UUID orderId, String ip) {
        requireConfigured();
        Order order = orders.findForPayment(orderId).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!buyer.equals(order.getBuyerId())) {
            throw new ApiException("PAYMENT_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Không được thanh toán đơn của người khác", null);
        }
        if (!"VNPAY".equals(order.getPaymentMethod())) { throw new BusinessException(ErrorCode.PAYMENT_METHOD_UNSUPPORTED); }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) { throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID); }
        var previous = payments.findByOrderIdAndProvider(orderId, "VNPAY");
        if (previous.isPresent()) {
            Payment payment = previous.get();
            if (!"PENDING".equals(payment.getStatus())) { throw new BusinessException(ErrorCode.PAYMENT_ALREADY_COMPLETED); }
            if (payment.getExpiresAt() == null || !payment.getExpiresAt().isAfter(Instant.now())) {
                throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);
            }
            return response(payment);
        }
        // Không gia hạn khi mở lại; VNPAY chỉ nhận chính xác đến giây nên DB cũng dùng cùng mốc, tránh lệch phần mili giây.
        // Đối với đơn hàng đấu giá, hạn thanh toán được xác định theo paymentDeadline (mặc định 24h theo SRS §3.6.1).
        Instant expires;
        if (order.getPaymentDeadline() != null) {
            expires = order.getPaymentDeadline().truncatedTo(ChronoUnit.SECONDS);
        } else if (order.getSourceType() == com.nexus.commerce.enums.OrderSourceType.AUCTION) {
            expires = order.getCreatedAt().plus(24, java.time.temporal.ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        } else {
            expires = order.getCreatedAt().plusSeconds(15 * 60).truncatedTo(ChronoUnit.SECONDS);
        }
        if (!expires.isAfter(Instant.now())) { throw new BusinessException(ErrorCode.PAYMENT_EXPIRED); }
        if (order.getReservationIds().isEmpty()) {
            throw new ApiException("PAYMENT_RESERVATIONS_MISSING", HttpStatus.CONFLICT, "Đơn chưa có snapshot phiếu giữ kho", null);
        }
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(orderId);
        payment.setProvider("VNPAY");
        payment.setMethod("VNPAY");
        payment.setAmount(order.getTotal());
        payment.setStatus("PENDING");
        payment.setIdempotencyKey("vnpay:" + orderId);
        payment.setMerchantCode(config.getTmnCode());
        payment.setCreatedAt(Instant.now());
        payment.setExpiresAt(expires);
        payment.setPaymentUrl(gateway.url(payment.getId(), order.getTotal(), payment.getCreatedAt(), expires, ip));
        return response(payments.saveAndFlush(payment));
    }

    /** Buyer sở hữu đơn được tra cứu kết quả DB và hạn URL; quá hạn trả EXPIRED nhưng chưa kết luận đã/không thu tiền.
     * Không ghi trạng thái terminal, hủy đơn hay release kho từ GET: IPN đã xác minh vẫn có thể đến chậm.
     */
    @Transactional(readOnly = true)
    public PaymentResponse get(UUID buyer, UUID id) {
        Payment payment = payments.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        Order order = orders.findById(payment.getOrderId()).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!buyer.equals(order.getBuyerId())) {
            throw new ApiException("PAYMENT_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Không được xem giao dịch của người khác", null);
        }
        return response(payment);
    }

    /**
     * Chỉ IPN gọi hàm này: kiểm tra chữ ký/merchant trước DB, đối chiếu TxnRef→Payment→Order và amount ở cả hai bảng.
     * Duplicate terminal trả 02, sai amount 04, thiếu order 01; trạng thái bất thường trả 99 để đối soát/retry.
     */
    @Transactional
    public VnpayIpnResponse ipn(Map<String, String> params) {
        if (!gateway.verify(params)) { return reply("97", "Invalid signature"); }
        UUID id;
        try { id = UUID.fromString(params.get("vnp_TxnRef")); }
        catch (IllegalArgumentException | NullPointerException error) { return reply("01", "Order not found"); }
        // Khóa theo paymentId trước đọc Payment; mỗi request dùng persistence context riêng, không lưu entity trước chờ khóa.
        locks.acquire("payment:" + id);
        Payment payment = payments.findById(id).orElse(null);
        if (payment == null || !"VNPAY".equals(payment.getProvider())) { return reply("01", "Order not found"); }
        // Fetch trước order.items vì NotificationOutbox.emailSnapshot() cần snapshot dòng hàng trong callback.
        // reservationIds vẫn lazy để không join-fetch đồng thời hai List, vốn bị Hibernate từ chối.
        Order order = orders.findForPaymentWithItems(payment.getOrderId()).orElse(null);
        if (order == null || !"VNPAY".equals(order.getPaymentMethod())) { return reply("01", "Order not found"); }
        if (!params.get("vnp_TmnCode").equals(payment.getMerchantCode())) { return reply("97", "Invalid merchant"); }
        String amount = params.get("vnp_Amount");
        if (!gateway.amount(payment.getAmount()).equals(amount) || !gateway.amount(order.getTotal()).equals(amount)) {
            return reply("04", "Invalid amount");
        }
        // EXPIRED chỉ là trạng thái hiển thị của URL, DB còn PENDING để IPN đã ký đến chậm vẫn được đối soát.
        // Không chặn IPN theo giờ nhận: khách có thể trả trong hạn nhưng callback bị trễ mạng.
        if (!"PENDING".equals(payment.getStatus())) { return reply("02", "Order already confirmed"); }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) { return reply("02", "Order already confirmed"); }
        String result = params.get("vnp_ResponseCode");
        String state = params.get("vnp_TransactionStatus");
        boolean success = "00".equals(result) && "00".equals(state);
        // 01/04/05/06/07 hoặc response 07 có thể còn xử lý/đã trừ tiền: không tự kết luận thất bại.
        boolean failure = "02".equals(state) && result != null && result.matches("[0-9]{2}")
                && !"00".equals(result) && !"07".equals(result);
        if (!success && !failure) { return reply("99", "Transaction requires reconciliation"); }
        String transactionNo = params.get("vnp_TransactionNo");
        if (success && (transactionNo == null || !transactionNo.matches("[0-9]{1,100}") || transactionNo.matches("0+"))) {
            return reply("99", "Invalid transaction reference");
        }
        if (success) {
            locks.acquire("vnpay-transaction:" + transactionNo);
            if (payments.existsByProviderAndProviderRefAndStatus("VNPAY", transactionNo, "SUCCESS")) {
                return reply("99", "Transaction reference already used");
            }
        }
        payment.setStatus(success ? "SUCCESS" : "FAILED");
        payment.setProviderRef(success ? transactionNo : null);
        payment.setResponseCode(result);
        payment.setTransactionStatus(state);
        payment.setCompletedAt(Instant.now());
        order.setStatus(success ? OrderStatus.PAID : OrderStatus.CANCELLED);
        // Outbox có unique key và chỉ tạo sau lần chuyển terminal đầu tiên, nên callback lặp không confirm/release hai lần.
        if (success) {
            for (UUID reservation : order.getReservationIds()) { enqueue(order.getId(), reservation, "CONFIRM"); }
        } else { enqueue(order.getId(), order.getId(), "RELEASE"); }
        jdbc.update("INSERT INTO order_status_history(id,order_id,from_status,to_status,reason) VALUES (?,?,'PENDING_PAYMENT',?,?)",
                UUID.randomUUID(), order.getId(), order.getStatus().name(), "VNPAY_IPN_" + result);
        // Chỉ lần chuyển trạng thái terminal đầu tiên tạo thông báo, cùng commit với kết quả thanh toán.
        notifications.enqueue(order, success ? "PAYMENT_SUCCESS" : "PAYMENT_FAILED");
        payments.flush();
        return reply("00", "Confirm Success");
    }

    private void enqueue(UUID order, UUID target, String action) {
        jdbc.update("INSERT INTO payment_inventory_tasks(id,order_id,target_id,action) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), order, target, action);
    }

    /** Chiếu PENDING quá hạn thành EXPIRED và bỏ link không còn dùng được; kết quả terminal từ IPN luôn được ưu tiên.
     * Đây là trạng thái trả cho client, không thay đổi entity/DB hay tạo thêm tác vụ kho.
     */
    private PaymentResponse response(Payment p) {
        boolean expired = "PENDING".equals(p.getStatus()) && p.getExpiresAt() != null
                && !p.getExpiresAt().isAfter(Instant.now());
        return new PaymentResponse(p.getId(), p.getOrderId(), expired ? "EXPIRED" : p.getStatus(), p.getAmount(),
                "VND", expired ? null : p.getPaymentUrl(), p.getExpiresAt());
    }
    private VnpayIpnResponse reply(String code, String message) { return new VnpayIpnResponse(code, message); }
}
