package com.dak.order.web.exception;

import com.dak.order.domain.exception.CatalogUnavailableException;
import com.dak.order.domain.exception.IdempotencyConflictException;
import com.dak.order.domain.exception.InvalidStateTransitionException;
import com.dak.order.domain.exception.OrderMutationForbiddenException;
import com.dak.order.domain.exception.OrderNotFoundException;
import com.dak.order.domain.exception.ProductOfferingNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleOrderNotFound_returns404_withOrderIdInDetail() {
        UUID id = UUID.randomUUID();
        ResponseEntity<ProblemDetail> response = handler.handleOrderNotFound(new OrderNotFoundException(id));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body.getTitle()).isEqualTo("Not Found");
        assertThat(body.getDetail()).contains(id.toString());
    }

    @Test
    void handleInvalidStateTransition_returns422() {
        ResponseEntity<ProblemDetail> response = handler.handleUnprocessableEntity(
                new InvalidStateTransitionException("DRAFT", "CONFIRMED"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body.getDetail()).contains("DRAFT").contains("CONFIRMED");
    }

    @Test
    void handleOrderMutationForbidden_returns422() {
        ResponseEntity<ProblemDetail> response = handler.handleUnprocessableEntity(
                new OrderMutationForbiddenException("Confirmed orders cannot be modified"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body.getDetail()).contains("Confirmed orders");
    }

    @Test
    void handleProductOfferingNotFound_returns422() {
        ResponseEntity<ProblemDetail> response = handler.handleUnprocessableEntity(
                new ProductOfferingNotFoundException("po-999"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body.getDetail()).contains("po-999");
    }

    @Test
    void handleCatalogUnavailable_returns503() {
        ResponseEntity<ProblemDetail> response = handler.handleCatalogUnavailable(
                new CatalogUnavailableException("Circuit open"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(body.getTitle()).isEqualTo("Service Unavailable");
    }

    @Test
    void handleIdempotencyConflict_returns409() {
        ResponseEntity<ProblemDetail> response = handler.handleIdempotencyConflict(
                new IdempotencyConflictException("key-abc"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body.getDetail()).contains("key-abc");
    }

    @Test
    void handleOptimisticLock_returns409_withRetryMessage() {
        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException("Order", "order-id-1"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body.getDetail()).contains("retry");
    }

    @Test
    void handleUnexpected_returns500_forAnyUnhandledException() {
        ResponseEntity<ProblemDetail> response = handler.handleUnexpected(
                new IllegalStateException("Corrupted idempotency store"));
        ProblemDetail body = Objects.requireNonNull(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body.getTitle()).isEqualTo("Internal Server Error");
        assertThat(body.getDetail()).doesNotContain("Corrupted"); // internal detail never leaked to client
    }
}
