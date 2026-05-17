package com.dak.catalog.web.controller;

import com.dak.catalog.domain.port.inbound.ProductOfferingUseCase;
import com.dak.catalog.web.dto.ProductOfferingResponse;
import com.dak.catalog.web.mapper.ProductOfferingWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Product Offerings", description = "Catalog of available product offerings")
@RestController
@RequestMapping("/product-offerings")
@Validated
public class ProductOfferingController {

    private final ProductOfferingUseCase productOfferingUseCase;
    private final ProductOfferingWebMapper webMapper;

    public ProductOfferingController(ProductOfferingUseCase productOfferingUseCase,
                                     ProductOfferingWebMapper webMapper) {
        this.productOfferingUseCase = productOfferingUseCase;
        this.webMapper = webMapper;
    }

    @Operation(summary = "Get a product offering by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Offering found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Offering not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public ProductOfferingResponse getById(
            @Parameter(description = "Product offering ID (e.g. po-1)", required = true)
            @PathVariable String id) {
        return webMapper.toResponse(productOfferingUseCase.getById(id));
    }

    @Operation(summary = "List all product offerings")
    @ApiResponse(responseCode = "200", description = "List of all offerings")
    @GetMapping
    public List<ProductOfferingResponse> getAll() {
        return productOfferingUseCase.getAll().stream().map(webMapper::toResponse).toList();
    }
}
