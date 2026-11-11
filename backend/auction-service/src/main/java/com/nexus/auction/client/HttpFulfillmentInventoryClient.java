package com.nexus.auction.client;

import com.nexus.auction.client.dto.ReservationResponseDto;
import com.nexus.auction.client.dto.TransitionAuctionReservationPayload;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Triển khai HTTP Client giao tiếp với Fulfillment Service qua RestClient (SRS §3.6.1).
 *
 * Chức năng:
 * - Gọi endpoint POST /api/v1/inventory/auctions/{auctionId}/transition-to-order bằng token service account nexus-auction-service.
 * - Chuyển tiếp mã tham chiếu giữ kho từ auctionId sang orderId của người thắng cuộc mà không nhả hàng (chống Reserve kép).
 * - Tích hợp cơ chế thử lại an toàn (Safe Retry) với tối đa 3 lần thử khi gặp sự cố mạng tạm thời hoặc lỗi 5xx.
 *
 * Đầu vào và kết quả:
 * - Đầu vào: auctionId, orderId, paymentDeadline.
 * - Kết quả: Danh sách các UUID của phiếu giữ kho (reservationId) đã gắn với orderId.
 *
 * Quy tắc và lý do:
 * - Sử dụng auctionInventoryClient đã cấu hình interceptor token OAuth2 (scope INVENTORY.AUCTION) và timeout 5 giây.
 * - Lỗi 4xx (như không tìm thấy phiếu giữ kho): ném lỗi nghiệp vụ ngay lập tức, không retry.
 * - Lỗi mạng hoặc 5xx: thử lại tối đa 3 lần với thời gian chờ lũy tiến (backoff).
 */
@Component
public class HttpFulfillmentInventoryClient implements FulfillmentInventoryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFulfillmentInventoryClient.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 200L;

    private final RestClient client;

    public HttpFulfillmentInventoryClient(@Qualifier("auctionInventoryClient") RestClient client) {
        this.client = client;
    }

    /**
     * Gửi yêu cầu chuyển tiếp phiếu giữ kho AUCTION -> ORDER sang Fulfillment Service.
     *
     * Luồng xử lý:
     * 1. Đóng gói TransitionAuctionReservationPayload với orderId và paymentDeadline.
     * 2. Gọi POST /api/v1/inventory/auctions/{auctionId}/transition-to-order qua RestClient.
     * 3. Bắt lỗi mạng / 5xx để retry tối đa 3 lần với backoff.
     * 4. Giải nén ApiResponse và trích xuất danh sách reservationId.
     */
    @Override
    public List<UUID> transitionAuctionToOrder(UUID auctionId, UUID orderId, Instant paymentDeadline) {
        if (auctionId == null || orderId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá và mã đơn hàng không được để trống");
        }

        TransitionAuctionReservationPayload payload = new TransitionAuctionReservationPayload(
                orderId,
                null,
                paymentDeadline
        );

        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                log.info("Gửi yêu cầu chuyển tiếp giữ kho AUCTION -> ORDER cho phiên [{}], order [{}], lần thử [{}/{}]",
                        auctionId, orderId, attempt, MAX_RETRY_ATTEMPTS);

                ApiResponse<ReservationResponseDto> response = client.post()
                        .uri("/api/v1/inventory/auctions/{auctionId}/transition-to-order", auctionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                            throw new HttpClientErrorException(res.getStatusCode(), res.getStatusText());
                        })
                        .body(new ParameterizedTypeReference<ApiResponse<ReservationResponseDto>>() { });

                if (response != null && response.success() && response.data() != null) {
                    ReservationResponseDto resData = response.data();
                    List<UUID> reservationIds = (resData.items() != null)
                            ? resData.items().stream()
                                    .map(ReservationResponseDto.ReservationItemDto::reservationId)
                                    .toList()
                            : Collections.emptyList();

                    log.info("Fulfillment Service đã xác nhận chuyển tiếp giữ kho thành công cho auction [{}], order [{}], số phiếu: [{}]",
                            auctionId, orderId, reservationIds.size());
                    return reservationIds;
                } else {
                    String msg = (response != null && response.error() != null)
                            ? response.error().message()
                            : "Fulfillment Service trả về phản hồi không hợp lệ khi chuyển tiếp kho";
                    throw new BusinessException(ErrorCode.BAD_GATEWAY, msg);
                }
            } catch (HttpClientErrorException clientEx) {
                log.error("Fulfillment Service từ chối yêu cầu chuyển tiếp kho cho phiên [{}] với mã HTTP {}: {}",
                        auctionId, clientEx.getStatusCode(), clientEx.getMessage());
                throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                        "Không thể chuyển tiếp phiếu giữ kho tại Fulfillment Service: " + clientEx.getMessage());
            } catch (ResourceAccessException | HttpServerErrorException netEx) {
                lastException = netEx;
                log.warn("Lỗi mạng/hệ thống khi gọi Fulfillment Service chuyển tiếp kho cho phiên [{}], lần thử [{}/{}]: {}",
                        auctionId, attempt, MAX_RETRY_ATTEMPTS, netEx.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(INITIAL_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "Tiến trình retry bị gián đoạn");
                    }
                }
            } catch (BusinessException be) {
                throw be;
            } catch (Exception ex) {
                lastException = ex;
                log.warn("Lỗi không mong muốn khi gọi Fulfillment Service cho phiên [{}], lần thử [{}/{}]: {}",
                        auctionId, attempt, MAX_RETRY_ATTEMPTS, ex.getMessage());
            }
        }

        log.error("Gọi Fulfillment Service chuyển tiếp kho cho phiên [{}] thất bại sau {} lần thử",
                auctionId, MAX_RETRY_ATTEMPTS, lastException);
        throw new BusinessException(ErrorCode.BAD_GATEWAY,
                "Không thể kết nối hoặc chuyển tiếp phiếu giữ kho sang Fulfillment Service sau " + MAX_RETRY_ATTEMPTS + " lần thử");
    }
}
