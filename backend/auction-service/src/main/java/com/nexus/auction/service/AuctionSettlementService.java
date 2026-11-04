package com.nexus.auction.service;

import com.nexus.auction.dto.response.AuctionSettlementResponse;
import java.time.Instant;
import java.util.UUID;

/**
 * Interface nghiệp vụ quyết toán phiên đấu giá và xác định người chiến thắng (DoD HG-S2-1).
 *
 * Chức năng:
 * - Khóa phiên tại thời điểm kết thúc.
 * - Xác định lượt bid hợp lệ cao nhất và người chiến thắng.
 * - Thiết lập kết quả SUCCESS cho phiên có người thắng hoặc FAILED cho phiên không có bid.
 * - Đảm bảo tính lũy kế (idempotent), chỉ quyết toán 1 lần duy nhất cho mỗi phiên.
 * - Cung cấp hàm kiểm tra ngăn chặn đặt giá sau khi phiên bị khóa hoặc kết thúc.
 */
public interface AuctionSettlementService {

    /**
     * Quyết toán một phiên đấu giá cụ thể theo ID.
     *
     * Luồng:
     * 1. Kiểm tra bản ghi settlement đã tồn tại chưa (Idempotency). Nếu có, trả về ngay kết quả.
     * 2. Khóa phiên (lock) bằng khóa bi quan để tránh cạnh tranh.
     * 3. Kiểm tra điều kiện: chỉ settle khi phiên đã đến endTime hoặc đã ở trạng thái ENDED.
     * 4. Khóa phiên, chuyển trạng thái sang ENDED.
     * 5. Tìm lượt bid hợp lệ cao nhất. Nếu có -> SUCCESS, winner_id, final_price, hạn thanh toán +24h.
     *    Nếu không có -> FAILED, winner_id = null, final_price = null.
     * 6. Lưu settlement và cập nhật trạng thái Auction (SETTLED nếu SUCCESS, ENDED nếu FAILED).
     *
     * @param auctionId định danh phiên đấu giá
     * @return kết quả quyết toán AuctionSettlementResponse
     */
    AuctionSettlementResponse settleAuction(UUID auctionId);

    /**
     * Quét và tự động quyết toán tất cả các phiên đấu giá đang ở trạng thái ACTIVE mà đã quá endTime.
     *
     * @return số lượng phiên đã được xử lý quyết toán
     */
    int settleExpiredAuctions();

    /**
     * Tra cứu thông tin quyết toán hiện tại của phiên đấu giá.
     *
     * @param auctionId định danh phiên đấu giá
     * @return kết quả quyết toán
     */
    AuctionSettlementResponse getSettlementByAuctionId(UUID auctionId);

    /**
     * Kiểm tra phiên đấu giá có cho phép nhận lượt bid mới tại thời điểm chỉ định hay không.
     *
     * @param auctionId định danh phiên đấu giá
     * @param bidTime thời điểm người mua gửi bid
     * @return true nếu phiên đang ACTIVE và bidTime nằm trong khoảng hợp lệ trước endTime
     */
    boolean canAcceptBid(UUID auctionId, Instant bidTime);
}

