package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.dto.request.CreateWarehouseRequest;
import com.nexus.fulfillment.dto.request.UpdateWarehouseRequest;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.WarehouseResponse;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.service.InventoryService;
import com.nexus.fulfillment.service.WarehouseService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử tầng REST API của WarehouseController:
 * xác minh phân quyền RBAC (BUYER bị chặn 403, SELLER/SUPPORT chỉ đọc, ADMIN/INVENTORY.MANAGE mới tạo/sửa),
 * validation đầu vào (422) và API xem tồn kho theo kho hàng.
 */
@WebMvcTest(WarehouseController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class WarehouseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WarehouseService warehouseService;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("Seller/Support đọc được danh sách và chi tiết kho; Buyer bị 403; chưa đăng nhập bị 401")
    void readWarehouseEndpoints_EnforcesRbac() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        WarehouseResponse wh = new WarehouseResponse(
                warehouseId,
                "WH_001",
                "Kho TP.HCM",
                "123 Nguyen Van Linh",
                WarehouseType.STANDARD,
                true
        );

        when(warehouseService.listWarehouses(eq(true), eq(WarehouseType.STANDARD), eq("HCM"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(wh), PageRequest.of(0, 50), 1));
        when(warehouseService.getWarehouse(warehouseId)).thenReturn(wh);
        when(warehouseService.getWarehouseByCode("WH_001")).thenReturn(wh);

        // Chưa xác thực -> 401
        mockMvc.perform(get("/api/v1/warehouses"))
                .andExpect(status().isUnauthorized());

        // Buyer -> 403
        mockMvc.perform(get("/api/v1/warehouses").with(roleJwt("ROLE_BUYER")))
                .andExpect(status().isForbidden());

        // Seller -> 200
        mockMvc.perform(get("/api/v1/warehouses")
                        .param("activeOnly", "true")
                        .param("type", "STANDARD")
                        .param("q", "HCM")
                        .with(roleJwt("ROLE_SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("WH_001"));

        // Support đọc chi tiết theo ID & code -> 200
        mockMvc.perform(get("/api/v1/warehouses/{id}", warehouseId).with(roleJwt("ROLE_SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(warehouseId.toString()));

        mockMvc.perform(get("/api/v1/warehouses/code/WH_001").with(roleJwt("ROLE_SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("WH_001"));
    }

    @Test
    @DisplayName("Tra cứu danh sách tồn kho theo kho hàng chuyển đúng warehouseId sang InventoryService")
    void listWarehouseInventory_DelegatesWithWarehouseId() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        InventoryStockResponse stock = new InventoryStockResponse(
                skuId, warehouseId, "WH_001", "Kho TP.HCM", "SKU-1", 50, 45, 5, 0
        );

        when(inventoryService.listInventory(eq(null), eq("SKU-1"), eq(warehouseId), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(stock), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/warehouses/{warehouseId}/inventory", warehouseId)
                        .param("skuCode", "SKU-1")
                        .with(roleJwt("ROLE_SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.data[0].availableQty").value(45));
    }

    @Test
    @DisplayName("Tạo và cập nhật kho: chỉ ADMIN hoặc INVENTORY.MANAGE được phép; SELLER/SUPPORT bị 403; thiếu field trả 422")
    void createAndUpdateWarehouse_EnforcesAdminAndValidation() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        WarehouseResponse created = new WarehouseResponse(
                warehouseId, "WH_RETURN", "Kho Hoan Tra", "Tan Binh, HCM", WarehouseType.RETURN, true
        );
        when(warehouseService.createWarehouse(any(CreateWarehouseRequest.class))).thenReturn(created);
        when(warehouseService.updateWarehouse(eq(warehouseId), any(UpdateWarehouseRequest.class))).thenReturn(created);

        CreateWarehouseRequest validCreate = new CreateWarehouseRequest(
                "WH_RETURN", "Kho Hoan Tra", "Tan Binh, HCM", WarehouseType.RETURN, true
        );

        // Seller & Support không được tạo/cập nhật kho -> 403
        mockMvc.perform(post("/api/v1/warehouses")
                        .with(roleJwt("ROLE_SELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreate)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/warehouses/{id}", warehouseId)
                        .with(roleJwt("ROLE_SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateWarehouseRequest("Kho moi", "Dia chi moi", WarehouseType.STANDARD, true))))
                .andExpect(status().isForbidden());

        // Admin tạo kho -> 201
        mockMvc.perform(post("/api/v1/warehouses")
                        .with(roleJwt("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("WH_RETURN"));

        // Quyền INVENTORY.MANAGE cập nhật kho -> 200
        mockMvc.perform(put("/api/v1/warehouses/{id}", warehouseId)
                        .with(roleJwt("INVENTORY.MANAGE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateWarehouseRequest("Kho Hoan Tra", "Tan Binh, HCM", WarehouseType.RETURN, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("RETURN"));

        // Validation 422 khi để trống mã code
        CreateWarehouseRequest invalidCreate = new CreateWarehouseRequest("", "Ten", "Dia chi", WarehouseType.STANDARD, true);
        mockMvc.perform(post("/api/v1/warehouses")
                        .with(roleJwt("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidCreate)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(warehouseService).createWarehouse(any(CreateWarehouseRequest.class));
        verify(warehouseService).updateWarehouse(eq(warehouseId), any(UpdateWarehouseRequest.class));
    }

    private JwtRequestPostProcessor roleJwt(String authority) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(authority));
    }
}
