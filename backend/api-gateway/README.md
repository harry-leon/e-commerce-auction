# api-gateway

Spring Cloud Gateway: điểm vào duy nhất cho FE. Định tuyến `/api/v1/*` và `/ws/auctions/*` tới từng service (xem `application.yml`).

Việc cần làm tiếp: xác thực JWT tại gateway, CORS, rate limiting, `X-Trace-Id`.
Khi thêm endpoint mới cho service, kiểm tra `Path=` của route tương ứng.
