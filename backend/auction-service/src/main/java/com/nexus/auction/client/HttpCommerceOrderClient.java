package com.nexus.auction.client;

import com.nexus.auction.client.dto.AttachOrderReservationsPayload;
import com.nexus.auction.client.dto.AuctionOrderResult;
import com.nexus.auction.client.dto.CreateAuctionOrderPayload;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
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
 * Triển khai HTTP Client giao tiếp với Commerce Service qua RestClient (SRS §3.5.3 & §3.4.3).
 *
 * Chức năng:
 * - Gọi endpoint POST /api/v1/internal/commerce/orders/from-auction bằng service account nexus-auction-service.
 * - Truyền auctionId làm Idempotency Key duy nhất qua HTTP header Idempotency-Key.
 * - Tích hợp cơ chế thử lại an toàn (Safe Retry) với 3 lần thử khi gặp lỗi mạng tạm thời hoặc 5xx.
 *
 * Quy tắc và lý do:
 * - Sử dụng auctionCommerceClient đã được cấu hình kèm interceptor token và timeout 5 giây.
 * - Lỗi 4xx là lỗi nghiệp vụ (như dữ liệu không hợp lệ) -> dừng ngay không thử lại mù quáng.
 * - Lỗi mạng hoặc 5xx -> thử lại tối đa 3 lần với backoff để đảm bảo không làm thất lạc kết quả trúng đấu giá.
 */
@Component
public class HttpCommerceOrderClient implements CommerceOrderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCommerceOrderClient.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 200L;

    private final RestClient client;

    public HttpCommerceOrderClient(@Qualifier("auctionCommerceClient") RestClient client) {
        this.client = client;
    }

    /**
     * Gửi yêu cầu khởi tạo đơn hàng đấu giá sang Commerce Service kèm retry và idempotency key.
     *
     * Luồng:
     * 1. Thiết lập Idempotency-Key bằng auctionId.
     * 2. Gọi POST /api/v1/internal/commerce/orders/from-auction.
     * 3. Bắt lỗi mạng / 5xx để retry tối đa 3 lần.
     * 4. Giải nén phong bì ApiResponse và trả về AuctionOrderResult.
     */
    @Override
    public AuctionOrderResult createAuctionOrder(CreateAuctionOrderPayload payload) {
        if (payload == null || payload.auctionId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Dữ liệu phiên đấu giá không hợp lệ");
        }

        String idempotencyKey = payload.auctionId().toString();
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                log.info("Gửi yêu cầu tạo đơn hàng đấu giá cho phiên [{}], lần thử [{}/{}]",
                        payload.auctionId(), attempt, MAX_RETRY_ATTEMPTS);

                ApiResponse<AuctionOrderResult> response = client.post()
                        .uri("/api/v1/internal/commerce/orders/from-auction")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                            // 4xx là lỗi nghiệp vụ không retry, ném thẳng exception để bắt ngoài vòng lặp
                            throw new HttpClientErrorException(res.getStatusCode(), res.getStatusText());
                        })
                        .body(new ParameterizedTypeReference<ApiResponse<AuctionOrderResult>>() { });

                if (response != null && response.success() && response.data() != null) {
                    log.info("Commerce Service đã xác nhận tạo đơn hàng [{}] cho phiên [{}] thành công",
                            response.data().orderId(), payload.auctionId());
                    return response.data();
                } else {
                    String msg = (response != null && response.error() != null)
                            ? response.error().message()
                            : "Commerce Service trả về phản hồi không hợp lệ";
                    throw new BusinessException(ErrorCode.BAD_GATEWAY, msg);
                }
            } catch (HttpClientErrorException clientEx) {
                // Lỗi client 4xx (ví dụ 409 conflict, 422 validation): dừng ngay, không retry
                log.error("Commerce Service từ chối yêu cầu tạo đơn cho phiên [{}] với mã HTTP {}: {}",
                        payload.auctionId(), clientEx.getStatusCode(), clientEx.getMessage());
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "Không thể tạo đơn hàng tại Commerce Service: " + clientEx.getMessage());
            } catch (ResourceAccessException | HttpServerErrorException netEx) {
                // Lỗi mạng hoặc 5xx: ghi log cảnh báo và retry nếu còn lượt
                lastException = netEx;
                log.warn("Lỗi mạng/hệ thống tạm thời khi gọi Commerce Service cho phiên [{}], lần thử [{}/{}]: {}",
                        payload.auctionId(), attempt, MAX_RETRY_ATTEMPTS, netEx.getMessage());

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
                log.warn("Lỗi không mong muốn khi gọi Commerce Service cho phiên [{}], lần thử [{}/{}]: {}",
                        payload.auctionId(), attempt, MAX_RETRY_ATTEMPTS, ex.getMessage());
            }
        }

        log.error("Gọi Commerce Service tạo đơn hàng cho phiên [{}] thất bại sau {} lần thử",
                payload.auctionId(), MAX_RETRY_ATTEMPTS, lastException);
        throw new BusinessException(ErrorCode.BAD_GATEWAY,
                "Không thể kết nối hoặc bàn giao đơn hàng sang Commerce Service sau " + MAX_RETRY_ATTEMPTS + " lần thử");
    }

    /**
     * Gửi yêu cầu gắn danh sách reservationId vào đơn hàng đã tạo tại Commerce Service.
     *
     * Luồng xử lý:
     * 1. Gọi POST /api/v1/internal/commerce/orders/{orderId}/reservations.
     * 2. Bắt lỗi mạng / 5xx để retry tối đa 3 lần với backoff.
     * 3. Bỏ qua 4xx và ném lỗi nghiệp vụ nếu dữ liệu không hợp lệ.
     *
     * @param orderId        Mã đơn hàng Commerce
     * @param reservationIds Danh sách mã phiếu giữ kho từ Fulfillment
     */
    @Override
    public void attachOrderReservations(UUID orderId, List<UUID> reservationIds) {
        if (orderId == null || reservationIds == null || reservationIds.isEmpty()) {
            return;
        }

        AttachOrderReservationsPayload payload = new AttachOrderReservationsPayload(reservationIds);
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                log.info("Gửi yêu cầu gắn [{}] phiếu giữ kho cho đơn [{}] tại Commerce Service, lần thử [{}/{}]",
                        reservationIds.size(), orderId, attempt, MAX_RETRY_ATTEMPTS);

                ApiResponse<Void> response = client.post()
                        .uri("/api/v1/internal/commerce/orders/{orderId}/reservations", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                            throw new HttpClientErrorException(res.getStatusCode(), res.getStatusText());
                        })
                        .body(new ParameterizedTypeReference<ApiResponse<Void>>() { });

                if (response != null && response.success()) {
                    log.info("Commerce Service đã gắn thành công [{}] phiếu giữ kho vào đơn [{}]",
                            reservationIds.size(), orderId);
                    return;
                }
            } catch (HttpClientErrorException clientEx) {
                log.error("Commerce Service từ chối gắn reservationIds cho đơn [{}] với mã HTTP {}: {}",
                        orderId, clientEx.getStatusCode(), clientEx.getMessage());
                throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                        "Không thể gắn reservationIds tại Commerce Service: " + clientEx.getMessage());
            } catch (ResourceAccessException | HttpServerErrorException netEx) {
                lastException = netEx;
                log.warn("Lỗi mạng khi gọi Commerce Service gắn reservations cho đơn [{}], lần thử [{}/{}]: {}",
                        orderId, attempt, MAX_RETRY_ATTEMPTS, netEx.getMessage());

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
                log.warn("Lỗi không mong muốn khi gọi Commerce Service gắn reservations cho đơn [{}], lần thử [{}/{}]: {}",
                        orderId, attempt, MAX_RETRY_ATTEMPTS, ex.getMessage());
            }
        }

        log.warn("Gọi Commerce Service gắn reservations cho đơn [{}] thất bại sau {} lần thử: {}",
                orderId, MAX_RETRY_ATTEMPTS, (lastException != null ? lastException.getMessage() : "unknown"));
        // Không nuốt lỗi cuối: scheduler phải giữ settlement chưa hoàn tất để thử lại bước attach sau restart.
        throw new BusinessException(ErrorCode.BAD_GATEWAY, "Chưa gắn được phiếu giữ kho vào đơn hàng");
    }
}
