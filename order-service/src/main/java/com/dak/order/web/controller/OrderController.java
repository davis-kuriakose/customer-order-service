package com.dak.order.web.controller;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.port.inbound.OrderUseCase;
import com.dak.order.web.dto.CreateOrderRequest;
import com.dak.order.web.dto.OrderResponse;
import com.dak.order.web.dto.PagedResponse;
import com.dak.order.web.mapper.OrderWebMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@Tag(name = "Orders", description = "Customer order lifecycle management")
@RestController
@RequestMapping("/customer-orders")
@Validated
public class OrderController {

    private final OrderUseCase orderUseCase;
    private final OrderWebMapper orderWebMapper;

    public OrderController(OrderUseCase orderUseCase, OrderWebMapper orderWebMapper) {
        this.orderUseCase = orderUseCase;
        this.orderWebMapper = orderWebMapper;
    }

    @Operation(
            summary = "Create a new order",
            description = "Creates a customer order in DRAFT state. Supply an Idempotency-Key header " +
                          "to make the call safely retryable — duplicate keys with the same payload " +
                          "return the original response (TASK-09).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created",
                    headers = @Header(name = "Location",
                            description = "URI of the newly created order",
                            schema = @Schema(type = "string", example = "/customer-orders/uuid"))),
            @ApiResponse(responseCode = "400", description = "Validation failed — missing or invalid fields",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency key conflict — same key, different payload",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Parameter(description = "Client-generated unique key for idempotent retries (UUID recommended)")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = orderWebMapper.toCommand(request);
        Order order = orderUseCase.createOrder(command, idempotencyKey);
        OrderResponse response = orderWebMapper.toResponse(order);
        return ResponseEntity
                .created(URI.create("/customer-orders/" + response.id()))
                .body(response);
    }

    @Operation(
            summary = "List orders",
            description = "Returns a paged list of orders. Filter by category (B2B or B2C) using the " +
                          "optional `category` query parameter. Results are ordered by creation time descending.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of orders"),
            @ApiResponse(responseCode = "400", description = "Invalid query parameters",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public PagedResponse<OrderResponse> listOrders(
            @Parameter(description = "Filter by order category (B2B or B2C). Omit to return all categories.")
            @RequestParam(required = false) OrderCategory category,
            @Parameter(description = "Maximum number of results to return (1–100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @Parameter(description = "Number of results to skip for pagination", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        List<Order> orders = orderUseCase.listOrders(category, limit, offset);
        long total = orderUseCase.countOrders(category);
        List<OrderResponse> items = orders.stream().map(orderWebMapper::toResponse).toList();
        return new PagedResponse<>(items, total, limit, offset);
    }

    @Operation(
            summary = "Get an order by ID",
            description = "Returns the full order details for the given UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public OrderResponse getOrder(
            @Parameter(description = "Order UUID", required = true)
            @PathVariable UUID id) {
        return orderWebMapper.toResponse(orderUseCase.getOrder(id));
    }
}
