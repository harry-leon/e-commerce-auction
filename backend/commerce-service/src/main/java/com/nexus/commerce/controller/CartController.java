package com.nexus.commerce.controller;

import com.nexus.commerce.dto.request.AddToCartRequest;
import com.nexus.commerce.dto.request.MergeCartRequest;
import com.nexus.commerce.dto.request.UpdateCartItemRequest;
import com.nexus.commerce.dto.response.ApiResponse;
import com.nexus.commerce.dto.response.CartResponse;
import com.nexus.commerce.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for Shopping Cart operations.
 * Base route: /api/v1/cart
 * Implements specifications defined in API_SPEC_CART_ORDER.md.
 */
@Tag(name = "Shopping Cart", description = "Endpoints for managing buyer shopping carts and line items")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /**
     * Resolves the current user's UUID from JWT principal or Gateway forwarded X-User-Id header.
     */
    private UUID resolveUserId(Jwt jwt, String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return UUID.fromString(headerUserId);
        }
        if (jwt != null) {
            String subject = jwt.getSubject();
            if (subject != null && !subject.isBlank()) {
                return UUID.fromString(subject);
            }
        }
        throw new IllegalStateException("Unable to resolve authenticated user ID");
    }

    @Operation(summary = "Get active shopping cart", description = "Retrieves the active cart for the authenticated user, revalidating prices and stock.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CartResponse> getActiveCart(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        return ApiResponse.ok(cartService.getActiveCart(userId));
    }

    @Operation(summary = "Add item to cart", description = "Adds a SKU item to the active cart, accumulating quantity if the SKU already exists.")
    @PostMapping("/items")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<CartResponse> addItem(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Valid @RequestBody AddToCartRequest request
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        return ApiResponse.ok(cartService.addItem(userId, request));
    }

    @Operation(summary = "Update cart item quantity", description = "Updates the quantity of a specific cart line item.")
    @PutMapping("/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CartResponse> updateItemQuantity(
            @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Valid @RequestBody UpdateCartItemRequest request
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        return ApiResponse.ok(cartService.updateItemQuantity(userId, itemId, request));
    }

    @Operation(summary = "Remove item from cart", description = "Deletes a specific line item from the active shopping cart.")
    @DeleteMapping("/items/{itemId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CartResponse> removeItem(
            @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        return ApiResponse.ok(cartService.removeItem(userId, itemId));
    }

    @Operation(summary = "Clear shopping cart", description = "Removes all items from the active shopping cart.")
    @DeleteMapping("/items")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> clearCart(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        cartService.clearCart(userId);
        return ApiResponse.ok();
    }

    @Operation(summary = "Merge guest cart", description = "Merges guest cart items into the authenticated user's active cart upon sign-in.")
    @PostMapping("/merge")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<CartResponse> mergeCart(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(hidden = true) @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Valid @RequestBody MergeCartRequest request
    ) {
        UUID userId = resolveUserId(jwt, headerUserId);
        return ApiResponse.ok(cartService.mergeCart(userId, request));
    }
}
