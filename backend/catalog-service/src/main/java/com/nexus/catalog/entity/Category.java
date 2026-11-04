package com.nexus.catalog.entity;

import com.nexus.catalog.enums.CategoryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Danh mục lưu tên tiếng Anh và tiếng Việt; cấp và quan hệ cha–con do CategoryService kiểm soát. */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent")
    private List<Category> children = new ArrayList<>();

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false, length = 255)
    private String name;

    /** Tên tiếng Việt dùng cho giao diện khi người dùng chọn ngôn ngữ VI. */
    @NotBlank
    @Size(max = 255)
    @Column(name = "name_vi", nullable = false, length = 255)
    private String nameVi;

    @Min(1)
    @Max(3)
    @Column(nullable = false)
    private Integer level = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategoryStatus status = CategoryStatus.ACTIVE;

    public Category() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Category getParent() { return parent; }
    public void setParent(Category parent) { this.parent = parent; }
    public List<Category> getChildren() { return children; }
    public void setChildren(List<Category> children) { this.children = children; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNameVi() { return nameVi; }
    public void setNameVi(String nameVi) { this.nameVi = nameVi; }
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    public CategoryStatus getStatus() { return status; }
    public void setStatus(CategoryStatus status) { this.status = status; }
}
