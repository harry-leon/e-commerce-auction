package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bảo vệ contract dashboard: tổng hợp toàn DB và luôn trả đủ bảy ngày kể cả ngày không có đơn. */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {
    @Mock private JdbcTemplate jdbc;
    @InjectMocks private AdminDashboardService service;

    @Test
    void returnsFullOrderTotalsAndSevenDaySeries() {
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of(
                "total_orders", 57L,
                "gmv", new BigDecimal("16850000.00")));

        var result = service.summary();

        assertEquals(57L, result.totalOrders());
        assertEquals(new BigDecimal("16850000.00"), result.gmv());
        assertEquals(7, result.daily().size());
        assertEquals(0L, result.daily().getFirst().orderCount());
    }
}
