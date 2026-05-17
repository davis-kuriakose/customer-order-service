package com.dak.order.web.controller;

import com.dak.order.domain.command.CreateOrderCommand;
import com.dak.order.domain.exception.OrderNotFoundException;
import com.dak.order.domain.model.Customer;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PaymentMethod;
import com.dak.order.domain.model.PaymentMethodType;
import com.dak.order.domain.model.Site;
import com.dak.order.domain.port.inbound.OrderUseCase;
import com.dak.order.domain.state.DraftState;
import com.dak.order.web.dto.OrderItemResponse;
import com.dak.order.web.dto.OrderResponse;
import com.dak.order.web.mapper.OrderWebMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@WithMockUser
class OrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    OrderUseCase orderUseCase;

    @MockBean
    OrderWebMapper orderWebMapper;

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Order buildOrder() {
        return Order.builder()
                .id(ORDER_ID)
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

    private OrderResponse buildOrderResponse() {
        return new OrderResponse(
                ORDER_ID, "DRAFT", "B2B", "cust-1", "site-1",
                List.of(new OrderItemResponse("po-1", 2)),
                "INVOICE", null,
                Instant.now(), Instant.now());
    }

    @Test
    void createOrder_returns201_withLocationHeader() throws Exception {
        CreateOrderCommand command = new CreateOrderCommand(
                OrderCategory.B2B, "cust-1", "site-1",
                List.of(new OrderItem("po-1", 2)), PaymentMethodType.INVOICE, null);
        Order order = buildOrder();
        OrderResponse response = buildOrderResponse();

        when(orderWebMapper.toCommand(any())).thenReturn(command);
        when(orderUseCase.createOrder(any(), isNull())).thenReturn(order);
        when(orderWebMapper.toResponse(any())).thenReturn(response);

        String body = """
                {
                  "category": "B2B",
                  "customerId": "cust-1",
                  "siteId": "site-1",
                  "orderItems": [{"productOfferingId": "po-1", "quantity": 2}],
                  "paymentMethodType": "INVOICE"
                }
                """;

        mockMvc.perform(post("/customer-orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/customer-orders/" + ORDER_ID))
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.state").value("DRAFT"));
    }

    @Test
    void createOrder_returns400_whenBodyMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/customer-orders")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrder_returns200_withOrderBody() throws Exception {
        Order order = buildOrder();
        OrderResponse response = buildOrderResponse();
        when(orderUseCase.getOrder(ORDER_ID)).thenReturn(order);
        when(orderWebMapper.toResponse(order)).thenReturn(response);

        mockMvc.perform(get("/customer-orders/{id}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.category").value("B2B"));
    }

    @Test
    void getOrder_propagatesOrderNotFoundException_whenNotFound() throws Exception {
        when(orderUseCase.getOrder(ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

        mockMvc.perform(get("/customer-orders/{id}", ORDER_ID))
                .andExpect(result ->
                        assertThat(result.getResolvedException())
                                .isInstanceOf(OrderNotFoundException.class));
    }

    @Test
    void listOrders_returns200_withPagedResponse() throws Exception {
        Order order = buildOrder();
        OrderResponse response = buildOrderResponse();
        when(orderUseCase.listOrders(isNull(), anyInt(), anyInt())).thenReturn(List.of(order));
        when(orderUseCase.countOrders(isNull())).thenReturn(1L);
        when(orderWebMapper.toResponse(order)).thenReturn(response);

        mockMvc.perform(get("/customer-orders")
                        .param("limit", "20")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    void listOrders_returns200_filteredByCategory() throws Exception {
        Order order = buildOrder();
        OrderResponse response = buildOrderResponse();
        when(orderUseCase.listOrders(any(OrderCategory.class), anyInt(), anyInt())).thenReturn(List.of(order));
        when(orderUseCase.countOrders(any(OrderCategory.class))).thenReturn(1L);
        when(orderWebMapper.toResponse(order)).thenReturn(response);

        mockMvc.perform(get("/customer-orders")
                        .param("category", "B2B")
                        .param("limit", "10")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").value(1));
    }
}
