package com.nexus.fulfillment.scheduler;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.fulfillment.service.InventoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kiểm thử bộ quét định kỳ giải phóng phiếu giữ hàng hết hạn (InventoryReservationScheduler).
 *
 * Tình huống kiểm thử:
 * - Scheduler gọi đúng hàm autoReleaseExpiredReservations của InventoryService.
 * - Khi có ngoại lệ không mong muốn xảy ra, scheduler không bị crash luồng.
 */
@ExtendWith(MockitoExtension.class)
class InventoryReservationSchedulerTest {

    @Mock
    private InventoryService inventoryService;

    @InjectMocks
    private InventoryReservationScheduler scheduler;

    @Test
    @DisplayName("Quét định kỳ: Gọi thành công hàm autoReleaseExpiredReservations")
    void testSweepExpiredReservations_Success() {
        when(inventoryService.autoReleaseExpiredReservations()).thenReturn(3);

        scheduler.sweepExpiredReservations();

        verify(inventoryService, times(1)).autoReleaseExpiredReservations();
    }

    @Test
    @DisplayName("Xử lý lỗi: Bắt và ghi nhận ngoại lệ không làm dừng tiến trình định kỳ")
    void testSweepExpiredReservations_HandlesException() {
        when(inventoryService.autoReleaseExpiredReservations()).thenThrow(new RuntimeException("DB Connection timeout"));

        scheduler.sweepExpiredReservations();

        verify(inventoryService, times(1)).autoReleaseExpiredReservations();
    }
}
