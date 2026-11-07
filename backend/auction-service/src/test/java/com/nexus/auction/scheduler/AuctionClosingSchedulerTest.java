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
 * Unit test kiểm thử Scheduler tự động đóng các phiên đấu giá đã quá hạn và kích hoạt quyết toán.
 */
@ExtendWith(MockitoExtension.class)
class AuctionClosingSchedulerTest {

    @Mock
    private AuctionLifecycleService auctionLifecycleService;

    @InjectMocks
    private AuctionClosingScheduler scheduler;

    @Test
    @DisplayName("Scheduler đóng phiên thực thi bình thường khi có phiên hết hạn")
    void testRunClosingJob_Success() {
        when(auctionLifecycleService.closeExpiredAuctions()).thenReturn(3);

        scheduler.runClosingJob();

        verify(auctionLifecycleService).closeExpiredAuctions();
    }

    @Test
    @DisplayName("Scheduler đóng phiên bắt và xử lý êm khi có lỗi xảy ra, không làm crash thread")
    void testRunClosingJob_HandlesExceptionGracefully() {
        doThrow(new RuntimeException("Lock wait timeout"))
                .when(auctionLifecycleService).closeExpiredAuctions();

        scheduler.runClosingJob();

        verify(auctionLifecycleService).closeExpiredAuctions();
    }

    @Test
    @DisplayName("Scheduler đóng phiên xử lý êm đềm khi không có phiên nào hết hạn (count = 0)")
    void testRunClosingJob_ZeroCount_HandlesGracefully() {
        when(auctionLifecycleService.closeExpiredAuctions()).thenReturn(0);

        scheduler.runClosingJob();

        verify(auctionLifecycleService).closeExpiredAuctions();
    }
}

