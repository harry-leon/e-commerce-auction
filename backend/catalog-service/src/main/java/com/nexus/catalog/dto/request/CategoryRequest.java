package com.nexus.catalog.dto.request;

import com.nexus.catalog.enums.CategoryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Chỉ nhận một tên name theo ERD. DTO tạo/sửa danh mục: parentId null là gốc; cấp được server tính từ cha, không nhận từ client. */
public record CategoryRequest(
        @NotBlank @Size(max = 255) String name,
        UUID parentId,
        @NotNull CategoryStatus status) {
}
