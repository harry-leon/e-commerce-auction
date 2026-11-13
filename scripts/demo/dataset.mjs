import { createHash } from "node:crypto";
import assert from "node:assert/strict";

/** Bộ fixture nghiệp vụ thuần dữ liệu: không gọi mạng/DB, không chứa credential hay thông tin cá nhân thật. */
export const DATASET = "nexus-demo-v1";
export const SERVICES = [
    "user",
    "catalog",
    "fulfillment",
    "commerce",
    "auction",
    "reputation",
    "notification",
];
export const VERSIONS = {
    user: 6,
    catalog: 7,
    // V7/V8 chỉ bổ sung carrier/tracking và mở rộng trạng thái; fixture cũ dùng được với default mới.
    fulfillment: 8,
    // V13 thêm bảng refund, không seed refund vì bộ demo không chứng minh tiền thật đã được hoàn.
    commerce: 13,
    auction: 3,
    reputation: 3,
    notification: 5,
};
export const DEFAULT_WAREHOUSE = "a0000000-0000-0000-0000-000000000001";

/** UUID ổn định theo dataset và khóa nghiệp vụ để chạy lại không tạo dòng trùng; không dùng UUID làm bằng chứng sở hữu. */
export function uid(key) {
    const bytes = createHash("sha256")
        .update(`${DATASET}:${key}`)
        .digest()
        .subarray(0, 16);
    bytes[6] = (bytes[6] & 15) | 80;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = bytes.toString("hex");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Danh tính giả dùng domain .example, CCCD giả và số điện thoại chỉ để qua validator; không liên hệ/xác minh thuê bao. */
export function accountDefinitions() {
    return [
        ["admin", "Quản trị Demo Nexus", "ADMIN"],
        ["support", "Hỗ trợ Demo Nexus", "SUPPORT"],
        ["seller-minhan", "Cửa hàng Minh An", "SELLER"],
        ["seller-thanhlam", "Cửa hàng Thanh Lam", "SELLER"],
        ["seller-annhien", "Cửa hàng An Nhiên", "SELLER"],
        ["buyer-mai", "Nguyễn Mai Demo", "BUYER"],
        ["buyer-nam", "Trần Nam Demo", "BUYER"],
        ["buyer-linh", "Lê Linh Demo", "BUYER"],
    ].map(([key, name, role], index) => ({
        key,
        name,
        role,
        email: `demo.${key}@nexus.example`,
        phone: `09000001${String(index + 1).padStart(2, "0")}`,
        identity: `0000000001${String(index + 1).padStart(2, "0")}`,
    }));
}

/** Ảnh SVG minh họa tự chứa, không phụ thuộc CDN và luôn nằm trong giới hạn URL 500 ký tự của schema. */
export const CLOUDINARY_IMAGES = [
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542324/Nexus/electronics/tai-nghe-khong-day-n1/tai-nghe-khong-day-n1-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542327/Nexus/electronics/ban-phim-co-k87/ban-phim-co-k87-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542330/Nexus/electronics/chuot-khong-day-m2/chuot-khong-day-m2-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542345/Nexus/fashion/ao-thun-cotton-thuong-ngay/ao-thun-cotton-thuong-ngay-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542347/Nexus/fashion/ao-khoac-gio-nhe/ao-khoac-gio-nhe-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542350/Nexus/fashion/ba-lo-di-hoc-20l/ba-lo-di-hoc-20l-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542353/Nexus/home-living/den-ban-hoc-led/den-ban-hoc-led-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542356/Nexus/home-living/binh-giu-nhiet-500ml/binh-giu-nhiet-500ml-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542358/Nexus/home-living/bo-hop-thuc-pham-3-mon/bo-hop-thuc-pham-3-mon-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542361/Nexus/sports/tham-tap-yoga-6mm/tham-tap-yoga-6mm-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542364/Nexus/sports/day-khang-luc-tap-luyen/day-khang-luc-tap-luyen-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542366/Nexus/sports/tui-the-thao-cuoi-tuan/tui-the-thao-cuoi-tuan-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542373/Nexus/stationery/so-tay-giay-cham-a5/so-tay-giay-cham-a5-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542375/Nexus/stationery/bo-but-viet-5-mau/bo-but-viet-5-mau-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542379/Nexus/stationery/gia-do-sach-de-ban/gia-do-sach-de-ban-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542384/Nexus/collectibles/mo-hinh-go-lap-ghep/mo-hinh-go-lap-ghep-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542386/Nexus/collectibles/tranh-in-nghe-thuat-a4/tranh-in-nghe-thuat-a4-main.jpg",
    "https://res.cloudinary.com/f4wgawlg/image/upload/v1794542389/Nexus/collectibles/bo-thiep-thu-cong-6-mau/bo-thiep-thu-cong-6-mau-main.jpg"
];

function image(index) {
    return CLOUDINARY_IMAGES[index] ?? CLOUDINARY_IMAGES[0];
}

/**
 * Tạo fixture liên service từ mốc UTC cố định và UUID tài khoản IAM thực đã tra cứu.
 * Dữ liệu lịch sử được đánh dấu DEMO_ONLY, không phát IPN, email, outbox hoặc giao dịch tài chính thật.
 * Mốc thời gian được lưu trong manifest; chạy lại dùng cùng mốc, không tự sửa dữ liệu đã demo.
 */
export function buildDataset({
    asOf,
    userAsOf = asOf,
    identities = {},
    warehouseId = DEFAULT_WAREHOUSE,
}) {
    const epoch = Date.parse(asOf);
    assert(Number.isFinite(epoch), "Mốc thời gian demo không hợp lệ");
    // Reset làm mới thời gian nghiệp vụ nhưng giữ timestamp hồ sơ User đã tồn tại để seed không ghi đè tài khoản.
    const userTimestamp = new Date(
        Date.parse(userAsOf) - 24 * 40 * 3600000,
    ).toISOString();
    const at = (hours) => new Date(epoch + hours * 3600000).toISOString();
    const services = Object.fromEntries(
        SERVICES.map((service) => [service, {}]),
    );
    const add = (service, table, row) =>
        (services[service][table] ??= []).push(row);
    const accounts = accountDefinitions().map((a) => ({
        ...a,
        id: identities[a.key] || uid(`account:${a.key}`),
    }));
    for (const a of accounts)
        add("user", "users", {
            id: a.id,
            keycloak_id: a.id,
            email: a.email,
            full_name: a.name,
            phone: a.phone,
            identify_number: a.identity,
            gender: "OTHER",
            date_of_birth: "1995-05-15",
            address: "Địa chỉ minh họa Nexus Demo, TP. Hồ Chí Minh",
            status: "ACTIVE",
            is_deleted: false,
            created_at: userTimestamp,
            updated_at: userTimestamp,
        });
    const sellers = accounts.filter((a) => a.role === "SELLER");
    const buyers = accounts.filter((a) => a.role === "BUYER");
    const categories = [
        ["Công nghệ & Phụ kiện", "Thiết bị cá nhân"],
        ["Thời trang & Phụ kiện", "Trang phục thường ngày"],
        ["Nhà cửa & Đời sống", "Đồ dùng gia đình"],
        ["Thể thao & Dã ngoại", "Dụng cụ vận động"],
        ["Sách & Văn phòng phẩm", "Góc học tập"],
        ["Đồ sưu tầm & Thủ công", "Quà tặng sáng tạo"],
    ];
    categories.forEach(([name, child], i) => {
        add("catalog", "categories", {
            id: uid(`category:${i}`),
            parent_id: null,
            name,
            level: 1,
            status: "ACTIVE",
        });
        add("catalog", "categories", {
            id: uid(`subcategory:${i}`),
            parent_id: uid(`category:${i}`),
            name: child,
            level: 2,
            status: "ACTIVE",
        });
    });
    const definitions = [
        ["Tai nghe không dây N1", 1490000],
        ["Bàn phím cơ K87", 890000],
        ["Chuột không dây M2", 349000],
        ["Áo thun cotton thường ngày", 249000],
        ["Áo khoác gió nhẹ", 590000],
        ["Ba lô đi học 20L", 450000],
        ["Đèn bàn học LED", 390000],
        ["Bình giữ nhiệt 500ml", 290000],
        ["Bộ hộp thực phẩm 3 món", 199000],
        ["Thảm tập yoga 6mm", 320000],
        ["Dây kháng lực tập luyện", 180000],
        ["Túi thể thao cuối tuần", 399000],
        ["Sổ tay giấy chấm A5", 89000],
        ["Bộ bút viết 5 màu", 69000],
        ["Giá đỡ sách để bàn", 149000],
        ["Mô hình gỗ lắp ghép", 490000],
        ["Tranh in nghệ thuật A4", 250000],
        ["Bộ thiệp thủ công 6 mẫu", 120000],
    ];
    definitions.forEach(([name, price], i) => {
        const product = {
            id: uid(`product:${i}`),
            seller_id: sellers[i % 3].id,
            category_id: uid(`subcategory:${Math.floor(i / 3)}`),
            name,
            description: `${name}. Sản phẩm minh họa thuộc bộ Nexus Demo; giá và thông số là dữ liệu giả lập phục vụ kiểm thử, không phải báo giá bán lẻ thực tế.`,
            status: i === 17 ? "DRAFT" : "ACTIVE",
            auction_enabled: i % 3 === 0,
            created_at: at(-24 * 35 + i),
        };
        add("catalog", "products", product);
        for (let v = 0; v < 2; v++)
            add("catalog", "skus", {
                id: uid(`sku:${i}:${v}`),
                product_id: product.id,
                sku_code: `NXDEMO-${String(i + 1).padStart(3, "0")}-${v + 1}`,
                attributes: { phienBan: v ? "Mở rộng" : "Tiêu chuẩn" },
                price: price + v * 20000,
                original_price: price + v * 20000 + 50000,
                status: "ACTIVE",
            });
        add("catalog", "product_images", {
            id: uid(`image:${i}`),
            product_id: product.id,
            url: image(i),
            sort_order: 0,
        });
    });
    for (const [i, code, name] of [
        [1, "NXDEMO_HN", "Kho demo Hà Nội"],
        [2, "NXDEMO_DN", "Kho demo Đà Nẵng"],
    ]) {
        add("fulfillment", "warehouses", {
            id: uid(`warehouse:${i}`),
            code,
            name,
            address: "Địa chỉ kho minh họa Nexus Demo",
            type: "STANDARD",
            is_active: true,
        });
    }
    add("fulfillment", "carriers", {
        id: uid("carrier"),
        code: "NXDEMO_DELIVERY",
        name: "Đơn vị vận chuyển minh họa Nexus",
        is_active: true,
    });
    const main = new Map();
    const ledger = (
        inventory,
        key,
        movement,
        quantity,
        refType,
        refId,
        when,
    ) => {
        add("fulfillment", "inventory_ledger", {
            id: uid(`ledger:${key}`),
            inventory_id: inventory.id,
            movement_type: movement,
            quantity,
            ref_type: refType,
            ref_id: refId,
            created_at: when,
        });
    };
    services.catalog.skus.forEach((sku, i) => {
        const total = i === 33 ? 0 : 80 + i;
        const inv = {
            id: uid(`inventory:${i}:main`),
            sku_id: sku.id,
            warehouse_id: warehouseId,
            total_qty: total,
            reserved_qty: 0,
            unavailable_qty: 0,
            version: 1,
        };
        add("fulfillment", "inventories", inv);
        main.set(sku.id, inv);
        if (total)
            ledger(
                inv,
                `initial:${i}`,
                "INBOUND",
                total,
                "INBOUND",
                uid("initial-stock"),
                at(-24 * 30),
            );
        if (i < 6) {
            const secondary = {
                ...inv,
                id: uid(`inventory:${i}:secondary`),
                warehouse_id: uid(`warehouse:${1 + (i % 2)}`),
                total_qty: 20,
            };
            add("fulfillment", "inventories", secondary);
            ledger(
                secondary,
                `secondary:${i}`,
                "INBOUND",
                20,
                "INBOUND",
                uid("initial-stock"),
                at(-24 * 30),
            );
        }
    });
    // Đơn lịch sử mô phỏng không ghi tác vụ worker: stock/ledger/payment/history được sinh nhất quán trong cùng bộ fixture.
    for (let i = 0; i < 9; i++) {
        const buyer = buyers[Math.floor(i / 3)];
        const seller = sellers[i % 3];
        const finalStatus = ["COMPLETED", "DELIVERED", "CANCELLED"][i % 3];
        const base = -24 * 20 + i * 24;
        const items = services.catalog.products
            .filter((p) => p.seller_id === seller.id && p.status === "ACTIVE")
            .slice(0, 2)
            .map((p, j) => ({
                id: uid(`item:${i}:${j}`),
                order_id: uid(`order:${i}`),
                sku_id: uid(`sku:${services.catalog.products.indexOf(p)}:0`),
                product_name: p.name,
                sku_code: services.catalog.skus.find(
                    (s) => s.product_id === p.id,
                ).sku_code,
                unit_price: services.catalog.skus.find(
                    (s) => s.product_id === p.id,
                ).price,
                quantity: j + 1,
            }));
        const subtotal = items.reduce(
            (sum, item) => sum + item.unit_price * item.quantity,
            0,
        );
        const order = {
            id: uid(`order:${i}`),
            order_no: `NXDEMO-ORDER-${String(i + 1).padStart(3, "0")}`,
            buyer_id: buyer.id,
            seller_id: seller.id,
            source_type: "REGULAR",
            auction_id: null,
            voucher_id: null,
            status: finalStatus,
            subtotal,
            shipping_fee: 30000,
            discount: 0,
            total: subtotal + 30000,
            shipping_address:
                buyer.name +
                ", địa chỉ giao hàng minh họa Nexus Demo, TP. Hồ Chí Minh",
            idempotency_key: uid(`checkout:${i}`),
            request_hash: createHash("sha256")
                .update(`DEMO_ONLY:${i}`)
                .digest("hex"),
            created_at: at(base),
            recipient_name: buyer.name,
            recipient_phone: buyer.phone,
            payment_method: "VNPAY",
            buyer_email: buyer.email,
        };
        add("commerce", "orders", order);
        items.forEach((item) => add("commerce", "order_items", item));
        const cancelled = finalStatus === "CANCELLED";
        add("commerce", "payments", {
            id: uid(`payment:${i}`),
            order_id: order.id,
            provider: "VNPAY",
            provider_ref: `NXDEMO-NOT-A-REAL-TRANSACTION-${i + 1}`,
            method: "VNPAY",
            amount: order.total,
            status: cancelled ? "FAILED" : "SUCCESS",
            retry_count: 0,
            idempotency_key: uid(`payment-key:${i}`),
            created_at: at(base),
            payment_url: null,
            merchant_code: "DEMO_ONLY",
            expires_at: at(base + 0.25),
            response_code: cancelled ? "24" : "00",
            transaction_status: cancelled ? "02" : "00",
            completed_at: at(base + 0.05),
        });
        const chain = cancelled
            ? ["PENDING_PAYMENT", "CANCELLED"]
            : [
                  "PENDING_PAYMENT",
                  "PAID",
                  "PROCESSING",
                  "SHIPPED",
                  "DELIVERED",
                  ...(finalStatus === "COMPLETED" ? ["COMPLETED"] : []),
              ];
        const steps = [0, 0.05, 1, 12, 36, 60];
        chain.forEach((status, j) =>
            add("commerce", "order_status_history", {
                id: uid(`history:${i}:${j}`),
                order_id: order.id,
                from_status: j ? chain[j - 1] : null,
                to_status: status,
                reason: "[DEMO FIXTURE] Lịch sử minh họa, không có giao dịch thanh toán thực",
                changed_at: at(base + steps[j]),
            }),
        );
        for (const [j, item] of items.entries()) {
            const inv = main.get(item.sku_id);
            const reservation = {
                id: uid(`reservation:order:${i}:${j}`),
                inventory_id: inv.id,
                quantity: item.quantity,
                ref_type: "ORDER",
                ref_id: order.id,
                status: cancelled ? "RELEASED" : "CONFIRMED",
                expires_at: at(base + 0.25),
            };
            add("fulfillment", "inventory_reservations", reservation);
            add("commerce", "order_reservations", {
                order_id: order.id,
                reservation_id: reservation.id,
            });
            ledger(
                inv,
                `reserve:order:${i}:${j}`,
                "RESERVE",
                item.quantity,
                "ORDER",
                order.id,
                at(base + 0.001),
            );
            ledger(
                inv,
                `finish:order:${i}:${j}`,
                cancelled ? "RELEASE" : "OUTBOUND",
                -item.quantity,
                "ORDER",
                order.id,
                at(base + 0.05),
            );
            if (!cancelled) inv.total_qty -= item.quantity;
        }
        if (!cancelled) {
            const shipment = {
                id: uid(`shipment:${i}`),
                order_id: order.id,
                warehouse_id: warehouseId,
                carrier_id: uid("carrier"),
                service_type: "DEMO_STANDARD",
                shipping_fee: order.shipping_fee,
                tracking_no: `NXDEMO-TRACK-${i + 1}`,
                status: "DELIVERED",
                created_at: at(base + 1),
            };
            add("fulfillment", "shipments", shipment);
            for (const [j, status] of [
                "PENDING",
                "PICKING",
                "PACKED",
                "SHIPPED",
                "IN_TRANSIT",
                "DELIVERED",
            ].entries()) {
                add("fulfillment", "shipment_status_history", {
                    id: uid(`shipment-history:${i}:${j}`),
                    shipment_id: shipment.id,
                    carrier_status: `DEMO_${status}`,
                    internal_status: status,
                    occurred_at: at(base + [1, 2, 3, 12, 24, 36][j]),
                });
            }
        }
        if (finalStatus === "COMPLETED") {
            const item = items[0];
            const product = services.catalog.skus.find(
                (s) => s.id === item.sku_id,
            ).product_id;
            add("catalog", "product_reviews", {
                id: uid(`review:${i}`),
                product_id: product,
                sku_id: item.sku_id,
                user_id: buyer.id,
                order_id: order.id,
                score: 5,
                comment:
                    "Đánh giá minh họa cho đơn demo đã hoàn tất; không phải nhận xét của khách hàng thật.",
                media_urls: [],
                seller_reply: null,
                seller_replied_at: null,
                is_hidden: false,
                created_at: at(base + 61),
            });
            for (const [j, rater, ratee] of [
                [0, buyer, seller],
                [1, seller, buyer],
            ])
                add("reputation", "ratings", {
                    id: uid(`rating:${i}:${j}`),
                    rater_id: rater.id,
                    ratee_id: ratee.id,
                    order_id: order.id,
                    score: 5,
                    comment: "Đánh giá giao dịch giả lập Nexus Demo.",
                    is_aggregated: true,
                    is_disputed: false,
                    created_at: at(base + 61),
                });
        }
        for (const [j, type, title] of [
            [0, "ORDER_CREATED", "Đơn hàng demo đã được ghi nhận"],
            [
                1,
                cancelled ? "PAYMENT_FAILED" : "PAYMENT_SUCCESS",
                cancelled
                    ? "Thanh toán demo bị hủy"
                    : "Thanh toán demo thành công",
            ],
        ]) {
            const notification = {
                id: uid(`notification:${i}:${j}`),
                user_id: buyer.id,
                event_id: uid(`notification-event:${i}:${j}`),
                channel: "IN_APP",
                title,
                content: `${order.order_no}: ${order.total.toLocaleString("vi-VN")} VND. Lịch sử giả lập, không phát sinh tiền thật.`,
                status: j ? "SENT" : "READ",
                sent_at: at(base + 0.06 * j),
                created_at: at(base + 0.06 * j),
                order_id: order.id,
                event_type: type,
            };
            add("notification", "notifications", notification);
            add("notification", "notification_attempts", {
                id: uid(`notification-attempt:${i}:${j}`),
                notification_id: notification.id,
                attempt_no: 1,
                result: "SUCCESS",
                error: null,
                attempted_at: notification.sent_at,
            });
        }
    }
    // Giỏ hiện tại khác lịch sử: chưa giữ kho, mỗi giỏ chỉ chứa hàng cùng một seller để checkout hợp lệ.
    buyers.forEach((buyer, i) => {
        const cart = {
            id: uid(`cart:${i}`),
            user_id: buyer.id,
            status: "ACTIVE",
            expires_at: at(24 * 14),
        };
        add("commerce", "carts", cart);
        services.catalog.products
            .filter(
                (p) => p.seller_id === sellers[i].id && p.status === "ACTIVE",
            )
            .slice(2, 4)
            .forEach((p, j) => {
                const sku = services.catalog.skus.find(
                    (s) => s.product_id === p.id,
                );
                add("commerce", "cart_items", {
                    id: uid(`cart-item:${i}:${j}`),
                    cart_id: cart.id,
                    sku_id: sku.id,
                    quantity: 1,
                    unit_price: sku.price,
                });
            });
    });
    for (let i = 0; i < 6; i++) {
        const product = services.catalog.products[i * 3];
        const sku = services.catalog.skus.find(
            (s) => s.product_id === product.id,
        );
        const status = [
            "ACTIVE",
            "ACTIVE",
            "SCHEDULED",
            "SCHEDULED",
            "SETTLED",
            "ENDED",
        ][i];
        const start = status === "SCHEDULED" ? 24 : i >= 4 ? -4 : -1;
        const end = status === "SCHEDULED" ? 72 : i >= 4 ? -2 : 8 + i;
        const auction = {
            id: uid(`auction:${i}`),
            product_id: product.id,
            sku_id: sku.id,
            seller_id: product.seller_id,
            start_price: sku.price,
            bid_increment: 10000,
            current_price: sku.price,
            highest_bidder_id: null,
            visibility: "PUBLIC",
            status,
            start_time: at(start),
            end_time: at(end),
            extension_count: 0,
            version: 1,
        };
        const count = i === 0 ? 3 : i === 1 ? 2 : i === 4 ? 3 : 0;
        for (let j = 0; j < count; j++) {
            const bid = {
                id: uid(`bid:${i}:${j}`),
                auction_id: auction.id,
                bidder_id: buyers[j % 3].id,
                amount: sku.price + (j + 1) * 10000,
                idempotency_key: uid(`bid-key:${i}:${j}`),
                placed_at: at(start + (j + 1) / 10),
            };
            add("auction", "bids", bid);
            auction.current_price = bid.amount;
            auction.highest_bidder_id = bid.bidder_id;
        }
        add("auction", "auctions", auction);
        if (i >= 4)
            add("auction", "auction_settlements", {
                id: uid(`settlement:${i}`),
                auction_id: auction.id,
                winner_id: auction.highest_bidder_id,
                final_price: count ? auction.current_price : null,
                result: count ? "SUCCESS" : "FAILED",
                order_id: null,
                payment_deadline: count ? at(end + 0.01 + 24) : null,
                settled_at: at(end + 0.01),
            });
        if (status === "ACTIVE" || status === "SETTLED") {
            const inv = main.get(sku.id);
            inv.reserved_qty++;
            add("fulfillment", "inventory_reservations", {
                id: uid(`reservation:auction:${i}`),
                inventory_id: inv.id,
                quantity: 1,
                ref_type: "AUCTION",
                ref_id: auction.id,
                status: "PENDING",
                expires_at: at(i === 4 ? end + 0.01 + 24 : end + 0.25),
            });
            ledger(
                inv,
                `reserve:auction:${i}`,
                "RESERVE",
                1,
                "AUCTION",
                auction.id,
                at(start),
            );
        }
    }
    for (const a of accounts) {
        const orders = services.commerce.orders.filter(
            (o) => o.buyer_id === a.id || o.seller_id === a.id,
        );
        add("reputation", "reputations", {
            user_id: a.id,
            score: 100,
            trust_level: "STANDARD",
            total_tx: orders.length,
            success_tx: orders.filter((o) => o.status === "COMPLETED").length,
            failed_tx: orders.filter((o) => o.status === "CANCELLED").length,
            auction_won: services.auction.auction_settlements.filter(
                (s) => s.winner_id === a.id,
            ).length,
            auction_failed: 0,
        });
    }
    // Đồng bộ Catalog V7/Fulfillment V6: mã SKU thuộc seller_skus; tồn kho tham chiếu master này.
    for (const category of services.catalog.categories)
        category.name_vi = category.name;
    services.fulfillment.seller_skus = services.catalog.skus.map((sku) => ({
        id: uid(`seller-sku:${sku.id}`),
        sku_id: sku.id,
        seller_id: services.catalog.products.find(
            (p) => p.id === sku.product_id,
        ).seller_id,
        sku_code: sku.sku_code,
        status: "ACTIVE",
    }));
    for (const inventory of services.fulfillment.inventories) {
        inventory.seller_sku_id = uid(`seller-sku:${inventory.sku_id}`);
    }
    // V6 bỏ giá bán cố định: các snapshot đơn lịch sử giữ giá giả lập, SKU chỉ lưu original_price.
    for (const sku of services.catalog.skus) {
        delete sku.sku_code;
        delete sku.price;
    }
    // V10 bắt buộc snapshot giá; payment DEMO_ONLY vẫn chỉ là dữ liệu giả lập.
    for (const payment of services.commerce.payments) {
        const order = services.commerce.orders.find(
            (o) => o.id === payment.order_id,
        );
        payment.inventory_ref_id = order.id;
        payment.price_snapshot = {
            subtotal: order.subtotal,
            shippingFee: order.shipping_fee,
            discount: order.discount,
            total: payment.amount,
            items: services.commerce.order_items
                .filter((i) => i.order_id === order.id)
                .map((i) => ({
                    orderItemId: i.id,
                    skuId: i.sku_id,
                    quantity: i.quantity,
                    unitPrice: i.unit_price,
                    itemTotal: i.unit_price * i.quantity,
                })),
        };
    }
    const result = {
        dataset: DATASET,
        asOf: new Date(epoch).toISOString(),
        warehouseId,
        accounts,
        services,
    };
    validateDataset(result);
    return result;
}

/** Kiểm tra FK logic liên service, tiền VND, sở hữu đơn, balance/ledger và winner trước khi sinh SQL hay ghi DB. */
export function validateDataset(data) {
    const { services: s } = data;
    const rows = (service, table) => s[service][table] || [];
    const map = (service, table, key = "id") =>
        new Map(rows(service, table).map((r) => [r[key], r]));
    const users = map("user", "users"),
        products = map("catalog", "products"),
        skus = map("catalog", "skus"),
        orders = map("commerce", "orders");
    for (const service of SERVICES)
        for (const [table, records] of Object.entries(s[service])) {
            const keys = records.map(
                (r) => r.id || r.user_id || `${r.order_id}:${r.reservation_id}`,
            );
            assert.equal(
                new Set(keys).size,
                keys.length,
                `Trùng khóa ${service}.${table}`,
            );
        }
    for (const a of data.accounts) {
        assert(
            /^[0-9]{12}$/.test(a.identity) &&
                /^0[35789][0-9]{8}$/.test(a.phone),
            "Sai định dạng tài khoản demo",
        );
        assert.equal(users.get(a.id)?.keycloak_id, a.id);
    }
    for (const p of products.values())
        assert(
            data.accounts.some(
                (a) => a.id === p.seller_id && a.role === "SELLER",
            ),
            "Seller không tồn tại",
        );
    for (const sku of skus.values()) {
        assert(
            products.has(sku.product_id) &&
                Number.isSafeInteger(sku.original_price) &&
                sku.original_price > 0,
            "SKU/giá VND sai",
        );
    }
    for (const image of rows("catalog", "product_images"))
        assert(image.url.length <= 500, "Ảnh vượt giới hạn schema");
    for (const o of orders.values()) {
        assert(
            users.has(o.buyer_id) &&
                users.has(o.seller_id) &&
                o.buyer_id !== o.seller_id,
            "Sai chủ đơn",
        );
        const items = rows("commerce", "order_items").filter(
            (i) => i.order_id === o.id,
        );
        assert.equal(
            o.subtotal,
            items.reduce((v, i) => v + i.quantity * i.unit_price, 0),
            "Sai subtotal",
        );
        assert.equal(
            o.total,
            o.subtotal + o.shipping_fee - o.discount,
            "Sai tổng tiền",
        );
        for (const item of items)
            assert.equal(
                products.get(skus.get(item.sku_id)?.product_id)?.seller_id,
                o.seller_id,
                "Đơn chứa hàng seller khác",
            );
        const payment = rows("commerce", "payments").find(
            (p) => p.order_id === o.id,
        );
        assert.equal(payment?.amount, o.total);
        assert.equal(
            payment.status,
            o.status === "CANCELLED" ? "FAILED" : "SUCCESS",
        );
        assert.equal(
            payment.merchant_code,
            "DEMO_ONLY",
            "Không được giả giao dịch merchant thật",
        );
        const history = rows("commerce", "order_status_history")
            .filter((h) => h.order_id === o.id)
            .sort(
                (a, b) => Date.parse(a.changed_at) - Date.parse(b.changed_at),
            );
        assert.equal(history.at(-1).to_status, o.status);
        for (let i = 1; i < history.length; i++) {
            assert.equal(history[i].from_status, history[i - 1].to_status);
            assert(
                Date.parse(history[i].changed_at) >=
                    Date.parse(history[i - 1].changed_at),
            );
        }
    }
    for (const inv of rows("fulfillment", "inventories")) {
        assert(skus.has(inv.sku_id), "Kho tham chiếu SKU không tồn tại");
        assert(
            inv.total_qty >= inv.reserved_qty + inv.unavailable_qty &&
                inv.reserved_qty >= 0,
            "Kho âm/bán vượt tồn",
        );
        const ledger = rows("fulfillment", "inventory_ledger").filter(
            (l) => l.inventory_id === inv.id,
        );
        const physical = ledger
            .filter((l) =>
                ["INBOUND", "OUTBOUND", "ADJUSTMENT"].includes(l.movement_type),
            )
            .reduce((n, l) => n + l.quantity, 0);
        assert.equal(inv.total_qty, physical, "Balance vật lý lệch ledger");
        const reserved = rows("fulfillment", "inventory_reservations")
            .filter((r) => r.inventory_id === inv.id && r.status === "PENDING")
            .reduce((n, r) => n + r.quantity, 0);
        assert.equal(inv.reserved_qty, reserved, "Reserved lệch phiếu giữ kho");
        const ledgerReserved = ledger
            .filter((l) =>
                ["RESERVE", "RELEASE", "OUTBOUND"].includes(l.movement_type),
            )
            .reduce((n, l) => n + l.quantity, 0);
        assert.equal(inv.reserved_qty, ledgerReserved, "Reserved lệch ledger");
    }
    for (const auction of rows("auction", "auctions")) {
        const product = products.get(auction.product_id);
        assert(
            product?.auction_enabled &&
                product.status === "ACTIVE" &&
                product.seller_id === auction.seller_id,
            "Phiên sai sản phẩm/seller",
        );
        assert.equal(skus.get(auction.sku_id)?.product_id, product.id);
        const bids = rows("auction", "bids").filter(
            (b) => b.auction_id === auction.id,
        );
        for (const b of bids)
            assert(
                users.has(b.bidder_id) &&
                    b.bidder_id !== auction.seller_id &&
                    b.amount >= auction.start_price,
                "Bid sai",
            );
        if (bids.length) {
            const highest = bids.reduce((a, b) =>
                a.amount > b.amount ? a : b,
            );
            assert.equal(auction.current_price, highest.amount);
            assert.equal(auction.highest_bidder_id, highest.bidder_id);
        }
    }
    for (const review of rows("catalog", "product_reviews")) {
        const order = orders.get(review.order_id);
        assert(
            order?.status === "COMPLETED" && order.buyer_id === review.user_id,
            "Review không thuộc đơn hoàn tất",
        );
        assert(
            rows("commerce", "order_items").some(
                (i) => i.order_id === order.id && i.sku_id === review.sku_id,
            ),
            "Review sai SKU đã mua",
        );
    }
    for (const rating of rows("reputation", "ratings")) {
        const order = orders.get(rating.order_id);
        assert(
            order?.status === "COMPLETED" &&
                rating.rater_id !== rating.ratee_id,
            "Rating không thuộc giao dịch hoàn tất",
        );
        assert(
            [order.buyer_id, order.seller_id].includes(rating.rater_id) &&
                [order.buyer_id, order.seller_id].includes(rating.ratee_id),
            "Rating sai bên giao dịch",
        );
    }
    for (const shipment of rows("fulfillment", "shipments")) {
        const order = orders.get(shipment.order_id);
        assert(
            ["DELIVERED", "COMPLETED"].includes(order?.status) &&
                shipment.status === "DELIVERED",
            "Shipment lệch trạng thái đơn",
        );
        assert.equal(shipment.shipping_fee, order.shipping_fee);
    }
    for (const settlement of rows("auction", "auction_settlements")) {
        const auction = rows("auction", "auctions").find(
            (a) => a.id === settlement.auction_id,
        );
        if (settlement.result === "SUCCESS") {
            assert.equal(settlement.winner_id, auction.highest_bidder_id);
            assert.equal(settlement.final_price, auction.current_price);
            assert(
                Date.parse(settlement.payment_deadline) >=
                    Date.parse(settlement.settled_at),
            );
        } else
            assert(
                !settlement.winner_id && !settlement.final_price,
                "Phiên thất bại không được có winner/giá chốt",
            );
    }
    for (const category of rows("catalog", "categories")) {
        const parent = rows("catalog", "categories").find(
            (c) => c.id === category.parent_id,
        );
        assert.equal(
            category.level,
            category.parent_id ? parent?.level + 1 : 1,
            "Sai cấp danh mục",
        );
        assert(category.level <= 3);
    }
    for (const n of rows("notification", "notifications"))
        assert.equal(
            orders.get(n.order_id)?.buyer_id,
            n.user_id,
            "Thông báo sai người nhận",
        );
    return true;
}
