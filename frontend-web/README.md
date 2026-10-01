# frontend-web

Web app React cho **Project Nexus** (Buyer, Seller, Admin, Support Staff). Gọi backend qua Spring Cloud Gateway.

## Stack
React 18 · TypeScript · Vite · React Router · TanStack Query · Axios · WebSocket (đấu giá realtime) · Vitest · ESLint · Docker (nginx)

## Bắt đầu

```bash
npm install            # lần đầu: sinh package-lock.json rồi COMMIT file này (CI dùng `npm ci`)
cp .env.example .env.local
npm run dev            # http://localhost:5173, proxy /api và /ws tới Gateway (mặc định localhost:8080)
```

| Lệnh | Tác dụng |
|---|---|
| `npm run lint` | ESLint |
| `npm run typecheck` | Kiểm tra TypeScript |
| `npm test` | Chạy test một lần |
| `npm run coverage` | Test + coverage (ngưỡng 70%) |
| `npm run build` | Build production vào `dist/` |
| `docker build -t nexus-web .` | Build image nginx |

## Cấu trúc

```
src/
├── api/        axios client (gắn JWT, chuẩn hóa lỗi ApiError)
├── auth/       AuthContext, token (in-memory), RequirePrivilege
├── features/   catalog, cart, checkout, orders, auction, profile
├── pages/      các trang cấp route
├── shared/     hook / component dùng chung (vd useAuctionSocket)
└── test/       setup test
```

Mỗi feature tương ứng một nhóm chức năng trong SRS (mục 3.3 Catalog, 3.4 Commerce, 3.5 Auction...).

## Ghi chú
- Phân quyền UI dựa trên `privileges` (SRS 2.7) qua `useAuth().can("AUCTION.BID")`. Đây chỉ là UX, quyền thật do backend kiểm tra.
- Đấu giá realtime dùng `useAuctionSocket(auctionId, handler)`; endpoint WebSocket phụ thuộc auction-service.
- Quy ước Git: xem `CONTRIBUTING.md` ở thư mục gốc repo. Chạy cả hệ thống: `docker compose up --build` ở thư mục gốc.
