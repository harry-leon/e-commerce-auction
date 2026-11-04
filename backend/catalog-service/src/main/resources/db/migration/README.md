# Ghi chú migration Catalog

Flyway chỉ thực thi các migration SQL đúng quy tắc tên; file này giải thích nghiệp vụ và không chạy vào DB.

## V3__category_translations.sql

- **Chức năng:** lưu tên tiếng Việt riêng cho danh mục, phục vụ form Admin có cả `name` và `nameVi`.
- **Luồng:** thêm cột `categories.name_vi` → sao chép `name` sang cột mới cho các bản ghi đang có giá trị null.
- **Kết quả:** dữ liệu cũ có tên hiển thị dự phòng; API tạo/sửa danh mục sau đó có thể lưu bản dịch riêng.
- **Không thay đổi:** UUID, quan hệ cha–con, sản phẩm đang tham chiếu danh mục.
- **Lưu ý:** migration này đã được chạy trong lần kiểm chứng local. Không sửa cả comment trong file SQL đã áp dụng vì Flyway lưu checksum; nếu cần thay đổi schema, tạo migration phiên bản tiếp theo. Phần giải thích được đặt tại đây để giữ checksum hiện tại.

## V5__restore_category_translations_and_merge_roots.sql

- **Chức năng:** khôi phục `categories.name_vi` để API trả tên EN/VI độc lập cho giao diện.
- **Luồng:** chuẩn hóa sáu root chuẩn, chuyển các nhánh con có sản phẩm sang root tương ứng rồi chỉ xóa root trùng khi không còn nhánh hoặc sản phẩm tham chiếu.
- **An toàn dữ liệu:** không cập nhật `products.category_id`; category ngoài bộ UUID dữ liệu mẫu chỉ được đặt bản dịch dự phòng bằng `name`.

## V4__align_categories_with_final_erd.sql

ERD đã chốt chỉ có `name`. V4 bỏ `name_vi`; API chỉ nhận `name` và trả `nameVi = name` như alias tương thích UI. Sao lưu bản dịch riêng trước khi nâng cấp; không sửa V3 đã chạy.
