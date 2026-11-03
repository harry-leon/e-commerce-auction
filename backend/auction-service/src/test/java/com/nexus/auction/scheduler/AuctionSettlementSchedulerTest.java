package com.nexus.auction.scheduler;

import com.nexus.auction.service.AuctionSettlementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test kiểm thử Scheduler tự động quyết toán các phiên đấu giá.
 */
@ExtendWith(MockitoExtension.class)
class AuctionSettlementSchedulerTest {

    @Mock
    private AuctionSettlementService auctionSettlementService;

    @InjectMocks
    private AuctionSettlementScheduler scheduler;

    @Test
    @DisplayName("Scheduler thực thi bình thường khi có phiên hết hạn")
    void testRunSettlementJob_Success() {
        when(auctionSettlementService.settleExpiredAuctions()).thenReturn(3);

        scheduler.runSettlementJob();

        verify(auctionSettlementService).settleExpiredAuctions();
    }

    @Test
    @DisplayName("Scheduler bắt và không làm gián đoạn tiến trình khi có lỗi xảy ra")
    void testRunSettlementJob_HandlesExceptionGracefully() {
        doThrow(new RuntimeException("Database timeout")).when(auctionSettlementService).settleExpiredAuctions();

        // Không được ném lỗi ra ngoài làm crash thread lập lịch
        scheduler.runSettlementJob();

        verify(auctionSettlementService).settleExpiredAuctions();
    }
}

