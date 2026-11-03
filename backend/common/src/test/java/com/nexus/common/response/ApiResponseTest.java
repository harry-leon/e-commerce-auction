package com.nexus.common.response;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("ok(data) bọc object hợp lệ và có đủ 4 key: success, data, error=null, meta")
    void testOkWithObject() throws Exception {
        Map<String, String> payload = Map.of("id", "prod-123", "name", "Áo thun");
        ApiResponse<Map<String, String>> response = ApiResponse.ok(payload);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(payload);
        assertThat(response.error()).isNull();
        assertThat(response.meta()).isNotNull();
        assertThat(response.meta().requestId()).isNotBlank();
        assertThat(response.meta().timestamp()).isNotNull();
        assertThat(response.meta().pagination()).isNull();

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("\"data\":{");
        assertThat(json).contains("\"error\":null");
        assertThat(json).contains("\"meta\":{");
        assertThat(json).contains("\"requestId\":");
        assertThat(json).contains("\"timestamp\":");
        assertThat(json).doesNotContain("pagination");
    }

    @Test
    @DisplayName("ok(list) với danh sách rỗng phải trả mảng rỗng [] thay vì null")
    void testOkWithEmptyList() throws Exception {
        ApiResponse<List<String>> response = ApiResponse.ok(Collections.emptyList());

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEmpty();

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains("\"data\":[]");
    }

    @Test
    @DisplayName("ok(page) chứa đầy đủ thông tin phân trang chuẩn trong meta.pagination")
    void testOkWithPage() throws Exception {
        List<String> items = List.of("A", "B", "C");
        PageImpl<String> page = new PageImpl<>(items, PageRequest.of(0, 2), 5);

        ApiResponse<List<String>> response = ApiResponse.ok(page);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly("A", "B", "C");
        assertThat(response.meta().pagination()).isNotNull();
        assertThat(response.meta().pagination().page()).isEqualTo(0);
        assertThat(response.meta().pagination().size()).isEqualTo(2);
        assertThat(response.meta().pagination().totalElements()).isEqualTo(5L);
        assertThat(response.meta().pagination().totalPages()).isEqualTo(3);
        assertThat(response.meta().pagination().hasNext()).isTrue();
        assertThat(response.meta().pagination().hasPrevious()).isFalse();

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains("\"pagination\":{");
        assertThat(json).contains("\"page\":0");
        assertThat(json).contains("\"totalElements\":5");
    }

    @Test
    @DisplayName("error() sinh response thất bại với success=false, data=null và chi tiết error")
    void testError() throws Exception {
        ApiResponse<Void> response = ApiResponse.error("PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm");

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(response.error().message()).isEqualTo("Không tìm thấy sản phẩm");
        assertThat(response.error().details()).isNull();

        String json = objectMapper.writeValueAsString(response);
        assertThat(json).contains("\"success\":false");
        assertThat(json).contains("\"data\":null");
        assertThat(json).contains("\"error\":{");
        assertThat(json).contains("\"code\":\"PRODUCT_NOT_FOUND\"");
        assertThat(json).contains("\"details\":null");
    }
}
