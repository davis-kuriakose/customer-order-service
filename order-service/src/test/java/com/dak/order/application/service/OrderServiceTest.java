package com.dak.order.application.service;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.command.PatchOrderCommand;
import com.dak.order.domain.exception.OrderNotFoundException;
import com.dak.order.domain.model.Customer;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PaymentMethod;
import com.dak.order.domain.model.PaymentMethodType;
import com.dak.order.domain.model.Site;
import com.dak.order.domain.port.outbound.OrderRepositoryPort;
import com.dak.order.domain.state.DraftState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepositoryPort orderRepositoryPort;

    @InjectMocks
    private OrderService orderService;

    private Order buildOrder(UUID id) {
        return Order.builder()
                .id(id)
                .state(new DraftState())
                .category(OrderCategory.B2B)
                .customer(new Customer("cust-1"))
                .site(new Site("site-1"))
                .orderItems(List.of(new OrderItem("po-1", 2)))
                .paymentMethod(new PaymentMethod(PaymentMethodType.INVOICE, null))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void createOrder_savesAndReturnsOrder() {
        CreateOrderCommand command = new CreateOrderCommand(
                OrderCategory.B2B, "cust-1", "site-1",
                List.of(new OrderItem("po-1", 2)),
                PaymentMethodType.INVOICE, null);
        UUID savedId = UUID.randomUUID();
        Order savedOrder = buildOrder(savedId);
        when(orderRepositoryPort.save(any(Order.class))).thenReturn(savedOrder);

        Order result = orderService.createOrder(command, null);

        assertThat(result.getId()).isEqualTo(savedId);
        assertThat(result.getCategory()).isEqualTo(OrderCategory.B2B);
        verify(orderRepositoryPort).save(any(Order.class));
    }

    @Test
    void getOrder_returnsOrder_whenFound() {
        UUID id = UUID.randomUUID();
        Order order = buildOrder(id);
        when(orderRepositoryPort.findById(id)).thenReturn(Optional.of(order));

        Order result = orderService.getOrder(id);

        assertThat(result).isEqualTo(order);
    }

    @Test
    void getOrder_throwsOrderNotFoundException_whenNotFound() {
        UUID id = UUID.randomUUID();
        when(orderRepositoryPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.getOrder(id))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void patchOrder_appliesPatchAndSaves() {
        UUID id = UUID.randomUUID();
        Order original = buildOrder(id);
        PatchOrderCommand patch = new PatchOrderCommand("PREVIEW", null, null, null, null, null);
        Order patched = original.applyPatch(patch);
        when(orderRepositoryPort.findById(id)).thenReturn(Optional.of(original));
        when(orderRepositoryPort.save(any(Order.class))).thenReturn(patched);

        Order result = orderService.patchOrder(id, patch);

        assertThat(result.getState().name()).isEqualTo("PREVIEW");
        verify(orderRepositoryPort).save(any(Order.class));
    }

    @Test
    void patchOrder_throwsOrderNotFoundException_whenNotFound() {
        UUID id = UUID.randomUUID();
        PatchOrderCommand patch = new PatchOrderCommand("PREVIEW", null, null, null, null, null);
        when(orderRepositoryPort.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.patchOrder(id, patch))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining(id.toString());
    }
}
