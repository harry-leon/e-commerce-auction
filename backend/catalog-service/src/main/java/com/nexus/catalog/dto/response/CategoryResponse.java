package com.nexus.catalog.dto.response;

import com.nexus.catalog.enums.CategoryStatus;
import java.util.List;
import java.util.UUID;

/** DTO cây danh mục; children chứa nhánh con và productCount là số sản phẩm trực tiếp tại node này. */
public record CategoryResponse(UUID id, String name, String nameVi, UUID parentId,
        int level, CategoryStatus status, long productCount, List<CategoryResponse> children) {
}
