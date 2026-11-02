# PHÂN CÔNG SPRINT 2

**Mốc toàn dự án:** Cuối Sprint 3 chạy các luồng chính, Sprint 4 hoàn thiện và bàn giao. Xem [kế hoạch Sprint 2–4](PROJECT_PLAN_SPRINT2_TO_4.md); yêu cầu chưa làm trong Sprint 2 được theo dõi tiếp ở đó, không mặc định loại khỏi SRS.

**Thời gian Sprint 2: 02/11 – 15/11/2026.** Các công việc thực hiện trong 10 ngày làm việc từ 02/11 đến 13/11; tổng kết và demo ngày 13/11.

**Mục tiêu Sprint 2: Hoàn thiện luồng đấu giá từ tạo phiên, trả giá trực tiếp đến chốt phiên và thanh toán đơn hàng đấu giá.**

- **Nền tảng tích hợp:** Rà soát, tích hợp các phần đơn hàng, thanh toán và thông báo còn lại từ Sprint 1 để phục vụ luồng đấu giá.
- **Quản lý phiên đấu giá:** Tạo, chỉnh sửa trước giờ mở, xem và hủy phiên theo quyền; kiểm tra sản phẩm, người bán và thời gian đấu giá.
- **Trả giá trực tiếp:** Đặt giá, kiểm tra bước giá, lưu lịch sử; xử lý đồng thời và ngăn ghi nhận trùng khi gửi lại yêu cầu.
- **Cập nhật thời gian thực:** Hiển thị giá cao nhất, lịch sử trả giá và thời gian còn lại qua WebSocket; đồng bộ lại khi kết nối bị gián đoạn.
- **Chống sniping:** Gia hạn phiên khi có lượt trả giá hợp lệ gần giờ kết thúc, theo thời gian và giới hạn được nhóm thống nhất.
- **Chốt phiên và tạo đơn:** Tự động mở/đóng phiên, xác định người thắng và chuyển kết quả sang Commerce để tạo một đơn hàng đấu giá.
- **Tồn kho và thanh toán:** Giữ hàng suốt vòng đời đấu giá và hạn thanh toán; xác nhận hoặc hoàn tồn theo kết quả; tái sử dụng cổng thanh toán sandbox.
- **Thông báo:** Hiển thị kết quả phiên và hạn thanh toán; bổ sung email/inbox cho người thắng khi phần tích hợp thông báo sẵn sàng.
- **Kiểm thử:** Kiểm thử toàn luồng đấu giá–đơn hàng–thanh toán, cạnh tranh trả giá, mất kết nối và yêu cầu gửi lại; sửa lỗi và đảm bảo CI đạt.

**Phân công nhiệm vụ trong Sprint 2**

| **Thành viên** | **Vai trò** | **Nhiệm vụ Sprint 2** | **Thời gian** |
| --- | --- | --- | --- |
| **Tiến Thương** | Leader / Backend | Chốt API, trạng thái phiên, cấu trúc sự kiện và luồng phối hợp Auction–Commerce–Inventory; tích hợp các phần đơn hàng/thanh toán còn lại từ Sprint 1. | 02/11 – 03/11 |
| | | Cấu hình Gateway/WebSocket, xác thực và phân quyền; thống nhất cấu hình đấu giá và cách gọi nội bộ giữa các service. | 04/11 – 05/11 |
| | | Tích hợp thanh toán đơn đấu giá; kiểm tra webhook, chống callback trùng và xử lý thanh toán cạnh tranh với hết hạn. | 06/11 – 10/11 |
| | | Review tích hợp, xử lý lỗi liên service; kiểm tra CI, chuẩn bị môi trường và hỗ trợ demo. | 11/11 – 13/11 |
| **Quốc Hùng** | Backend | Chốt mô hình dữ liệu và API đấu giá; triển khai tạo, danh sách, chi tiết, sửa trước giờ mở và hủy phiên theo quyền. | 02/11 – 04/11 |
| | | Triển khai đặt giá, kiểm tra bước giá, quyền tham gia và lịch sử; xử lý đồng thời, chống bid trùng và phát sự kiện sau khi lưu thành công. | 05/11 – 06/11 |
| | | Tự động mở/đóng phiên; triển khai chống sniping và bàn giao kết quả kết thúc phiên cho luồng settlement. | 09/11 – 10/11 |
| | | Kiểm thử cạnh tranh trả giá, ranh giới kết thúc/gia hạn; sửa lỗi backend và phối hợp kiểm thử toàn luồng. | 11/11 – 13/11 |
| **Vinh Khang** | Backend / Support UI | Chốt và triển khai giữ kho cho đấu giá; điều chỉnh thời hạn giữ hàng theo lịch phiên, gia hạn và hạn thanh toán; xử lý hủy/hoàn tồn. | 02/11 – 04/11 |
| | | Triển khai settlement: xác định kết quả phiên, chuyển thông tin người thắng sang Commerce để tạo đơn; chống tạo đơn trùng và xử lý gửi lại khi lỗi. | 05/11 – 10/11 |
| | | Tích hợp xác nhận/hoàn tồn cho đơn đấu giá; hỗ trợ email/inbox cho người thắng khi nền thông báo sẵn sàng. | 11/11 – 12/11 |
| | | Kiểm thử không có người trả giá, hủy phiên, hết hạn thanh toán và lỗi liên service; sửa lỗi tồn kho và hỗ trợ demo. | 12/11 – 13/11 |
| **Quốc Hào** | Frontend | Dựng và tích hợp danh sách/chi tiết phiên đấu giá; bổ sung trạng thái tải, rỗng và lỗi theo API thống nhất. | 02/11 – 05/11 |
| | | Tích hợp đặt giá và WebSocket; cập nhật giá cao nhất, lịch sử trả giá; xử lý lỗi cạnh tranh, kết nối lại và đồng bộ dữ liệu. | 06/11 – 09/11 |
| | | Hoàn thiện đồng hồ đếm ngược, cập nhật khi gia hạn/đóng phiên; hiển thị kết quả người thắng/người thua và điều hướng đến đơn hàng. | 10/11 – 11/11 |
| | | Kiểm thử nhiều trình duyệt, mất kết nối và phân quyền; sửa lỗi giao diện, validation và phối hợp kiểm thử toàn luồng. | 12/11 – 13/11 |
| **Linh Đang** | Frontend / BA | Rà soát User Stories/Acceptance Criteria; chốt các quy tắc còn khác nhau giữa SRS và stories; lập kịch bản E2E và thống nhất API với nhóm. | 02/11 – 03/11 |
| | | Làm giao diện Seller tạo, sửa và hủy phiên; hiển thị trạng thái và lỗi theo quyền; bổ sung thao tác Admin theo phạm vi đã thống nhất. | 04/11 – 06/11 |
| | | Tích hợp đơn hàng đấu giá: lịch sử/chi tiết, nhãn đơn đấu giá, hạn thanh toán và thông tin giao hàng; nối sang luồng thanh toán sandbox. | 09/11 – 11/11 |
| | | Kiểm thử E2E đấu giá–tạo đơn–thanh toán; tổng hợp kết quả nghiệm thu, hoàn thiện tài liệu và chuẩn bị demo. | 12/11 – 13/11 |

**Các mốc kiểm tra chung**

- **03/11:** Chốt quy tắc, API và nền đơn hàng/thanh toán cần kế thừa từ Sprint 1.
- **06/11:** Tạo phiên, giữ kho và trả giá thật giữa hai trình duyệt qua Gateway.
- **10/11:** Tự động chốt phiên và tạo một đơn hàng cho người thắng, không trùng khi gửi lại.
- **13/11:** Hoàn tất kiểm thử, sửa lỗi, demo và tổng kết sprint.

**Lưu ý phạm vi:** Đây là phân công đề xuất dựa trên rà soát source và lịch sử Git, chưa được gán lên board GitLab. Ưu tiên luồng đấu giá công khai; email/inbox phụ thuộc phần thông báo đã tích hợp. Đấu giá riêng tư, tự động trả giá, Web push và tự động trừ điểm/cấm tài khoản chưa nằm trong cam kết này. Nếu nền Sprint 1 chưa tích hợp xong vào 03/11, nhóm cần điều chỉnh phạm vi trước khi nhận thêm việc.

Xem [kế hoạch chi tiết](SPRINT2_DETAILED_PLAN.md) để đối chiếu bằng chứng source, rủi ro, phụ thuộc và tiêu chí nghiệm thu từng task.
