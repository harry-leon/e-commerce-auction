package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.StockAllocationRequest;
import com.nexus.fulfillment.dto.response.StockAllocationResponse;

/**
 * Dịch vụ thuật toán phân bổ tồn kho đa kho cho đơn hàng (SRS §3.6.1, KH-S3-2).
 *
 * Chức năng:
 * - Xác định phương án phân bổ tồn kho tối ưu từ danh sách kho vật lý đang hoạt động.
 * - Quy tắc bắt buộc:
 *   1. Ưu tiên tuyệt đối phân bổ từ kho mặc định DEFAULT_WAREHOUSE_ID (WH_001) nếu đủ tồn khả dụng.
 *   2. Trường hợp kho mặc định không đủ, tìm kiếm kho phụ để gom đơn nhưng không bao giờ vượt quá
 *      MAX_WAREHOUSES_PER_ORDER = 2 kho cho một đơn hàng.
 *   3. Từ chối đơn hàng nếu việc gom tồn kho đòi hỏi từ 3 kho trở lên hoặc tổng tồn không đủ.
 */
public interface WarehouseStockAllocationService {

    /**
     * Tính toán phương án phân bổ kho hàng tuân thủ quy tắc DEFAULT_WAREHOUSE_ID và MAX_WAREHOUSES_PER_ORDER = 2.
     *
     * @param request danh sách SKU và số lượng đặt trong đơn hàng
     * @return phương án phân bổ chi tiết theo từng kho
     */
    StockAllocationResponse computeAllocation(StockAllocationRequest request);
}
