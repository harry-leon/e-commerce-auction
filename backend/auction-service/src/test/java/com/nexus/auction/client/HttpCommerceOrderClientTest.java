package com.nexus.auction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.auction.client.dto.AuctionOrderResult;
import com.nexus.auction.client.dto.CreateAuctionOrderPayload;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.response.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Bộ kiểm thử đơn vị cho HttpCommerceOrderClient (DoD KH-S2-2 / SRS §3.5.3 & §3.4.3).
 *
 * Chức năng:
 * - Kiểm tra cơ chế truyền Idempotency-Key = auctionId qua HTTP header.
 * - Kiểm tra cơ chế Retry an toàn (tối đa 3 lần) khi gặp lỗi mạng ResourceAccessException.
 * - Kiểm tra xử lý lỗi 4xx client dừng ngay không retry vô ích.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpCommerceOrderClient Unit Tests - HTTP Client tạo đơn hàng đấu giá kèm Retry")
class HttpCommerceOrderClientTest {
    /** Exhaust retry attach phải báo lỗi cho handover, không return void làm scheduler đánh dấu hoàn tất giả. */
    @Test
    void exhaustedAttachRetryFailsForSchedulerRecovery() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(eq("/api/v1/internal/commerce/orders/{orderId}/reservations"), eq(orderId))).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenThrow(new ResourceAccessException("timeout"));
        assertThatThrownBy(() -> client.attachOrderReservations(orderId, java.util.List.of(UUID.randomUUID())))
                .isInstanceOf(BusinessException.class);
        verify(restClient, times(3)).post();
    }

    @Mock
    private RestClient restClient;

    private HttpCommerceOrderClient client;

    private final UUID auctionId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UUID winnerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000044");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000055");
    private final BigDecimal finalPrice = new BigDecimal("1200000.00");

    @BeforeEach
    void setUp() {
        client = new HttpCommerceOrderClient(restClient);
    }

    @Test
    @DisplayName("Gửi request tạo order thành công lần đầu trả về kết quả AuctionOrderResult")
    void test_createAuctionOrder_successFirstAttempt() {
        CreateAuctionOrderPayload payload = new CreateAuctionOrderPayload(
                auctionId, winnerId, sellerId, null, skuId, finalPrice, Instant.now().plusSeconds(86400), "Ao thun", "SKU-01"
        );

        AuctionOrderResult expectedResult = new AuctionOrderResult(
                orderId, "ORD-001", auctionId, winnerId, sellerId, "PENDING_PAYMENT", finalPrice, Instant.now().plusSeconds(86400)
        );
        ApiResponse<AuctionOrderResult> apiResponse = ApiResponse.ok(expectedResult);

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/v1/internal/commerce/orders/from-auction")).thenReturn(bodySpec);
        when(bodySpec.header(eq("Idempotency-Key"), eq(auctionId.toString()))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(payload)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(apiResponse);

        AuctionOrderResult result = client.createAuctionOrder(payload);

        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(orderId);
        assertThat(result.auctionId()).isEqualTo(auctionId);
        verify(restClient, times(1)).post();
    }

    @Test
    @DisplayName("Thử lại khi gặp ResourceAccessException và thành công ở lần thử thứ 2")
    void test_createAuctionOrder_retrySuccessOnSecondAttempt() {
        CreateAuctionOrderPayload payload = new CreateAuctionOrderPayload(
                auctionId, winnerId, sellerId, null, skuId, finalPrice, Instant.now().plusSeconds(86400), "Ao thun", "SKU-01"
        );

        AuctionOrderResult expectedResult = new AuctionOrderResult(
                orderId, "ORD-001", auctionId, winnerId, sellerId, "PENDING_PAYMENT", finalPrice, Instant.now().plusSeconds(86400)
        );
        ApiResponse<AuctionOrderResult> apiResponse = ApiResponse.ok(expectedResult);

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/v1/internal/commerce/orders/from-auction")).thenReturn(bodySpec);
        when(bodySpec.header(eq("Idempotency-Key"), eq(auctionId.toString()))).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(payload)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);

        // Lần 1 ném ResourceAccessException (mạng lỗi), Lần 2 trả về kết quả thành công
        when(responseSpec.body(any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("Connection reset by peer"))
                .thenReturn(apiResponse);

        AuctionOrderResult result = client.createAuctionOrder(payload);

        assertThat(result).isNotNull();
        assertThat(result.orderId()).isEqualTo(orderId);
        verify(restClient, times(2)).post();
    }

    @Test
    @DisplayName("Lỗi 4xx client dừng ngay lập tức không thử lại và ném BusinessException")
    void test_createAuctionOrder_clientError_noRetry() {
        CreateAuctionOrderPayload payload = new CreateAuctionOrderPayload(
                auctionId, winnerId, sellerId, null, skuId, finalPrice, Instant.now().plusSeconds(86400), "Ao thun", "SKU-01"
        );

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/v1/internal/commerce/orders/from-auction")).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(payload)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT, "Order already exists"));

        assertThatThrownBy(() -> client.createAuctionOrder(payload))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.BUSINESS_ERROR.name());

        verify(restClient, times(1)).post();
    }
}
