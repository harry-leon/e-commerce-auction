package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.nexus.commerce.repository.CartItemRepository;
import com.nexus.commerce.repository.CartRepository;
import com.nexus.commerce.service.impl.CartServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private CatalogClient catalogClient;

    private CartMapper cartMapper;
    private CartServiceImpl cartService;

    private final UUID userId = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cartMapper = new CartMapper();
        cartService = new CartServiceImpl(cartRepository, cartItemRepository, cartMapper, catalogClient, new CommerceProperties());
    }

    @Test
    void getActiveCart_createsNewCartIfNoneExists() {
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart c = invocation.getArgument(0);
            c.setId(cartId);
            return c;
        });

        CartResponse response = cartService.getActiveCart(userId);

        assertNotNull(response);
        assertEquals(cartId, response.cartId());
        assertEquals(userId, response.userId());
        assertEquals(CartStatus.ACTIVE, response.status());
        assertEquals(0, response.totalItems());
        assertEquals(BigDecimal.ZERO.setScale(2), response.subtotal());
        verify(cartRepository, times(1)).save(any(Cart.class));
    }

    @Test
    void getActiveCart_expiresOldCartAndCreatesNew() {
        Cart expiredCart = Cart.builder()
                .id(cartId)
                .userId(userId)
                .status(CartStatus.ACTIVE)
                .expiresAt(Instant.now().minus(8, ChronoUnit.DAYS))
                .build();

        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(expiredCart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart c = invocation.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });

        CartResponse response = cartService.getActiveCart(userId);

        assertNotNull(response);
        assertEquals(CartStatus.EXPIRED, expiredCart.getStatus());
        verify(cartRepository, times(2)).save(any(Cart.class));
    }

    @Test
    void addItem_successNewItem() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        SkuInfo skuInfo = new SkuInfo(skuId, "SKU-01", "Nike Shoe", "thumb.jpg", new BigDecimal("100.00"), 50, true);
        when(catalogClient.getSkuInfo(skuId)).thenReturn(skuInfo);
        when(catalogClient.getSkuDetails(any())).thenReturn(Map.of(skuId, skuInfo));
        when(cartItemRepository.findByCartIdAndSkuId(cartId, skuId)).thenReturn(Optional.empty());

        CartResponse response = cartService.addItem(userId, new AddToCartRequest(skuId, 2));

        assertNotNull(response);
        assertEquals(1, response.totalItems());
        assertEquals(2, response.totalQuantity());
        assertEquals(new BigDecimal("200.00"), response.subtotal());
        verify(cartItemRepository, times(1)).save(any(CartItem.class));
    }

    @Test
    void addItem_accumulatesQuantityWhenSkuAlreadyInCart() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        CartItem existingItem = CartItem.builder().id(UUID.randomUUID()).cart(cart).skuId(skuId).quantity(2).build();
        cart.getItems().add(existingItem);

        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        SkuInfo skuInfo = new SkuInfo(skuId, "SKU-01", "Nike Shoe", "thumb.jpg", new BigDecimal("100.00"), 50, true);
        when(catalogClient.getSkuInfo(skuId)).thenReturn(skuInfo);
        when(catalogClient.getSkuDetails(any())).thenReturn(Map.of(skuId, skuInfo));
        when(cartItemRepository.findByCartIdAndSkuId(cartId, skuId)).thenReturn(Optional.of(existingItem));

        CartResponse response = cartService.addItem(userId, new AddToCartRequest(skuId, 3));

        assertNotNull(response);
        assertEquals(1, response.totalItems());
        assertEquals(5, response.totalQuantity());
        assertEquals(new BigDecimal("500.00"), response.subtotal());
        assertEquals(5, existingItem.getQuantity());
        verify(cartItemRepository, times(1)).save(existingItem);
    }

    @Test
    void addItem_throwsExceptionWhenStockInsufficient() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        SkuInfo skuInfo = new SkuInfo(skuId, "SKU-01", "Nike Shoe", "thumb.jpg", new BigDecimal("100.00"), 2, true);
        when(catalogClient.getSkuInfo(skuId)).thenReturn(skuInfo);

        AddToCartRequest request = new AddToCartRequest(skuId, 5);
        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.addItem(userId, request));
        assertEquals("INSUFFICIENT_STOCK", ex.getCode());
        verify(cartItemRepository, never()).save(any(CartItem.class));
    }

    @Test
    void addItem_throwsExceptionWhenSkuInactive() {
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(new Cart()));
        when(catalogClient.getSkuInfo(skuId)).thenReturn(new SkuInfo(skuId, "SKU-01", "Nike", null, BigDecimal.TEN, 10, false));

        AddToCartRequest request = new AddToCartRequest(skuId, 1);
        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.addItem(userId, request));
        assertEquals("PRODUCT_UNAVAILABLE", ex.getCode());
    }

    @Test
    void updateItemQuantity_success() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        UUID itemId = UUID.randomUUID();
        CartItem item = CartItem.builder().id(itemId).cart(cart).skuId(skuId).quantity(1).build();
        cart.getItems().add(item);

        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        SkuInfo skuInfo = new SkuInfo(skuId, "SKU-01", "Nike Shoe", "thumb.jpg", new BigDecimal("100.00"), 50, true);
        when(catalogClient.getSkuInfo(skuId)).thenReturn(skuInfo);
        when(catalogClient.getSkuDetails(any())).thenReturn(Map.of(skuId, skuInfo));

        CartResponse response = cartService.updateItemQuantity(userId, itemId, new UpdateCartItemRequest(4));

        assertNotNull(response);
        assertEquals(4, item.getQuantity());
        assertEquals(new BigDecimal("400.00"), response.subtotal());
        verify(cartItemRepository, times(1)).save(item);
    }

    @Test
    void updateItemQuantity_unauthorizedAccess() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).build();
        Cart otherCart = Cart.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).build();
        UUID itemId = UUID.randomUUID();
        CartItem item = CartItem.builder().id(itemId).cart(otherCart).skuId(skuId).quantity(1).build();

        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        UpdateCartItemRequest request = new UpdateCartItemRequest(5);
        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.updateItemQuantity(userId, itemId, request));
        assertEquals("UNAUTHORIZED_ACCESS", ex.getCode());
    }

    @Test
    void removeItem_success() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        UUID itemId = UUID.randomUUID();
        CartItem item = CartItem.builder().id(itemId).cart(cart).skuId(skuId).quantity(1).build();
        cart.getItems().add(item);

        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        CartResponse response = cartService.removeItem(userId, itemId);

        assertNotNull(response);
        assertEquals(0, response.totalItems());
        verify(cartItemRepository, times(1)).delete(item);
    }

    @Test
    void clearCart_success() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        cartService.clearCart(userId);

        verify(cartItemRepository, times(1)).deleteByCartId(cartId);
        assertEquals(0, cart.getItems().size());
    }

    @Test
    void mergeCart_success() {
        Cart cart = Cart.builder().id(cartId).userId(userId).status(CartStatus.ACTIVE).items(new ArrayList<>()).build();
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        SkuInfo skuInfo = new SkuInfo(skuId, "SKU-01", "Nike Shoe", "thumb.jpg", new BigDecimal("100.00"), 50, true);
        when(catalogClient.getSkuInfo(skuId)).thenReturn(skuInfo);
        when(catalogClient.getSkuDetails(any())).thenReturn(Map.of(skuId, skuInfo));
        when(cartItemRepository.findByCartIdAndSkuId(cartId, skuId)).thenReturn(Optional.empty());

        MergeCartRequest request = new MergeCartRequest(List.of(new AddToCartRequest(skuId, 3)));
        CartResponse response = cartService.mergeCart(userId, request);

        assertNotNull(response);
        assertEquals(1, response.totalItems());
        assertEquals(3, response.totalQuantity());
        verify(cartItemRepository, times(1)).save(any(CartItem.class));
    }

    @Test
    void addItem_throwsExceptionWhenMaxItemsExceeded() {
        CommerceProperties customProps = new CommerceProperties();
        customProps.getCart().setMaxItemsPerCart(1);
        CartServiceImpl limitedCartService = new CartServiceImpl(cartRepository, cartItemRepository, cartMapper, catalogClient, customProps);

        Cart cart = Cart.builder()
                .id(cartId)
                .userId(userId)
                .status(CartStatus.ACTIVE)
                .items(new ArrayList<>(List.of(CartItem.builder().id(UUID.randomUUID()).skuId(UUID.randomUUID()).quantity(1).build())))
                .build();
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(catalogClient.getSkuInfo(skuId)).thenReturn(new SkuInfo(skuId, "SKU-01", "Nike Shoe", "thumb.jpg", new BigDecimal("100.00"), 50, true));
        when(cartItemRepository.findByCartIdAndSkuId(cartId, skuId)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> limitedCartService.addItem(userId, new AddToCartRequest(skuId, 1)));
    }
}
