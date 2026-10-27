package com.nexus.commerce.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Lớp cấu hình Type-Safe chứa các thông số nghiệp vụ của commerce-service.
 * Chức năng: Đọc cấu hình từ Spring Cloud Config Server dưới tiền tố 'app.commerce'.
 * Luồng xử lý: Sử dụng @RefreshScope để Spring Cloud tự động nạp lại giá trị mới tại runtime
 *              khi admin kích hoạt endpoint POST /actuator/refresh mà không cần khởi động lại service.
 * Quy tắc: Các thông số bắt buộc phải được validate (@NotNull, @Min) nhằm tránh lỗi logic nghiệp vụ.
 */
@Configuration
@ConfigurationProperties(prefix = "app.commerce")
@RefreshScope
@Validated
@Getter
@Setter
public class CommerceProperties {

    private Cart cart = new Cart();
    private Order order = new Order();

    /**
     * Cấu hình liên quan đến giỏ hàng (Cart)
     */
    @Getter
    @Setter
    public static class Cart {

        /**
         * Thời gian hết hạn của giỏ hàng (ngày) theo Business Rule BR-CFG-15.
         * Mặc định: 7 ngày.
         */
        @NotNull(message = "Thời gian hết hạn giỏ hàng không được để trống")
        @Min(value = 1, message = "Thời gian hết hạn giỏ hàng tối thiểu là 1 ngày")
        private Integer expirationDays = 7;

        /**
         * Thời gian hết hạn tính theo phút (phục vụ kiểm tra linh hoạt hơn).
         * Mặc định: 10080 phút (tương đương 7 ngày).
         */
        @NotNull(message = "Thời gian hết hạn giỏ hàng theo phút không được để trống")
        @Min(value = 1, message = "Thời gian hết hạn giỏ hàng tối thiểu là 1 phút")
        private Integer expireAfterMinutes = 10080;

        /**
         * Số lượng sản phẩm / SKU tối đa được phép lưu trong 1 giỏ hàng.
         * Mặc định: 50.
         */
        @NotNull(message = "Số lượng mặt hàng tối đa trong giỏ không được để trống")
        @Min(value = 1, message = "Giỏ hàng phải chứa được tối thiểu 1 mặt hàng")
        private Integer maxItemsPerCart = 50;
    }

    /**
     * Cấu hình liên quan đến đơn hàng (Order)
     */
    @Getter
    @Setter
    public static class Order {

        /**
         * Số lượng đơn hàng tối đa 1 người dùng được tạo trong ngày.
         * Mặc định: 10 đơn/ngày.
         */
        @NotNull(message = "Giới hạn đơn hàng mỗi ngày không được để trống")
        @Min(value = 1, message = "Giới hạn đơn hàng mỗi ngày tối thiểu là 1")
        private Integer maxDailyOrders = 10;

        /**
         * Thời gian tối đa (phút) cho phép người mua hủy đơn hàng sau khi tạo.
         * Mặc định: 30 phút.
         */
        @NotNull(message = "Thời gian cho phép hủy đơn không được để trống")
        @Min(value = 1, message = "Thời gian cho phép hủy đơn tối thiểu là 1 phút")
        private Integer cancellationTimeoutMinutes = 30;
    }
}
