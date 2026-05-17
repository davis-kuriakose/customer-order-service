package com.dak.catalog.web.controller;

import com.dak.catalog.domain.exception.ProductOfferingNotFoundException;
import com.dak.catalog.domain.port.inbound.ProductOfferingUseCase;
import com.dak.catalog.web.dto.ProductOfferingResponse;
import com.dak.catalog.web.exception.GlobalExceptionHandler;
import com.dak.catalog.web.mapper.ProductOfferingWebMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = ProductOfferingController.class,
            excludeAutoConfiguration = SecurityAutoConfiguration.class)
@Import(GlobalExceptionHandler.class)
class ProductOfferingControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ProductOfferingUseCase productOfferingUseCase;

    @MockBean
    ProductOfferingWebMapper webMapper;

    private static final ProductOfferingResponse RESPONSE =
            new ProductOfferingResponse("po-1", "Basic Plan", new BigDecimal("9.99"));

    @Test
    void getById_returns200_withOffering() throws Exception {
        when(productOfferingUseCase.getById("po-1")).thenReturn(
                new com.dak.catalog.domain.model.ProductOffering("po-1", "Basic Plan", new BigDecimal("9.99")));
        when(webMapper.toResponse(org.mockito.ArgumentMatchers.any())).thenReturn(RESPONSE);

        mockMvc.perform(get("/product-offerings/{id}", "po-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("po-1"))
                .andExpect(jsonPath("$.name").value("Basic Plan"))
                .andExpect(jsonPath("$.price").value(9.99));
    }

    @Test
    void getById_returns404_whenNotFound() throws Exception {
        when(productOfferingUseCase.getById("po-999"))
                .thenThrow(new ProductOfferingNotFoundException("po-999"));

        mockMvc.perform(get("/product-offerings/{id}", "po-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getAll_returns200_withList() throws Exception {
        when(productOfferingUseCase.getAll()).thenReturn(List.of(
                new com.dak.catalog.domain.model.ProductOffering("po-1", "Basic Plan", new BigDecimal("9.99"))));
        when(webMapper.toResponse(org.mockito.ArgumentMatchers.any())).thenReturn(RESPONSE);

        mockMvc.perform(get("/product-offerings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value("po-1"));
    }
}
