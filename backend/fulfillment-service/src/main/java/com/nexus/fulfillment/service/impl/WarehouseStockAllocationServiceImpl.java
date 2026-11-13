package com.nexus.fulfillment.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.StockAllocationRequest;
import com.nexus.fulfillment.dto.response.StockAllocationResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.WarehouseStockAllocationService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai dịch vụ phân bổ tồn kho đa kho cho đơn hàng (SRS §3.6.1, KH-S3-2).
 *
 * Chức năng:
 * - Thuật toán phân bổ tồn kho thông minh theo đúng quy tắc SRS:
 *   + DEFAULT_WAREHOUSE_ID = WH_001
 *   + MAX_WAREHOUSES_PER_ORDER = 2
 * - Luồng xử lý:
 *   1. Tìm kiếm và kiểm tra số dư tồn khả dụng của từng SKU tại kho mặc định WH_001.
 *   2. Nếu WH_001 đủ hàng cho toàn bộ đơn -> phân bổ 100% tại WH_001 (1 kho).
 *   3. Nếu WH_001 không đủ -> tìm kho đơn lẻ khác đủ hàng, hoặc kết hợp WH_001 với tối đa 1 kho phụ (tổng 2 kho).
 *   4. Nếu cần từ 3 kho trở lên để gom đủ hàng cho đơn -> từ chối và ném INVENTORY_INSUFFICIENT.
 *
 * Quy tắc và lý do:
 * - Giới hạn tối đa 2 kho/đơn hàng nhằm kiểm soát chi phí vận chuyển, tránh phân mảnh kiện hàng gây trải nghiệm xấu cho người mua.
 * - Ưu tiên kho mặc định WH_001 để tối ưu luồng lấy hàng tập trung tại trung tâm fulfillment chính.
 */
@Service
public class WarehouseStockAllocationServiceImpl implements WarehouseStockAllocationService {

    private static final Logger log = LoggerFactory.getLogger(WarehouseStockAllocationServiceImpl.class);

    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final FulfillmentProperties properties;

    public WarehouseStockAllocationServiceImpl(
            InventoryRepository inventoryRepository,
            WarehouseRepository warehouseRepository,
            FulfillmentProperties properties) {
        this.inventoryRepository = inventoryRepository;
        this.warehouseRepository = warehouseRepository;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public StockAllocationResponse computeAllocation(StockAllocationRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Danh sách mặt hàng yêu cầu phân bổ không được rỗng");
        }

        String defaultCode = properties.getDefaultWarehouseCode();
        int maxWarehouses = (properties.getMaxWarehousesPerOrder() != null && properties.getMaxWarehousesPerOrder() > 0)
                ? properties.getMaxWarehousesPerOrder()
                : 2;

        Warehouse defaultWarehouse = warehouseRepository.findByCode(defaultCode).orElse(null);
        List<Warehouse> activeWarehouses = warehouseRepository.findByIsActiveTrue();

        // Bước 1: Thử phân bổ toàn bộ 100% vào kho mặc định (WH_001)
        if (defaultWarehouse != null && defaultWarehouse.isActive()) {
            if (canFulfillAllItems(defaultWarehouse, request.items())) {
                log.info("Đơn hàng {} được phân bổ 100% tại kho mặc định {}", request.orderId(), defaultCode);
                List<StockAllocationResponse.AllocatedSkuItem> items = request.items().stream()
                        .map(i -> new StockAllocationResponse.AllocatedSkuItem(i.skuId(), i.quantity()))
                        .toList();

                return new StockAllocationResponse(
                        request.orderId(),
                        1,
                        true,
                        List.of(new StockAllocationResponse.WarehouseAllocationPlan(
                                defaultWarehouse.getId(),
                                defaultWarehouse.getCode(),
                                defaultWarehouse.getName(),
                                items
                        ))
                );
            }
        }

        // Bước 2: Thử tìm một kho đơn lẻ khác có khả năng đáp ứng 100% các mặt hàng (1 kho)
        for (Warehouse candidate : activeWarehouses) {
            if (defaultWarehouse != null && candidate.getId().equals(defaultWarehouse.getId())) {
                continue;
            }
            if (canFulfillAllItems(candidate, request.items())) {
                log.info("Đơn hàng {} được phân bổ 100% tại kho thay thế {}", request.orderId(), candidate.getCode());
                List<StockAllocationResponse.AllocatedSkuItem> items = request.items().stream()
                        .map(i -> new StockAllocationResponse.AllocatedSkuItem(i.skuId(), i.quantity()))
                        .toList();

                return new StockAllocationResponse(
                        request.orderId(),
                        1,
                        true,
                        List.of(new StockAllocationResponse.WarehouseAllocationPlan(
                                candidate.getId(),
                                candidate.getCode(),
                                candidate.getName(),
                                items
                        ))
                );
            }
        }

        // Bước 3: Thử kết hợp kho mặc định WH_001 + 1 kho phụ (tổng 2 kho)
        if (defaultWarehouse != null && defaultWarehouse.isActive() && maxWarehouses >= 2) {
            StockAllocationResponse splitWithDefault = trySplitBetweenTwoWarehouses(
                    request.orderId(), defaultWarehouse, activeWarehouses, request.items()
            );
            if (splitWithDefault != null) {
                return splitWithDefault;
            }
        }

        // Bước 4: Thử kết hợp bất kỳ 2 kho nào trong hệ thống (tổng 2 kho)
        if (maxWarehouses >= 2) {
            for (Warehouse w1 : activeWarehouses) {
                StockAllocationResponse plan = trySplitBetweenTwoWarehouses(
                        request.orderId(), w1, activeWarehouses, request.items()
                );
                if (plan != null) {
                    return plan;
                }
            }
        }

        // Bước 5: Không thể đáp ứng trong giới hạn MAX_WAREHOUSES_PER_ORDER = 2 kho
        throw new BusinessException(
                ErrorCode.INVENTORY_INSUFFICIENT,
                "Không thể phân bổ đơn hàng " + request.orderId() + ": số lượng kho yêu cầu vượt quá giới hạn "
                        + maxWarehouses + " kho (MAX_WAREHOUSES_PER_ORDER = 2) hoặc tồn kho không đủ khả dụng."
        );
    }

    private boolean canFulfillAllItems(Warehouse warehouse, List<StockAllocationRequest.AllocationItemRequest> items) {
        for (StockAllocationRequest.AllocationItemRequest item : items) {
            Optional<Inventory> inv = inventoryRepository.findBySkuIdAndWarehouseId(item.skuId(), warehouse.getId());
            if (inv.isEmpty() || inv.get().getAvailableQty() < item.quantity()) {
                return false;
            }
        }
        return true;
    }

    private StockAllocationResponse trySplitBetweenTwoWarehouses(
            UUID orderId,
            Warehouse primaryWarehouse,
            List<Warehouse> candidates,
            List<StockAllocationRequest.AllocationItemRequest> items
    ) {
        for (Warehouse secondaryWarehouse : candidates) {
            if (secondaryWarehouse.getId().equals(primaryWarehouse.getId())) {
                continue;
            }

            Map<UUID, Integer> primaryAllocation = new LinkedHashMap<>();
            Map<UUID, Integer> secondaryAllocation = new LinkedHashMap<>();
            boolean satisfied = true;

            for (StockAllocationRequest.AllocationItemRequest item : items) {
                int needed = item.quantity();
                Optional<Inventory> inv1 = inventoryRepository.findBySkuIdAndWarehouseId(item.skuId(), primaryWarehouse.getId());
                int avail1 = inv1.map(Inventory::getAvailableQty).orElse(0);

                int takeFrom1 = Math.min(avail1, needed);
                int remaining = needed - takeFrom1;

                if (remaining > 0) {
                    Optional<Inventory> inv2 = inventoryRepository.findBySkuIdAndWarehouseId(item.skuId(), secondaryWarehouse.getId());
                    int avail2 = inv2.map(Inventory::getAvailableQty).orElse(0);
                    if (avail2 < remaining) {
                        satisfied = false;
                        break;
                    }
                    if (takeFrom1 > 0) {
                        primaryAllocation.put(item.skuId(), takeFrom1);
                    }
                    secondaryAllocation.put(item.skuId(), remaining);
                } else {
                    primaryAllocation.put(item.skuId(), takeFrom1);
                }
            }

            if (satisfied) {
                List<StockAllocationResponse.WarehouseAllocationPlan> plans = new ArrayList<>();
                if (!primaryAllocation.isEmpty()) {
                    List<StockAllocationResponse.AllocatedSkuItem> pItems = primaryAllocation.entrySet().stream()
                            .map(e -> new StockAllocationResponse.AllocatedSkuItem(e.getKey(), e.getValue()))
                            .toList();
                    plans.add(new StockAllocationResponse.WarehouseAllocationPlan(
                            primaryWarehouse.getId(), primaryWarehouse.getCode(), primaryWarehouse.getName(), pItems
                    ));
                }
                if (!secondaryAllocation.isEmpty()) {
                    List<StockAllocationResponse.AllocatedSkuItem> sItems = secondaryAllocation.entrySet().stream()
                            .map(e -> new StockAllocationResponse.AllocatedSkuItem(e.getKey(), e.getValue()))
                            .toList();
                    plans.add(new StockAllocationResponse.WarehouseAllocationPlan(
                            secondaryWarehouse.getId(), secondaryWarehouse.getCode(), secondaryWarehouse.getName(), sItems
                    ));
                }

                log.info("Phân bổ đơn hàng {} thành công trên {} kho: primary={}, secondary={}",
                        orderId, plans.size(), primaryWarehouse.getCode(), secondaryWarehouse.getCode());
                return new StockAllocationResponse(orderId, plans.size(), plans.size() == 1, plans);
            }
        }
        return null;
    }
}
