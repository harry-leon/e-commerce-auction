package com.nexus.commerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.dto.request.CheckoutPreviewRequest;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.CheckoutPreviewResponse;
import com.nexus.commerce.dto.response.CreateOrderResponse;
import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.entity.CartItem;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.enums.CartStatus;
import com.nexus.commerce.repository.CartItemRepository;
import com.nexus.commerce.repository.CartRepository;
import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tạo đơn: khóa key/buyer → validate giỏ hoặc SKU Mua ngay → lưu giá dự kiến → giữ kho COD.
 * Chỉ checkout giỏ mới xóa item đã mua; Mua ngay dùng dòng tạm, không thay đổi giỏ.
 * VNPAY chỉ tạo đơn, buyer gọi POST /payments để tính giá mới/giữ kho theo attempt; chưa ghi giá chính thức.
 * PostgreSQL commit toàn bộ đơn/items/giỏ; rollback gọi release theo orderId kể cả lỗi commit hoặc reserve timeout.
 * Nếu process chết hoặc release lỗi mạng, TTL 15 phút của Fulfillment là phương án hoàn kho cuối; chưa có outbox retry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final OrderRepository orders;
    private final CheckoutLock locks;
    private final CatalogClient catalog;
    private final InventoryClient inventory;
    private final CommerceProperties properties;
    private final ObjectMapper mapper;
    private final PaymentService payments;
    private final NotificationOutbox notifications;

    /** Preview dùng giá hiện tại, cảnh báo đổi giá/thiếu kho; không thay đổi giỏ hoặc tạo reservation. */
    @Transactional(readOnly = true)
    public CheckoutPreviewResponse preview(UUID buyer, CheckoutPreviewRequest request) {
        rejectVoucher(request.voucherCode());
        // Mua ngay tạo dòng tạm trong bộ nhớ, không đọc/ghi giỏ; giá và tồn vẫn kiểm tra bằng service thật.
        Cart cart = request.buyNow() != null ? null : carts.findByUserIdAndStatus(buyer, CartStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));
        var selected = request.buyNow() != null ? directItems(request.buyNow(), request.itemIds())
                : select(cart, cartItems.findByCartId(cart.getId()), request.itemIds());
        var lines = snapshots(buyer, selected);
        List<String> warnings = new ArrayList<>();
        boolean orderable = true;
        for (int i = 0; i < selected.size(); i++) {
            var line = lines.get(i);
            if (line.cartUnitPrice() != null && line.unitPrice().compareTo(line.cartUnitPrice()) != 0) {
                warnings.add("Giá SKU " + line.skuCode() + " đã thay đổi; đơn sẽ dùng giá hiện tại");
            }
            if (inventory.available(line.skuId()) < line.quantity()) {
                warnings.add("Không đủ tồn kho cho SKU " + line.skuCode());
                orderable = false;
            }
        }
        BigDecimal subtotal = subtotal(lines);
        return new CheckoutPreviewResponse(subtotal, ZERO, shippingFee(), ZERO, subtotal.add(shippingFee()),
                null, lines, orderable, warnings);
    }

    /**
     * Tạo đơn REGULAR/COD hoặc VNPAY, key bắt buộc UUID. Replay trước khi đọc giỏ vì lần đầu đã xóa items.
     * Hash bao gồm buyer/nội dung và sắp xếp item IDs để thứ tự chọn không làm phát sinh xung đột giả.
     */
    @Transactional
    public CreateOrderResponse create(UUID buyer, String key, CreateOrderRequest request, String token) {
        return create(buyer, key, request, token, "127.0.0.1");
    }

    /** Biến thể HTTP nhận IP peer để ký URL VNPAY; cùng transaction đơn, phiếu giữ kho và Payment. */
    @Transactional
    public CreateOrderResponse create(UUID buyer, String key, CreateOrderRequest request, String token, String ip) {
        return create(buyer, key, request, token, ip, null);
    }

    /** Snapshot email từ JWT; replay giữ người nhận đã lưu và không tạo notification lặp. */
    @Transactional
    public CreateOrderResponse create(UUID buyer, String key, CreateOrderRequest request, String token, String ip, String email) {
        String normalizedKey = normalizeKey(key);
        String hash = fingerprint(buyer, request);
        locks.acquire("key:" + normalizedKey);
        var previous = orders.findByIdempotencyKey(normalizedKey);
        if (previous.isPresent()) {
            Order order = previous.get();
            if (!buyer.equals(order.getBuyerId()) || !hash.equals(order.getRequestHash())) {
                throw new BusinessException(ErrorCode.ORDER_IDEMPOTENCY_CONFLICT);
            }
            return response(order, ip);
        }
        // Khóa buyer giữ hạn mức ngày và các key khác nhau của cùng giỏ nhất quán trên nhiều instance.
        locks.acquire("buyer:" + buyer);
        validateOptions(request);
        Instant start = Instant.now().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
                .atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
        if (orders.countByBuyerIdAndCreatedAtGreaterThanEqual(buyer, start) >= properties.getOrder().getMaxDailyOrders()) {
            throw invalid("ORDER_DAILY_LIMIT_EXCEEDED", "Đã đạt giới hạn số đơn trong ngày");
        }
        // Mua trực tiếp độc lập với giỏ, kể cả khi SKU này đã có số lượng khác trong giỏ.
        Cart cart = request.buyNow() != null ? null : carts.findActiveForCheckout(buyer)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));
        var selected = request.buyNow() != null ? directItems(request.buyNow(), request.selectedItemIds())
                : select(cart, cartItems.findForCheckout(cart.getId()), request.selectedItemIds());
        var lines = snapshots(buyer, selected);
        // Kiểm tra kho thật trước khi ghi đơn/xóa giỏ cho cả COD và VNPAY; chưa reserve ở bước này.
        // Kho vẫn có thể thay đổi sau kiểm tra, nên reserve của COD/Payment tiếp tục là bước quyết định cuối.
        for (var line : lines) {
            int available = inventory.available(line.skuId());
            if (available < line.quantity()) {
                throw new ApiException("CHECKOUT_OUT_OF_STOCK", HttpStatus.CONFLICT,
                        "Sản phẩm \"" + line.productName() + "\" "
                                + (available <= 0 ? "đã hết hàng" : "chỉ còn " + available + " sản phẩm")
                                + ". Vui lòng cập nhật giỏ hàng trước khi đặt.", null);
            }
        }
        Order order = buildOrder(buyer, normalizedKey, hash, request, lines);
        order.setBuyerEmail(email == null || email.isBlank() ? null : email);
        // Flush trước gọi kho để lỗi constraint/schema không gây reservation thừa; vẫn chưa commit đơn.
        order = orders.saveAndFlush(order);
        UUID orderId = order.getId();
        // Đăng ký trước reserve: timeout HTTP có thể xảy ra sau khi Fulfillment đã giữ kho thành công.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK && "COD".equals(request.paymentMethod())) {
                    try {
                        inventory.release(orderId, token);
                    } catch (RuntimeException cleanupError) {
                        log.error("Không hoàn kho được cho order {}; chờ TTL Fulfillment", orderId, cleanupError);
                    }
                }
                if (status == STATUS_UNKNOWN) {
                    // Không release khi chưa biết commit có thành công: tránh bán lại hàng của đơn đã lưu.
                    log.error("Chưa xác định kết quả commit order {}; cần đối soát trước TTL kho", orderId);
                }
            }
        });
        // COD giữ luồng giữ kho theo đơn; VNPAY giữ theo attempt riêng ở POST /payments để retry không dùng lại phiếu đã release.
        if ("COD".equals(order.getPaymentMethod())) {
            order.setReservationIds(new ArrayList<>(inventory.reserve(orderId,
                    selected.stream().map(i -> new InventoryClient.Item(i.getSkuId(), null, i.getQuantity())).toList(), token)));
        }
        // Chỉ checkout từ giỏ mới xóa dòng đã mua; Mua ngay không thay đổi bất kỳ số lượng/trạng thái giỏ nào.
        if (cart != null) {
        cart.getItems().removeIf(i -> selected.stream().anyMatch(s -> s.getId().equals(i.getId())));
        cartItems.deleteAll(selected);
        cartItems.flush();
        if (cartItems.findByCartId(cart.getId()).isEmpty()) { cart.setStatus(CartStatus.CONVERTED); }
        }
        // Snapshot cùng transaction; worker chỉ gửi sau commit, rollback không tạo thông báo ma.
        notifications.enqueue(order, "ORDER_CREATED");
        return response(order, ip);
    }

    /** Chặn payload trộn hai chế độ; dòng Mua ngay chỉ dùng nội bộ và tuyệt đối không persist thành CartItem. */
    private List<CartItem> directItems(com.nexus.commerce.dto.request.BuyNowRequest target, List<UUID> ids) {
        if (ids != null || target.skuId() == null || target.quantity() < 1) {
            throw invalid("CHECKOUT_INVALID_ITEMS", "Mua ngay cần một SKU/số lượng hợp lệ, không kèm item giỏ");
        }
        return List.of(CartItem.builder().skuId(target.skuId()).quantity(target.quantity()).build());
    }

    /** Chặn giỏ hết hạn, chọn trùng hoặc item thuộc giỏ khác trước khi gọi service kho. */
    private List<CartItem> select(Cart cart, List<CartItem> items, List<UUID> ids) {
        if (cart.getExpiresAt() == null || !cart.getExpiresAt().isAfter(Instant.now())) {
            throw invalid("CHECKOUT_EXPIRED", "Giỏ hàng đã hết hạn");
        }
        if (ids != null && (ids.isEmpty() || new HashSet<>(ids).size() != ids.size())) {
            throw invalid("CHECKOUT_INVALID_ITEMS", "Danh sách item rỗng hoặc trùng lặp");
        }
        var selected = ids == null ? items : items.stream().filter(i -> ids.contains(i.getId())).toList();
        if (ids != null && selected.size() != ids.size()) { throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND); }
        if (selected.isEmpty()) { throw invalid("CHECKOUT_EMPTY_CART", "Không thể đặt giỏ hàng rỗng"); }
        if (selected.size() > properties.getCart().getMaxItemsPerCart()) {
            throw new BusinessException(ErrorCode.CART_MAX_ITEMS_EXCEEDED);
        }
        return selected;
    }

    /** Giá/tên/seller chỉ tin Catalog; chặn tự mua, nhiều seller, quantity sai và số tiền vượt numeric(15,2). */
    private List<CheckoutPreviewResponse.Line> snapshots(UUID buyer, List<CartItem> selected) {
        var details = catalog.getSkuDetails(selected.stream().map(CartItem::getSkuId).toList());
        UUID seller = null;
        List<CheckoutPreviewResponse.Line> lines = new ArrayList<>();
        for (CartItem item : selected) {
            SkuInfo sku = details.get(item.getSkuId());
            if (sku == null || !Boolean.TRUE.equals(sku.isActive())) { throw new BusinessException(ErrorCode.CART_SKU_NOT_AVAILABLE); }
            if (sku.sellerId() == null || sku.price() == null || sku.price().signum() < 0
                    || sku.productName() == null) {
                throw new ApiException("CATALOG_INVALID_RESPONSE", HttpStatus.BAD_GATEWAY, "Catalog thiếu snapshot SKU", null);
            }
            if (buyer.equals(sku.sellerId())) { throw new BusinessException(ErrorCode.ORDER_SELLER_CANNOT_BUY_OWN_PRODUCT); }
            if (seller != null && !seller.equals(sku.sellerId())) {
                throw invalid("CHECKOUT_MULTIPLE_SELLERS", "Mỗi đơn chỉ hỗ trợ một shop; hãy chọn sản phẩm cùng shop");
            }
            seller = sku.sellerId();
            if (item.getQuantity() == null || item.getQuantity() < 1) { throw invalid("CHECKOUT_INVALID_QUANTITY", "Số lượng không hợp lệ"); }
            BigDecimal price = sku.price().setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = price.multiply(BigDecimal.valueOf(item.getQuantity()));
            if (price.precision() > 15 || total.precision() > 15) { throw invalid("CHECKOUT_AMOUNT_EXCEEDED", "Giá trị đơn vượt giới hạn"); }
            // Sau HG-S2-6 Catalog không còn trả skuCode; fallback theo prefix skuId để snapshot đơn vẫn nhất quán.
            String resolvedSkuCode = SkuInfo.resolveSkuCode(item.getSkuId(), sku.skuCode());
            lines.add(new CheckoutPreviewResponse.Line(item.getSkuId(), sku.sellerId(), sku.productName(), resolvedSkuCode, price,
                    item.getUnitPrice(), item.getQuantity(), total));
        }
        return lines;
    }

    /** Tạo thông tin đơn và giá dự kiến; các cột giá chính thức để null tới khi backend xác nhận thu tiền. */
    private Order buildOrder(UUID buyer, String key, String hash, CreateOrderRequest request,
                             List<CheckoutPreviewResponse.Line> lines) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNo("ORD-" + order.getId());
        order.setBuyerId(buyer);
        // Lấy seller từ cùng snapshot đã validate, không tải Catalog lần hai gây lệch dữ liệu.
        order.setSellerId(lines.getFirst().sellerId());
        order.setSourceType(OrderSourceType.REGULAR);
        // Enum dùng chung với quản lý đơn; DB vẫn lưu tên trạng thái theo Flyway.
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setEstimatedSubtotal(subtotal(lines));
        // Ưu tiên phí vận chuyển thực tế từ hãng vận chuyển do client gửi lên, fallback phí cấu hình hệ thống
        BigDecimal resolvedShipping = resolveShippingFee(request);
        order.setEstimatedShippingFee(resolvedShipping);
        order.setEstimatedDiscount(ZERO);
        order.setEstimatedTotal(order.getEstimatedSubtotal().add(resolvedShipping));
        order.setPriceFinalized(false);
        order.setSubtotal(null);
        order.setShippingFee(null);
        order.setDiscount(null);
        order.setTotal(null);
        if (order.getEstimatedTotal().precision() > 15) { throw invalid("CHECKOUT_AMOUNT_EXCEEDED", "Giá trị đơn vượt giới hạn"); }
        order.setShippingAddress(request.shippingAddress());
        order.setRecipientName(request.recipientName());
        order.setRecipientPhone(request.recipientPhone());
        order.setPaymentMethod(request.paymentMethod());
        order.setIdempotencyKey(key);
        order.setRequestHash(hash);
        order.setCreatedAt(Instant.now());
        for (var line : lines) {
            var item = new OrderItem();
            item.setId(UUID.randomUUID());
            item.setSkuId(line.skuId());
            item.setProductName(line.productName());
            item.setSkuCode(line.skuCode());
            item.setEstimatedUnitPrice(line.unitPrice());
            item.setUnitPrice(null);
            item.setQuantity(line.quantity());
            order.addItem(item);
        }
        return order;
    }

    /** Tạo draft chỉ nhận COD/VNPAY và REGULAR; merchant kiểm tra ở POST /payments, voucher chưa hỗ trợ. */
    private void validateOptions(CreateOrderRequest request) {
        rejectVoucher(request.voucherCode());
        if (!"COD".equals(request.paymentMethod()) && !"VNPAY".equals(request.paymentMethod())) {
            throw new BusinessException(ErrorCode.PAYMENT_METHOD_UNSUPPORTED);
        }
        // Tạo draft không cần merchant; cấu hình và quyền gọi kho được kiểm tra trước khi tạo Payment Attempt.
        if ((request.sourceType() != null && !"REGULAR".equals(request.sourceType())) || request.auctionId() != null) {
            throw invalid("CHECKOUT_SOURCE_UNSUPPORTED", "Checkout giỏ hàng chỉ hỗ trợ đơn REGULAR");
        }
    }

    private void rejectVoucher(String code) {
        if (code != null && !code.isBlank()) { throw invalid("CHECKOUT_VOUCHER_UNSUPPORTED", "Chưa hỗ trợ voucher khi checkout"); }
    }

    /** Phí cố định cấu hình (mặc định miễn phí); chưa tính theo khoảng cách/cân nặng, VAT đã gồm trong giá Catalog. */
    private BigDecimal shippingFee() { return properties.getOrder().getShippingFee().setScale(2, RoundingMode.HALF_UP); }

    /**
     * Xác định phí vận chuyển áp dụng cho đơn hàng:
     * Chức năng: Ưu tiên sử dụng phí ship thực tế từ hãng vận chuyển (GHN/GHTK) do client gửi lên,
     *            nếu client không truyền thì fallback về phí cấu hình hệ thống.
     * Đầu vào: CreateOrderRequest request.
     * Kết quả: BigDecimal phí vận chuyển đã làm tròn 2 chữ số thập phân (HALF_UP).
     * Quy tắc: Phí ship không được âm; bảo toàn tính đúng đắn của tổng tiền đơn hàng.
     */
    private BigDecimal resolveShippingFee(CreateOrderRequest request) {
        if (request.shippingFee() != null && request.shippingFee().compareTo(BigDecimal.ZERO) >= 0) {
            return request.shippingFee().setScale(2, RoundingMode.HALF_UP);
        }
        return shippingFee();
    }

    private BigDecimal subtotal(List<CheckoutPreviewResponse.Line> lines) {
        return lines.stream().map(CheckoutPreviewResponse.Line::itemTotal).reduce(ZERO, BigDecimal::add);
    }

    private CreateOrderResponse response(Order order, String ip) {
        // Không tự tạo attempt khi tạo/replay đơn: FE phải xác nhận bước thanh toán riêng qua POST /payments.
        return new CreateOrderResponse(order.getId(), order.getOrderNo(), order.getStatus().name(), order.getSubtotal(),
                order.getShippingFee(), order.getDiscount(), order.getTotal(), order.getPaymentMethod(), null,
                order.getEstimatedSubtotal(), order.getEstimatedShippingFee(), order.getEstimatedDiscount(),
                order.getEstimatedTotal(), order.isPriceFinalized());
    }

    private String normalizeKey(String key) {
        try {
            String normalized = UUID.fromString(key).toString();
            if (!normalized.equalsIgnoreCase(key)) { throw new IllegalArgumentException(); }
            return normalized;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw invalid("CHECKOUT_INVALID_IDEMPOTENCY_KEY", "Idempotency-Key phải là UUID hợp lệ");
        }
    }

    /** Hash JSON có thứ tự field cố định, không lưu dữ liệu nhạy cảm trong khóa hoặc log. */
    private String fingerprint(UUID buyer, CreateOrderRequest request) {
        var canonical = new CreateOrderRequest(request.shippingAddress(), request.recipientName(), request.recipientPhone(),
                request.voucherCode(), request.paymentMethod(), Objects.requireNonNullElse(request.sourceType(), "REGULAR"),
                request.auctionId(), request.selectedItemIds() == null ? null : request.selectedItemIds().stream().sorted().toList(),
                request.buyNow(), request.shippingFee(), request.carrier());
        try {
            // Bỏ field mới khi không dùng để key checkout giỏ đã lưu trước nâng cấp vẫn replay đúng hash cũ.
            com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.valueToTree(canonical);
            if (request.buyNow() == null) { payload.remove("buyNow"); }
            if (request.shippingFee() == null) { payload.remove("shippingFee"); }
            if (request.carrier() == null) { payload.remove("carrier"); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(List.of(buyer, payload))));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Không thể tạo fingerprint checkout", error);
        }
    }

    private ApiException invalid(String code, String message) {
        return new ApiException(code, HttpStatus.UNPROCESSABLE_ENTITY, message, null);
    }
}
