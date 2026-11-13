package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.response.ShipmentResponse;
import java.util.UUID;

/**
 * Dịch vụ xử lý các thao tác kho vận vật lý: Pick, Pack, Ship (SRS §3.6.1, §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Pick: Nhân viên lấy hàng từ kệ, cập nhật trạng thái vận đơn sang PICKING và ghi lịch sử trạng thái.
 * - Pack: Đóng gói hoàn tất kiện hàng, cập nhật trạng thái vận đơn sang PACKED và ghi lịch sử trạng thái.
 * - Ship: Xuất kho bàn giao cho đơn vị vận chuyển:
 *   + Cập nhật trạng thái vận đơn sang SHIPPED (hoặc PICKED_UP).
 *   + Trừ số lượng giữ hàng thực tế: reserved_qty -> xuất kho (total_qty giảm, reserved_qty giảm, reservation CONFIRMED).
 *   + Ghi dòng biến động bất biến OUTBOUND vào Sổ cái tồn kho (inventory_ledger).
 *   + Phát sự kiện miền InventoryCommittedEvent và ShipmentPickedUpEvent.
 */
public interface WarehouseFulfillmentService {

    /**
     * Bắt đầu lấy hàng từ kệ cho vận đơn (Pick Process).
     * @param shipmentId ID vận đơn
     * @return thông tin vận đơn sau khi chuyển sang PICKING
     */
    ShipmentResponse pick(UUID shipmentId);

    /**
     * Hoàn tất đóng gói hàng vào kiện (Pack Process).
     * @param shipmentId ID vận đơn
     * @return thông tin vận đơn sau khi chuyển sang PACKED
     */
    ShipmentResponse pack(UUID shipmentId);

    /**
     * Xuất kho bàn giao cho đơn vị vận chuyển (Ship Process).
     * Cập nhật trạng thái sang SHIPPED, trừ tồn kho vật lý (reserved_qty -> committed/outbound),
     * ghi sổ cái bất biến inventory_ledger và phát các sự kiện miền InventoryCommitted, ShipmentPickedUp.
     *
     * @param shipmentId ID vận đơn
     * @return thông tin vận đơn sau khi xuất kho thành công
     */
    ShipmentResponse ship(UUID shipmentId);

    /**
     * Xử lý hoàn kho khi vận đơn giao hàng thất bại hoặc người mua trả hàng (Return Process - SRS §3.6.1, §3.6.2, KH-S3-3).
     *
     * Chức năng:
     * - Cập nhật trạng thái vận đơn sang RETURNED.
     * - Hoàn trả tồn kho vật lý cho các SKU đã xuất của đơn hàng vào kho xuất ban đầu (tăng total_qty và available_qty).
     * - Ghi dòng biến động bất biến INBOUND/RETURN vào Sổ cái tồn kho (inventory_ledger) theo đúng nguyên tắc Idempotent.
     * - Phát sự kiện miền ShipmentReturnedEvent và ghi Transactional Outbox.
     *
     * @param shipmentId ID vận đơn bị hoàn trả
     * @param reason Lý do hoàn trả (khách từ chối nhận, không liên lạc được, hoàn trả theo yêu cầu)
     * @return Thông tin vận đơn sau khi cập nhật hoàn kho
     */
    ShipmentResponse processReturn(UUID shipmentId, String reason);
}
