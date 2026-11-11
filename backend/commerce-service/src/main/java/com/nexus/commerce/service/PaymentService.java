package com.nexus.commerce.service;

import com.nexus.commerce.entity.PaymentPriceSnapshot;

import com.nexus.commerce.client.VnpayGateway;
import com.nexus.commerce.client.InventoryServiceToken;
import com.nexus.commerce.config.VnpayProperties;
import com.nexus.commerce.dto.response.PaymentResponse;
import com.nexus.commerce.dto.response.VnpayIpnResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.enums.OrderSourceType;
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
 * Quản lý nhiều attempt VNPAY trên cùng đơn; tính giá mới khi tạo attempt và chốt đúng snapshot sau IPN đã ký.
 * IPN khóa TxnRef rồi Order; tiền/giá/outbox commit cùng transaction. Order chỉ PAID sau worker xác nhận kho.
 * Retry dùng key/ref kho riêng; callback muộn từ FAILED lưu REVIEW, không tự thu lại hoặc hoàn tiền.
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
    private final PaymentPricingService pricing;
    private final com.nexus.commerce.client.InventoryClient inventory;

    /** Kiểm tra cấu hình trước reserve checkout để thiếu merchant không tạo reservation thừa. */
    public void requireConfigured() {
        gateway.requireConfigured();
        // Không nhận thanh toán mới khi chưa thể giao tác vụ kho sau IPN; callback cũ vẫn được ghi nhận khi worker thiếu config.
        if (!inventoryTokens.configured()) {
            throw new ApiException("PAYMENT_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình service account hoàn tất tồn kho", null);
        }
    }

    /** Chỉ đọc URL attempt hiện có cho caller cũ; Create Order không gọi hàm này để tự tạo thanh toán. */
    @Transactional
    public String checkoutUrl(Order order, String ip) {
        return payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(order.getId(), "VNPAY")
                .map(payment -> response(payment).paymentUrl()).orElse(null);
    }

    /** Caller cũ không có key chỉ được tạo lần đầu hoặc lấy attempt đang PENDING; retry FAILED phải dùng overload có key. */
    @Transactional
    public PaymentResponse start(UUID buyer, UUID orderId, String ip) {
        return start(buyer, orderId, ip, null);
    }

    /**
     * Khóa Order để tạo/replay attempt; key UUID chống việc gửi lại sau timeout tạo lần thu tiền mới.
     * Retry sau FAILED phải có key mới; PENDING hết hạn vẫn không cho tạo mới vì chưa biết kết quả cổng.
     * Giá mới và ref kho thuộc riêng attempt; rollback release đúng ref đó, không release attempt khác.
     */
    @Transactional
    public PaymentResponse start(UUID buyer, UUID orderId, String ip, String key) {
        requireConfigured();
        Order order = orders.findForPaymentWithItems(orderId).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (!buyer.equals(order.getBuyerId())) {
            throw new ApiException("PAYMENT_ACCESS_DENIED", HttpStatus.FORBIDDEN, "Không được thanh toán đơn của người khác", null);
        }
        String attemptKey = null;
        if (key != null) {
            try {
                if (!UUID.fromString(key).toString().equalsIgnoreCase(key)) { throw new IllegalArgumentException(); }
                // Bao gồm order để key UUID vô tình dùng lại ở đơn khác không va chạm unique toàn bảng.
                attemptKey = orderId + ":" + key.toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException error) {
                throw new ApiException("PAYMENT_KEY_INVALID", HttpStatus.BAD_REQUEST, "Idempotency-Key phải là UUID", null);
            }
            var replay = payments.findByOrderIdAndIdempotencyKey(orderId, attemptKey);
            if (replay.isPresent()) { return response(replay.get()); }
        }
        // AUCTION là marker từ handover, không phải provider; chỉ winner được chọn VNPAY dưới khóa Order.
        boolean auction = order.getSourceType() == OrderSourceType.AUCTION;
        if (!"VNPAY".equals(order.getPaymentMethod()) && !(auction && "AUCTION".equals(order.getPaymentMethod()))) {
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_UNSUPPORTED);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) { throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID); }
        if (payments.existsByOrderIdAndInventoryStatus(orderId, "REVIEW")) {
            throw new ApiException("PAYMENT_REQUIRES_REVIEW", HttpStatus.CONFLICT, "Đơn có tiền cần đối soát, không thanh toán thêm", null);
        }
        var previous = payments.findFirstByOrderIdAndProviderOrderByCreatedAtDescIdDesc(orderId, "VNPAY");
        if (previous.isPresent()) {
            Payment payment = previous.get();
            // Winner quay lại khi worker đang xử lý phải xem kết quả cũ, không tạo lần thu tiền mới.
            if ("SUCCESS".equals(payment.getStatus())) { return response(payment); }
            if (!"PENDING".equals(payment.getStatus()) && !"FAILED".equals(payment.getStatus())) {
                throw new BusinessException(ErrorCode.PAYMENT_ALREADY_COMPLETED);
            }
            if ("FAILED".equals(payment.getStatus())) {
                if (attemptKey == null) {
                    throw new ApiException("PAYMENT_RETRY_KEY_REQUIRED", HttpStatus.CONFLICT, "Thử lại cần Idempotency-Key mới", null);
                }
            } else {
                if (payment.getExpiresAt() == null || !payment.getExpiresAt().isAfter(Instant.now())) {
                    throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);
                }
                // Key khác không được nhận replay như thể đã gắn với attempt cũ: sau FAILED nó có thể vô tình tạo lần thu mới.
                if (attemptKey != null) {
                    throw new ApiException("PAYMENT_IN_PROGRESS", HttpStatus.CONFLICT,
                            "Đang có attempt khác xử lý; tra cứu giao dịch đó thay vì tạo lần thanh toán mới", null);
                }
                return response(payment);
            }
        }
        // Không gia hạn khi mở lại; VNPAY chỉ nhận chính xác đến giây nên DB cũng dùng cùng mốc, tránh lệch phần mili giây.
        // Đối với đơn hàng đấu giá, hạn thanh toán được xác định theo paymentDeadline (mặc định 24h theo SRS §3.6.1).
        Instant expires;
        if (order.getPaymentDeadline() != null) {
            expires = order.getPaymentDeadline().truncatedTo(ChronoUnit.SECONDS);
        } else if (auction) {
            // Không tự gia hạn từ createdAt khi thiếu deadline settlement: từ chối dữ liệu handover chưa đầy đủ.
            throw new ApiException("AUCTION_PAYMENT_NOT_READY", HttpStatus.CONFLICT, "Đơn đấu giá chưa có hạn thanh toán", null);
        } else {
            expires = Instant.now().plusSeconds(15 * 60).truncatedTo(ChronoUnit.SECONDS);
        }
        if (!expires.isAfter(Instant.now())) { throw new BusinessException(ErrorCode.PAYMENT_EXPIRED); }
        if (auction && order.getReservationIds().isEmpty()) {
            throw new ApiException("PAYMENT_RESERVATIONS_MISSING", HttpStatus.CONFLICT, "Đơn chưa có snapshot phiếu giữ kho", null);
        }
        order.setPaymentMethod("VNPAY");
        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrderId(orderId);
        payment.setProvider("VNPAY");
        payment.setMethod("VNPAY");
        PaymentPriceSnapshot snapshot = pricing.quote(order);
        // gateway.amount kiểm tra VND nguyên và giới hạn trước khi gọi kho; URL và amount dùng đúng một snapshot.
        gateway.amount(snapshot.total());
        payment.setPriceSnapshot(snapshot);
        payment.setAmount(snapshot.total());
        payment.setStatus("PENDING");
        payment.setIdempotencyKey(attemptKey == null ? "vnpay:" + payment.getId() : attemptKey);
        payment.setMerchantCode(config.getTmnCode());
        payment.setCreatedAt(Instant.now());
        payment.setExpiresAt(expires);
        payment.setPaymentUrl(gateway.url(payment.getId(), snapshot.total(), payment.getCreatedAt(), expires, ip));
        payment.setInventoryRefId(auction ? orderId : payment.getId());
        if (!auction) {
            reserveAttempt(order, payment);
        }
        return response(payments.saveAndFlush(payment));
    }

    /** Giữ kho bằng paymentId để release FAILED cũ không thể nhả hàng của attempt mới; TTL xử lý crash trước commit. */
    private void reserveAttempt(Order order, Payment payment) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                // HTTP có thể đã reserve thành công nhưng mất ACK; release ref vẫn an toàn khi chưa có phiếu.
                                try { inventory.release(payment.getInventoryRefId(), null); }
                                catch (RuntimeException cleanupError) {
                                    // Không che lỗi gốc/không log token; Fulfillment TTL hoàn kho nếu cleanup mất mạng.
                                    org.slf4j.LoggerFactory.getLogger(PaymentService.class).warn(
                                            "Chưa release attempt rollback {}; chờ TTL kho", payment.getId());
                                }
                            }
                        }
                    });
        }
        order.setReservationIds(new java.util.ArrayList<>(inventory.reserve(payment.getInventoryRefId(),
                order.getItems().stream().map(item -> new com.nexus.commerce.client.InventoryClient.Item(
                        item.getSkuId(), null, item.getQuantity())).toList(), null)));
        if (order.getReservationIds().isEmpty()) {
            throw new ApiException("PAYMENT_RESERVATIONS_MISSING", HttpStatus.CONFLICT, "Không có phiếu giữ kho", null);
        }
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
     * Chỉ IPN gọi hàm này: kiểm tra chữ ký/merchant trước DB, đối chiếu amount với Payment và snapshot đã gửi cổng.
     * Duplicate terminal trả 02, sai amount 04, thiếu order 01; trạng thái bất thường trả 99 để đối soát/retry.
     * Thu tiền chốt giá và tạo COMMIT trong outbox; IPN sau hủy/FAILED lưu REVIEW, không hồi sinh Order hoặc tự hoàn tiền.
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
        if (!gateway.amount(payment.getAmount()).equals(amount) || payment.getPriceSnapshot() == null
                || payment.getAmount().compareTo(payment.getPriceSnapshot().total()) != 0) {
            return reply("04", "Invalid amount");
        }
        // EXPIRED chỉ là trạng thái hiển thị của URL, DB còn PENDING để IPN đã ký đến chậm vẫn được đối soát.
        // Không chặn IPN theo giờ nhận: khách có thể trả trong hạn nhưng callback bị trễ mạng.
        boolean auction = order.getSourceType() == OrderSourceType.AUCTION;
        // IPN thu tiền đến sau hủy/hết hạn vẫn phải được ghi nhận để đối soát; tuyệt đối không hồi sinh đơn đã hủy.
        String result = params.get("vnp_ResponseCode");
        String state = params.get("vnp_TransactionStatus");
        boolean success = "00".equals(result) && "00".equals(state);
        // 01/04/05/06/07 hoặc response 07 có thể còn xử lý/đã trừ tiền: không tự kết luận thất bại.
        boolean failure = "02".equals(state) && result != null && result.matches("[0-9]{2}")
                && !"00".equals(result) && !"07".equals(result);
        if (!success && !failure) { return reply("99", "Transaction requires reconciliation"); }
        // Terminal cùng kết quả là replay. FAILED nhận success muộn phải ghi nhận REVIEW, không bỏ qua tiền cổng đã thu.
        boolean lateSuccess = success && "FAILED".equals(payment.getStatus());
        if (!"PENDING".equals(payment.getStatus()) && !lateSuccess) { return reply("02", "Order already confirmed"); }
        String transactionNo = params.get("vnp_TransactionNo");
        if (success && (transactionNo == null || !transactionNo.matches("[0-9]{1,100}") || transactionNo.matches("0+"))) {
            return reply("99", "Invalid transaction reference");
        }
        if (success) {
            locks.acquire("vnpay-transaction:" + transactionNo);
            if (payments.existsByProviderAndProviderRefAndStatusIn("VNPAY", transactionNo, java.util.List.of("SUCCESS", "REVIEW"))) {
                return reply("99", "Transaction reference already used");
            }
        }
        boolean review = success && (lateSuccess || order.getStatus() != OrderStatus.PENDING_PAYMENT || order.isPriceFinalized()
                || payments.existsByOrderIdAndStatusIn(order.getId(), java.util.List.of("SUCCESS", "REVIEW")));
        payment.setStatus(review ? "REVIEW" : success ? "SUCCESS" : "FAILED");
        payment.setProviderRef(success ? transactionNo : null);
        payment.setResponseCode(result);
        payment.setTransactionStatus(state);
        payment.setCompletedAt(Instant.now());
        if (success) {
            if (review) {
                payment.setInventoryStatus("REVIEW");
                // Không hồi sinh đơn đã hủy hoặc chốt giá của attempt cũ; giữ tiền/snapshot để đối soát thủ công.
                payments.flush();
                return reply("00", "Funds recorded for reconciliation");
            }
            PaymentPricingService.finalizePrices(order, payment.getPriceSnapshot());
            payment.setInventoryStatus("PENDING");
            // Giá/tiền/outbox cùng transaction; đơn chỉ PAID sau commit kho nguyên tử, áp dụng cả REGULAR lẫn AUCTION.
            enqueue(order.getId(), payment.getId(), "COMMIT");
            payments.flush();
            return reply("00", "Confirm Success");
        }
        // FAILED xác định giữ đơn chờ để retry; AUCTION giữ phiếu tới deadline, regular release đúng ref attempt.
        if (!auction) { enqueue(order.getId(), payment.getInventoryRefId(), "RELEASE"); }
        notifications.enqueue(order, "PAYMENT_FAILED");
        payments.flush();
        return reply("00", "Confirm Success");
    }

    private void enqueue(UUID order, UUID target, String action) {
        jdbc.update("INSERT INTO payment_inventory_tasks(id,order_id,target_id,action) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), order, target, action);
    }

    /** Chiếu trạng thái cho client; cả replay/GET/caller URL cũ đều kiểm tra REVIEW trên toàn bộ đơn.
     * Attempt khác đã thu tiền muộn thì không được tiếp tục cấp URL dù attempt đang xem vẫn PENDING.
     * Đây là trạng thái trả cho client, không thay đổi entity/DB hay tạo thêm tác vụ kho.
     */
    private PaymentResponse response(Payment p) {
        boolean expired = "PENDING".equals(p.getStatus()) && p.getExpiresAt() != null
                && !p.getExpiresAt().isAfter(Instant.now());
        // SUCCESS của provider chưa đủ để giao hàng: client tiếp tục đợi kho hoặc hiển thị cần đối soát, không mời trả lần hai.
        boolean review = "REVIEW".equals(p.getInventoryStatus())
                || payments.existsByOrderIdAndInventoryStatus(p.getOrderId(), "REVIEW");
        String status = review ? "REQUIRES_REVIEW"
                : "PENDING".equals(p.getInventoryStatus()) ? "PROCESSING" : expired ? "EXPIRED" : p.getStatus();
        return new PaymentResponse(p.getId(), p.getOrderId(), status, p.getAmount(),
                "VND", review || expired || !"PENDING".equals(p.getStatus()) ? null : p.getPaymentUrl(), p.getExpiresAt(),
                p.getPriceSnapshot(), p.getInventoryRefId());
    }
    private VnpayIpnResponse reply(String code, String message) { return new VnpayIpnResponse(code, message); }
}
