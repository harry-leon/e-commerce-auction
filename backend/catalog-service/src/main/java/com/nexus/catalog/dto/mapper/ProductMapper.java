package com.nexus.catalog.dto.mapper;

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
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ProductMapper {

    ProductMapper INSTANCE = Mappers.getMapper(ProductMapper.class);

    @Mapping(target = "categoryId", source = "category.id")
    ProductResponse toResponse(Product product);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sellerId", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "skus", ignore = true)
    @Mapping(target = "images", ignore = true)
    @Mapping(target = "status", constant = "DRAFT")
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

    SkuResponse toSku(Sku sku);

    ImageResponse toImage(ProductImage image);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    Sku toSkuEntity(SkuRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    ProductImage toImageEntity(ImageRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    void updateSkuFromRequest(SkuRequest request, @MappingTarget Sku sku);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "product", ignore = true)
    void updateImageFromRequest(ImageRequest request, @MappingTarget ProductImage image);
}
