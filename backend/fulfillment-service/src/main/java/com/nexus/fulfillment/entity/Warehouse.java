package com.nexus.fulfillment.entity;

import com.nexus.fulfillment.enums.WarehouseType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * Thực thể đại diện cho một kho hàng vật lý hoặc kho đối tác thứ ba (SRS §3.6.1).
 *
 * Chức năng:
 * - Quản lý định danh kho, mã kho (ví dụ WH_001), địa chỉ và loại kho.
 * - Ánh xạ bảng 'fulfillment_svc.warehouses' (hoặc 'warehouses' theo schema của service).
 */
@Entity
@Table(name = "warehouses")
public class Warehouse {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private WarehouseType type = WarehouseType.STANDARD;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    public Warehouse() {
    }

    public Warehouse(UUID id, String code, String name, String address, WarehouseType type, boolean isActive) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.address = address;
        this.type = type;
        this.isActive = isActive;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public WarehouseType getType() {
        return type;
    }

    public void setType(WarehouseType type) {
        this.type = type;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Warehouse warehouse = (Warehouse) o;
        return Objects.equals(id, warehouse.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
