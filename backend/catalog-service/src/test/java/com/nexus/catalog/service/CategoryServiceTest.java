package com.nexus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import com.nexus.catalog.dto.request.CategoryRequest;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.enums.CategoryStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.catalog.repository.CategoryRepository;
import com.nexus.catalog.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bảo vệ quy tắc cây danh mục: giới hạn ba cấp, chặn vòng lặp, cập nhật cấp cây con và điều kiện xóa. */
class CategoryServiceTest {
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryService service = new CategoryService(categories, products);

    private Category category(Category parent) {
        Category result = new Category();
        result.setId(UUID.randomUUID());
        result.setName("Category");
        result.setParent(parent);
        result.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        if (parent != null) {
            parent.getChildren().add(result);
        }
        when(categories.findById(result.getId())).thenReturn(Optional.of(result));
        when(categories.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return result;
    }

    private CategoryRequest request(Category parent) {
        return new CategoryRequest("Updated", parent == null ? null : parent.getId(), CategoryStatus.ACTIVE);
    }

    @Test
    void buildsNestedTreeAndReturnsProductCounts() {
        Category root = category(null);
        Category child = category(root);
        when(categories.findAll()).thenReturn(List.of(root, child));
        when(products.countByCategoryId(child.getId())).thenReturn(3L);
        var tree = service.tree();
        assertEquals(1, tree.size());
        assertEquals(3, tree.getFirst().children().getFirst().productCount());
        assertEquals(root.getId(), service.get(root.getId()).id());
    }

    @Test
    void createsCategoryBelowParent() {
        Category root = category(null);
        var created = service.create(request(root));
        assertEquals(2, created.level());
        // ERD chỉ có name: alias UI phải phản ánh đúng tên đã lưu.
        assertEquals("Updated", created.nameVi());
    }

    @Test
    void rejectsFourthLevel() {
        Category third = category(category(category(null)));
        assertThrows(BusinessException.class, () -> service.create(request(third)));
    }

    @Test
    void rejectsCyclesWhenReparenting() {
        Category root = category(null);
        Category child = category(root);
        assertThrows(BusinessException.class, () -> service.update(root.getId(), request(child)));
        assertThrows(BusinessException.class, () -> service.update(root.getId(), request(root)));
    }

    @Test
    void rejectsMovingSubtreePastMaximumDepth() {
        Category root = category(null);
        category(category(root));
        Category otherRoot = category(null);
        assertThrows(BusinessException.class, () -> service.update(root.getId(), request(otherRoot)));
    }

    @Test
    void adjustsLevelsForMovedSubtree() {
        Category root = category(null);
        Category child = category(root);
        Category grandchild = category(child);
        service.update(child.getId(), request(null));
        assertEquals(1, child.getLevel());
        assertEquals(2, grandchild.getLevel());
    }

    @Test
    void doesNotDeleteCategoryWithChildrenOrProducts() {
        Category root = category(null);
        Category child = category(root);
        assertThrows(BusinessException.class, () -> service.delete(root.getId()));
        when(products.existsByCategoryId(child.getId())).thenReturn(true);
        assertThrows(BusinessException.class, () -> service.delete(child.getId()));
    }

    @Test
    void deletesEmptyLeafAndRejectsMissingId() {
        Category root = category(null);
        service.delete(root.getId());
        verify(categories).delete(root);
        assertThrows(ResourceNotFoundException.class, () -> service.get(UUID.randomUUID()));
    }
}
