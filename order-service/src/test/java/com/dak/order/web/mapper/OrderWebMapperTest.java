package com.dak.order.web.mapper;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.model.Customer;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PaymentMethod;
import com.dak.order.domain.model.PaymentMethodType;
import com.dak.order.domain.model.Site;
import com.dak.order.domain.state.DraftState;
import com.dak.order.web.dto.CreateOrderRequest;
import com.dak.order.web.dto.OrderItemRequest;
import com.dak.order.web.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@Import(OrderWebMapperImpl.class)
class OrderWebMapperTest {

    @Autowired
    private OrderWebMapper mapper;

    @Test
    void toCommand_mapsAllFields() {
        CreateOrderRequest request = new CreateOrderRequest(
                OrderCategory.B2B,
                "cust-42",
                "site-99",
                List.of(new OrderItemRequest("po-1", 3)),
                PaymentMethodType.INVOICE,
                null);

        CreateOrderCommand command = mapper.toCommand(request);

        assertThat(command.category()).isEqualTo(OrderCategory.B2B);
        assertThat(command.customerId()).isEqualTo("cust-42");
        assertThat(command.siteId()).isEqualTo("site-99");
        assertThat(command.orderItems()).hasSize(1);
        assertThat(command.orderItems().get(0).productOfferingId()).isEqualTo("po-1");
        assertThat(command.orderItems().get(0).quantity()).isEqualTo(3);
        assertThat(command.paymentMethodType()).isEqualTo(PaymentMethodType.INVOICE);
        assertThat(command.paymentMethodIban()).isNull();
    }

    @Test
    void toCommand_mapsDirectDebitWithIban() {
        CreateOrderRequest request = new CreateOrderRequest(
                OrderCategory.B2C,
                "cust-1",
                "site-1",
                List.of(new OrderItemRequest("po-2", 1)),
                PaymentMethodType.DIRECT_DEBIT,
                "DE89370400440532013000");

        CreateOrderCommand command = mapper.toCommand(request);

        assertThat(command.paymentMethodType()).isEqualTo(PaymentMethodType.DIRECT_DEBIT);
        assertThat(command.paymentMethodIban()).isEqualTo("DE89370400440532013000");
    }

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Order order = Order.builder()
                .id(id)
                .state(new DraftState())
                .category(OrderCategory.B2B)
                .customer(new Customer("cust-1"))
                .site(new Site("site-1"))
                .orderItems(List.of(new OrderItem("po-1", 2)))
                .paymentMethod(new PaymentMethod(PaymentMethodType.INVOICE, null))
                .createdAt(now)
                .updatedAt(now)
                .build();

        OrderResponse response = mapper.toResponse(order);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.state()).isEqualTo("DRAFT");
        assertThat(response.category()).isEqualTo("B2B");
        assertThat(response.customerId()).isEqualTo("cust-1");
        assertThat(response.siteId()).isEqualTo("site-1");
        assertThat(response.orderItems()).hasSize(1);
        assertThat(response.orderItems().get(0).productOfferingId()).isEqualTo("po-1");
        assertThat(response.orderItems().get(0).quantity()).isEqualTo(2);
        assertThat(response.paymentMethodType()).isEqualTo("INVOICE");
        assertThat(response.paymentMethodIban()).isNull();
        assertThat(response.createdAt()).isEqualTo(now);
    }
}
