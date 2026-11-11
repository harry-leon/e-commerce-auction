package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.dto.request.CreateWarehouseRequest;
import com.nexus.fulfillment.dto.request.UpdateWarehouseRequest;
import com.nexus.fulfillment.dto.response.WarehouseResponse;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.security.InventoryAccessPolicy;
import com.nexus.fulfillment.service.WarehouseService;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt nghiệp vụ quản lý kho hàng (Warehouse).
 *
 * Chức năng:
 * - Tra cứu danh sách kho, chi tiết theo ID hoặc mã code.
 * - Tạo và cập nhật kho hàng (chỉ ADMIN/INVENTORY.MANAGE); chặn trùng mã kho (HTTP 409).
 */
@Service
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final InventoryAccessPolicy accessPolicy;

    public WarehouseServiceImpl(WarehouseRepository warehouseRepository, InventoryAccessPolicy accessPolicy) {
        this.warehouseRepository = warehouseRepository;
        this.accessPolicy = accessPolicy;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WarehouseResponse> listWarehouses(Boolean activeOnly, WarehouseType type, String keyword, Pageable pageable) {
        Specification<Warehouse> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (Boolean.TRUE.equals(activeOnly)) {
                predicates.add(cb.isTrue(root.get("isActive")));
            } else if (Boolean.FALSE.equals(activeOnly)) {
                predicates.add(cb.isFalse(root.get("isActive")));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), pattern),
                        cb.like(cb.lower(root.get("name")), pattern)
                ));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return warehouseRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouse(UUID warehouseId) {
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy kho hàng với ID " + warehouseId
                ));
        return toResponse(warehouse);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouseByCode(String code) {
        String normalized = normalizeCode(code);
        Warehouse warehouse = warehouseRepository.findByCodeIgnoreCase(normalized)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy kho hàng với mã " + normalized
                ));
        return toResponse(warehouse);
    }

    @Override
    @Transactional
    public WarehouseResponse createWarehouse(CreateWarehouseRequest request) {
        accessPolicy.verifyWarehouseAdminAccess();
        String code = normalizeCode(request.code());
        if (warehouseRepository.existsByCodeIgnoreCase(code)) {
            throw new ApiException(
                    "WAREHOUSE_CODE_ALREADY_EXISTS",
                    HttpStatus.CONFLICT,
                    "Mã kho '" + code + "' đã tồn tại trong hệ thống",
                    null
            );
        }
        Warehouse warehouse = new Warehouse(
                UUID.randomUUID(),
                code,
                request.name().trim(),
                request.address().trim(),
                request.type() != null ? request.type() : WarehouseType.STANDARD,
                request.active() == null || request.active()
        );
        return toResponse(warehouseRepository.save(warehouse));
    }

    @Override
    @Transactional
    public WarehouseResponse updateWarehouse(UUID warehouseId, UpdateWarehouseRequest request) {
        accessPolicy.verifyWarehouseAdminAccess();
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy kho hàng với ID " + warehouseId
                ));
        warehouse.setName(request.name().trim());
        warehouse.setAddress(request.address().trim());
        if (request.type() != null) {
            warehouse.setType(request.type());
        }
        if (request.active() != null) {
            warehouse.setActive(request.active());
        }
        return toResponse(warehouseRepository.save(warehouse));
    }

    private String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã kho không được để trống");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private WarehouseResponse toResponse(Warehouse w) {
        return new WarehouseResponse(
                w.getId(),
                w.getCode(),
                w.getName(),
                w.getAddress(),
                w.getType(),
                w.isActive()
        );
    }
}
