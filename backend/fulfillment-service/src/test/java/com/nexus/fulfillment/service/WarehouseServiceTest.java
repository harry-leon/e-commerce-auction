package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.dto.request.CreateWarehouseRequest;
import com.nexus.fulfillment.dto.request.UpdateWarehouseRequest;
import com.nexus.fulfillment.dto.response.WarehouseResponse;
import com.nexus.fulfillment.entity.Warehouse;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.repository.WarehouseRepository;
import com.nexus.fulfillment.security.InventoryAccessPolicy;
import com.nexus.fulfillment.service.impl.WarehouseServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

/**
 * Kiểm thử đơn vị cho nghiệp vụ quản lý kho hàng (WarehouseServiceImpl):
 * bảo vệ phân quyền quản trị kho, chuẩn hóa mã code, chặn trùng mã kho (HTTP 409) và lọc danh sách kho.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private InventoryAccessPolicy accessPolicy;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    @Test
    @DisplayName("Liệt kê danh sách kho theo bộ lọc và phân trang")
    @SuppressWarnings("unchecked")
    void listWarehouses_MapsPageResponse() {
        Warehouse warehouse = new Warehouse(
                UUID.randomUUID(),
                "WH_001",
                "Kho TP.HCM",
                "123 Nguyen Van Linh",
                WarehouseType.STANDARD,
                true
        );
        Pageable pageable = PageRequest.of(0, 10);
        when(warehouseRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(warehouse), pageable, 1));

        Page<WarehouseResponse> result = warehouseService.listWarehouses(true, WarehouseType.STANDARD, "HCM", pageable);

        assertEquals(1, result.getTotalElements());
        WarehouseResponse item = result.getContent().get(0);
        assertEquals(warehouse.getId(), item.id());
        assertEquals("WH_001", item.code());
        assertEquals("Kho TP.HCM", item.name());
        assertEquals(WarehouseType.STANDARD, item.type());
        assertTrue(item.active());
    }

    @Test
    @DisplayName("Tra cứu kho theo ID và mã code; ném RESOURCE_NOT_FOUND khi không tồn tại")
    void getWarehouseAndByCode_SuccessAndNotFound() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse(
                warehouseId,
                "WH_HN",
                "Kho Ha Noi",
                "Cau Giay, Ha Noi",
                WarehouseType.STANDARD,
                true
        );
        when(warehouseRepository.findById(warehouseId)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.findByCodeIgnoreCase("WH_HN")).thenReturn(Optional.of(warehouse));

        WarehouseResponse byId = warehouseService.getWarehouse(warehouseId);
        WarehouseResponse byCode = warehouseService.getWarehouseByCode("  wh_hn ");

        assertEquals(warehouseId, byId.id());
        assertEquals("WH_HN", byCode.code());

        UUID missingId = UUID.randomUUID();
        when(warehouseRepository.findById(missingId)).thenReturn(Optional.empty());
        BusinessException notFoundEx = assertThrows(BusinessException.class,
                () -> warehouseService.getWarehouse(missingId));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND.name(), notFoundEx.getCode());

        BusinessException blankCodeEx = assertThrows(BusinessException.class,
                () -> warehouseService.getWarehouseByCode("   "));
        assertEquals(ErrorCode.INVALID_PARAM_VALUE.name(), blankCodeEx.getCode());
    }

    @Test
    @DisplayName("Tạo kho mới thành công với Admin, chuẩn hóa mã code và gán mặc định type/active")
    void createWarehouse_NormalizesCodeAndAppliesDefaults() {
        doNothing().when(accessPolicy).verifyWarehouseAdminAccess();
        when(warehouseRepository.existsByCodeIgnoreCase("WH_HCM_02")).thenReturn(false);
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateWarehouseRequest request = new CreateWarehouseRequest(
                " wh_hcm_02 ",
                " Kho Binh Chanh ",
                " 99 Nguyen Huu Tri ",
                null,
                null
        );

        WarehouseResponse created = warehouseService.createWarehouse(request);

        ArgumentCaptor<Warehouse> captor = ArgumentCaptor.forClass(Warehouse.class);
        verify(warehouseRepository).save(captor.capture());
        Warehouse saved = captor.getValue();
        assertEquals("WH_HCM_02", saved.getCode());
        assertEquals("Kho Binh Chanh", saved.getName());
        assertEquals("99 Nguyen Huu Tri", saved.getAddress());
        assertEquals(WarehouseType.STANDARD, saved.getType());
        assertTrue(saved.isActive());
        assertEquals("WH_HCM_02", created.code());
    }

    @Test
    @DisplayName("Chặn tạo kho khi trùng mã code (409) hoặc người gọi không có quyền Admin (403)")
    void createWarehouse_RejectsDuplicateCodeAndForbiddenCaller() {
        doNothing().when(accessPolicy).verifyWarehouseAdminAccess();
        when(warehouseRepository.existsByCodeIgnoreCase("WH_001")).thenReturn(true);

        ApiException duplicateEx = assertThrows(ApiException.class, () -> warehouseService.createWarehouse(
                new CreateWarehouseRequest("wh_001", "Trung ma", "Dia chi", WarehouseType.STANDARD, true)));
        assertEquals("WAREHOUSE_CODE_ALREADY_EXISTS", duplicateEx.getCode());
        assertEquals(HttpStatus.CONFLICT, duplicateEx.getHttpStatus());
        verify(warehouseRepository, never()).save(any());

        doThrow(new BusinessException(ErrorCode.AUTH_FORBIDDEN, "Khong co quyen"))
                .when(accessPolicy).verifyWarehouseAdminAccess();
        BusinessException forbiddenEx = assertThrows(BusinessException.class, () -> warehouseService.createWarehouse(
                new CreateWarehouseRequest("WH_NEW", "Kho moi", "Dia chi", WarehouseType.RETURN, true)));
        assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), forbiddenEx.getCode());
    }

    @Test
    @DisplayName("Cập nhật thông tin kho và trạng thái hoạt động")
    void updateWarehouse_UpdatesFieldsSuccessfully() {
        doNothing().when(accessPolicy).verifyWarehouseAdminAccess();
        UUID warehouseId = UUID.randomUUID();
        Warehouse existing = new Warehouse(
                warehouseId,
                "WH_003",
                "Kho cu",
                "Dia chi cu",
                WarehouseType.STANDARD,
                true
        );
        when(warehouseRepository.findById(warehouseId)).thenReturn(Optional.of(existing));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateWarehouseRequest request = new UpdateWarehouseRequest(
                " Kho Hoan Tra ",
                " 12 Le Loi ",
                WarehouseType.RETURN,
                false
        );

        WarehouseResponse updated = warehouseService.updateWarehouse(warehouseId, request);

        assertEquals("Kho Hoan Tra", updated.name());
        assertEquals("12 Le Loi", updated.address());
        assertEquals(WarehouseType.RETURN, updated.type());
        assertEquals(false, updated.active());
    }
}
