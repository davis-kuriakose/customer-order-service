package com.dak.order.infrastructure.persistence.repository;

import com.dak.order.domain.model.Customer;
import com.dak.order.domain.model.Order;
import com.dak.order.domain.model.OrderCategory;
import com.dak.order.domain.model.OrderItem;
import com.dak.order.domain.model.PagedOrders;
import com.dak.order.domain.model.PaymentMethod;
import com.dak.order.domain.model.PaymentMethodType;
import com.dak.order.domain.model.Site;
import com.dak.order.domain.state.DraftState;
import com.dak.order.infrastructure.persistence.entity.OrderItemJpaEntity;
import com.dak.order.infrastructure.persistence.entity.OrderJpaEntity;
import com.dak.order.infrastructure.persistence.mapper.OrderPersistenceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderRepositoryAdapterTest {

    @Mock
    private OrderJpaRepository jpaRepository;

    @Mock
    private OrderPersistenceMapper mapper;

    @InjectMocks
    private OrderRepositoryAdapter adapter;

    // ── TASK-25: create() skips SELECT ───────────────────────────────────────

    @Test
    void create_neverCallsFindById_insertsDirectly() {
        Order order = buildOrder(UUID.randomUUID());
        OrderJpaEntity savedEntity = new OrderJpaEntity();
        OrderItemJpaEntity itemEntity = new OrderItemJpaEntity();

        when(mapper.toItemEntity(any(OrderItem.class))).thenReturn(itemEntity);
        when(jpaRepository.save(any(OrderJpaEntity.class))).thenReturn(savedEntity);
        when(mapper.toDomain(savedEntity)).thenReturn(order);

        adapter.create(order);

        // The key assertion: no SELECT before the INSERT
        verify(jpaRepository, never()).findById(any(UUID.class));
        verify(jpaRepository).save(any(OrderJpaEntity.class));
    }

    @Test
    void save_throwsIllegalStateException_whenEntityNotFound() {
        // A missing entity during update is a data integrity bug — the ID was already
        // validated by OrderService.patchOrder. orElseThrow surfaces it immediately.
        UUID id = UUID.randomUUID();
        Order order = buildOrder(id);
        when(jpaRepository.findById(id)).thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.save(order))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void save_callsFindByIdFirst_toPreserveVersion() {
        UUID id = UUID.randomUUID();
        Order order = buildOrder(id);
        OrderJpaEntity existingEntity = new OrderJpaEntity();
        OrderItemJpaEntity itemEntity = new OrderItemJpaEntity();

        when(jpaRepository.findById(id)).thenReturn(Optional.of(existingEntity));
        when(mapper.toItemEntity(any(OrderItem.class))).thenReturn(itemEntity);
        when(jpaRepository.save(any(OrderJpaEntity.class))).thenReturn(existingEntity);
        when(mapper.toDomain(existingEntity)).thenReturn(order);

        adapter.save(order);

        // UPDATE path must load existing entity to get the @Version value
        verify(jpaRepository).findById(id);
        verify(jpaRepository).save(any(OrderJpaEntity.class));
    }

    // ── TASK-22: findAll returns PagedOrders with total ──────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void findAll_returnsPagedOrdersWithTotalFromPage() {
        Order order = buildOrder(UUID.randomUUID());
        Page<OrderJpaEntity> mockPage = org.mockito.Mockito.mock(Page.class);

        when(jpaRepository.findAll(any(Pageable.class))).thenReturn(mockPage);
        when(mockPage.map(any())).thenReturn(org.mockito.Mockito.mock(Page.class));
        when(mockPage.getTotalElements()).thenReturn(42L);
        // Simplified: just verify total is surfaced
        when(mockPage.map(any())).thenAnswer(inv -> {
            Page<Order> domainPage = org.mockito.Mockito.mock(Page.class);
            when(domainPage.toList()).thenReturn(List.of(order));
            return domainPage;
        });

        PagedOrders result = adapter.findAll(null, 20, 0);

        assertThat(result.total()).isEqualTo(42L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
}
