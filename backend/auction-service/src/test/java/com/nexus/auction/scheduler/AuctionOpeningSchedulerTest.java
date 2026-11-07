package com.nexus.auction.scheduler;

import com.nexus.auction.service.AuctionLifecycleService;
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
 * Unit test kiểm thử Scheduler tự động mở các phiên đấu giá đến giờ bắt đầu.
 */
@ExtendWith(MockitoExtension.class)
class AuctionOpeningSchedulerTest {

    @Mock
    private AuctionLifecycleService auctionLifecycleService;

    @InjectMocks
    private AuctionOpeningScheduler scheduler;

    @Test
    @DisplayName("Scheduler mở phiên thực thi bình thường khi có phiên đến giờ bắt đầu")
    void testRunOpeningJob_Success() {
        when(auctionLifecycleService.openScheduledAuctions()).thenReturn(2);

        scheduler.runOpeningJob();

        verify(auctionLifecycleService).openScheduledAuctions();
    }

    @Test
    @DisplayName("Scheduler mở phiên bắt và xử lý êm khi có lỗi xảy ra, không làm gián đoạn thread")
    void testRunOpeningJob_HandlesExceptionGracefully() {
        doThrow(new RuntimeException("Database connection timeout"))
                .when(auctionLifecycleService).openScheduledAuctions();

        // Không ném exception ra ngoài gây crash thread lập lịch
        scheduler.runOpeningJob();

        verify(auctionLifecycleService).openScheduledAuctions();
    }

    @Test
    @DisplayName("Scheduler mở phiên xử lý êm đềm khi không có phiên nào đến giờ mở (count = 0)")
    void testRunOpeningJob_ZeroCount_HandlesGracefully() {
        when(auctionLifecycleService.openScheduledAuctions()).thenReturn(0);

        scheduler.runOpeningJob();

        verify(auctionLifecycleService).openScheduledAuctions();
    }
}

