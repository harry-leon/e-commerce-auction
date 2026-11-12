package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.InventoryLedger;
import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.event.InventoryCommittedEvent;
import com.nexus.fulfillment.event.ShipmentPickedUpEvent;
import com.nexus.fulfillment.repository.InventoryLedgerRepository;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.InventoryReservationRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.service.FulfillmentEventOutboxService;
import com.nexus.fulfillment.service.WarehouseFulfillmentService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai dịch vụ thao tác kho vận vật lý: Pick, Pack, Ship và tích hợp Sổ cái bất biến (SRS §3.6.1, §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Pick: Chuyển trạng thái vận đơn sang PICKING khi nhân viên bắt đầu lấy hàng trên giá kệ.
 * - Pack: Chuyển trạng thái vận đơn sang PACKED khi đóng gói kiểm đếm hoàn tất.
 * - Ship: Xuất kho giao vận:
 *   1. Chuyển trạng thái sang SHIPPED (hoặc PICKED_UP).
 *   2. Cập nhật số dư tồn kho: reserved_qty -> shipped/committed (giảm reserved_qty, giảm total_qty, chuyển reservation sang CONFIRMED).
 *   3. Ghi bản ghi biến động OUTBOUND bất biến vào bảng inventory_ledger.
 *   4. Bắn sự kiện miền InventoryCommittedEvent và ShipmentPickedUpEvent.
 *
 * Quy tắc và lý do:
 * - Khóa bi quan (Pessimistic Lock) khi trừ kho xuất hàng để chống Race Condition khi có thao tác hủy đơn hoặc xuất kho đồng thời.
 * - Xử lý Idempotent: nếu đơn hàng đã được commit trước đó (ví dụ tại OrderPaid), bỏ qua trừ kho lặp, chỉ cập nhật trạng thái vận đơn.
 */
@Service
public class WarehouseFulfillmentServiceImpl implements WarehouseFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseFulfillmentServiceImpl.class);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository historyRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryLedgerRepository ledgerRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FulfillmentEventOutboxService outboxService;

    public WarehouseFulfillmentServiceImpl(
            ShipmentRepository shipmentRepository,
            ShipmentStatusHistoryRepository historyRepository,
            InventoryReservationRepository reservationRepository,
            InventoryRepository inventoryRepository,
            InventoryLedgerRepository ledgerRepository,
            ApplicationEventPublisher eventPublisher,
            FulfillmentEventOutboxService outboxService) {
        this.shipmentRepository = shipmentRepository;
        this.historyRepository = historyRepository;
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.ledgerRepository = ledgerRepository;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional
    public ShipmentResponse pick(UUID shipmentId) {
        Shipment shipment = getShipmentOrThrow(shipmentId);

        if (shipment.getStatus() != ShipmentStatus.PENDING && shipment.getStatus() != ShipmentStatus.CREATED) {
            if (shipment.getStatus() == ShipmentStatus.PICKING) {
                log.info("Vận đơn {} đã ở trạng thái PICKING (Idempotent)", shipmentId);
                return toResponse(shipment);
            }
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Không thể bắt đầu lấy hàng cho vận đơn đang ở trạng thái: " + shipment.getStatus()
            );
        }

        shipment.setStatus(ShipmentStatus.PICKING);
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);

        historyRepository.save(new ShipmentStatusHistory(
                shipment,
                "PICKING",
                ShipmentStatus.PICKING,
                null,
                "Nhân viên kho bắt đầu lấy hàng từ kệ",
                Instant.now()
        ));

        log.info("Vận đơn {} chuyển trạng thái sang PICKING thành công", shipmentId);
        return toResponse(shipment);
    }

    @Override
    @Transactional
    public ShipmentResponse pack(UUID shipmentId) {
        Shipment shipment = getShipmentOrThrow(shipmentId);

        if (shipment.getStatus() != ShipmentStatus.PICKING) {
            if (shipment.getStatus() == ShipmentStatus.PACKED) {
                log.info("Vận đơn {} đã ở trạng thái PACKED (Idempotent)", shipmentId);
                return toResponse(shipment);
            }
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Chỉ có thể đóng gói kiện hàng khi vận đơn ở trạng thái PICKING, hiện tại: " + shipment.getStatus()
            );
        }

        shipment.setStatus(ShipmentStatus.PACKED);
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);

        historyRepository.save(new ShipmentStatusHistory(
                shipment,
                "PACKED",
                ShipmentStatus.PACKED,
                null,
                "Đóng gói hoàn tất kiện hàng",
                Instant.now()
        ));

        log.info("Vận đơn {} chuyển trạng thái sang PACKED thành công", shipmentId);
        return toResponse(shipment);
    }

    @Override
    @Transactional
    public ShipmentResponse ship(UUID shipmentId) {
        Shipment shipment = getShipmentOrThrow(shipmentId);

        if (shipment.getStatus() == ShipmentStatus.SHIPPED || shipment.getStatus() == ShipmentStatus.PICKED_UP) {
            log.info("Vận đơn {} đã được xuất kho trước đó (Idempotent)", shipmentId);
            return toResponse(shipment);
        }

        if (shipment.getStatus().isTerminal()) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Không thể xuất kho vận đơn đã ở trạng thái kết thúc: " + shipment.getStatus()
            );
        }

        // Cập nhật tồn kho vật lý (reserved_qty -> shipped/committed) và ghi sổ cái bất biến inventory_ledger
        commitWarehouseInventory(shipment);

        shipment.setStatus(ShipmentStatus.SHIPPED);
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);

        historyRepository.save(new ShipmentStatusHistory(
                shipment,
                "SHIPPED",
                ShipmentStatus.SHIPPED,
                null,
                "Kiện hàng đã xuất kho bàn giao giao vận",
                Instant.now()
        ));

        // Phát sự kiện miền ShipmentPickedUpEvent
        eventPublisher.publishEvent(ShipmentPickedUpEvent.of(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getCarrierCode(),
                shipment.getTrackingNo()
        ));
        outboxService.enqueueShipmentPickedUp(shipment);

        log.info("Vận đơn {} xuất kho thành công (SHIPPED), đã trừ tồn và ghi Sổ cái tồn kho", shipmentId);
        return toResponse(shipment);
    }

    private void commitWarehouseInventory(Shipment shipment) {
        List<InventoryReservation> reservations = reservationRepository.findByRefTypeAndRefIdForUpdate(
                ReservationRefType.ORDER,
                shipment.getOrderId()
        );

        for (InventoryReservation res : reservations) {
            // Chỉ trừ kho cho các phiếu giữ hàng thuộc về đúng kho xuất của vận đơn này
            if (res.getInventory() != null && res.getInventory().getWarehouse() != null
                    && res.getInventory().getWarehouse().getId().equals(shipment.getWarehouse().getId())) {

                if (res.getStatus() == ReservationStatus.PENDING) {
                    Inventory inventory = inventoryRepository.findByIdForUpdate(res.getInventory().getId())
                            .orElseThrow(() -> new BusinessException(
                                    ErrorCode.INVENTORY_NOT_FOUND,
                                    "Không tìm thấy bản ghi tồn kho liên kết với phiếu giữ hàng " + res.getId()
                            ));

                    int qty = res.getQuantity();
                    if (inventory.getReservedQty() < qty || (inventory.getTotalQty() - qty) < inventory.getUnavailableQty()) {
                        throw new BusinessException(
                                ErrorCode.INVENTORY_INSUFFICIENT,
                                "Không thể xuất kho vì số dư tồn hoặc đang giữ không hợp lệ cho SKU " + inventory.getSkuId()
                        );
                    }

                    inventory.setReservedQty(inventory.getReservedQty() - qty);
                    inventory.setTotalQty(inventory.getTotalQty() - qty);
                    inventoryRepository.save(inventory);

                    res.setStatus(ReservationStatus.CONFIRMED);
                    reservationRepository.save(res);

                    // Ghi dòng biến động bất biến vào Sổ cái tồn kho (Immutable Inventory Ledger)
                    InventoryLedger ledger = new InventoryLedger(
                            UUID.randomUUID(),
                            inventory,
                            LedgerMovementType.OUTBOUND,
                            qty,
                            "SHIPMENT",
                            shipment.getId(),
                            Instant.now(),
                            null,
                            "Xuất kho giao hàng theo vận đơn " + shipment.getTrackingNo(),
                            null
                    );
                    ledgerRepository.save(ledger);

                    // Phát sự kiện miền InventoryCommittedEvent (SRS §2.8)
                    eventPublisher.publishEvent(InventoryCommittedEvent.of(
                            res.getId(),
                            inventory.getSkuId(),
                            inventory.getWarehouse().getId(),
                            qty,
                            res.getRefType(),
                            res.getRefId()
                    ));

                    log.info("Đã trừ kho SKU {} (qty={}) và ghi ledger OUTBOUND theo vận đơn {}",
                            inventory.getSkuId(), qty, shipment.getId());
                }
            }
        }
    }

    private Shipment getShipmentOrThrow(UUID shipmentId) {
        return shipmentRepository.findWithDetailsById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn với ID: " + shipmentId
                ));
    }

    private ShipmentResponse toResponse(Shipment s) {
        return new ShipmentResponse(
                s.getId(),
                s.getOrderId(),
                s.getWarehouse().getId(),
                s.getWarehouse().getCode(),
                s.getCarrierCode() != null ? s.getCarrierCode() : s.getCarrier().getCode(),
                s.getServiceType(),
                s.getShippingFee(),
                s.isFreeShippingApplied(),
                s.getTrackingNo(),
                s.getStatus(),
                s.getEstimatedDeliveryDays(),
                s.getEstimatedDeliveryAt(),
                s.getRecipientName(),
                s.getRecipientPhone(),
                s.getShippingAddress(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
