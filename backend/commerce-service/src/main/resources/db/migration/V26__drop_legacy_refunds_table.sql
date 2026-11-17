-- Xóa bảng refunds từ thiết kế ban đầu (V2). Luồng hoàn tiền hiện dùng refund_requests cùng các bảng
-- refund_attempts/refund_provider_receipts/refund_credit_notes; không còn entity, repository hay SQL nào
-- đọc/ghi refunds. Các API /api/v1/refunds/** thao tác trên refund_requests, không phụ thuộc bảng này.
-- Ảnh hưởng dữ liệu: bảng rỗng trên mọi môi trường đã kiểm tra (local và Supabase) tại thời điểm viết.
-- Chặn xóa nếu vẫn còn dữ liệu để không mất bản ghi ngoài dự kiến; khi đó cần chuyển dữ liệu thủ công trước.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM refunds) THEN
        RAISE EXCEPTION 'Bảng refunds vẫn còn dữ liệu; cần chuyển dữ liệu sang refund_requests trước khi xóa.';
    END IF;
END $$;

DROP TABLE refunds;
