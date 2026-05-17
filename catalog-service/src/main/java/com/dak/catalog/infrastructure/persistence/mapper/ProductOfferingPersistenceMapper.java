package com.dak.catalog.infrastructure.persistence.mapper;

import com.dak.catalog.domain.model.ProductOffering;
import com.dak.catalog.infrastructure.persistence.entity.ProductOfferingJpaEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductOfferingPersistenceMapper {

    ProductOffering toDomain(ProductOfferingJpaEntity entity);

    ProductOfferingJpaEntity toEntity(ProductOffering offering);
}
