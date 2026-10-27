-- V4__split_iam_and_reputation.sql
-- Chức năng: Tinh gọn database user-service, tách trách nhiệm IAM sang Keycloak và tách Reputation/Ratings sang reputation-service.
-- Đầu vào và kết quả: Thay đổi schema bảng users (bổ sung keycloak_id, nới lỏng password_hash), đồng thời DROP các bảng IAM và bảng đánh giá/uy tín.
-- Quy tắc và lý do:
--   1. Keycloak sẽ là Identity Provider (IdP) quản lý toàn bộ việc xác thực (password, reset token) và phân quyền (roles, privileges).
--   2. Bảng users chỉ lưu trữ thông tin hồ sơ cá nhân (Profile) gắn với định danh keycloak_id (Subject claim trong JWT).
--   3. Các bảng reputations, penalties, ratings chuyển sang schema riêng thuộc reputation-service để xử lý non-realtime và giảm tải.

-- 1. Bổ sung trường keycloak_id và điều chỉnh ràng buộc bảng users
ALTER TABLE users ADD COLUMN IF NOT EXISTS keycloak_id VARCHAR(255) UNIQUE;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users DROP COLUMN IF EXISTS token_version;

-- 2. Loại bỏ các bảng thuộc miền Đánh giá & Uy tín (Chuyển giao cho reputation-service)
DROP TABLE IF EXISTS ratings CASCADE;
DROP TABLE IF EXISTS penalties CASCADE;
DROP TABLE IF EXISTS reputations CASCADE;

-- 3. Loại bỏ các bảng thuộc miền Xác thực & Phân quyền IAM (Chuyển giao cho Keycloak Realm DB)
DROP TABLE IF EXISTS password_reset_tokens CASCADE;
DROP TABLE IF EXISTS user_roles CASCADE;
DROP TABLE IF EXISTS role_privileges CASCADE;
DROP TABLE IF EXISTS roles CASCADE;
DROP TABLE IF EXISTS privileges CASCADE;
