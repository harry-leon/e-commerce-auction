package com.nexus.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.config.CommonAutoConfiguration;
import com.nexus.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandlerTest.TestSampleController.class, CommonAutoConfiguration.class})
class GlobalExceptionHandlerTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication
    static class TestApplication {}

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Test DTO
    record SampleRequest(
            @NotBlank(message = "Tên sản phẩm không được để trống")
            String name,

            @Positive(message = "Giá bán sản phẩm phải lớn hơn 0")
            Double basePrice,

            @Min(value = 1, message = "Số lượng tối thiểu là 1")
            Integer quantity
    ) {}

    @RestController
    static class TestSampleController {

        @PostMapping("/test/validate")
        public ApiResponse<String> testValidate(@Valid @RequestBody SampleRequest request) {
            return ApiResponse.ok("success");
        }

        @GetMapping("/test/param-type/{id}")
        public ApiResponse<Integer> testParamType(@PathVariable Integer id) {
            return ApiResponse.ok(id);
        }

        @GetMapping("/test/required-param")
        public ApiResponse<String> testRequiredParam(@RequestParam String code) {
            return ApiResponse.ok(code);
        }

        @GetMapping("/test/required-header")
        public ApiResponse<String> testRequiredHeader(@RequestHeader("Idempotency-Key") String header) {
            return ApiResponse.ok(header);
        }

        @GetMapping("/test/business-conflict")
        public ApiResponse<Void> testBusinessConflict() {
            throw new BusinessException(ErrorCode.INVENTORY_INSUFFICIENT,
                    "Số lượng tồn kho khả dụng không đủ",
                    Map.of("skuId", "sku-1", "requested", 5, "available", 2));
        }

        @GetMapping("/test/resource-not-found")
        public ApiResponse<Void> testResourceNotFound() {
            throw new ResourceNotFoundException(ErrorCode.PRODUCT_NOT_FOUND, "Không tìm thấy sản phẩm");
        }

        @GetMapping("/test/access-denied")
        public ApiResponse<Void> testAccessDenied() {
            throw new AccessDeniedException("Forbidden");
        }

        @GetMapping("/test/internal-error")
        public ApiResponse<Void> testInternalError() {
            throw new RuntimeException("DB Connection Crash");
        }
    }

    @Test
    @DisplayName("Validation thất bại trả HTTP 422 VALIDATION_ERROR với mảng details chuẩn")
    void testValidationFailureReturns422() throws Exception {
        SampleRequest invalidBody = new SampleRequest("", -10.0, 0);

        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidBody)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.data", nullValue()))
                .andExpect(jsonPath("$.error.code", equalTo("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.error.message", equalTo("Dữ liệu yêu cầu không hợp lệ")))
                .andExpect(jsonPath("$.error.details", hasSize(3)))
                .andExpect(jsonPath("$.meta.requestId", notNullValue()))
                .andExpect(jsonPath("$.meta.timestamp", notNullValue()));
    }

    @Test
    @DisplayName("Body JSON sai cú pháp trả HTTP 400 BAD_REQUEST")
    void testMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("BAD_REQUEST")));
    }

    @Test
    @DisplayName("Path param sai kiểu trả HTTP 400 INVALID_PARAM_TYPE")
    void testParamTypeMismatchReturns400() throws Exception {
        mockMvc.perform(get("/test/param-type/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("INVALID_PARAM_TYPE")));
    }

    @Test
    @DisplayName("Thiếu header bắt buộc trả HTTP 400 MISSING_REQUIRED_HEADER")
    void testMissingHeaderReturns400() throws Exception {
        mockMvc.perform(get("/test/required-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("MISSING_REQUIRED_HEADER")));
    }

    @Test
    @DisplayName("ResourceNotFoundException trả HTTP 404 với error.code tương ứng")
    void testResourceNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/test/resource-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("PRODUCT_NOT_FOUND")))
                .andExpect(jsonPath("$.error.details", nullValue()));
    }

    @Test
    @DisplayName("BusinessException trả HTTP status tương ứng (409) kèm details dạng Object")
    void testBusinessConflictReturns409WithDetails() throws Exception {
        mockMvc.perform(get("/test/business-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_INSUFFICIENT")))
                .andExpect(jsonPath("$.error.details.skuId", equalTo("sku-1")))
                .andExpect(jsonPath("$.error.details.requested", equalTo(5)))
                .andExpect(jsonPath("$.error.details.available", equalTo(2)));
    }

    @Test
    @DisplayName("AccessDeniedException trả HTTP 403 AUTH_FORBIDDEN")
    void testAccessDeniedReturns403() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("AUTH_FORBIDDEN")));
    }

    @Test
    @DisplayName("Lỗi không mong muốn trả HTTP 500 INTERNAL_SERVER_ERROR không để lộ stack trace")
    void testInternalErrorReturns500WithoutStackTrace() throws Exception {
        mockMvc.perform(get("/test/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success", equalTo(false)))
                .andExpect(jsonPath("$.error.code", equalTo("INTERNAL_SERVER_ERROR")))
                .andExpect(jsonPath("$.error.message", equalTo("Đã xảy ra lỗi nội bộ không mong muốn trong hệ thống")))
                .andExpect(jsonPath("$.error.details", nullValue()));
    }
}
