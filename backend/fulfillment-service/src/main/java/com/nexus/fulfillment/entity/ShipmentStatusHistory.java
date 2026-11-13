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
 * Lịch sử chuyển trạng thái của vận đơn theo phản hồi từ hãng vận chuyển hoặc thao tác kho (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Ghi lại cặp trạng thái gốc từ carrier (carrierStatus) và trạng thái chuẩn hóa nội bộ (internalStatus).
 * - Lưu trữ event_id (mã sự kiện/giao dịch từ carrier callback hoặc polling) phục vụ cơ chế xử lý idempotent.
 * - Lưu trữ ghi chú/lý do chi tiết (notes) khi giao dịch thất bại, chuyển trạng thái hoặc lý do trả hàng.
 *
 * Quy tắc và lý do:
 * - Cột event_id được đánh UNIQUE INDEX trong migration V8 để đảm bảo idempotent ở cấp cơ sở dữ liệu.
 * - Thời gian occurredAt mặc định là thời điểm ghi nhận nếu carrier không cung cấp mốc thời gian cụ thể.
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

    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public ShipmentStatusHistory() {
    }

    public ShipmentStatusHistory(
            Shipment shipment,
            String carrierStatus,
            ShipmentStatus internalStatus,
            Instant occurredAt) {
        this(shipment, carrierStatus, internalStatus, null, null, occurredAt);
    }

    public ShipmentStatusHistory(
            Shipment shipment,
            String carrierStatus,
            ShipmentStatus internalStatus,
            String eventId,
            String notes,
            Instant occurredAt) {
        this.id = UUID.randomUUID();
        this.shipment = shipment;
        this.carrierStatus = carrierStatus;
        this.internalStatus = internalStatus;
        this.eventId = eventId;
        this.notes = notes;
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

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
