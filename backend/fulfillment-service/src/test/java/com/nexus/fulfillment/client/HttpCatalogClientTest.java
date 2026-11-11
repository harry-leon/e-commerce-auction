package com.nexus.fulfillment.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Kiểm thử RestClient gọi Catalog Service (HttpCatalogClient) bằng MockRestServiceServer:
 * xác minh giải mã SKU theo ID (kèm price/originalPrice/sellerId), phân trang danh sách SKU của seller và xử lý lỗi 404/502.
 */
class HttpCatalogClientTest {

    private static final String BASE_URL = "http://catalog-service.local";

    @Test
    @DisplayName("Tra cứu SKU theo ID thành công từ envelope ApiResponse và không phụ thuộc skuCode")
    void findSkuById_ReturnsSnapshot() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID skuId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID productId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        UUID sellerId = UUID.fromString("22222222-2222-4222-8222-222222222222");

        String body = """
                {
                  "success": true,
                  "data": {
                    "skuId": "%s",
                    "productId": "%s",
                    "productName": "iPhone 15 Pro",
                    "price": 25000000,
                    "originalPrice": 22000000,
                    "sellerId": "%s",
                    "isActive": true
                  },
                  "error": null,
                  "meta": {}
                }
                """.formatted(skuId, productId, sellerId);

        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/" + skuId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<CatalogClient.SkuOwnershipInfo> result = client.findSkuById(skuId);

        server.verify();
        assertTrue(result.isPresent());
        assertEquals(skuId, result.get().skuId());
        assertEquals(productId, result.get().productId());
        assertEquals(new BigDecimal("25000000"), result.get().price());
        assertEquals(new BigDecimal("22000000"), result.get().originalPrice());
        assertEquals(sellerId, result.get().sellerId());
        assertTrue(Boolean.TRUE.equals(result.get().isActive()));
    }

    @Test
    @DisplayName("Tra cứu theo ID trả Optional.empty khi Catalog trả 404 và ném 502 khi thiếu sellerId")
    void findSkuById_HandlesNotFoundAndMissingSellerId() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID missingId = UUID.fromString("99999999-9999-4999-8999-999999999999");
        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/" + missingId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());

        assertTrue(client.findSkuById(missingId).isEmpty());
        assertTrue(client.findSkuById(null).isEmpty());
        server.verify();

        server.reset();
        UUID skuMissingSeller = UUID.randomUUID();
        String missingSellerBody = """
                {
                  "success": true,
                  "data": {
                    "skuId": "%s",
                    "productName": "SKU thieu seller"
                  },
                  "error": null
                }
                """.formatted(skuMissingSeller);
        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/" + skuMissingSeller))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(missingSellerBody, MediaType.APPLICATION_JSON));

        ApiException ex = assertThrows(ApiException.class, () -> client.findSkuById(skuMissingSeller));
        assertEquals("CATALOG_INVALID_RESPONSE", ex.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        server.verify();
    }

    @Test
    @DisplayName("Lấy toàn bộ SKU của seller qua nhiều trang và tính đúng cờ active")
    void findSkusBySeller_PaginatesAcrossPages() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID sellerId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID sku1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        UUID sku2 = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

        String page0 = """
                {
                  "success": true,
                  "data": [
                    {
                      "id": "10000000-0000-4000-8000-000000000001",
                      "sellerId": "%s",
                      "name": "Ban phim co",
                      "status": "ACTIVE",
                      "skus": [
                        {"id": "%s", "price": 1200000, "originalPrice": 1000000, "status": "ACTIVE"}
                      ]
                    }
                  ],
                  "error": null,
                  "meta": {
                    "pagination": {
                      "page": 0,
                      "size": 100,
                      "hasNext": true
                    }
                  }
                }
                """.formatted(sellerId, sku1);

        String page1 = """
                {
                  "success": true,
                  "data": [
                    {
                      "id": "10000000-0000-4000-8000-000000000002",
                      "sellerId": "%s",
                      "name": "Chuot gaming",
                      "status": "ACTIVE",
                      "skus": [
                        {"id": "%s", "price": 800000, "originalPrice": 700000, "status": "INACTIVE"}
                      ]
                    }
                  ],
                  "error": null,
                  "meta": {
                    "pagination": {
                      "page": 1,
                      "size": 100,
                      "hasNext": false
                    }
                  }
                }
                """.formatted(sellerId, sku2);

        server.expect(requestTo(BASE_URL + "/api/v1/products/seller/" + sellerId + "?page=0&size=100"))
                .andRespond(withSuccess(page0, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/v1/products/seller/" + sellerId + "?page=1&size=100"))
                .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));

        List<CatalogClient.SkuOwnershipInfo> items = client.findSkusBySeller(sellerId);

        server.verify();
        assertEquals(2, items.size());
        assertEquals(sku1, items.get(0).skuId());
        assertEquals(new BigDecimal("1200000"), items.get(0).price());
        assertTrue(Boolean.TRUE.equals(items.get(0).isActive()));
        assertEquals(sku2, items.get(1).skuId());
        assertEquals(new BigDecimal("700000"), items.get(1).originalPrice());
        assertFalse(Boolean.TRUE.equals(items.get(1).isActive()));
    }

    @Test
    @DisplayName("Ném 502 BAD_GATEWAY khi Catalog lỗi 5xx hoặc trả envelope success=false")
    void findSkuById_ThrowsBadGatewayOnFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID skuId = UUID.randomUUID();
        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/" + skuId))
                .andRespond(withServerError());

        ApiException unavailableEx = assertThrows(ApiException.class, () -> client.findSkuById(skuId));
        assertEquals("CATALOG_UNAVAILABLE", unavailableEx.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, unavailableEx.getHttpStatus());
        server.verify();

        server.reset();
        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/" + skuId))
                .andRespond(withSuccess("{\"success\":false,\"data\":null}", MediaType.APPLICATION_JSON));

        ApiException invalidEx = assertThrows(ApiException.class, () -> client.findSkuById(skuId));
        assertEquals("CATALOG_INVALID_RESPONSE", invalidEx.getCode());
        assertEquals(HttpStatus.BAD_GATEWAY, invalidEx.getHttpStatus());
        server.verify();
    }
}
