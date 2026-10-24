-- Đồng bộ hồ sơ ERD V2 sau khi V4 đã chuyển IAM sang Keycloak.
-- Giữ nguyên id và các hồ sơ cũ; không tự suy đoán keycloak_id cho tài khoản chưa được liên kết.
-- UUID không hợp lệ làm migration dừng để quản trị sửa dữ liệu, tránh liên kết nhầm tài khoản.
ALTER TABLE users ALTER COLUMN keycloak_id TYPE uuid USING NULLIF(keycloak_id, '')::uuid;
ALTER TABLE users ADD COLUMN updated_at timestamp;
UPDATE users SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE users ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE users ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;

-- Mật khẩu/đếm đăng nhập cục bộ không còn được ứng dụng sử dụng.
-- Sao lưu và chuyển credential sang Keycloak trước khi áp dụng; không thể hoàn tác giá trị đã drop.
ALTER TABLE users DROP COLUMN IF EXISTS password_hash;
ALTER TABLE users DROP COLUMN IF EXISTS failed_login_attempts;
ALTER TABLE users DROP COLUMN IF EXISTS locked_until;
