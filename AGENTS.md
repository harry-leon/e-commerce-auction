# Quy tắc làm việc dành cho AI trong Project Nexus

Áp dụng cho toàn bộ repository. Khi làm trong một thư mục có `AGENTS.md` riêng, đọc thêm hướng dẫn tại thư mục đó; các quy tắc về comment bên dưới vẫn áp dụng.

## Bắt buộc giải thích phần code được tạo hoặc sửa

Mỗi lần AI thêm hoặc thay đổi code, phải cập nhật comment ngay tại phần code liên quan để người khác hiểu được:

1. **Chức năng:** đoạn code phục vụ nghiệp vụ/tác vụ nào.
2. **Đầu vào và kết quả:** nhận dữ liệu từ đâu, trả về gì hoặc thay đổi dữ liệu/state nào.
3. **Luồng xử lý:** các bước chính, gọi API/service/repository nào, dữ liệu được lưu ở đâu.
4. **Quy tắc và lý do:** điều kiện phân quyền, validation, giới hạn nghiệp vụ, lựa chọn triển khai cần lưu ý.
5. **Lỗi và giới hạn:** xử lý lỗi, retry, transaction, cleanup; nêu rõ phần mock/demo hoặc chưa triển khai khi có.

Chỉ ghi những mục thực sự liên quan đến đoạn code. Viết ngắn gọn bằng tiếng Việt có dấu, dùng tên kỹ thuật trong code khi cần. Comment phải giải thích đúng hành vi hiện tại, không mô tả chức năng dự kiến như đã hoàn thành.

## Cách đặt comment

- **Class, module, component, hook mới:** thêm Javadoc/JSDoc hoặc comment đầu khối mô tả trách nhiệm và luồng tổng quát.
- **Hàm nghiệp vụ, API endpoint, hàm dùng chung mới hoặc thay đổi:** mô tả mục đích, đầu vào/kết quả và tác dụng phụ đáng chú ý. Hàm đơn giản có thể dùng một dòng comment.
- **Luồng phức tạp:** đặt comment trước bước kiểm tra quyền, validation, cập nhật DB/state, refresh token, xử lý đồng thời, retry hoặc cleanup để giải thích lý do thực hiện.
- **SQL migration:** ghi rõ thay đổi schema phục vụ chức năng nào và ảnh hưởng dữ liệu hiện có. Không sửa migration đã chạy chỉ để thêm comment; dùng migration mới hoặc tài liệu liên quan nếu cần.
- **Docker, YAML, nginx, CI:** giải thích vai trò khối cấu hình, liên kết giữa các thành phần và lý do của healthcheck/cache/proxy/biến môi trường.
- **Test:** ghi rõ tình huống nghiệp vụ hoặc lỗi hồi quy mà test bảo vệ; không chỉ lặp lại từng câu lệnh assert.
- **JSON, lockfile và file sinh tự động:** không chèn comment trái định dạng, không sửa thủ công lockfile hoặc code sinh tự động. Giải thích trong file cấu hình hỗ trợ comment hoặc tài liệu đi kèm và chỉ rõ file được nói tới.

Không cần comment từng dòng, getter/setter hoặc JSX hiển nhiên. Một comment tốt phải giúp người đọc hiểu **vì sao** và **luồng chạy**, không chỉ diễn giải lại cú pháp. Không thêm nhãn “AI viết”, tên tác giả giả, thông tin bí mật, token hoặc mật khẩu vào comment.

## Khi sửa code có sẵn

- Đọc code và comment hiện có trước khi sửa; giữ nguyên thay đổi của người dùng.
- Nếu hành vi thay đổi, sửa comment cũ cho khớp; không để mô tả cũ mâu thuẫn với code mới.
- Ghi chú tại đúng phần được sửa, không chỉ ghi trong câu trả lời chat hoặc một file tổng kết.
- Ưu tiên giữ nguyên hành vi khi nhiệm vụ chỉ yêu cầu bổ sung comment/tài liệu.
- Trước khi hoàn tất, rà comment trong diff và chạy kiểm tra phù hợp. Không tạo test chỉ để kiểm tra sự tồn tại của comment.
- Khi bàn giao, nêu những phần đã chú thích, kiểm tra đã chạy và giới hạn còn lại; không khẳng định các AI khác sẽ tự động tuân thủ nếu chúng không đọc file này.

## Ví dụ

```java
/**
 * Chuyển danh mục sang cha mới và cập nhật cấp của toàn bộ cây con.
 * Luồng: tìm cha → chặn vòng lặp → kiểm tra độ sâu tối đa → cập nhật entity trong transaction.
 * Từ chối thao tác nếu cây con sau khi chuyển vượt quá ba cấp.
 */
```

```ts
// Dùng chung một Promise refresh cho các request cùng gặp 401,
// sau đó gửi lại mỗi request tối đa một lần để tránh vòng lặp refresh vô hạn.
```
