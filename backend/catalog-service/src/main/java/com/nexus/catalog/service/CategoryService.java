package com.nexus.catalog.service;

import com.nexus.catalog.dto.request.CategoryRequest;
import com.nexus.catalog.dto.response.CategoryResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.exception.NotFoundException;
import com.nexus.catalog.repository.CategoryRepository;
import com.nexus.catalog.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xử lý nghiệp vụ danh mục: đọc cây, tạo/sửa/xóa và kiểm soát quan hệ cha–con.
 * Luồng ghi: controller xác thực quyền/DTO → service kiểm tra quy tắc → repository lưu DB.
 * Mỗi thao tác chạy trong một transaction; lỗi nghiệp vụ làm rollback thay đổi.
 */
@Service
@Transactional
public class CategoryService {
    private final CategoryRepository categories;
    private final ProductRepository products;

    public CategoryService(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    /** Lấy danh mục gốc và dựng các nhánh con thành DTO khi transaction đọc còn mở. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> tree() {
        return categories.findAll().stream().filter(c -> c.getParent() == null)
                .map(this::response).toList();
    }

    /** Tìm theo UUID; trả DTO kèm cây con hoặc báo không tìm thấy. */
    @Transactional(readOnly = true)
    public CategoryResponse get(UUID id) {
        return response(find(id));
    }

    /** Kiểm tra cha/cấp trước khi lưu; flush để DTO trả về có UUID do Hibernate sinh. */
    public CategoryResponse create(CategoryRequest request) {
        Category category = new Category();
        apply(category, request);
        return response(categories.saveAndFlush(category));
    }

    /** Cập nhật danh mục và cấp của cây con nếu đổi cha; Hibernate lưu các entity được quản lý. */
    public CategoryResponse update(UUID id, CategoryRequest request) {
        Category category = find(id);
        apply(category, request);
        return response(categories.saveAndFlush(category));
    }

    /** Chỉ xóa danh mục lá không chứa sản phẩm, tránh mất cấu trúc cây hoặc vi phạm khóa ngoại. */
    public void delete(UUID id) {
        Category category = find(id);
        if (!category.getChildren().isEmpty()) {
            throw new BusinessException("Delete or move child categories first");
        }
        if (products.existsByCategoryId(id)) {
            throw new BusinessException("Cannot delete a category containing products");
        }
        categories.delete(category);
    }

    /** Áp dụng DTO sau khi chặn vòng lặp và kiểm tra độ sâu của toàn bộ nhánh được di chuyển. */
    private void apply(Category category, CategoryRequest request) {
        Category parent = request.parentId() == null ? null : find(request.parentId());
        // Đi ngược từ cha dự kiến lên gốc: nếu gặp chính danh mục đang sửa thì quan hệ sẽ thành vòng lặp.
        for (Category ancestor = parent; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getId().equals(category.getId())) {
                throw new BusinessException("A category cannot be its own ancestor");
            }
        }
        int level = parent == null ? 1 : parent.getLevel() + 1;
        // Không chỉ kiểm tra danh mục hiện tại: nhánh con sâu nhất cũng phải nằm trong giới hạn ba cấp.
        if (level + height(category) - 1 > 3) {
            throw new BusinessException("Maximum category depth is 3 levels");
        }
        category.setParent(parent);
        category.setName(request.name().trim());
        category.setNameVi(request.nameVi() == null ? request.name().trim() : request.nameVi().trim());
        category.setStatus(request.status());
        setLevels(category, level);
    }

    /** Chiều cao nhánh tính cả node hiện tại, phục vụ kiểm tra khi chuyển cả cây con sang cha mới. */
    private int height(Category category) {
        return 1 + category.getChildren().stream().mapToInt(this::height).max().orElse(0);
    }

    /** Đồng bộ cấp của node và mọi hậu duệ; các entity này được lưu trong cùng transaction cập nhật. */
    private void setLevels(Category category, int level) {
        category.setLevel(level);
        category.getChildren().forEach(child -> setLevels(child, level + 1));
    }

    /** Gom xử lý không tìm thấy vào một chỗ để controller nhận lỗi 404 thống nhất. */
    private Category find(UUID id) {
        return categories.findById(id).orElseThrow(() -> new NotFoundException("Category not found: " + id));
    }

    /** productCount chỉ đếm sản phẩm gắn trực tiếp với node, không cộng dồn sản phẩm của các node con. */
    private CategoryResponse response(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getNameVi(),
                category.getParent() == null ? null : category.getParent().getId(),
                category.getLevel(), category.getStatus(), products.countByCategoryId(category.getId()),
                category.getChildren().stream().map(this::response).toList());
    }
}
