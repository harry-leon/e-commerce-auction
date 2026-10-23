package com.nexus.catalog.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Lớp cấu hình thuộc tính nghiệp vụ dành riêng cho catalog-service.
 *
 * Chức năng:
 * - Ánh xạ type-safe các tham số cấu hình từ prefix 'app.catalog' nạp từ Spring Cloud Config Server.
 * - Hỗ trợ cập nhật tham số tại runtime thông qua @RefreshScope và actuator refresh endpoint.
 *
 * Quy tắc và lý do:
 * - Thay thế các hằng số tĩnh hard-code (như ProductConstant.MAX_IMAGES) thành cấu hình động tập trung.
 * - Có sẵn giá trị mặc định để ứng dụng và các unit test vẫn chạy độc lập an toàn khi Config Server chưa bật.
 * - Dùng Bean Validation (@Validated, @NotNull, @Min) ngăn chặn cấu hình sai giá trị âm hoặc vượt ngưỡng.
 */
@Component
@ConfigurationProperties(prefix = "app.catalog")
@RefreshScope
@Validated
@Getter
@Setter
public class CatalogProperties {

    @Valid
    @NotNull
    private Product product = new Product();

    @Valid
    @NotNull
    private Pagination pagination = new Pagination();

    @Getter
    @Setter
    public static class Product {
        /**
         * Số lượng ảnh tối đa cho phép gắn với một sản phẩm.
         * Mặc định là 5 ảnh.
         */
        @NotNull
        @Min(value = 1, message = "Số lượng ảnh tối đa phải lớn hơn hoặc bằng 1")
        @Max(value = 50, message = "Số lượng ảnh tối đa không được vượt quá 50")
        private Integer maxImages = 5;
    }

    @Getter
    @Setter
    public static class Pagination {
        /**
         * Kích thước phân trang mặc định khi truy vấn danh sách sản phẩm.
         */
        @NotNull
        @Min(value = 1, message = "Kích thước trang mặc định phải lớn hơn hoặc bằng 1")
        private Integer defaultPageSize = 20;

        /**
         * Kích thước phân trang tối đa cho phép.
         */
        @NotNull
        @Min(value = 1, message = "Kích thước trang tối đa phải lớn hơn hoặc bằng 1")
        private Integer maxPageSize = 100;
    }
}
