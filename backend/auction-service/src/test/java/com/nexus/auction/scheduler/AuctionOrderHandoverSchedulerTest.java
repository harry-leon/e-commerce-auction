package com.nexus.auction.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.service.AuctionOrderHandoverService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Bộ kiểm thử đơn vị cho AuctionOrderHandoverScheduler (Worker quét và retry bàn giao đơn hàng nền).
 *
 * Chức năng kiểm thử:
 * - Đảm bảo worker tự động quét danh sách settlement có result = SUCCESS và orderId IS NULL.
 * - Gọi handoverAuctionOrder cho từng phiên.
 * - Cô lập lỗi: Một phiên gặp ngoại lệ không làm dừng hoặc làm hỏng tiến trình của các phiên còn lại.
 * - Danh sách rỗng: không thực hiện hành động nào.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuctionOrderHandoverScheduler Unit Test - Quét và thử lại bàn giao đơn hàng chạy nền")
class AuctionOrderHandoverSchedulerTest {

    @Mock
    private AuctionSettlementRepository auctionSettlementRepository;

    @Mock
    private AuctionOrderHandoverService auctionOrderHandoverService;

    private AuctionOrderHandoverScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuctionOrderHandoverScheduler(auctionSettlementRepository, auctionOrderHandoverService);
    }

    @Test
    @DisplayName("Quét thấy 2 phiên chưa có đơn hàng: Bàn giao thành công cả 2 phiên")
    void test_retryPendingHandovers_success() {
        UUID auction1 = UUID.randomUUID();
        UUID auction2 = UUID.randomUUID();

        AuctionSettlement s1 = new AuctionSettlement();
        s1.setAuctionId(auction1);
        s1.setResult(SettlementResult.SUCCESS);

        AuctionSettlement s2 = new AuctionSettlement();
        s2.setAuctionId(auction2);
        s2.setResult(SettlementResult.SUCCESS);

        when(auctionSettlementRepository.findByResultAndOrderIdIsNull(SettlementResult.SUCCESS))
                .thenReturn(List.of(s1, s2));

        scheduler.retryPendingHandovers();

        verify(auctionOrderHandoverService, times(1)).handoverAuctionOrder(auction1);
        verify(auctionOrderHandoverService, times(1)).handoverAuctionOrder(auction2);
    }

    @Test
    @DisplayName("Cô lập lỗi: Phiên thứ nhất ném ngoại lệ -> Phiên thứ hai vẫn được bàn giao bình thường")
    void test_retryPendingHandovers_isolationWhenOneFails() {
        UUID auction1 = UUID.randomUUID();
        UUID auction2 = UUID.randomUUID();

        AuctionSettlement s1 = new AuctionSettlement();
        s1.setAuctionId(auction1);
        s1.setResult(SettlementResult.SUCCESS);

        AuctionSettlement s2 = new AuctionSettlement();
        s2.setAuctionId(auction2);
        s2.setResult(SettlementResult.SUCCESS);

        when(auctionSettlementRepository.findByResultAndOrderIdIsNull(SettlementResult.SUCCESS))
                .thenReturn(List.of(s1, s2));
        doThrow(new RuntimeException("Mạng Commerce Service tạm thời mất kết nối"))
                .when(auctionOrderHandoverService).handoverAuctionOrder(auction1);

        scheduler.retryPendingHandovers();

        verify(auctionOrderHandoverService, times(1)).handoverAuctionOrder(auction1);
        verify(auctionOrderHandoverService, times(1)).handoverAuctionOrder(auction2);
    }

    @Test
    @DisplayName("Không có phiên nào tồn đọng: Không gọi handover")
    void test_retryPendingHandovers_emptyList() {
        when(auctionSettlementRepository.findByResultAndOrderIdIsNull(SettlementResult.SUCCESS))
                .thenReturn(Collections.emptyList());

        scheduler.retryPendingHandovers();

        verify(auctionOrderHandoverService, never()).handoverAuctionOrder(org.mockito.ArgumentMatchers.any());
    }
}
