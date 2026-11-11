package com.nexus.auction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.auction.client.dto.ReservationResponseDto;
import com.nexus.auction.client.dto.TransitionAuctionReservationPayload;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.response.ApiResponse;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Bộ kiểm thử đơn vị cho HttpFulfillmentInventoryClient (SRS §3.6.1).
 *
 * Chức năng:
 * - Kiểm tra gọi thành công POST /api/v1/inventory/auctions/{auctionId}/transition-to-order và trích xuất reservationIds.
 * - Kiểm tra xử lý lỗi 4xx client dừng ngay không thử lại.
 * - Kiểm tra cơ chế retry an toàn khi gặp sự cố mạng hoặc lỗi 5xx.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpFulfillmentInventoryClient Unit Tests - HTTP Client chuyển tiếp kho sang Order kèm Retry")
class HttpFulfillmentInventoryClientTest {

    @Mock
    private RestClient restClient;

    private HttpFulfillmentInventoryClient client;

    private final UUID auctionId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000055");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private final Instant deadline = Instant.parse("2026-11-10T10:00:00Z");

    @BeforeEach
    void setUp() {
        client = new HttpFulfillmentInventoryClient(restClient);
    }

    @Test
    @DisplayName("Chuyển tiếp kho thành công ngay lần đầu: Trả về danh sách reservationIds")
    @SuppressWarnings("unchecked")
    void test_transitionAuctionToOrder_success() {
        ReservationResponseDto.ReservationItemDto item = new ReservationResponseDto.ReservationItemDto(
                reservationId, UUID.randomUUID(), UUID.randomUUID(), 1, "PENDING", deadline
        );
        ReservationResponseDto responseDto = new ReservationResponseDto(
                "ORDER", orderId, "PENDING", List.of(item), deadline
        );
        ApiResponse<ReservationResponseDto> apiResponse = ApiResponse.ok(responseDto);

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/v1/inventory/auctions/{auctionId}/transition-to-order", auctionId)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(any(TransitionAuctionReservationPayload.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(any(ParameterizedTypeReference.class))).thenReturn(apiResponse);

        List<UUID> result = client.transitionAuctionToOrder(auctionId, orderId, deadline);

        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("Lỗi 404 Không tìm thấy phiếu kho: Dừng ngay lập tức, không retry")
    void test_transitionAuctionToOrder_4xxNoRetry() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/v1/inventory/auctions/{auctionId}/transition-to-order", auctionId)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(any(TransitionAuctionReservationPayload.class))).thenThrow(
                new HttpClientErrorException(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu giữ kho")
        );

        assertThatThrownBy(() -> client.transitionAuctionToOrder(auctionId, orderId, deadline))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không thể chuyển tiếp phiếu giữ kho tại Fulfillment Service");
    }

    @Test
    @DisplayName("Lỗi 500 mạng tạm thời: Thử lại 3 lần rồi ném BusinessException(BAD_GATEWAY)")
    void test_transitionAuctionToOrder_5xxRetriesAndFails() {
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/api/v1/inventory/auctions/{auctionId}/transition-to-order", auctionId)).thenReturn(bodySpec);
        when(bodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(bodySpec);
        when(bodySpec.body(any(TransitionAuctionReservationPayload.class))).thenThrow(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi server nội bộ Fulfillment")
        );

        assertThatThrownBy(() -> client.transitionAuctionToOrder(auctionId, orderId, deadline))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sau 3 lần thử");
    }
}
