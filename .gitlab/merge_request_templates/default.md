## Mô tả
<!-- Thay đổi gì và vì sao? -->

## Thành phần bị ảnh hưởng
<!-- user / catalog / commerce / auction / fulfillment / notification / gateway / web / deploy -->

## Ticket
NEX-

## Cách test
<!-- Các bước test thủ công / test tự động đã thêm -->

## Checklist
- [ ] Tiêu đề MR theo Conventional Commits (vd: `feat(auction): add anti-sniping`)
- [ ] Đã thêm / cập nhật unit test
- [ ] Pipeline xanh, coverage không giảm dưới 70%
- [ ] Không có secret / thông tin nhạy cảm trong code
- [ ] Đã cập nhật OpenAPI / tài liệu nếu đổi API
- [ ] Đã thêm migration Flyway nếu đổi DB (không sửa migration cũ)
- [ ] Đã cập nhật event schema và báo các service liên quan nếu đổi event / contract
- [ ] Nếu thêm đường dẫn API mới: đã cập nhật route trong `backend/api-gateway`
- [ ] Nếu đổi UI: đính kèm ảnh chụp màn hình; `npm run lint && npm test` pass
