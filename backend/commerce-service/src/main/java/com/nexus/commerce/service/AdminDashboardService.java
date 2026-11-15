package com.nexus.commerce.service;

import com.nexus.commerce.dto.response.AdminDashboardSummaryResponse;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Tổng hợp báo cáo đơn trực tiếp trong Commerce DB bằng truy vấn aggregate, không tải toàn bộ order lên bộ nhớ. */
@Service
public class AdminDashboardService {
    private static final String QUALIFYING_STATUSES = "'PAID','PROCESSING','SHIPPED','DELIVERED','COMPLETED'";
    private final JdbcTemplate jdbc;

    public AdminDashboardService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Trả GMV toàn kỳ, tổng số đơn và chuỗi 7 ngày theo UTC; timestamp cho biết thời điểm truy vấn hoàn tất. */
    public AdminDashboardSummaryResponse summary() {
        var totals = jdbc.queryForMap("SELECT COUNT(*) AS total_orders, "
                + "COALESCE(SUM(total) FILTER (WHERE status IN (" + QUALIFYING_STATUSES + ")), 0) AS gmv FROM orders");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(6);
        Map<LocalDate, AdminDashboardSummaryResponse.DailyOrderMetric> byDate = new HashMap<>();
        for (int day = 0; day < 7; day++) {
            LocalDate date = start.plusDays(day);
            byDate.put(date, new AdminDashboardSummaryResponse.DailyOrderMetric(date, 0, BigDecimal.ZERO));
        }
        jdbc.query("SELECT CAST(created_at AS date) AS report_date, COUNT(*) AS order_count, "
                        + "COALESCE(SUM(total) FILTER (WHERE status IN (" + QUALIFYING_STATUSES + ")), 0) AS gmv "
                        + "FROM orders WHERE created_at >= ? AND created_at < ? "
                        + "GROUP BY CAST(created_at AS date) ORDER BY report_date",
                statement -> {
                    statement.setTimestamp(1, java.sql.Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC)));
                    statement.setTimestamp(2, java.sql.Timestamp.from(today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
                }, result -> {
                    LocalDate date = ((Date) result.getObject("report_date")).toLocalDate();
                    byDate.put(date, new AdminDashboardSummaryResponse.DailyOrderMetric(
                            date, result.getLong("order_count"), result.getBigDecimal("gmv")));
                });
        List<AdminDashboardSummaryResponse.DailyOrderMetric> daily = new ArrayList<>(byDate.values());
        daily.sort(java.util.Comparator.comparing(AdminDashboardSummaryResponse.DailyOrderMetric::date));
        return new AdminDashboardSummaryResponse(
                ((Number) totals.get("total_orders")).longValue(),
                (BigDecimal) totals.get("gmv"), daily, Instant.now());
    }
}
