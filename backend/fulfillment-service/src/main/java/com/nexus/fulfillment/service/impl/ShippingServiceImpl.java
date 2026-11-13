package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.CarrierWebhookRequest;
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
import com.nexus.fulfillment.event.ShipmentDeliveredEvent;
import com.nexus.fulfillment.event.ShipmentFailedEvent;
import com.nexus.fulfillment.event.ShipmentInTransitEvent;
import com.nexus.fulfillment.event.ShipmentPickedUpEvent;
import com.nexus.fulfillment.event.ShipmentReturnedEvent;
import com.nexus.fulfillment.event.ShippingQuoteRequestedEvent;
import com.nexus.fulfillment.repository.CarrierRepository;
import com.nexus.fulfillment.repository.ShipmentRepository;
import com.nexus.fulfillment.repository.ShipmentStatusHistoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.FulfillmentEventOutboxService;
import com.nexus.fulfillment.service.ShippingService;
import com.nexus.fulfillment.shipping.adapter.CarrierAdapterRegistry;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentResult;
import com.nexus.fulfillment.shipping.adapter.CarrierQuoteRequest;
import com.nexus.fulfillment.shipping.adapter.CarrierQuoteResult;
import com.nexus.fulfillment.shipping.adapter.CarrierTrackingResult;
import com.nexus.fulfillment.shipping.adapter.ShippingCarrierAdapter;
import com.nexus.fulfillment.shipping.adapter.resilience.CarrierResilienceManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai Shipping Orchestrator cho Fulfillment Service (SRS §3.6.2, KH-S3-1, KH-S3-2).
 *
 * Chức năng:
 * - Báo giá phí giao hàng và tạo vận đơn kết hợp khả năng chịu lỗi (Circuit Breaker & Retry).
 * - Tiếp nhận và xử lý webhook/callback từ đối tác giao vận theo cơ chế Idempotent (chống xử lý lặp sự kiện).
 * - Đồng bộ định kỳ (polling) trạng thái cho các vận đơn active (CREATED, PENDING, PICKING, PACKED, PICKED_UP, SHIPPED, IN_TRANSIT).
 * - Chuẩn hóa trạng thái và phát sinh domain events tương ứng khi có state transition hợp lệ:
 *   ShipmentPickedUpEvent, ShipmentInTransitEvent, ShipmentDeliveredEvent, ShipmentFailedEvent, ShipmentReturnedEvent.
 *
 * Quy tắc và lý do:
 * - Bảo vệ kết nối qua CarrierResilienceManager: lỗi của một hãng không bao giờ làm sập luồng đơn hàng hay nghẽn thread pool.
 * - Sử dụng event_id làm khóa chặn trùng lặp callback ở cả tầng nghiệp vụ và cơ sở dữ liệu.
 */
@Service
public class ShippingServiceImpl implements ShippingService {

    private static final Logger log = LoggerFactory.getLogger(ShippingServiceImpl.class);

    private static final Set<ShipmentStatus> ACTIVE_POLLING_STATUSES = Set.of(
            ShipmentStatus.CREATED,
            ShipmentStatus.PENDING,
            ShipmentStatus.PICKING,
            ShipmentStatus.PACKED,
            ShipmentStatus.PICKED_UP,
            ShipmentStatus.SHIPPED,
            ShipmentStatus.IN_TRANSIT
    );

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository historyRepository;
    private final CarrierRepository carrierRepository;
    private final WarehouseRepository warehouseRepository;
    private final CarrierAdapterRegistry adapterRegistry;
    private final FulfillmentProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final FulfillmentEventOutboxService outboxService;
    private final CarrierResilienceManager resilienceManager;
    private com.nexus.fulfillment.service.ShipmentDispatchGuard dispatchGuard;

    /** Spring bắt buộc tiêm guard DB; constructor unit test phải gắn fixture riêng, không cho production bỏ qua dấu gửi. */
    @Autowired
    public void setDispatchGuard(com.nexus.fulfillment.service.ShipmentDispatchGuard guard) { this.dispatchGuard = guard; }

    public ShippingServiceImpl(
            ShipmentRepository shipmentRepository,
            ShipmentStatusHistoryRepository historyRepository,
            CarrierRepository carrierRepository,
            WarehouseRepository warehouseRepository,
            CarrierAdapterRegistry adapterRegistry,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher,
            FulfillmentEventOutboxService outboxService) {
        this(shipmentRepository, historyRepository, carrierRepository, warehouseRepository,
                adapterRegistry, properties, eventPublisher, outboxService, new CarrierResilienceManager(properties));
    }

    @Autowired
    public ShippingServiceImpl(
            ShipmentRepository shipmentRepository,
            ShipmentStatusHistoryRepository historyRepository,
            CarrierRepository carrierRepository,
            WarehouseRepository warehouseRepository,
            CarrierAdapterRegistry adapterRegistry,
            FulfillmentProperties properties,
            ApplicationEventPublisher eventPublisher,
            FulfillmentEventOutboxService outboxService,
            CarrierResilienceManager resilienceManager) {
        this.shipmentRepository = shipmentRepository;
        this.historyRepository = historyRepository;
        this.carrierRepository = carrierRepository;
        this.warehouseRepository = warehouseRepository;
        this.adapterRegistry = adapterRegistry;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.outboxService = outboxService;
        this.resilienceManager = resilienceManager;
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
            try {
                // Bọc gọi quote qua Circuit Breaker & Retry của từng carrier
                CarrierQuoteResult quote = resilienceManager.executeWithResilience(
                        adapter.getCarrierCode(),
                        "quote",
                        () -> adapter.quote(carrierRequest),
                        null
                );

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
            } catch (Exception ex) {
                log.warn("Không thể lấy báo giá từ hãng {}: {}", adapter.getCarrierCode(), ex.getMessage());
                // Khi một hãng bị lỗi hoặc Circuit Breaker OPEN, không chặn toàn bộ luồng báo giá
            }
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

        // Tạo vận đơn chỉ một lần qua Circuit Breaker; timeout phải đối soát carrier, không retry mutation tự động.
        if (dispatchGuard == null) { throw new IllegalStateException("Thiếu durable dispatch guard"); }
        // Snapshot chuẩn hóa sau resolve kho/carrier được commit trước khi HTTP bắt đầu.
        var dispatchRequest = new CarrierCreateShipmentRequest(request.orderId(), warehouse.getId(), warehouse.getAddress(),
                request.recipientName(), request.recipientPhone(), request.shippingAddress(), request.toDistrictId(),
                request.toWardCode(), request.weightGrams(), request.codAmount(), request.orderTotal(), request.serviceType(), request.note());
        CarrierCreateShipmentResult carrierResult = dispatchGuard.send(adapter.getCarrierCode(), dispatchRequest,
                () -> resilienceManager.executeWithResilience(
                adapter.getCarrierCode(),
                "createShipment",
                () -> adapter.createShipment(dispatchRequest),
                null
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

    /**
     * Tiếp nhận và xử lý webhook/callback từ đối tác giao vận một cách idempotent (SRS §3.6.2, KH-S3-2).
     *
     * Luồng xử lý:
     * 1. Tìm vận đơn theo trackingNumber (ném SHIPMENT_NOT_FOUND nếu không tồn tại).
     * 2. Kiểm tra tính Idempotent: nếu eventId đã được xử lý trước đó, bỏ qua xử lý và trả về dữ liệu hiện tại ngay.
     * 3. Ánh xạ trạng thái carrierStatus sang ShipmentStatus nội bộ.
     * 4. Kiểm tra luồng trạng thái: nếu trạng thái không đổi hoặc đã ở trạng thái kết thúc (DELIVERED, RETURNED), bỏ qua phát sự kiện lặp.
     * 5. Cập nhật trạng thái, lưu lịch sử kèm eventId và phát sự kiện miền tương ứng.
     */
    @Override
    @Transactional
    public ShipmentResponse processCarrierWebhook(String carrierCode, CarrierWebhookRequest request) {
        if (request == null || request.trackingNumber() == null || request.trackingNumber().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thiếu mã vận đơn trackingNumber trong callback");
        }

        Shipment shipment = shipmentRepository.findByTrackingNo(request.trackingNumber().trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn với mã tracking: " + request.trackingNumber()));

        String eventId = (request.eventId() != null && !request.eventId().isBlank()) ? request.eventId().trim() : null;

        // Kiểm tra Idempotent theo event_id
        if (eventId != null && historyRepository.existsByEventId(eventId)) {
            log.info("Callback mang eventId={} đã được xử lý trước đó (Idempotent), bỏ qua cập nhật lặp", eventId);
            return toResponse(shipment);
        }

        String effectiveCarrierCode = (carrierCode != null && !carrierCode.isBlank())
                ? carrierCode
                : (request.carrierCode() != null && !request.carrierCode().isBlank())
                ? request.carrierCode()
                : shipment.getCarrierCode();

        Optional<ShippingCarrierAdapter> adapterOpt = adapterRegistry.findAdapter(effectiveCarrierCode);
        ShipmentStatus mappedStatus;
        if (adapterOpt.isPresent()) {
            mappedStatus = adapterOpt.get().mapCarrierStatus(request.status());
        } else {
            mappedStatus = mapFallbackStatus(request.status());
        }

        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();

        // Nếu trạng thái giống hệt trạng thái hiện tại: chỉ ghi nhận eventId vào lịch sử nếu có, không phát sự kiện lặp
        if (mappedStatus == shipment.getStatus()) {
            if (eventId != null) {
                historyRepository.save(new ShipmentStatusHistory(
                        shipment,
                        request.status(),
                        mappedStatus,
                        eventId,
                        request.description(),
                        occurredAt
                ));
            }
            return toResponse(shipment);
        }

        // Bỏ qua cập nhật trễ (out-of-order) nếu vận đơn đã ở trạng thái kết thúc và trạng thái mới không hợp lệ
        if (!shipment.getStatus().canTransitionTo(mappedStatus)) {
            log.warn("Vận đơn {} hiện ở trạng thái {}, từ chối chuyển về trạng thái không hợp lệ {} từ webhook",
                    shipment.getId(), shipment.getStatus(), mappedStatus);
            if (eventId != null) {
                historyRepository.save(new ShipmentStatusHistory(
                        shipment,
                        request.status(),
                        shipment.getStatus(),
                        eventId,
                        "Ignored invalid transition to " + mappedStatus + ": " + request.description(),
                        occurredAt
                ));
            }
            return toResponse(shipment);
        }

        applyStateTransition(
                shipment,
                mappedStatus,
                request.status(),
                request.location(),
                eventId,
                request.description(),
                occurredAt
        );

        return toResponse(shipment);
    }

    @Override
    @Transactional
    public ShipmentResponse updateShipmentStatus(
            UUID shipmentId,
            ShipmentStatus newStatus,
            String carrierStatus,
            String eventId,
            String notes
    ) {
        Shipment shipment = shipmentRepository.findWithDetailsById(shipmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.SHIPMENT_NOT_FOUND,
                        "Không tìm thấy vận đơn với ID: " + shipmentId));

        if (eventId != null && !eventId.isBlank() && historyRepository.existsByEventId(eventId)) {
            log.info("Sự kiện cập nhật trạng thái với eventId={} đã tồn tại (Idempotent), bỏ qua", eventId);
            return toResponse(shipment);
        }

        if (shipment.getStatus() == newStatus) {
            return toResponse(shipment);
        }

        applyStateTransition(shipment, newStatus, carrierStatus, null, eventId, notes, Instant.now());
        return toResponse(shipment);
    }

    /**
     * Quét và thăm dò hành trình (polling) cho toàn bộ các vận đơn active (KH-S3-2).
     */
    @Override
    @Transactional
    public int pollActiveShipments() {
        List<Shipment> activeShipments = shipmentRepository.findByStatusIn(ACTIVE_POLLING_STATUSES);
        int updatedCount = 0;

        for (Shipment shipment : activeShipments) {
            try {
                ShipmentStatus beforeStatus = shipment.getStatus();
                syncAndBuildTracking(shipment);
                if (shipment.getStatus() != beforeStatus) {
                    updatedCount++;
                }
            } catch (Exception ex) {
                log.warn("Lỗi khi polling cập nhật hành trình vận đơn {}: {}", shipment.getId(), ex.getMessage());
            }
        }
        return updatedCount;
    }

    private ShipmentTrackingResponse syncAndBuildTracking(Shipment shipment) {
        String carrierCode = shipment.getCarrierCode() != null
                ? shipment.getCarrierCode()
                : shipment.getCarrier().getCode();
        ShippingCarrierAdapter adapter = adapterRegistry.getRequiredAdapter(carrierCode);

        // Gọi tracking qua Circuit Breaker & Retry
        CarrierTrackingResult tracking;
        try {
            tracking = resilienceManager.executeWithResilience(
                    carrierCode,
                    "trackShipment",
                    () -> adapter.trackShipment(shipment.getTrackingNo()),
                    null
            );
        } catch (Exception ex) {
            log.warn("Không thể đồng bộ hành trình từ hãng {} cho trackingNo={}: {}",
                    carrierCode, shipment.getTrackingNo(), ex.getMessage());
            // Fallback an toàn: trả về trạng thái đã lưu trữ mà không làm sập giao diện người dùng
            return buildCachedTrackingResponse(shipment, carrierCode);
        }

        if (tracking.mappedStatus() != null && tracking.mappedStatus() != shipment.getStatus()) {
            if (shipment.getStatus().canTransitionTo(tracking.mappedStatus())) {
                String deterministicEventId = "poll:" + shipment.getId() + ":" + tracking.mappedStatus() + ":"
                        + (tracking.updatedAt() != null ? tracking.updatedAt().toEpochMilli() : System.currentTimeMillis());

                applyStateTransition(
                        shipment,
                        tracking.mappedStatus(),
                        tracking.carrierStatus(),
                        tracking.currentLocation(),
                        deterministicEventId,
                        "Cập nhật định kỳ từ carrier polling",
                        tracking.updatedAt() != null ? tracking.updatedAt() : Instant.now()
                );
            }
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

    /**
     * Thực hiện chuyển trạng thái vận đơn, lưu lịch sử và phát các sự kiện miền tương ứng (SRS §2.8).
     */
    private void applyStateTransition(
            Shipment shipment,
            ShipmentStatus newStatus,
            String carrierStatus,
            String location,
            String eventId,
            String notes,
            Instant occurredAt
    ) {
        Instant effectiveTime = occurredAt != null ? occurredAt : Instant.now();
        shipment.setStatus(newStatus);
        shipment.setUpdatedAt(Instant.now());
        shipmentRepository.save(shipment);

        historyRepository.save(new ShipmentStatusHistory(
                shipment,
                carrierStatus != null ? carrierStatus : newStatus.name(),
                newStatus,
                eventId,
                notes,
                effectiveTime
        ));

        // Phát sự kiện miền tương ứng và ghi vào Transactional Outbox
        switch (newStatus) {
            case PICKED_UP -> {
                eventPublisher.publishEvent(ShipmentPickedUpEvent.of(
                        shipment.getId(),
                        shipment.getOrderId(),
                        shipment.getCarrierCode(),
                        shipment.getTrackingNo()
                ));
                outboxService.enqueueShipmentPickedUp(shipment);
            }
            case IN_TRANSIT -> {
                eventPublisher.publishEvent(ShipmentInTransitEvent.of(
                        shipment.getId(),
                        shipment.getOrderId(),
                        shipment.getCarrierCode(),
                        shipment.getTrackingNo(),
                        location
                ));
                outboxService.enqueueShipmentInTransit(shipment, location);
            }
            case DELIVERED -> {
                eventPublisher.publishEvent(ShipmentDeliveredEvent.of(
                        shipment.getId(),
                        shipment.getOrderId(),
                        shipment.getCarrierCode(),
                        shipment.getTrackingNo(),
                        effectiveTime
                ));
                outboxService.enqueueShipmentDelivered(shipment);
            }
            case FAILED -> {
                eventPublisher.publishEvent(ShipmentFailedEvent.of(
                        shipment.getId(),
                        shipment.getOrderId(),
                        shipment.getCarrierCode(),
                        shipment.getTrackingNo(),
                        notes
                ));
                outboxService.enqueueShipmentFailed(shipment, notes);
            }
            case RETURNED -> {
                eventPublisher.publishEvent(ShipmentReturnedEvent.of(
                        shipment.getId(),
                        shipment.getOrderId(),
                        shipment.getCarrierCode(),
                        shipment.getTrackingNo(),
                        notes
                ));
                outboxService.enqueueShipmentReturned(shipment, notes);
            }
            default -> log.debug("Chuyển trạng thái nội bộ {} không kích hoạt domain event bổ sung", newStatus);
        }
    }

    private ShipmentTrackingResponse buildCachedTrackingResponse(Shipment shipment, String carrierCode) {
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
                shipment.getStatus().name(),
                null,
                shipment.getEstimatedDeliveryAt(),
                history
        );
    }

    private ShipmentStatus mapFallbackStatus(String raw) {
        if (raw == null) {
            return ShipmentStatus.PENDING;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "created" -> ShipmentStatus.CREATED;
            case "picking" -> ShipmentStatus.PICKING;
            case "packed" -> ShipmentStatus.PACKED;
            case "picked_up", "picked" -> ShipmentStatus.PICKED_UP;
            case "shipped" -> ShipmentStatus.SHIPPED;
            case "delivering", "in_transit" -> ShipmentStatus.IN_TRANSIT;
            case "delivered" -> ShipmentStatus.DELIVERED;
            case "failed", "fail" -> ShipmentStatus.FAILED;
            case "returned", "return" -> ShipmentStatus.RETURNED;
            case "cancel", "cancelled" -> ShipmentStatus.CANCELLED;
            default -> ShipmentStatus.IN_TRANSIT;
        };
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
