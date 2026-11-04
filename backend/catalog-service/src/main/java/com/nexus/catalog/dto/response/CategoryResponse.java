package com.nexus.catalog.dto.response;

import com.nexus.catalog.enums.CategoryStatus;
import java.util.List;
import java.util.UUID;

/** DTO cây danh mục song ngữ; productCount gồm node hiện tại và các nhánh con để category gốc phản ánh đúng catalog. */
public record CategoryResponse(UUID id, String name, String nameVi, UUID parentId,
        int level, CategoryStatus status, long productCount, List<CategoryResponse> children) {
}
