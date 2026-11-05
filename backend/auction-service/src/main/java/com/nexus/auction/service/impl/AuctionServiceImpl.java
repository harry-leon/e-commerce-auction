package com.nexus.auction.service.impl;

import com.nexus.auction.config.AuctionProperties;
import com.nexus.auction.dto.mapper.AuctionMapper;
import com.nexus.auction.dto.request.CancelAuctionRequest;
import com.nexus.auction.dto.request.CreateAuctionRequest;
import com.nexus.auction.dto.request.UpdateAuctionRequest;
import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.service.AuctionService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai dịch vụ quản lý phiên đấu giá (Auction Session Management).
 *
 * Chức năng:
 * - Tạo phiên đấu giá mới với kiểm tra toàn vẹn thời gian, bước giá, hạn mức người bán và chống trùng phiên.
 * - Truy vấn danh sách phiên đấu giá có phân trang, lọc theo trạng thái, người bán và visibility.
 * - Xem chi tiết phiên đấu giá theo ID.
 * - Cập nhật phiên đấu giá trước giờ bắt đầu (chỉ người tạo hoặc Admin có quyền).
 * - Hủy phiên đấu giá theo quy định nghiệp vụ (chặn hủy khi đã kết thúc hoặc đã quyết toán).
 *
 * Quy tắc và lý do:
 * - Tuân thủ SRS §3.5, Epic 1 (US-AUC-01) và BR-CFG-24.
 * - Toàn bộ cấu hình nghiệp vụ (bước giá, thời lượng tối thiểu/tối đa, số phiên mở tối đa) được nạp động từ Config Server.
 * - Sử dụng @Transactional đảm bảo tính toàn vẹn dữ liệu cho các thao tác ghi.
 * - Tận dụng ErrorCode chuẩn từ nexus-common.
 */
@Service
public class AuctionServiceImpl implements AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionServiceImpl.class);

    private static final List<AuctionStatus> ACTIVE_STATUSES = Arrays.asList(AuctionStatus.SCHEDULED, AuctionStatus.ACTIVE);

    private final AuctionRepository auctionRepository;
    private final AuctionProperties auctionProperties;

    public AuctionServiceImpl(AuctionRepository auctionRepository, AuctionProperties auctionProperties) {
        this.auctionRepository = auctionRepository;
        this.auctionProperties = auctionProperties;
    }

    /**
     * Tạo phiên đấu giá mới cho Seller.
     * Luồng:
     *   1. Kiểm tra bước giá bidIncrement >= cấu hình tối thiểu (BR-CFG-24).
     *   2. Kiểm tra khung thời gian: startTime < endTime, thời lượng từ minDurationSeconds đến maxDurationSeconds nạp từ Config Server.
     *   3. Kiểm tra hạn mức phiên đang mở của Seller (mặc định tối đa 20 phiên theo cấu hình).
     *   4. Kiểm tra sản phẩm/SKU chưa có phiên nào đang ở trạng thái SCHEDULED hoặc ACTIVE.
     *   5. Khởi tạo thực thể Auction với trạng thái SCHEDULED và lưu vào DB.
     */
    @Override
    @Transactional
    public AuctionResponse createAuction(UUID sellerId, CreateAuctionRequest request) {
        if (sellerId == null) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "Không xác định được danh tính người bán");
        }

        Instant now = Instant.now();
        validateAuctionTiming(request.getStartTime(), request.getEndTime(), now, true);
        validateBidIncrement(request.getBidIncrement());

        // Kiểm tra hạn mức phiên đang hoạt động của người bán
        int maxActive = auctionProperties.getRules().getMaxActiveAuctionsPerSeller();
        long currentActiveCount = auctionRepository.countBySellerIdAndStatusIn(sellerId, ACTIVE_STATUSES);
        if (currentActiveCount >= maxActive) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Bạn đã đạt giới hạn tối đa " + maxActive + " phiên đấu giá hoạt động cùng thời điểm"
            );
        }

        // Kiểm tra chống trùng phiên đang mở cho cùng một sản phẩm hoặc SKU
        if (auctionRepository.existsByProductIdAndStatusIn(request.getProductId(), ACTIVE_STATUSES)) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Sản phẩm này hiện đang có phiên đấu giá khác đang mở hoặc đang diễn ra"
            );
        }
        if (request.getSkuId() != null && auctionRepository.existsBySkuIdAndStatusIn(request.getSkuId(), ACTIVE_STATUSES)) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Biến thể SKU này hiện đang có phiên đấu giá khác đang mở hoặc đang diễn ra"
            );
        }

        Auction auction = new Auction();
        auction.setId(UUID.randomUUID());
        auction.setProductId(request.getProductId());
        auction.setSkuId(request.getSkuId());
        auction.setSellerId(sellerId);
        auction.setStartPrice(request.getStartPrice());
        auction.setBidIncrement(request.getBidIncrement());
        auction.setCurrentPrice(request.getStartPrice());
        auction.setVisibility(request.getVisibility() != null ? request.getVisibility() : AuctionVisibility.PUBLIC);
        auction.setStatus(AuctionStatus.SCHEDULED);
        auction.setStartTime(request.getStartTime());
        auction.setEndTime(request.getEndTime());
        auction.setExtensionCount(0);
        auction.setVersion(1);

        Auction saved = auctionRepository.save(auction);
        log.info("Người bán [{}] đã tạo phiên đấu giá [{}] cho sản phẩm [{}] thành công",
                sellerId, saved.getId(), saved.getProductId());

        return AuctionMapper.toResponse(saved);
    }

    /**
     * Tra cứu danh sách phiên đấu giá có phân trang và lọc tiêu chí.
     * Luồng: Xây dựng JPA Specification kết hợp các điều kiện status, sellerId, visibility.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<AuctionResponse> getAuctions(
            AuctionStatus status,
            UUID sellerId,
            AuctionVisibility visibility,
            Pageable pageable) {

        Specification<Auction> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (sellerId != null) {
                predicates.add(cb.equal(root.get("sellerId"), sellerId));
            }
            if (visibility != null) {
                predicates.add(cb.equal(root.get("visibility"), visibility));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return auctionRepository.findAll(spec, pageable).map(AuctionMapper::toResponse);
    }

    /**
     * Lấy chi tiết phiên đấu giá theo ID.
     */
    @Override
    @Transactional(readOnly = true)
    public AuctionResponse getAuctionById(UUID auctionId) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Không tìm thấy phiên đấu giá: " + auctionId));
        return AuctionMapper.toResponse(auction);
    }

    /**
     * Cập nhật thông số phiên đấu giá trước giờ bắt đầu.
     * Luồng:
     *   1. Tìm kiếm phiên đấu giá theo ID.
     *   2. Kiểm tra quyền sở hữu (chỉ Seller tạo phiên hoặc ADMIN).
     *   3. Kiểm tra trạng thái: chỉ cho phép sửa khi status là DRAFT hoặc SCHEDULED và now < startTime.
     *   4. Cập nhật các trường được chỉ định sau khi validate hợp lệ.
     */
    @Override
    @Transactional
    public AuctionResponse updateAuction(
            UUID auctionId,
            UUID currentUserId,
            boolean isAdmin,
            UpdateAuctionRequest request) {

        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Không tìm thấy phiên đấu giá: " + auctionId));

        validateOwnership(auction, currentUserId, isAdmin);

        Instant now = Instant.now();
        if (auction.getStatus() != AuctionStatus.SCHEDULED && auction.getStatus() != AuctionStatus.DRAFT) {
            throw new BusinessException(
                    ErrorCode.AUCTION_ALREADY_STARTED,
                    "Không thể chỉnh sửa phiên đấu giá ở trạng thái: " + auction.getStatus()
            );
        }
        if (!now.isBefore(auction.getStartTime())) {
            throw new BusinessException(
                    ErrorCode.AUCTION_ALREADY_STARTED,
                    "Không thể sửa thông số khi phiên đấu giá đã đến hoặc vượt quá thời gian bắt đầu"
            );
        }

        Instant newStartTime = request.getStartTime() != null ? request.getStartTime() : auction.getStartTime();
        Instant newEndTime = request.getEndTime() != null ? request.getEndTime() : auction.getEndTime();

        if (request.getStartTime() != null || request.getEndTime() != null) {
            validateAuctionTiming(newStartTime, newEndTime, now, false);
            auction.setStartTime(newStartTime);
            auction.setEndTime(newEndTime);
        }

        if (request.getBidIncrement() != null) {
            validateBidIncrement(request.getBidIncrement());
            auction.setBidIncrement(request.getBidIncrement());
        }

        if (request.getStartPrice() != null) {
            if (request.getStartPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Giá khởi điểm phải lớn hơn 0");
            }
            auction.setStartPrice(request.getStartPrice());
            // Nếu chưa có ai đặt giá thì đồng bộ currentPrice
            if (auction.getHighestBidderId() == null) {
                auction.setCurrentPrice(request.getStartPrice());
            }
        }

        if (request.getVisibility() != null) {
            auction.setVisibility(request.getVisibility());
        }

        Auction updated = auctionRepository.save(auction);
        log.info("Phiên đấu giá [{}] đã được cập nhật bởi user [{}]", auctionId, currentUserId);
        return AuctionMapper.toResponse(updated);
    }

    /**
     * Hủy phiên đấu giá theo quy định nghiệp vụ.
     * Luồng:
     *   1. Tìm kiếm phiên đấu giá.
     *   2. Kiểm tra quyền sở hữu (Seller tạo phiên hoặc ADMIN).
     *   3. Chặn hủy nếu phiên đã kết thúc (ENDED) hoặc đã quyết toán (SETTLED) hoặc đã bị hủy (CANCELLED).
     *   4. Chuyển trạng thái sang CANCELLED và ghi log lý do.
     */
    @Override
    @Transactional
    public AuctionResponse cancelAuction(
            UUID auctionId,
            UUID currentUserId,
            boolean isAdmin,
            CancelAuctionRequest request) {

        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Không tìm thấy phiên đấu giá: " + auctionId));

        validateOwnership(auction, currentUserId, isAdmin);

        if (auction.getStatus() == AuctionStatus.CANCELLED) {
            // Idempotent: nếu đã hủy thì trả về kết quả hiện tại
            return AuctionMapper.toResponse(auction);
        }

        if (auction.getStatus() == AuctionStatus.ENDED || auction.getStatus() == AuctionStatus.SETTLED) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Không thể hủy phiên đấu giá đã kết thúc hoặc đã quyết toán"
            );
        }

        String reason = (request != null && request.getReason() != null) ? request.getReason().trim() : "Người dùng yêu cầu hủy";
        auction.setStatus(AuctionStatus.CANCELLED);
        Auction cancelled = auctionRepository.save(auction);

        log.info("Phiên đấu giá [{}] đã bị hủy bởi user [{}], lý do: {}", auctionId, currentUserId, reason);
        return AuctionMapper.toResponse(cancelled);
    }

    /**
     * Kiểm tra tính hợp lệ của thời gian bắt đầu và kết thúc phiên đấu giá dựa trên cấu hình tập trung.
     */
    private void validateAuctionTiming(Instant startTime, Instant endTime, Instant now, boolean isNew) {
        if (startTime == null || endTime == null) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Thời gian bắt đầu và kết thúc không được để trống");
        }

        if (isNew && !startTime.isAfter(now)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Thời gian bắt đầu phải ở tương lai");
        }

        if (!endTime.isAfter(startTime)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Thời gian kết thúc phải diễn ra sau thời gian bắt đầu");
        }

        long minDurationSeconds = auctionProperties.getRules().getMinDurationSeconds();
        long maxDurationSeconds = auctionProperties.getRules().getMaxDurationSeconds();

        long durationSeconds = Duration.between(startTime, endTime).getSeconds();
        if (durationSeconds < minDurationSeconds) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Thời lượng phiên đấu giá tối thiểu phải từ " + (minDurationSeconds / 60) + " phút"
            );
        }
        if (durationSeconds > maxDurationSeconds) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_ERROR,
                    "Thời lượng phiên đấu giá tối đa không được vượt quá " + (maxDurationSeconds / 86400) + " ngày"
            );
        }
    }

    /**
     * Kiểm tra tính hợp lệ của bước giá dựa trên cấu hình hệ thống (BR-CFG-24).
     */
    private void validateBidIncrement(BigDecimal bidIncrement) {
        if (bidIncrement == null || bidIncrement.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Bước giá phải lớn hơn 0");
        }

        Long minIncrementConfig = auctionProperties.getBidding().getMinIncrement();
        if (minIncrementConfig != null && bidIncrement.compareTo(BigDecimal.valueOf(minIncrementConfig)) < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Bước giá tối thiểu phải từ " + String.format("%,d", minIncrementConfig) + " VND"
            );
        }
    }

    /**
     * Kiểm tra quyền sở hữu phiên đấu giá (IDOR protection).
     */
    private void validateOwnership(Auction auction, UUID currentUserId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (currentUserId == null || !Objects.equals(auction.getSellerId(), currentUserId)) {
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Bạn không có quyền thao tác trên phiên đấu giá của người khác"
            );
        }
    }
}
