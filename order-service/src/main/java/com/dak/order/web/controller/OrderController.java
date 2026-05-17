package com.dak.order.web.controller;

import com.dak.order.application.service.IdempotencyService;
import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.PagedOrders;
import com.dak.order.domain.port.inbound.OrderUseCase;
import com.dak.order.domain.port.outbound.IdempotencyRepositoryPort.StoredResponse;
import com.dak.order.web.dto.CreateOrderRequest;
import com.dak.order.web.dto.OrderResponse;
import com.dak.order.web.dto.PagedResponse;
import com.dak.order.web.dto.PatchOrderRequest;
import com.dak.order.web.mapper.OrderWebMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Tag(name = "Orders", description = "Customer order lifecycle management")
@RestController
@RequestMapping("/customer-orders")
@Validated
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderUseCase orderUseCase;
    private final OrderWebMapper orderWebMapper;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public OrderController(OrderUseCase orderUseCase, OrderWebMapper orderWebMapper,
                           IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.orderUseCase = orderUseCase;
        this.orderWebMapper = orderWebMapper;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
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

        String requestHash = idempotencyKey != null ? idempotencyService.computeHash(request) : null;

        if (idempotencyKey != null) {
            Optional<StoredResponse> replay = idempotencyService.check(idempotencyKey, requestHash);
            if (replay.isPresent()) {
                return buildReplayResponse(replay.get());
            }
        }

        CreateOrderCommand command = orderWebMapper.toCommand(request);
        Order order = orderUseCase.createOrder(command);
        OrderResponse response = orderWebMapper.toResponse(order);

        if (idempotencyKey != null) {
            try {
                idempotencyService.store(idempotencyKey, requestHash, order.getId(), 201,
                        objectMapper.writeValueAsString(response));
            } catch (JsonProcessingException e) {
                log.warn("Failed to store idempotency result for key {}", idempotencyKey, e);
            }
        }

        return ResponseEntity.created(URI.create("/customer-orders/" + response.id())).body(response);
    }

    private ResponseEntity<OrderResponse> buildReplayResponse(StoredResponse stored) {
        try {
            return ResponseEntity.status(stored.responseStatus())
                    .header("X-Idempotent-Replayed", "true")
                    .body(objectMapper.readValue(stored.responseBody(), OrderResponse.class));
        } catch (JsonProcessingException e) {
            // Logged here (not in the catch-all) so the corrupted body is visible in the log entry.
            log.error("Cannot deserialise stored idempotency response body: [{}]", stored.responseBody(), e);
            throw new IllegalStateException("Corrupted idempotency store", e);
        }
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
        PagedOrders result = orderUseCase.listOrders(category, limit, offset);
        List<OrderResponse> items = result.items().stream().map(orderWebMapper::toResponse).toList();
        return new PagedResponse<>(items, result.total(), limit, offset);
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

    @Operation(
            summary = "Patch an order",
            description = "Applies a partial update using JSON Merge Patch semantics (RFC 7396). " +
                          "Only fields present in the request body are updated. " +
                          "State transitions are driven by `targetStateName`. " +
                          "SUBMITTED orders reject non-state field changes (422). " +
                          "CONFIRMED orders reject all patches (422).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order updated"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid API key",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Order not found",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Concurrent modification — optimistic lock conflict",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Invalid state transition or forbidden mutation",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping(value = "/{id}", consumes = "application/merge-patch+json")
    public OrderResponse patchOrder(
            @Parameter(description = "Order UUID", required = true)
            @PathVariable UUID id,
            @RequestBody PatchOrderRequest request) {
        PatchOrderCommand command = orderWebMapper.toPatchCommand(request);
        return orderWebMapper.toResponse(orderUseCase.patchOrder(id, command));
    }
}
