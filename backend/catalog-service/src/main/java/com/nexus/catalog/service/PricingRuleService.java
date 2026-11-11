package com.nexus.catalog.service;

import com.nexus.catalog.dto.request.PricingRuleRequest;
import com.nexus.catalog.dto.response.PricePreviewResponse;
import com.nexus.catalog.dto.response.PricingRuleResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.PricingRule;
import com.nexus.catalog.repository.CategoryRepository;
import com.nexus.catalog.repository.PricingRuleRepository;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nghiệp vụ quản lý quy tắc định giá động (CRUD) và xem trước giá bán cho Catalog.
 * Kiểm tra tính hợp lệ của khoảng thời gian hiệu lực và danh mục trước khi lưu xuống database.
 */
@Service
@Transactional
public class PricingRuleService {

    private final PricingRuleRepository pricingRuleRepository;
    private final CategoryRepository categoryRepository;
    private final DynamicPricingService dynamicPricingService;

    public PricingRuleService(
            PricingRuleRepository pricingRuleRepository,
            CategoryRepository categoryRepository,
            DynamicPricingService dynamicPricingService) {
        this.pricingRuleRepository = pricingRuleRepository;
        this.categoryRepository = categoryRepository;
        this.dynamicPricingService = dynamicPricingService;
    }

    /** Lấy các quy tắc đang hiệu lực ở thời điểm hiện tại cho public/seller. */
    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listActive() {
        return pricingRuleRepository.findActiveRulesAt(Instant.now()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Lấy toàn bộ quy tắc (gồm cả tắt/hết hạn) cho trang quản trị Admin. */
    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listAll() {
        return pricingRuleRepository.findAllWithCategoryOrderByPriorityDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    /** Xem trước giá bán động từ giá nhập tối thiểu và danh mục (không ghi DB). */
    @Transactional(readOnly = true)
    public PricePreviewResponse preview(BigDecimal originalPrice, UUID categoryId) {
        if (originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("originalPrice must be >= 0");
        }
        Category category = null;
        if (categoryId != null) {
            category = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
        }
        return dynamicPricingService.previewPrice(originalPrice, category);
    }

    /** Tạo quy tắc định giá mới sau khi kiểm tra khoảng thời gian hiệu lực và danh mục. */
    public PricingRuleResponse create(PricingRuleRequest request) {
        validateDateRange(request);
        PricingRule rule = new PricingRule();
        applyRequest(rule, request);
        return toResponse(pricingRuleRepository.save(rule));
    }

    /** Cập nhật cấu hình quy tắc giá theo ID. */
    public PricingRuleResponse update(UUID id, PricingRuleRequest request) {
        validateDateRange(request);
        PricingRule rule = pricingRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found: " + id));
        applyRequest(rule, request);
        return toResponse(pricingRuleRepository.save(rule));
    }

    /** Xóa quy tắc giá theo ID. */
    public void delete(UUID id) {
        PricingRule rule = pricingRuleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found: " + id));
        pricingRuleRepository.delete(rule);
    }

    private void validateDateRange(PricingRuleRequest request) {
        if (request.startDate() != null && request.endDate() != null
                && request.endDate().isBefore(request.startDate())) {
            throw new BusinessException("endDate must be >= startDate");
        }
    }

    private void applyRequest(PricingRule rule, PricingRuleRequest request) {
        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
        }
        rule.setName(request.name().trim());
        rule.setCategory(category);
        rule.setMarginRate(request.marginRate());
        rule.setSeasonFactor(request.seasonFactor());
        rule.setMarketAdjustmentRate(request.marketAdjustmentRate());
        rule.setPriority(request.priority());
        rule.setStartDate(request.startDate());
        rule.setEndDate(request.endDate());
        rule.setIsActive(request.isActive() == null ? Boolean.TRUE : request.isActive());
    }

    private PricingRuleResponse toResponse(PricingRule rule) {
        Category cat = rule.getCategory();
        return new PricingRuleResponse(
                rule.getId(),
                rule.getName(),
                cat != null ? cat.getId() : null,
                cat != null ? cat.getNameVi() : null,
                rule.getMarginRate(),
                rule.getSeasonFactor(),
                rule.getMarketAdjustmentRate(),
                rule.getPriority(),
                rule.getStartDate(),
                rule.getEndDate(),
                rule.getIsActive(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }
}
