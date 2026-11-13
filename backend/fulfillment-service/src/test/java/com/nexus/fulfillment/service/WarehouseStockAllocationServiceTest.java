package com.nexus.fulfillment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.request.StockAllocationRequest;
import com.nexus.fulfillment.dto.response.StockAllocationResponse;
import com.nexus.fulfillment.entity.Inventory;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.repository.InventoryRepository;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.service.impl.WarehouseStockAllocationServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử quy tắc phân bổ tồn kho đa kho (SRS §2.6, §3.6.1, KH-S3-2):
 * - Ưu tiên phân bổ từ kho mặc định DEFAULT_WAREHOUSE_ID = WH_001.
 * - Giới hạn phân bổ tối đa MAX_WAREHOUSES_PER_ORDER = 2 kho cho một đơn hàng.
 * - Từ chối phân bổ nếu cần từ 3 kho trở lên hoặc không đủ hàng.
 */
class WarehouseStockAllocationServiceTest {

    private InventoryRepository inventoryRepository;
    private WarehouseRepository warehouseRepository;
    private FulfillmentProperties properties;
    private WarehouseStockAllocationServiceImpl allocationService;

    private Warehouse wh001;
    private Warehouse wh002;
    private Warehouse wh003;

    @BeforeEach
    void setUp() {
        inventoryRepository = mock(InventoryRepository.class);
        warehouseRepository = mock(WarehouseRepository.class);
        properties = new FulfillmentProperties();
        properties.setDefaultWarehouseCode("WH_001");
        properties.setMaxWarehousesPerOrder(2);

        allocationService = new WarehouseStockAllocationServiceImpl(
                inventoryRepository,
                warehouseRepository,
                properties
        );

        wh001 = new Warehouse(UUID.randomUUID(), "WH_001", "Kho Trung Tam WH001", "TP.HCM", WarehouseType.STANDARD, true);
        wh002 = new Warehouse(UUID.randomUUID(), "WH_002", "Kho Ve Tinh WH002", "Ha Noi", WarehouseType.STANDARD, true);
        wh003 = new Warehouse(UUID.randomUUID(), "WH_003", "Kho Mien Trung WH003", "Da Nang", WarehouseType.STANDARD, true);

        when(warehouseRepository.findByCode("WH_001")).thenReturn(Optional.of(wh001));
        when(warehouseRepository.findByIsActiveTrue()).thenReturn(List.of(wh001, wh002, wh003));
    }

    @Test
    @DisplayName("computeAllocation: ưu tiên tuyệt đối phân bổ 100% tại WH_001 khi đủ tồn")
    void computeAllocation_fulfillsAllFromDefaultWarehouse() {
        UUID orderId = UUID.randomUUID();
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();

        Inventory inv1 = new Inventory(UUID.randomUUID(), sku1, wh001, 100, 0, 0);
        Inventory inv2 = new Inventory(UUID.randomUUID(), sku2, wh001, 50, 0, 0);

        when(inventoryRepository.findBySkuIdAndWarehouseId(sku1, wh001.getId())).thenReturn(Optional.of(inv1));
        when(inventoryRepository.findBySkuIdAndWarehouseId(sku2, wh001.getId())).thenReturn(Optional.of(inv2));

        StockAllocationRequest request = new StockAllocationRequest(
                orderId,
                List.of(
                        new StockAllocationRequest.AllocationItemRequest(sku1, 5),
                        new StockAllocationRequest.AllocationItemRequest(sku2, 10)
                )
        );

        StockAllocationResponse response = allocationService.computeAllocation(request);

        assertThat(response.totalWarehouses()).isEqualTo(1);
        assertThat(response.singleWarehouseFulfilled()).isTrue();
        assertThat(response.allocations()).hasSize(1);
        assertThat(response.allocations().get(0).warehouseCode()).isEqualTo("WH_001");
        assertThat(response.allocations().get(0).items()).hasSize(2);
    }

    @Test
    @DisplayName("computeAllocation: chia tách giữa WH_001 và 1 kho phụ khi WH_001 thiếu hàng (tổng 2 kho)")
    void computeAllocation_splitsBetweenDefaultAndSecondaryWarehouse() {
        UUID orderId = UUID.randomUUID();
        UUID sku1 = UUID.randomUUID();

        // WH_001 chỉ có sẵn 3 cái, nhưng yêu cầu 10 cái -> thiếu 7 cái
        Inventory invWh1 = new Inventory(UUID.randomUUID(), sku1, wh001, 3, 0, 0);
        // WH_002 có sẵn 20 cái -> bù đủ 7 cái còn thiếu
        Inventory invWh2 = new Inventory(UUID.randomUUID(), sku1, wh002, 20, 0, 0);

        when(inventoryRepository.findBySkuIdAndWarehouseId(sku1, wh001.getId())).thenReturn(Optional.of(invWh1));
        when(inventoryRepository.findBySkuIdAndWarehouseId(sku1, wh002.getId())).thenReturn(Optional.of(invWh2));

        StockAllocationRequest request = new StockAllocationRequest(
                orderId,
                List.of(new StockAllocationRequest.AllocationItemRequest(sku1, 10))
        );

        StockAllocationResponse response = allocationService.computeAllocation(request);

        assertThat(response.totalWarehouses()).isEqualTo(2);
        assertThat(response.singleWarehouseFulfilled()).isFalse();
        assertThat(response.allocations()).hasSize(2);

        // WH_001 cấp 3 cái
        assertThat(response.allocations().get(0).warehouseCode()).isEqualTo("WH_001");
        assertThat(response.allocations().get(0).items().get(0).allocatedQuantity()).isEqualTo(3);

        // WH_002 cấp 7 cái còn lại
        assertThat(response.allocations().get(1).warehouseCode()).isEqualTo("WH_002");
        assertThat(response.allocations().get(1).items().get(0).allocatedQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("computeAllocation: từ chối phân bổ khi cần từ 3 kho trở lên (vi phạm MAX_WAREHOUSES_PER_ORDER = 2)")
    void computeAllocation_rejectsWhenRequiresMoreThanMaxWarehouses() {
        UUID orderId = UUID.randomUUID();
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();
        UUID sku3 = UUID.randomUUID();

        // Mỗi kho chỉ có đúng 1 SKU riêng biệt, không kho nào có 2 SKU
        // WH1 chỉ có SKU1
        when(inventoryRepository.findBySkuIdAndWarehouseId(sku1, wh001.getId()))
                .thenReturn(Optional.of(new Inventory(UUID.randomUUID(), sku1, wh001, 10, 0, 0)));
        // WH2 chỉ có SKU2
        when(inventoryRepository.findBySkuIdAndWarehouseId(sku2, wh002.getId()))
                .thenReturn(Optional.of(new Inventory(UUID.randomUUID(), sku2, wh002, 10, 0, 0)));
        // WH3 chỉ có SKU3
        when(inventoryRepository.findBySkuIdAndWarehouseId(sku3, wh003.getId()))
                .thenReturn(Optional.of(new Inventory(UUID.randomUUID(), sku3, wh003, 10, 0, 0)));

        StockAllocationRequest request = new StockAllocationRequest(
                orderId,
                List.of(
                        new StockAllocationRequest.AllocationItemRequest(sku1, 1),
                        new StockAllocationRequest.AllocationItemRequest(sku2, 1),
                        new StockAllocationRequest.AllocationItemRequest(sku3, 1)
                )
        );

        // Cần cả 3 kho -> vi phạm giới hạn 2 kho -> ném lỗi INVENTORY_INSUFFICIENT
        assertThatThrownBy(() -> allocationService.computeAllocation(request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT);
    }
}
