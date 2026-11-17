package com.nexus.commerce.service.impl;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.config.CommerceProperties;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.dto.mapper.CartMapper;
import com.nexus.commerce.dto.request.AddToCartRequest;
import com.nexus.commerce.dto.request.MergeCartRequest;
import com.nexus.commerce.dto.request.UpdateCartItemRequest;
import com.nexus.commerce.dto.response.CartResponse;
import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.entity.CartItem;
import com.nexus.commerce.enums.CartStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.commerce.repository.CartItemRepository;
import com.nexus.commerce.repository.CartRepository;
import com.nexus.commerce.service.CartService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production implementation of the CartService interface.
 * Implements SRS §3.4.1 requirements and Business Rules:
 * - BR-CFG-14: At most 1 active cart per user.
 * - BR-CFG-15: 7-day cart expiration window (extended upon each interaction).
 * - BR-CFG-16: Guest cart merging upon authentication.
 * - Giá/trạng thái SKU lấy từ Catalog; tồn kho khả dụng lấy từ Fulfillment để chặn cộng dồn vượt tồn.
 * - Cart không giữ hàng (reserve): số lượng thật vẫn được kiểm tra lại và giữ kho nguyên tử khi checkout.
 */
@Service
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CartMapper cartMapper;
    private final CatalogClient catalogClient;
    // Đọc tồn khả dụng thật của Fulfillment để giỏ không hiển thị/chấp nhận số lượng vượt kho.
    private final InventoryClient inventoryClient;
    // Cấu hình tham số commerce từ Spring Cloud Config Server / application.yml
    private final CommerceProperties properties;

    /**
     * Constructor chính được Spring IoC container inject dependencies.
     * Quy tắc & lý do: Đánh dấu @Autowired rõ ràng để Spring Boot nhận diện chính xác constructor
     * khi khởi tạo bean, tránh lỗi NoSuchMethodException (do có 2 constructors mà thiếu @Autowired).
     *
     * @param cartRepository repository truy xuất thông tin giỏ hàng
     * @param cartItemRepository repository truy xuất các sản phẩm trong giỏ
     * @param cartMapper mapper chuyển đổi entity giỏ hàng sang DTO response
     * @param catalogClient Feign/Http client gọi Catalog Service lấy thông tin SKU/giá
     * @param inventoryClient Http client gọi Fulfillment Service lấy tồn kho thực tế
     * @param properties cấu hình tham số commerce
     */
    @Autowired
    public CartServiceImpl(CartRepository cartRepository, CartItemRepository cartItemRepository,
            CartMapper cartMapper, CatalogClient catalogClient, InventoryClient inventoryClient,
            CommerceProperties properties) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.cartMapper = cartMapper;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
        this.properties = properties;
    }

    /**
     * Constructor tương thích ngược cho unit test khi không inject InventoryClient (mặc định null).
     */
    public CartServiceImpl(CartRepository cartRepository, CartItemRepository cartItemRepository,
            CartMapper cartMapper, CatalogClient catalogClient, CommerceProperties properties) {
        this(cartRepository, cartItemRepository, cartMapper, catalogClient, null, properties);
    }

    @Override
    @Transactional
    public CartResponse getActiveCart(UUID userId) {
        Cart cart = getOrCreateActiveCart(userId);
        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse addItem(UUID userId, AddToCartRequest request) {
        Cart cart = getOrCreateActiveCart(userId);
        processAddItem(cart, request.skuId(), request.quantity());
        renewCartExpiration(cart);
        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItemQuantity(UUID userId, UUID itemId, UpdateCartItemRequest request) {
        Cart cart = getOrCreateActiveCart(userId);

        CartItem cartItem = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found with id: " + itemId));

        // Enforce cart ownership
        if (!Objects.equals(cartItem.getCart().getId(), cart.getId())) {
            throw new BusinessException("UNAUTHORIZED_ACCESS", "Cart item does not belong to the active user cart");
        }

        // SKU phải còn bán khi sửa giỏ; Catalog không sở hữu tồn kho nên kiểm tra tồn khả dụng từ Fulfillment.
        SkuInfo skuInfo = catalogClient.getSkuInfo(cartItem.getSkuId());
        if (skuInfo == null || !Boolean.TRUE.equals(skuInfo.isActive())) {
            throw new BusinessException("PRODUCT_UNAVAILABLE", "SKU không tồn tại hoặc đã ngừng bán");
        }

        // Chức năng: Kiểm tra tồn kho khả dụng từ Fulfillment trước khi cập nhật số lượng dòng giỏ.
        // Đầu vào: cartItem.getSkuId(), request.quantity().
        // Luồng: Gọi inventoryClient.available() -> nếu lỗi mạng thì fallback sang skuInfo.stockQuantity().
        // Quy tắc: Chặn không cho cập nhật vượt quá tồn khả dụng thật trong kho.
        int availableStock = -1;
        if (inventoryClient != null) {
            try {
                availableStock = inventoryClient.available(cartItem.getSkuId());
            } catch (Exception ex) {
                // Fulfillment không phản hồi: fallback sang stockQuantity của skuInfo nếu có
            }
        }
        if (availableStock < 0 && skuInfo != null && skuInfo.stockQuantity() != null) {
            availableStock = skuInfo.stockQuantity();
        }

        if (availableStock >= 0 && request.quantity() > availableStock) {
            String name = skuInfo != null && skuInfo.productName() != null ? skuInfo.productName() : "Sản phẩm";
            throw new BusinessException("INSUFFICIENT_STOCK",
                    "Sản phẩm \"" + name + "\" chỉ còn " + availableStock + " sản phẩm. Vui lòng cập nhật giỏ hàng.");
        }

        cartItem.setQuantity(request.quantity());
        if (skuInfo != null && skuInfo.price() != null) {
            cartItem.setUnitPrice(skuInfo.price());
        }

        cartItemRepository.save(cartItem);
        renewCartExpiration(cart);

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(UUID userId, UUID itemId) {
        Cart cart = getOrCreateActiveCart(userId);

        CartItem cartItem = cartItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found with id: " + itemId));

        if (!Objects.equals(cartItem.getCart().getId(), cart.getId())) {
            throw new BusinessException("UNAUTHORIZED_ACCESS", "Cart item does not belong to the active user cart");
        }

        cart.getItems().remove(cartItem);
        cartItemRepository.delete(cartItem);
        renewCartExpiration(cart);

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public void clearCart(UUID userId) {
        Cart cart = getOrCreateActiveCart(userId);
        cartItemRepository.deleteByCartId(cart.getId());
        cart.getItems().clear();
        renewCartExpiration(cart);
    }

    @Override
    @Transactional
    public CartResponse mergeCart(UUID userId, MergeCartRequest request) {
        Cart cart = getOrCreateActiveCart(userId);

        if (request != null && request.items() != null) {
            for (AddToCartRequest itemReq : request.items()) {
                try {
                    processAddItem(cart, itemReq.skuId(), itemReq.quantity());
                } catch (BusinessException ex) {
                    // Skip unavailable or out-of-stock guest items gracefully during merge
                }
            }
        }

        renewCartExpiration(cart);
        return buildCartResponse(cart);
    }

    private void processAddItem(Cart cart, UUID skuId, int quantity) {
        SkuInfo skuInfo = catalogClient.getSkuInfo(skuId);
        if (skuInfo == null || !Boolean.TRUE.equals(skuInfo.isActive())) {
            throw new BusinessException("PRODUCT_UNAVAILABLE", "Requested product SKU is inactive or does not exist");
        }

        CartItem cartItem = cartItemRepository.findByCartIdAndSkuId(cart.getId(), skuId)
                .orElse(null);

        int targetQuantity = quantity;
        if (cartItem != null) {
            targetQuantity += cartItem.getQuantity();
        }

        // Chức năng: Đọc tồn kho khả dụng từ Fulfillment để kiểm tra và chặn cộng dồn vượt tồn kho.
        // Đầu vào: skuId, targetQuantity (số lượng hiện tại trong giỏ + số lượng thêm mới).
        // Luồng: Gọi inventoryClient.available(skuId) -> nếu Fulfillment không phản hồi thì fallback sang skuInfo.stockQuantity().
        // Quy tắc: Nếu tổng số lượng yêu cầu > tồn kho khả dụng thì từ chối ngay lập tức với thông báo chi tiết số lượng hiện có trong giỏ.
        int availableStock = -1;
        if (inventoryClient != null) {
            try {
                availableStock = inventoryClient.available(skuId);
            } catch (Exception ex) {
                // Fulfillment không phản hồi: fallback sang stockQuantity của skuInfo nếu có
            }
        }
        if (availableStock < 0 && skuInfo.stockQuantity() != null) {
            availableStock = skuInfo.stockQuantity();
        }

        if (availableStock >= 0 && targetQuantity > availableStock) {
            String name = skuInfo.productName() != null ? skuInfo.productName() : "Sản phẩm";
            int inCart = cartItem != null ? cartItem.getQuantity() : 0;
            if (inCart > 0) {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Sản phẩm \"" + name + "\" chỉ còn " + availableStock + " sản phẩm. Bạn đã có " + inCart + " sản phẩm trong giỏ hàng.");
            } else {
                throw new BusinessException("INSUFFICIENT_STOCK",
                        "Sản phẩm \"" + name + "\" chỉ còn " + availableStock + " sản phẩm (yêu cầu " + targetQuantity + ").");
            }
        }

        if (cartItem == null) {
            if (cart.getItems() != null && cart.getItems().size() >= properties.getCart().getMaxItemsPerCart()) {
                throw new BusinessException("CART_MAX_ITEMS_EXCEEDED",
                        "Giỏ hàng đã đạt giới hạn tối đa " + properties.getCart().getMaxItemsPerCart() + " sản phẩm");
            }
            cartItem = CartItem.builder()
                    .cart(cart)
                    .skuId(skuId)
                    .quantity(quantity)
                    .unitPrice(skuInfo.price())
                    .build();
            cart.getItems().add(cartItem);
        } else {
            cartItem.setQuantity(targetQuantity);
            cartItem.setUnitPrice(skuInfo.price());
        }

        cartItemRepository.save(cartItem);
    }

    /**
     * Retrieves the existing active cart for the user or provisions a new one.
     * Enforces the 7-day expiration policy (BR-CFG-15) and unique active cart (BR-CFG-14).
     */
    private Cart getOrCreateActiveCart(UUID userId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .map(cart -> {
                    if (cart.getExpiresAt() != null && Instant.now().isAfter(cart.getExpiresAt())) {
                        cart.setStatus(CartStatus.EXPIRED);
                        cartRepository.save(cart);
                        return createNewActiveCart(userId);
                    }
                    return cart;
                })
                .orElseGet(() -> createNewActiveCart(userId));
    }

    private Cart createNewActiveCart(UUID userId) {
        Cart newCart = Cart.builder()
                .userId(userId)
                .status(CartStatus.ACTIVE)
                .expiresAt(Instant.now().plus(properties.getCart().getExpirationDays(), ChronoUnit.DAYS))
                .build();
        return cartRepository.save(newCart);
    }

    private void renewCartExpiration(Cart cart) {
        cart.setExpiresAt(Instant.now().plus(properties.getCart().getExpirationDays(), ChronoUnit.DAYS));
        cartRepository.save(cart);
    }

    /**
     * Xây dựng CartResponse hoàn chỉnh kèm chi tiết Catalog và tồn kho khả dụng từ Fulfillment.
     * Chức năng: Đồng bộ giá mới nhất và số lượng tồn khả dụng cho từng dòng giỏ hàng.
     * Đầu vào: cart (Cart entity).
     * Luồng: Lấy danh sách skuIds -> gọi CatalogClient đọc giá/thông tin -> gọi InventoryClient đọc tồn kho hàng loạt -> map qua CartMapper.
     * Lỗi: Nếu inventoryClient ném ngoại lệ kết nối, bỏ qua để không gián đoạn giao diện xem giỏ hàng của người dùng.
     */
    private CartResponse buildCartResponse(Cart cart) {
        List<UUID> skuIds = cart.getItems().stream()
                .map(CartItem::getSkuId)
                .toList();
        Map<UUID, SkuInfo> skuMap = catalogClient.getSkuDetails(skuIds);
        Map<UUID, Integer> stockMap = null;
        if (inventoryClient != null && !skuIds.isEmpty()) {
            try {
                stockMap = inventoryClient.availability(skuIds);
            } catch (Exception ex) {
                // Bỏ qua lỗi kho khi hiển thị giỏ hàng để không chặn người dùng xem giỏ oan
            }
        }
        return cartMapper.toCartResponse(cart, skuMap, stockMap);
    }
}
