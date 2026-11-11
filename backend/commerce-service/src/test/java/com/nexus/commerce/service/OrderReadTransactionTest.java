package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.impl.OrderServiceImpl;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Hồi quy overload mặc định gọi nội bộ: proxy Spring thật phải mở transaction trước khi repository/mapper đọc quan hệ lazy.
 * Transaction manager là fixture không kết nối DB; kiểm tra đúng biên giao dịch, không giả lập truy vấn Hibernate.
 */
class OrderReadTransactionTest {
    /**
     * Kiểm tra bảo vệ lỗi hồi quy LazyInitializationException:
     * Đảm bảo Spring TransactionInterceptor kích hoạt transaction read-only ngay từ các default overload
     * của OrderService trước khi repository truy vấn và OrderMapper duyệt qua các quan hệ lazy (items, history).
     */
    @Test
    void defaultReadOverloadsKeepRepositoryAndMapperInsideReadOnlyTransaction() {
        var repository = mock(OrderRepository.class);
        var history = mock(OrderStatusHistoryRepository.class);
        var buyer = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var order = Order.builder().id(orderId).buyerId(buyer).sellerId(UUID.randomUUID()).build();
        var page = new PageImpl<>(List.of(order));
        when(repository.findByBuyerId(any(), any())).thenAnswer(invocation -> { assertReadTransaction(); return page; });
        when(repository.findBySellerId(any(), any())).thenAnswer(invocation -> { assertReadTransaction(); return page; });
        when(repository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenAnswer(invocation -> { assertReadTransaction(); return page; });
        // Truy vấn chi tiết nạp items trong transaction read-only; dùng đúng repository method của implementation hiện tại.
        when(repository.findWithItemsById(orderId)).thenAnswer(invocation -> { assertReadTransaction(); return Optional.of(order); });
        when(history.findByOrderIdOrderByChangedAtAsc(orderId)).thenReturn(List.of());

        var manager = new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
            @Override protected void doCommit(DefaultTransactionStatus status) { }
            @Override protected void doRollback(DefaultTransactionStatus status) { }
        };
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var target = new OrderServiceImpl(repository, history, new OrderStatusStateMachine(), new OrderMapper(), mock(JdbcTemplate.class));
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        var service = (OrderService) factory.getProxy();
        var pageable = PageRequest.of(0, 10);
        service.getBuyerOrders(buyer, null, (LocalDateTime) null, null, pageable);
        service.getSellerOrders(order.getSellerId(), null, (LocalDateTime) null, null, pageable);
        service.getAdminOrders(null, (LocalDateTime) null, null, pageable);
        service.getOrderDetail(orderId, buyer, false);
    }

    /** Truy vấn và ánh xạ lazy phải hoàn tất trước khi Spring đóng transaction read-only. */
    private void assertReadTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }
}
