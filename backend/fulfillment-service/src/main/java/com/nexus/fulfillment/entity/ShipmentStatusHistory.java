package com.nexus.fulfillment.entity;

import com.nexus.fulfillment.enums.ShipmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Lịch sử chuyển trạng thái của vận đơn theo phản hồi từ hãng vận chuyển (SRS §3.6.2).
 * Ghi lại cặp trạng thái gốc từ carrier (carrierStatus) và trạng thái chuẩn hóa nội bộ (internalStatus).
 */
@Entity
@Table(name = "shipment_status_history")
public class ShipmentStatusHistory {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "carrier_status", length = 50)
    private String carrierStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "internal_status", nullable = false, length = 30)
    private ShipmentStatus internalStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public ShipmentStatusHistory() {
    }

    public ShipmentStatusHistory(
            Shipment shipment,
            String carrierStatus,
            ShipmentStatus internalStatus,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.shipment = shipment;
        this.carrierStatus = carrierStatus;
        this.internalStatus = internalStatus;
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    @PrePersist
    void ensureDefaults() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.occurredAt == null) {
            this.occurredAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public void setShipment(Shipment shipment) {
        this.shipment = shipment;
    }

    public String getCarrierStatus() {
        return carrierStatus;
    }

    public void setCarrierStatus(String carrierStatus) {
        this.carrierStatus = carrierStatus;
    }

    public ShipmentStatus getInternalStatus() {
        return internalStatus;
    }

    public void setInternalStatus(ShipmentStatus internalStatus) {
        this.internalStatus = internalStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ShipmentStatusHistory that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
