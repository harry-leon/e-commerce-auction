package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Snapshot dữ liệu đơn hàng cho Dashboard Admin; GMV chỉ gồm đơn đã thu tiền và chưa ở trạng thái REFUNDED. */
public record AdminDashboardSummaryResponse(
        long totalOrders,
        BigDecimal gmv,
        List<DailyOrderMetric> daily,
        Instant updatedAt) {

    /** Số đơn và GMV theo ngày UTC; các ngày không phát sinh giao dịch được trả với giá trị 0. */
    public record DailyOrderMetric(LocalDate date, long orderCount, BigDecimal gmv) { }
}
