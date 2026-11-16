# KẾ HOẠCH PHÂN CHIA KIỂM THỬ CÂN BẰNG THEO LUỒNG NGƯỜI DÙNG (USER FLOW)
### DỰ ÁN NEXUS — PHỦ 100% CÁC TRANG & MÀN HÌNH HỆ THỐNG

**Nguyên tắc thiết kế lại:**
1. **Cân bằng tải:** Phân bổ đều số lượng trang và độ phức tạp (~5 đến 7 nhóm màn hình/người), không dồn toàn bộ Seller + Admin cho 1 người.
2. **Theo luồng người dùng (User Journey):** Mỗi người sở hữu trọn vẹn một hành trình từ đầu đến cuối (End-to-End) để việc kiểm thử liền mạch và phát hiện lỗi luồng dữ liệu.
3. **Phủ kín toàn bộ các trang (100% Coverage):** Tất cả các route trong [`AppRouter.tsx`](file:///D:/hcm26_cpl_java_11_group_7/frontend-web/src/routes/AppRouter.tsx) đều được phân bổ chính xác.

---

## LUỒNG 1: XÁC THỰC, TÀI KHOẢN & MUA HÀNG THƯỜNG (Auth & Direct Shopping Flow)
**Phụ trách:** **Nguyễn Tiến Thương** (Leader / Backend)  
**Mục tiêu luồng:** Kiểm chứng toàn bộ chu trình từ khi khách hàng tạo tài khoản, đăng nhập an toàn bằng mã hóa RSA, quản lý hồ sơ, chọn mua sản phẩm thường vào giỏ, checkout và thanh toán qua cổng Sandbox.

### Danh sách các trang kiểm tra (7 trang)
| Trang / Màn hình | Route / URL | Mô tả & Thao tác kiểm tra |
| :--- | :--- | :--- |
| **Login** | `/login` | Đăng nhập với tài khoản hợp lệ/không hợp lệ; kiểm tra mã hóa mật khẩu RSA gửi qua Gateway; kiểm tra lưu trữ Access Token & Refresh Token. |
| **Register** | `/register` | Đăng ký tài khoản mới; kiểm tra validation form (email, password độ dài, confirm password); kiểm tra cấp quyền mặc định `BUYER`. |
| **Forgot Password** | `/forgot-password` | Nhập email khôi phục mật khẩu, kiểm tra thông báo và luồng reset. |
| **Profile** | `/profile` | Xem và cập nhật thông tin cá nhân (họ tên, số điện thoại, địa chỉ nhận hàng); kiểm tra đồng bộ dữ liệu vào `user-service`. |
| **Cart** | `/cart` | Thêm/bớt số lượng SKU; xóa sản phẩm khỏi giỏ hàng; kiểm tra tính đúng tổng tiền và kiểm tra SKU hết hàng. |
| **Checkout** | `/checkout` | Chọn địa chỉ giao hàng, phương thức vận chuyển; kiểm tra tóm tắt đơn và tạo đơn hàng mua thường (`PENDING_PAYMENT`). |
| **Sandbox Payment & Result** | `/sandbox-payment`, `/payment-result` | Chuyển hướng sang cổng Sandbox, chọn ngân hàng/ví (Momo/VNPay); mô phỏng Thành công/Thất bại; kiểm tra callback webhook cập nhật đơn thành `PAID` và chống callback lặp (idempotency). |

---

## LUỒNG 2: KHÁM PHÁ SÀN & SÀN ĐẤU GIÁ TRỰC TIẾP (Discovery & Live Bidding Flow)
**Phụ trách:** **Trần Quốc Hào** (Frontend)  
**Mục tiêu luồng:** Đóng vai trò Người mua hàng (Buyer) khám phá hệ sinh thái, tìm kiếm sản phẩm và tham gia phòng đấu giá thời gian thực với trải nghiệm STOMP WebSocket, đồng hồ đếm ngược và cạnh tranh giá thầu.

### Danh sách các trang kiểm tra (6 trang)
| Trang / Màn hình | Route / URL | Mô tả & Thao tác kiểm tra |
| :--- | :--- | :--- |
| **Home Page** | `/home` | Hiển thị Banner Hero, danh mục sản phẩm nổi bật, các phiên đấu giá đang hot; kiểm tra responsive và điều hướng các banner. |
| **Search Page** | `/search` | Tìm kiếm theo từ khóa; lọc theo danh mục, khoảng giá, thương hiệu; kiểm tra phân trang và trạng thái rỗng khi không có kết quả. |
| **Product Detail** | `/product-detail` | Xem ảnh, thông số kỹ thuật, mô tả SKU; so sánh giá bán thẳng (Dynamic Pricing) và nút "Xem phiên đấu giá liên quan". |
| **Auction List** | `/auction-list` | Danh sách toàn bộ các phiên đấu giá; bộ lọc trạng thái: **Đang diễn ra (Active)**, **Sắp diễn ra (Upcoming)**, **Đã kết thúc (Ended)**. |
| **Auction Detail** | `/auction-detail` | **Phòng đấu giá trực tiếp:** Kết nối STOMP WebSocket `/ws-auction`; test 2 trình duyệt đặt giá đồng thời; kiểm tra nhảy giá tức thì không cần F5; kiểm tra đồng hồ đếm ngược từng giây; test gia hạn phiên (Anti-sniping) khi có bid ở 30s cuối. |
| **My Bids** | `/my-bids` | Quản lý danh sách các phiên người dùng đã đặt giá; hiển thị huy hiệu "Đang dẫn đầu" (Winning) hoặc "Bị vượt qua" (Outbid). |

---

## LUỒNG 3: KẾT THÚC ĐẤU GIÁ, THANH TOÁN TRÚNG THẦU & ĐÁNH GIÁ (Settlement, Winner Checkout & Reputation Flow)
**Phụ trách:** **Hà Thúc Quốc Hùng** (Backend)  
**Mục tiêu luồng:** Kiểm chứng luồng tự động chốt phiên đấu giá khi hết giờ, bàn giao settlement sang Commerce tạo đơn cho người thắng, người thắng nhận thông báo và hoàn tất thanh toán hạn định, xem lịch sử đơn và đánh giá uy tín người bán.

### Danh sách các trang kiểm tra (6 trang)
| Trang / Màn hình | Route / URL | Mô tả & Thao tác kiểm tra |
| :--- | :--- | :--- |
| **Notifications** | `/notifications` | Nhận thông báo realtime: thông báo bị trả giá cao hơn (Outbid alert), thông báo chúc mừng trúng đấu giá kèm nút điều hướng thanh toán. |
| **Auction Payment** | `/auction-payment` | Màn hình dành riêng cho Winner: hiển thị thông tin sản phẩm trúng thầu, giá trúng, đồng hồ đếm ngược hạn chót thanh toán (Payment Deadline); nút chuyển sang Sandbox Payment. |
| **Order History** | `/order-history` | Danh sách lịch sử đơn hàng của người mua (phân biệt rõ nhãn đơn mua thường và đơn trúng đấu giá); bộ lọc theo trạng thái đơn. |
| **Order Detail** | `/order-detail` | Chi tiết đơn hàng trúng đấu giá: mã đơn, tiến trình thanh toán, địa chỉ nhận hàng, trạng thái xử lý kho và vận chuyển. |
| **Seller Shop & Public Reputation** | `/seller-shop`, `/public-reputation`, `/my-reputation` | Xem trang của gian hàng bán, điểm tín nhiệm (Trust Score), tỷ lệ đánh giá sao và danh sách bình luận/review sau khi giao dịch hoàn tất. |
| **Auction Scheduler Test** | *Swagger / Background* | Kiểm tra tự động chuyển trạng thái phiên từ `ACTIVE` $\rightarrow$ `FINISHED` khi hết giờ; kích hoạt tạo Order `PENDING_PAYMENT` cho winner. |

---

## LUỒNG 4: KÊNH NGƯỜI BÁN TOÀN DIỆN (Seller Portal & Shop Management Flow)
**Phụ trách:** **Trần Ngọc Linh Đang** (Frontend / BA)  
**Mục tiêu luồng:** Đóng vai trò Chủ shop (Seller), thực hiện trọn vẹn nghiệp vụ kinh doanh: từ quản lý hàng hóa, đăng bán, khởi tạo và điều chỉnh phiên đấu giá, theo dõi đơn bán và kiểm tra ví/doanh thu.

### Danh sách các trang kiểm tra (9 trang)
| Trang / Màn hình | Route / URL | Mô tả & Thao tác kiểm tra |
| :--- | :--- | :--- |
| **Seller Dashboard** | `/seller-dashboard` | Bảng điều khiển kinh doanh: tổng doanh thu, số phiên đang chạy, số đơn hàng cần giao, biểu đồ tăng trưởng. |
| **Seller Products** | `/seller-products` | Danh sách sản phẩm của người bán; hiển thị trạng thái tồn kho, duyệt và giá niêm yết. |
| **Create/Edit Product** | `/seller-create-product`, `/seller-edit-product` | Form tạo mới hoặc chỉnh sửa sản phẩm: nhập tên, chọn danh mục, mô tả, SKU, tải ảnh, cài đặt giá. |
| **Seller Auctions** | `/seller-auctions` | Danh sách các phiên đấu giá do chính shop tạo; lọc theo trạng thái Draft, Scheduled, Active, Ended. |
| **Create Auction** | `/seller-create-auction` | Form tạo phiên đấu giá: chọn SKU có sẵn trong kho, đặt giá khởi điểm, bước giá, giá mua ngay/giá dự trữ, ngày bắt đầu và kết thúc; kiểm tra validation chặn ngày kết thúc $\le$ ngày bắt đầu. |
| **Seller Orders** | `/seller-orders` | Quản lý danh sách đơn hàng khách đặt của shop; phân biệt đơn mua thường và đơn đấu giá; xem trạng thái thanh toán của khách. |
| **Seller Balance** | `/seller-balance` | Quản lý số dư tài khoản người bán, lịch sử đối soát và rút tiền doanh thu bán hàng. |
| **Seller Reputation** | `/seller-reputation` | Quản lý đánh giá và phản hồi của khách hàng đối với shop; lịch sử điểm phạt (nếu có). |
| **Seller Notifications & Profile** | `/seller-notifications`, `/seller-profile` | Thông báo đơn hàng mới, thông báo phiên đấu giá thành công và cập nhật thông tin cửa hàng. |

---

## LUỒNG 5: QUẢN TRỊ VIÊN HỆ THỐNG, VẬN HÀNH KHO & CHĂM SÓC KHÁCH HÀNG (Admin, Support & Warehouse Flow)
**Phụ trách:** **Lý Vĩnh Khang** (Backend / Support UI)  
**Mục tiêu luồng:** Đóng vai trò Quản trị viên (Admin) và Nhân viên Vận hành/Hỗ trợ (Support & Warehouse): kiểm soát tồn kho khả dụng vs tồn kho bị giữ đấu giá, tra cứu hỗ trợ khách hàng, giám sát phiên đấu giá toàn sàn, quản lý người dùng, danh mục và giám sát hệ thống.

### Danh sách các trang kiểm tra (11 trang)
| Phân hệ | Trang / Màn hình | Route / URL | Mô tả & Thao tác kiểm tra |
| :--- | :--- | :--- | :--- |
| **Warehouse & Stock** | **Seller Inventory** | `/seller-inventory` | Kiểm tra tồn kho theo SKU: kiểm tra trừ 1 khả dụng và chuyển sang `RESERVED_AUCTION` khi phiên mở; kiểm tra hoàn kho khi hủy phiên. |
| | **Admin Warehouses** | `/admin-warehouses` | Quản lý danh sách kho bãi, số lượng hàng lưu kho từng vị trí. |
| | **Admin Shipments** | `/admin-shipments` | Quản lý danh sách các vận đơn giao hàng, mã vận đơn và trạng thái vận chuyển. |
| **Support Portal** | **Support Dashboard** | `/support-dashboard` | Bảng điều khiển CSKH, tiếp nhận các ca khiếu nại. |
| | **Support Order Lookup** | `/support-order-lookup` | Tra cứu đơn hàng theo ID, xem chi tiết lịch sử thay đổi trạng thái và mã thanh toán. |
| | **Support User Lookup** | `/support-user-lookup` | Tra cứu người dùng theo email/SĐT, kiểm tra trạng thái kích hoạt tài khoản. |
| **Admin Control** | **Admin Dashboard** | `/admin-dashboard` | Chỉ số vận hành toàn sàn: tổng user, tổng GMV, số phiên đang chạy, sức khỏe hệ thống. |
| | **Admin Auctions** | `/admin-auctions` | Giám sát toàn bộ phiên đấu giá trên hệ thống; can thiệp hủy phiên vi phạm kèm ghi nhận lý do. |
| | **Admin Users & Roles** | `/admin-users`, `/admin-user-detail`, `/admin-roles` | Quản trị tài khoản toàn sàn, gán vai trò (`BUYER`, `SELLER`, `ADMIN`, `SUPPORT`), khóa tài khoản vi phạm. |
| | **Admin Categories & Products** | `/admin-categories`, `/admin-products` | Quản lý cây danh mục sản phẩm sàn (thêm/sửa danh mục), duyệt hoặc ẩn sản phẩm vi phạm. |
| | **System Health & Logs** | `/admin-system-health`, `/admin-system-config`, `/admin-audit-logs`, `/admin-event-logs` | Kiểm tra trạng thái kết nối các microservices, cấu hình tham số hệ thống và tra cứu lịch sử audit/event log. |

---

## BẢNG TỔNG HỢP CÂN BẰNG KHỐI LƯỢNG (BALANCED MATRIX)

| Thành viên | Luồng người dùng đảm nhiệm | Số trang | Phạm vi kiểm tra trọng tâm |
| :--- | :--- | :---: | :--- |
| **1. Tiến Thương** | **Auth, Tài khoản & Mua sắm Checkout** | **7 trang** | Đăng ký, đăng nhập RSA, Profile, Giỏ hàng, Checkout, Sandbox Payment, Callback Webhook. |
| **2. Quốc Hào** | **Khám phá sàn & Đấu giá Realtime** | **6 trang** | Home, Tìm kiếm sản phẩm, Chi tiết sản phẩm, Sàn đấu giá, Bidding WebSocket trực tiếp, My Bids. |
| **3. Quốc Hùng** | **Chốt phiên, Thanh toán Winner & Uy tín** | **6 trang** | Scheduler chốt phiên, Notification, Màn hình thanh toán Winner, Lịch sử đơn, Trang uy tín Shop. |
| **4. Linh Đang** | **Kênh Người Bán toàn diện (Seller)** | **9 trang** | Dashboard Shop, Quản lý & Tạo sản phẩm, Tạo & Quản lý phiên đấu giá, Đơn bán, Ví doanh thu. |
| **5. Vinh Khang** | **Quản trị Admin, Vận hành Kho & Support** | **11 trang** | Tồn kho giữ đấu giá, Quản lý kho & vận đơn, Tra cứu CSKH, Quản trị phiên/user/danh mục toàn sàn, Audit Logs. |

---
*Kế hoạch này đảm bảo 100% trang trong router đều có người chịu trách nhiệm, phân công theo chuỗi hành trình người dùng liền mạch và chia đều khối lượng công việc cho cả 5 thành viên.*
