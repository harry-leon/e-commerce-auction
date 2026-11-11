package com.nexus.fulfillment.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Kiểm thử hợp đồng HTTP Client tra cứu SKU từ Catalog Service trước khi nhập kho Fulfillment. */
class HttpCatalogSkuClientTest {

    private MockRestServiceServer server;
    private HttpCatalogSkuClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpCatalogSkuClient(builder.build());
    }

    @Test
    @DisplayName("GET /api/v1/skus/{id} trả về 200 -> parse đầy đủ snapshot SKU và sellerId")
    void getSku_success_returnsSnapshot() {
        UUID skuId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        String body = """
                {
                  "success": true,
                  "data": {
                    "skuId": "%s",
                    "productId": "%s",
                    "productName": "Bàn phím cơ Nexus",
                    "price": 1250000,
                    "originalPrice": 1000000,
                    "isActive": true,
                    "sellerId": "%s"
                  }
                }
                """.formatted(skuId, productId, sellerId);

        server.expect(requestTo("http://catalog-service/api/v1/skus/" + skuId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        CatalogSkuClient.CatalogSkuSnapshot snapshot = client.getSku(skuId);
        assertEquals(skuId, snapshot.skuId());
        assertEquals(productId, snapshot.productId());
        assertEquals(sellerId, snapshot.sellerId());
        assertEquals(new BigDecimal("1250000"), snapshot.price());
        assertEquals(new BigDecimal("1000000"), snapshot.originalPrice());
        assertTrue(Boolean.TRUE.equals(snapshot.isActive()));
        server.verify();
    }

    @Test
    @DisplayName("GET /api/v1/skus/{id} trả về 404 -> ném ResourceNotFoundException")
    void getSku_notFound_throwsResourceNotFound() {
        UUID skuId = UUID.randomUUID();
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + skuId))
                .andRespond(withResourceNotFound());

        assertThrows(ResourceNotFoundException.class, () -> client.getSku(skuId));
        server.verify();
    }

    @Test
    @DisplayName("Catalog trả về 5xx -> ném ApiException 502 BAD_GATEWAY")
    void getSku_serverError_throwsBadGateway() {
        UUID skuId = UUID.randomUUID();
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + skuId))
                .andRespond(withServerError());

        ApiException ex = assertThrows(ApiException.class, () -> client.getSku(skuId));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("CATALOG_UNAVAILABLE", ex.getCode());
        server.verify();
    }

    @Test
    @DisplayName("Catalog trả về 200 nhưng thiếu sellerId -> ném ApiException 502 CATALOG_INVALID_RESPONSE")
    void getSku_missingSellerId_throwsBadGateway() {
        UUID skuId = UUID.randomUUID();
        String body = """
                {
                  "success": true,
                  "data": {
                    "skuId": "%s",
                    "productName": "Test",
                    "price": 100000
                  }
                }
                """.formatted(skuId);
        server.expect(requestTo("http://catalog-service/api/v1/skus/" + skuId))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> client.getSku(skuId));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        assertEquals("CATALOG_INVALID_RESPONSE", ex.getCode());
        server.verify();
    }

    @Test
    @DisplayName("skuId null -> từ chối ngay ResourceNotFoundException")
    void getSku_nullId_throwsResourceNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> client.getSku(null));
    }
}
