package com.nexus.fulfillment.security;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.client.CatalogClient;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Kiểm soát quyền thao tác và phạm vi dữ liệu seller trên Inventory & Warehouse.
 *
 * Quy tắc:
 * - ADMIN hoặc token có INVENTORY.MANAGE: toàn quyền thao tác mọi SKU/kho.
 * - SUPPORT hoặc token có INVENTORY.VIEW: toàn quyền đọc tồn kho, sổ cái và đối soát; không được sửa dữ liệu.
 * - SELLER: chỉ được đọc/nhập/điều chỉnh/tra cứu sổ cái/đối soát đối với các SKU thuộc quyền sở hữu của chính mình
 *   (xác minh thông qua Catalog Service, không tin sellerId từ request).
 * - BUYER hoặc tài khoản không có vai trò quản trị/seller: từ chối với HTTP 403 AUTH_FORBIDDEN.
 * - Tác vụ nội bộ chạy không qua HTTP (scheduler, test unit không có SecurityContext) được coi là internal system.
 */
@Component
public class InventoryAccessPolicy {

    private final CatalogClient catalogClient;

    public InventoryAccessPolicy(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    /**
     * Thông tin tác nhân đang thực thi nghiệp vụ.
     */
    public record ActorContext(
            UUID actorId,
            boolean adminManager,
            boolean supportViewer,
            boolean seller,
            boolean internalSystem
    ) {
        /**
         * Chỉ áp dụng giới hạn phạm vi seller khi là user SELLER thuần (không kiêm ADMIN/SUPPORT/internal).
         */
        public boolean isSellerScoped() {
            return !internalSystem && !adminManager && !supportViewer && seller;
        }
    }

    /**
     * Trích xuất thông tin quyền và UUID người dùng từ SecurityContext hiện tại.
     */
    public ActorContext currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return new ActorContext(null, false, false, false, true);
        }

        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        boolean adminManager = hasAnyAuthority(authorities, "ROLE_ADMIN", "ADMIN", "INVENTORY.MANAGE");
        boolean supportViewer = hasAnyAuthority(authorities, "ROLE_SUPPORT", "SUPPORT", "INVENTORY.VIEW");
        boolean seller = hasAnyAuthority(authorities, "ROLE_SELLER", "SELLER");
        UUID actorId = parseUuid(auth.getName());

        return new ActorContext(actorId, adminManager, supportViewer, seller, false);
    }

    /**
     * Kiểm tra quyền ghi (nhập kho / điều chỉnh kho) trên một SKU cụ thể.
     * Seller bắt buộc phải sở hữu SKU trong Catalog Service.
     */
    public ActorContext verifyWriteAccessAndOwnership(UUID skuId) {
        ActorContext actor = currentActor();
        if (actor.internalSystem() || actor.adminManager()) {
            return actor;
        }
        if (!actor.seller()) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Bạn không có quyền nhập kho hoặc điều chỉnh tồn kho"
            );
        }
        UUID sellerId = requireSellerUuid(actor);
        CatalogClient.SkuOwnershipInfo sku = catalogClient.findSkuById(skuId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SKU_NOT_FOUND,
                        "Không tìm thấy SKU " + skuId + " trong hệ thống Catalog"
                ));
        if (!sellerId.equals(sku.sellerId())) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Bạn không có quyền thao tác tồn kho đối với SKU thuộc người bán khác"
            );
        }
        return actor;
    }

    /**
     * Xác minh phạm vi đọc tồn kho, sổ cái hoặc đối soát; trả về danh sách skuId giới hạn nếu là Seller.
     * Trả về null khi tác nhân có quyền xem toàn hệ thống (ADMIN/SUPPORT/internal).
     */
    public List<UUID> resolveReadableSellerSkuScope(UUID requestedSellerId, UUID requestedSkuId) {
        ActorContext actor = currentActor();
        if (actor.internalSystem() || actor.adminManager() || actor.supportViewer()) {
            if (requestedSellerId != null) {
                return catalogClient.findSkusBySeller(requestedSellerId).stream()
                        .map(CatalogClient.SkuOwnershipInfo::skuId)
                        .distinct()
                        .toList();
            }
            return null;
        }
        if (!actor.seller()) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Bạn không có quyền truy cập dữ liệu quản trị tồn kho"
            );
        }

        UUID sellerId = requireSellerUuid(actor);
        if (requestedSellerId != null && !sellerId.equals(requestedSellerId)) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Seller không được phép truy cập dữ liệu tồn kho của người bán khác"
            );
        }

        if (requestedSkuId != null) {
            CatalogClient.SkuOwnershipInfo sku = catalogClient.findSkuById(requestedSkuId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.SKU_NOT_FOUND,
                            "Không tìm thấy SKU " + requestedSkuId + " trong hệ thống Catalog"
                    ));
            if (!sellerId.equals(sku.sellerId())) {
                throw new BusinessException(
                        ErrorCode.AUTH_FORBIDDEN,
                        "Seller không được phép xem tồn kho hoặc lịch sử biến động của SKU thuộc người bán khác"
                );
            }
            return List.of(requestedSkuId);
        }

        return catalogClient.findSkusBySeller(sellerId).stream()
                .map(CatalogClient.SkuOwnershipInfo::skuId)
                .distinct()
                .toList();
    }

    /**
     * Kiểm tra quyền thao tác quản lý cấu hình kho (chỉ ADMIN hoặc INVENTORY.MANAGE).
     */
    public void verifyWarehouseAdminAccess() {
        ActorContext actor = currentActor();
        if (actor.internalSystem() || actor.adminManager()) {
            return;
        }
        throw new BusinessException(
                ErrorCode.AUTH_FORBIDDEN,
                "Chỉ quản trị viên mới có quyền tạo hoặc chỉnh sửa thông tin kho hàng"
        );
    }

    private UUID requireSellerUuid(ActorContext actor) {
        if (actor.actorId() == null) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Không xác định được mã định danh người bán từ phiên đăng nhập"
            );
        }
        return actor.actorId();
    }

    private boolean hasAnyAuthority(Collection<? extends GrantedAuthority> authorities, String... expected) {
        if (authorities == null) {
            return false;
        }
        for (GrantedAuthority authority : authorities) {
            if (authority == null) {
                continue;
            }
            String value = authority.getAuthority();
            for (String candidate : expected) {
                if (candidate.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
