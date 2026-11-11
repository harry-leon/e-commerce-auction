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
 * xác minh giải mã SKU theo ID/skuCode, phân trang danh sách SKU của seller và xử lý lỗi 404/5xx.
 */
class HttpCatalogClientTest {

    private static final String BASE_URL = "http://catalog-service.local";

    @Test
    @DisplayName("Tra cứu SKU theo ID thành công từ envelope ApiResponse")
    void findSkuById_ReturnsSnapshot() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID skuId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID sellerId = UUID.fromString("22222222-2222-4222-8222-222222222222");

        String body = """
                {
                  "success": true,
                  "data": {
                    "skuId": "%s",
                    "skuCode": "SKU-IP15-BLK",
                    "productName": "iPhone 15 Pro",
                    "sellerId": "%s",
                    "isActive": true
                  },
                  "error": null,
                  "meta": {}
                }
                """.formatted(skuId, sellerId);

        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/" + skuId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        Optional<CatalogClient.SkuOwnershipInfo> result = client.findSkuById(skuId);

        server.verify();
        assertTrue(result.isPresent());
        assertEquals(skuId, result.get().skuId());
        assertEquals("SKU-IP15-BLK", result.get().skuCode());
        assertEquals(sellerId, result.get().sellerId());
        assertTrue(Boolean.TRUE.equals(result.get().isActive()));
    }

    @Test
    @DisplayName("Tra cứu theo skuCode trả Optional.empty khi Catalog trả 404")
    void findSkuByCode_ReturnsEmptyOnNotFound() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        server.expect(requestTo(BASE_URL + "/api/v1/products/skus/code/UNKNOWN-SKU"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withResourceNotFound());

        Optional<CatalogClient.SkuOwnershipInfo> result = client.findSkuByCode("  UNKNOWN-SKU ");

        server.verify();
        assertTrue(result.isEmpty());
        assertTrue(client.findSkuByCode("  ").isEmpty());
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
                        {"id": "%s", "skuCode": "KB-01", "status": "ACTIVE"}
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
                        {"id": "%s", "skuCode": "MS-01", "status": "INACTIVE"}
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
        assertEquals("KB-01", items.get(0).skuCode());
        assertTrue(Boolean.TRUE.equals(items.get(0).isActive()));
        assertEquals("MS-01", items.get(1).skuCode());
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
