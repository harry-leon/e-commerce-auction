package com.nexus.fulfillment.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Kiểm thử chính sách phân quyền và phạm vi dữ liệu Seller (InventoryAccessPolicy):
 * đảm bảo Seller chỉ thao tác/đọc dữ liệu SKU của chính mình qua Catalog Service,
 * Support chỉ có quyền đọc toàn cục, Admin toàn quyền và Buyer bị từ chối HTTP 403.
 */
class InventoryAccessPolicyTest {

    private final CatalogClient catalogClient = mock(CatalogClient.class);
    private final InventoryAccessPolicy accessPolicy = new InventoryAccessPolicy(catalogClient);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Seller được ghi kho đối với SKU thuộc sở hữu của chính mình và bị chặn khi đụng SKU seller khác")
    void verifyWriteAccessAndOwnership_EnforcesSellerOwnership() {
        UUID sellerId = UUID.randomUUID();
        UUID otherSellerId = UUID.randomUUID();
        UUID ownSku = UUID.randomUUID();
        UUID foreignSku = UUID.randomUUID();

        setActor(sellerId.toString(), "ROLE_SELLER");
        when(catalogClient.findSkuById(ownSku))
                .thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(ownSku, "OWN-1", "SP A", sellerId, true)));
        when(catalogClient.findSkuById(foreignSku))
                .thenReturn(Optional.of(new CatalogClient.SkuOwnershipInfo(foreignSku, "OTH-1", "SP B", otherSellerId, true)));

        InventoryAccessPolicy.ActorContext actor = accessPolicy.verifyWriteAccessAndOwnership(ownSku);
        assertEquals(sellerId, actor.actorId());

        BusinessException forbidden = assertThrows(BusinessException.class,
                () -> accessPolicy.verifyWriteAccessAndOwnership(foreignSku));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), forbidden.getCode());
    }

    @Test
    @DisplayName("Support và Buyer bị chặn khi thực hiện thao tác ghi tồn kho")
    void verifyWriteAccessAndOwnership_RejectsSupportAndBuyer() {
        UUID skuId = UUID.randomUUID();
        setActor(UUID.randomUUID().toString(), "ROLE_SUPPORT");

        BusinessException supportEx = assertThrows(BusinessException.class,
                () -> accessPolicy.verifyWriteAccessAndOwnership(skuId));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), supportEx.getCode());

        setActor(UUID.randomUUID().toString(), "ROLE_BUYER");
        BusinessException buyerEx = assertThrows(BusinessException.class,
                () -> accessPolicy.verifyWriteAccessAndOwnership(skuId));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), buyerEx.getCode());

        verify(catalogClient, never()).findSkuById(skuId);
    }

    @Test
    @DisplayName("Phân giải phạm vi đọc theo Seller, Support và Admin")
    void resolveReadableSellerSkuScope_RespectsRoleBoundaries() {
        UUID sellerId = UUID.randomUUID();
        UUID otherSellerId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        // Seller: bị chặn nếu cố truyền sellerId của người khác
        setActor(sellerId.toString(), "ROLE_SELLER");
        BusinessException crossSeller = assertThrows(BusinessException.class,
                () -> accessPolicy.resolveReadableSellerSkuScope(otherSellerId, null));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), crossSeller.getCode());

        // Seller tra cứu toàn bộ SKU của chính mình
        when(catalogClient.findSkusBySeller(sellerId)).thenReturn(List.of(
                new CatalogClient.SkuOwnershipInfo(skuId, "SKU-1", "SP 1", sellerId, true),
                new CatalogClient.SkuOwnershipInfo(skuId, "SKU-1", "SP 1", sellerId, true)
        ));
        List<UUID> sellerSkus = accessPolicy.resolveReadableSellerSkuScope(null, null);
        assertEquals(List.of(skuId), sellerSkus);

        // Support: xem toàn cục khi không truyền sellerId
        setActor(UUID.randomUUID().toString(), "ROLE_SUPPORT");
        assertNull(accessPolicy.resolveReadableSellerSkuScope(null, null));

        // Buyer: không được phép truy cập API quản trị tồn kho
        setActor(UUID.randomUUID().toString(), "ROLE_BUYER");
        BusinessException buyerEx = assertThrows(BusinessException.class,
                () -> accessPolicy.resolveReadableSellerSkuScope(null, null));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), buyerEx.getCode());
    }

    @Test
    @DisplayName("Chỉ Admin hoặc INVENTORY.MANAGE mới được quản trị cấu hình kho hàng")
    void verifyWarehouseAdminAccess_AllowsAdminAndRejectsSeller() {
        setActor(UUID.randomUUID().toString(), "ROLE_ADMIN");
        assertDoesNotThrow(accessPolicy::verifyWarehouseAdminAccess);

        setActor(UUID.randomUUID().toString(), "ROLE_SELLER");
        BusinessException sellerEx = assertThrows(BusinessException.class,
                accessPolicy::verifyWarehouseAdminAccess);
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), sellerEx.getCode());
    }

    private void setActor(String principal, String... authorities) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(principal, "N/A", authorities);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
