-- Bắt buộc mọi hồ sơ users liên kết với tài khoản Keycloak. Cả hai luồng tạo hồ sơ
-- (UserService.createUserProfile và đồng bộ từ Keycloak) đều gán keycloak_id = id, nên NULL chỉ có
-- thể xuất hiện do dữ liệu nhập tay và sẽ tạo hồ sơ không đăng nhập/đổi mật khẩu được.
-- Đánh số V8 vì V7__system_configs đang nằm ở nhánh feature/admin-dashboard-apis; user-service bật out-of-order.
-- Ảnh hưởng dữ liệu: dừng migration nếu còn hồ sơ thiếu keycloak_id để xử lý thủ công, không tự xóa hay sinh giá trị.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users WHERE keycloak_id IS NULL) THEN
        RAISE EXCEPTION 'Còn hồ sơ users thiếu keycloak_id; cần liên kết Keycloak trước khi đặt NOT NULL.';
    END IF;
END $$;

ALTER TABLE users ALTER COLUMN keycloak_id SET NOT NULL;
