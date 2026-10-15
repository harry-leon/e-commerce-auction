package com.nexus.catalog.repository;

import com.nexus.catalog.entity.Category;
import com.nexus.catalog.enums.CategoryStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByParentIsNullOrderByNameAsc();
    List<Category> findByParentIdOrderByNameAsc(UUID parentId);
    List<Category> findByLevelOrderByNameAsc(Integer level);
    List<Category> findByLevelAndStatusOrderByNameAsc(Integer level, CategoryStatus status);
    boolean existsByParentId(UUID parentId);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndParentId(String name, UUID parentId);
}
