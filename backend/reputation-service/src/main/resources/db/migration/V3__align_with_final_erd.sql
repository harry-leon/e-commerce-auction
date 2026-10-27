-- ERD reputations chỉ chứa điểm/cấp uy tín và bộ đếm giao dịch, đấu giá.
-- avg_rating/total_ratings có thể tính từ ratings; bỏ thống kê bổ sung và updated_at, giữ nguyên lịch sử ratings/penalties.
ALTER TABLE reputations DROP CONSTRAINT chk_reputation_counts;
ALTER TABLE reputations DROP COLUMN avg_rating;
ALTER TABLE reputations DROP COLUMN total_ratings;
ALTER TABLE reputations DROP COLUMN updated_at;
-- Giữ validation số đếm không âm sau khi loại total_ratings khỏi schema.
ALTER TABLE reputations ADD CONSTRAINT chk_reputation_counts CHECK (
    total_tx >= 0 AND success_tx >= 0 AND failed_tx >= 0 AND auction_won >= 0 AND auction_failed >= 0
);
