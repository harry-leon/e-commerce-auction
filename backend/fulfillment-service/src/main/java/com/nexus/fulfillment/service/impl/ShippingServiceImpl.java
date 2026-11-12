package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.CarrierInfoResponse;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteOptionResponse;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.entity.Carrier;
import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.ShipmentStatus;
import com.nexus.fulfillment.event.ShipmentCreatedEvent;
import com.nexus.fulfillment.event.ShippingQuoteRequestedEvent;
import com.nexus.fulfillment.repository.CarrierRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.shipping.adapter.CarrierAdapterRegistry;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentResult;
import com.nexus.fulfillment.shipping.adapter.CarrierQuoteRequest;
import com.nexus.fulfillment.shipping.adapter.CarrierQuoteResult;
import com.nexus.fulfillment.shipping.adapter.CarrierTrackingResult;
import com.nexus.fulfillment.shipping.adapter.ShippingCarrierAdapter;
import com.nexus.fulfillment.service.FulfillmentEventOutboxService;
import com.nexus.fulfillment.service.ShippingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai Shipping Orchestrator cho Fulfillment Service (SRS §3.6.2, KH-S3-1).
 *
 * Luồng xử lý:
 * - Báo giá: phát ShippingQuoteRequestedEvent -> gọi CarrierAdapterRegistry -> áp dụng FREE_SHIPPING_THRESHOLD.
 * - Tạo vận đơn: kiểm tra idempotency / trùng orderId -> chọn hãng qua CarrierAdapterRegistry -> gọi adapter
 *   tạo vận đơn lấy trackingNo -> lưu Shipment + ShipmentStatusHistory -> ghi Outbox & phát ShipmentCreatedEvent.
 * - Tracking: gọi adapter theo trackingNo -> cập nhật trạng thái mới và ghi ShipmentStatusHistory nếu thay đổi.
 */
@Service
public class ShippingServiceImpl implements ShippingService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository historyRepository;
    private final CarrierRepository carrierRepository;
    private final WarehouseRepository warehouseRepository;
    private final CarrierAdapterRegistry adapterRegistry;
    private final FulfillmentProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final FulfillmentEventOutboxService outboxService;

    public ShippingServiceImpl(
            ShipmentRepository shipmentRepository,
            ShipmentStatusHistoryRepository historyRepository,
            CarrierRepository carrierRepository,
            WarehouseRepository warehouseRepository,
            CarrierAdapterRegistry adapterRegistry,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher,
            FulfillmentEventOutboxService outboxService) {
        this.shipmentRepository = shipmentRepository;
        this.historyRepository = historyRepository;
        this.carrierRepository = carrierRepository;
        this.warehouseRepository = warehouseRepository;
        this.adapterRegistry = adapterRegistry;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
    }

    @Override
    @Transactional(readOnly = true)
    public ShippingQuoteResponse quoteShipping(ShippingQuoteRequest request) {
        Warehouse warehouse = resolveWarehouse(request.warehouseId());

        // Phát sự kiện ShippingQuoteRequested theo SRS §2.8 trước khi tổng hợp báo giá từ các hãng.
        eventPublisher.publishEvent(ShippingQuoteRequestedEvent.of(
                request.orderId(),
                warehouse.getId(),
                request.carrier(),
                request.orderTotal(),
                request.weightGrams()
        ));

        List<ShippingCarrierAdapter> adapters;
        if (request.carrier() != null && !request.carrier().isBlank()) {
            adapters = List.of(adapterRegistry.resolveAdapter(
                    request.carrier(), request.weightGrams(), request.serviceType()));
        } else {
            adapters = adapterRegistry.getSupportedAdapters();
        }

        BigDecimal threshold = properties.getShipping().getFreeShippingThreshold();
        boolean freeShipping = isFreeShipping(request.orderTotal(), threshold);

        CarrierQuoteRequest carrierRequest = new CarrierQuoteRequest(
                request.orderId(),
                warehouse.getId(),
                warehouse.getAddress(),
                request.shippingAddress(),
                request.toDistrictId(),
                request.toWardCode(),
                request.weightGrams(),
                request.orderTotal(),
                request.serviceType()
        );

        List<ShippingQuoteOptionResponse> options = new ArrayList<>();
        for (ShippingCarrierAdapter adapter : adapters) {
            CarrierQuoteResult quote = adapter.quote(carrierRequest);
            BigDecimal originalFee = quote.fee() != null ? quote.fee() : BigDecimal.ZERO;
            BigDecimal finalFee = freeShipping ? BigDecimal.ZERO : originalFee;
            options.add(new ShippingQuoteOptionResponse(
                    quote.carrierCode(),
                    quote.serviceType(),
                    finalFee,
                    originalFee,
                    freeShipping,
                    quote.estimatedDeliveryDays(),
                    quote.estimatedDeliveryAt()
            ));
        }

        return new ShippingQuoteResponse(
                request.orderId(),
                warehouse.getId(),
                properties.getShipping().getDefaultCarrier(),
                threshold,
                freeShipping,
                options
        );
    }

    @Override
    @Transactional
    public ShipmentResponse createShipment(CreateShipmentRequest request, String idempotencyKey) {
        String normalizedKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey.trim()
                : null;
        if (normalizedKey != null) {
            Optional<Shipment> existingByKey = shipmentRepository.findByIdempotencyKey(normalizedKey);
            if (existingByKey.isPresent()) {
                return toResponse(existingByKey.get());
            }
        }

        Optional<Shipment> existingByOrder = shipmentRepository.findByOrderId(request.orderId());
        if (existingByOrder.isPresent()) {
            throw new BusinessException(
                    ErrorCode.SHIPMENT_ALREADY_CREATED,
                    "Đơn hàng " + request.orderId() + " đã được tạo vận đơn trước đó");
        }

        Warehouse warehouse = resolveWarehouse(request.warehouseId());
        ShippingCarrierAdapter adapter = adapterRegistry.resolveAdapter(
                request.carrier(), request.weightGrams(), request.serviceType());
        Carrier carrier = resolveOrProvisionCarrier(adapter.getCarrierCode());

        CarrierCreateShipmentResult carrierResult = adapter.createShipment(new CarrierCreateShipmentRequest(
                request.orderId(),
                warehouse.getId(),
                warehouse.getAddress(),
                request.recipientName(),
                request.recipientPhone(),
                request.shippingAddress(),
                request.toDistrictId(),
                request.toWardCode(),
                request.weightGrams(),
                request.codAmount(),
                request.orderTotal(),
                request.serviceType(),
                request.note()
        ));

        BigDecimal threshold = properties.getShipping().getFreeShippingThreshold();
        boolean freeShipping = isFreeShipping(request.orderTotal(), threshold);
        BigDecimal finalFee = freeShipping
                ? BigDecimal.ZERO
                : (carrierResult.fee() != null ? carrierResult.fee() : BigDecimal.ZERO);

        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID());
        shipment.setOrderId(request.orderId());
        shipment.setWarehouse(warehouse);
        shipment.setCarrier(carrier);
        shipment.setCarrierCode(carrier.getCode());
        shipment.setServiceType(carrierResult.serviceType());
        shipment.setShippingFee(finalFee);
        shipment.setTrackingNo(carrierResult.trackingNumber());
        shipment.setStatus(carrierResult.mappedStatus() != null ? carrierResult.mappedStatus() : ShipmentStatus.PENDING);
        shipment.setEstimatedDeliveryDays(carrierResult.estimatedDeliveryDays());
        shipment.setEstimatedDeliveryAt(carrierResult.estimatedDeliveryAt());
        shipment.setRecipientName(request.recipientName());
        shipment.setRecipientPhone(request.recipientPhone());
        shipment.setShippingAddress(request.shippingAddress());
        shipment.setWeightGrams(request.weightGrams());
        shipment.setOrderTotal(request.orderTotal());
        shipment.setFreeShippingApplied(freeShipping);
        shipment.setIdempotencyKey(normalizedKey);

        Shipment saved = shipmentRepository.save(shipment);
        historyRepository.save(new ShipmentStatusHistory(
                saved,
                carrierResult.carrierStatus(),
                saved.getStatus(),
                Instant.now()
        ));

        outboxService.enqueueShipmentCreated(saved);
        eventPublisher.publishEvent(ShipmentCreatedEvent.of(
                saved.getId(),
                saved.getOrderId(),
                warehouse.getId(),
                saved.getCarrierCode(),
                saved.getServiceType(),
                saved.getShippingFee(),
                saved.getTrackingNo(),
                saved.getStatus()
        ));

        return toResponse(saved);
    }

    @Override
    @Transactional
    public ShipmentResponse createShipmentForPaidOrder(UUID orderId) {
        if (orderId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "orderId không được để trống");
        }
        Optional<Shipment> existing = shipmentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        CreateShipmentRequest request = new CreateShipmentRequest(
                orderId,
                null,
                null,
                "STANDARD",
                null,
                null,
                null,
                null,
                null,
                500,
                null,
                BigDecimal.ZERO,
                "Auto-created from OrderPaid"
        );
        return createShipment(request, "order-paid:" + orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentResponse getShipment(UUID shipmentId) {
        Shipment shipment = shipmentRepository.findWithDetailsById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn với ID: " + shipmentId));
        return toResponse(shipment);
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentResponse getShipmentByOrderId(UUID orderId) {
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn cho đơn hàng: " + orderId));
        return toResponse(shipment);
    }

    @Override
    @Transactional
    public ShipmentTrackingResponse trackShipment(UUID shipmentId) {
        Shipment shipment = shipmentRepository.findWithDetailsById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn với ID: " + shipmentId));
        return syncAndBuildTracking(shipment);
    }

    @Override
    @Transactional
    public ShipmentTrackingResponse trackByTrackingNumber(String trackingNumber) {
        Shipment shipment = shipmentRepository.findByTrackingNo(trackingNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn với mã tracking: " + trackingNumber));
        return syncAndBuildTracking(shipment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CarrierInfoResponse> listCarriers() {
        String defaultCode = properties.getShipping().getDefaultCarrier() != null
                ? properties.getShipping().getDefaultCarrier().trim().toUpperCase(Locale.ROOT)
                : "GHN";
        return adapterRegistry.getSupportedAdapters().stream()
                .map(adapter -> {
                    String code = adapter.getCarrierCode();
                    Optional<Carrier> carrierEntity = carrierRepository.findByCodeIgnoreCase(code);
                    String name = carrierEntity.map(Carrier::getName).orElse(code);
                    boolean active = carrierEntity.map(Carrier::isActive).orElse(true);
                    return new CarrierInfoResponse(code, name, active, code.equalsIgnoreCase(defaultCode));
                })
                .toList();
    }

    private ShipmentTrackingResponse syncAndBuildTracking(Shipment shipment) {
        String carrierCode = shipment.getCarrierCode() != null
                ? shipment.getCarrierCode()
                : shipment.getCarrier().getCode();
        ShippingCarrierAdapter adapter = adapterRegistry.getRequiredAdapter(carrierCode);
        CarrierTrackingResult tracking = adapter.trackShipment(shipment.getTrackingNo());

        if (tracking.mappedStatus() != null && tracking.mappedStatus() != shipment.getStatus()) {
            shipment.setStatus(tracking.mappedStatus());
            shipmentRepository.save(shipment);
            historyRepository.save(new ShipmentStatusHistory(
                    shipment,
                    tracking.carrierStatus(),
                    tracking.mappedStatus(),
                    tracking.updatedAt() != null ? tracking.updatedAt() : Instant.now()
            ));
        }

        List<ShipmentTrackingResponse.TrackingHistoryItem> history = historyRepository
                .findByShipmentIdOrderByOccurredAtDesc(shipment.getId())
                .stream()
                .map(h -> new ShipmentTrackingResponse.TrackingHistoryItem(
                        h.getCarrierStatus(),
                        h.getInternalStatus(),
                        h.getOccurredAt()
                ))
                .toList();

        return new ShipmentTrackingResponse(
                shipment.getId(),
                shipment.getOrderId(),
                carrierCode,
                shipment.getTrackingNo(),
                shipment.getStatus(),
                tracking.carrierStatus(),
                tracking.currentLocation(),
                shipment.getEstimatedDeliveryAt(),
                history
        );
    }

    private Warehouse resolveWarehouse(UUID warehouseId) {
        if (warehouseId != null) {
            return warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            "Không tìm thấy kho hàng với ID: " + warehouseId));
        }
        String defaultCode = properties.getDefaultWarehouseCode();
        return warehouseRepository.findByCode(defaultCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy kho mặc định: " + defaultCode));
    }

    private Carrier resolveOrProvisionCarrier(String carrierCode) {
        String normalized = carrierCode.trim().toUpperCase(Locale.ROOT);
        return carrierRepository.findByCodeIgnoreCase(normalized)
                .map(existing -> {
                    if (!existing.isActive()) {
                        throw new BusinessException(
                                ErrorCode.BUSINESS_ERROR,
                                "Hãng vận chuyển " + normalized + " hiện đang tạm ngưng hoạt động");
                    }
                    return existing;
                })
                .orElseGet(() -> carrierRepository.save(new Carrier(
                        UUID.randomUUID(),
                        normalized,
                        normalized + " Carrier",
                        true
                )));
    }

    private boolean isFreeShipping(BigDecimal orderTotal, BigDecimal threshold) {
        return orderTotal != null && threshold != null && orderTotal.compareTo(threshold) >= 0;
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
