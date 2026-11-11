package com.nexus.catalog.dto.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.ProductImage;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.dto.request.ProductRequest;
import org.mapstruct.BeanMapping;
import org.mapstruct.NullValuePropertyMappingStrategy;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

/**
 * MapStruct sinh code chuyển DTO ↔ entity; sửa quy tắc tại interface này, không sửa file trong target/generated-sources.
 * Khi tạo mới, giá trị tùy chọn có default để không ghi null đè mặc định entity; khi cập nhật bỏ qua field null.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductMapper {

    ProductMapper INSTANCE = Mappers.getMapper(ProductMapper.class);

    @Mapping(target = "categoryId", source = "category.id")
    @Mapping(target = "minPrice", ignore = true)
    @Mapping(target = "maxPrice", ignore = true)
    ProductResponse toResponse(Product product);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sellerId", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "skus", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "status", constant = "DRAFT")
    @Mapping(target = "auctionEnabled", source = "auctionEnabled", defaultValue = "false")
    Product toEntity(ProductRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sellerId", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "skus", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateProductFromRequest(ProductRequest request, @MappingTarget Product product);

    List<SkuResponse> toSkuResponseList(List<Sku> skus);

    List<ImageResponse> toImageResponseList(List<ProductImage> images);

    @Mapping(target = "price", ignore = true)
    @Mapping(target = "attributes", source = "attributes", qualifiedByName = "formatAttributes")
    SkuResponse toSku(Sku sku);

    ImageResponse toImage(ProductImage image);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "status", source = "status", defaultValue = "ACTIVE")
    @Mapping(target = "attributes", source = "attributes", qualifiedByName = "normalizeAttributes")
    Sku toSkuEntity(SkuRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "sortOrder", source = "sortOrder", defaultValue = "0")
    ProductImage toImageEntity(ImageRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "attributes", source = "attributes", qualifiedByName = "normalizeAttributes")
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateSkuFromRequest(SkuRequest request, @MappingTarget Sku sku);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateImageFromRequest(ImageRequest request, @MappingTarget ProductImage image);

    /** ObjectMapper nội bộ chuẩn hóa chuỗi attributes sang JSONB hợp lệ cho PostgreSQL. */
    final class JsonHolder {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private JsonHolder() {}
    }

    /**
     * Bảo đảm skus.attributes luôn là JSON hợp lệ trước khi ghi cột PostgreSQL jsonb:
     * giữ nguyên JSON object/array, chuyển chuỗi rỗng thành null, và bọc chuỗi thuần thành JSON string.
     */
    @Named("normalizeAttributes")
    static String normalizeAttributes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            JsonHolder.MAPPER.readTree(trimmed);
            return trimmed;
        } catch (JsonProcessingException ignored) {
            try {
                return JsonHolder.MAPPER.writeValueAsString(trimmed);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Không thể chuẩn hóa thuộc tính SKU sang JSON", ex);
            }
        }
    }

    /**
     * Giải bọc chuỗi JSON scalar (ví dụ "\"Black / Size M\"") thành chuỗi hiển thị gọn cho UI,
     * nhưng vẫn giữ nguyên nguyên văn nếu attributes là JSON object/array có cấu trúc.
     */
    @Named("formatAttributes")
    static String formatAttributes(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            JsonNode node = JsonHolder.MAPPER.readTree(raw);
            return node.isTextual() ? node.asText() : raw;
        } catch (JsonProcessingException ignored) {
            return raw;
        }
    }
}
