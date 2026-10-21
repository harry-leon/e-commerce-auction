package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.request.AddToCartRequest;
import com.nexus.commerce.dto.request.MergeCartRequest;
import com.nexus.commerce.dto.request.UpdateCartItemRequest;
import com.nexus.commerce.dto.response.CartResponse;
import com.nexus.commerce.enums.CartStatus;
import com.nexus.commerce.service.CartService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CartController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CartService cartService;

    private final UUID userId = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private CartResponse mockCartResponse() {
        return new CartResponse(
                cartId,
                userId,
                CartStatus.ACTIVE,
                Collections.emptyList(),
                0,
                0,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Instant.now()
        );
    }

    @Test
    void getActiveCart_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getActiveCart_successWhenAuthenticated() throws Exception {
        when(cartService.getActiveCart(any(UUID.class))).thenReturn(mockCartResponse());

        mockMvc.perform(get("/api/v1/cart")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cartId").value(cartId.toString()));
    }

    @Test
    void addItem_success() throws Exception {
        AddToCartRequest request = new AddToCartRequest(skuId, 2);
        when(cartService.addItem(any(UUID.class), any(AddToCartRequest.class))).thenReturn(mockCartResponse());

        mockMvc.perform(post("/api/v1/cart/items")
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void addItem_validationErrorWhenQuantityInvalid() throws Exception {
        AddToCartRequest request = new AddToCartRequest(skuId, 0);

        mockMvc.perform(post("/api/v1/cart/items")
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateItemQuantity_success() throws Exception {
        UpdateCartItemRequest request = new UpdateCartItemRequest(5);
        when(cartService.updateItemQuantity(any(UUID.class), eq(itemId), any(UpdateCartItemRequest.class)))
                .thenReturn(mockCartResponse());

        mockMvc.perform(put("/api/v1/cart/items/{itemId}", itemId)
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void removeItem_success() throws Exception {
        when(cartService.removeItem(any(UUID.class), eq(itemId))).thenReturn(mockCartResponse());

        mockMvc.perform(delete("/api/v1/cart/items/{itemId}", itemId)
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void clearCart_success() throws Exception {
        doNothing().when(cartService).clearCart(any(UUID.class));

        mockMvc.perform(delete("/api/v1/cart/items")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void mergeCart_success() throws Exception {
        MergeCartRequest request = new MergeCartRequest(List.of(new AddToCartRequest(skuId, 1)));
        when(cartService.mergeCart(any(UUID.class), any(MergeCartRequest.class))).thenReturn(mockCartResponse());

        mockMvc.perform(post("/api/v1/cart/merge")
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
