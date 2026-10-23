package com.nexus.commerce.service.impl;

import com.nexus.commerce.client.CatalogClient;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production implementation of the CartService interface.
 * Implements SRS §3.4.1 requirements and Business Rules:
 * - BR-CFG-14: At most 1 active cart per user.
 * - BR-CFG-15: 7-day cart expiration window (extended upon each interaction).
 * - BR-CFG-16: Guest cart merging upon authentication.
 * - Real-time SKU existence, status, and stock availability verification.
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {



    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CartMapper cartMapper;
    private final CatalogClient catalogClient;
    // Cấu hình tham số commerce từ Spring Cloud Config Server / application.yml
    private final CommerceProperties properties;

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

        // Validate stock with Catalog
        SkuInfo skuInfo = catalogClient.getSkuInfo(cartItem.getSkuId());
        if (skuInfo != null && skuInfo.stockQuantity() != null && skuInfo.stockQuantity() < request.quantity()) {
            throw new BusinessException("INSUFFICIENT_STOCK",
                    "Requested quantity (" + request.quantity() + ") exceeds available stock (" + skuInfo.stockQuantity() + ")");
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

        if (skuInfo.stockQuantity() != null && skuInfo.stockQuantity() < targetQuantity) {
            throw new BusinessException("INSUFFICIENT_STOCK",
                    "Requested quantity (" + targetQuantity + ") exceeds available stock (" + skuInfo.stockQuantity() + ")");
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

    private CartResponse buildCartResponse(Cart cart) {
        List<UUID> skuIds = cart.getItems().stream()
                .map(CartItem::getSkuId)
                .toList();
        Map<UUID, SkuInfo> skuMap = catalogClient.getSkuDetails(skuIds);
        return cartMapper.toCartResponse(cart, skuMap);
    }
}
