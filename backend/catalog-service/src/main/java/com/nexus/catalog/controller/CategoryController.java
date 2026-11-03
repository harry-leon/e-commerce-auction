package com.nexus.catalog.controller;

import com.nexus.catalog.dto.request.CategoryRequest;
import com.nexus.common.response.ApiResponse;
import com.nexus.catalog.dto.response.CategoryResponse;
import com.nexus.catalog.service.CategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API danh mục v1: GET trả cây/chi tiết công khai; thao tác ghi yêu cầu quyền CATEGORY tương ứng.
 * Controller kiểm tra DTO rồi chuyển nghiệp vụ cho CategoryService; ApiResponse giữ định dạng Catalog thống nhất.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Category", description = "Category tree, maximum 3 levels")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    /** Trả các node gốc, mỗi node chứa danh sách children lồng nhau. */
    @GetMapping
    public ApiResponse<List<CategoryResponse>> tree() {
        return ApiResponse.ok(service.tree());
    }

    /** Tra chi tiết một danh mục theo UUID; service xử lý trường hợp không tồn tại. */
    @GetMapping("/{id}")
    public ApiResponse<CategoryResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    /** Tạo danh mục sau validation và kiểm tra quyền; thành công trả HTTP 201. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATEGORY.CREATE')")
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    /** Sửa tên/trạng thái/cha; quy tắc chặn vòng lặp và giới hạn cấp nằm trong service. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CATEGORY.UPDATE')")
    public ApiResponse<CategoryResponse> update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    /** Xóa danh mục đủ điều kiện; HTTP 204 không có response body. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CATEGORY.DELETE')")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
