package com.nexus.catalog.repository;

import com.nexus.catalog.entity.PricingRule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Truy vấn các quy tắc định giá động trong bảng pricing_rules theo độ ưu tiên và thời gian hiệu lực. */
public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    /** Lấy danh sách quy tắc đang bật và còn hiệu lực tại thời điểm now, nạp sẵn category để tránh N+1. */
    @Query("""
            select r from PricingRule r
            left join fetch r.category
            where r.isActive = true
              and (r.startDate is null or r.startDate <= :now)
              and (r.endDate is null or r.endDate >= :now)
            order by r.priority desc, r.createdAt desc
            """)
    List<PricingRule> findActiveRulesAt(@Param("now") Instant now);

    /** Lấy toàn bộ quy tắc phục vụ màn hình quản trị sắp xếp theo độ ưu tiên giảm dần. */
    @Query("select r from PricingRule r left join fetch r.category order by r.priority desc, r.createdAt desc")
    List<PricingRule> findAllWithCategoryOrderByPriorityDesc();
}
