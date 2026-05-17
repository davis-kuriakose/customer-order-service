package com.dak.catalog.web.mapper;

import com.dak.catalog.domain.model.ProductOffering;
import com.dak.catalog.web.dto.ProductOfferingResponse;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProductOfferingWebMapper {

    ProductOfferingResponse toResponse(ProductOffering offering);
}
