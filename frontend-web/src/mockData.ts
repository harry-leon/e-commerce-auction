import type { Product, Auction, Order, User, Category, Notification, Role, EventLog, SystemService } from './types'

export const CATEGORIES: Category[] = [
  { id: 'c1', name: 'Electronics', nameVi: 'Điện tử', icon: '', slug: 'electronics', children: [
    { id: 'c1-1', name: 'Smartphones', nameVi: 'Điện thoại thông minh', icon: '', slug: 'smartphones', parentId: 'c1', productCount: 142 },
    { id: 'c1-2', name: 'Laptops', nameVi: 'Máy tính xách tay', icon: '', slug: 'laptops', parentId: 'c1', productCount: 87 },
    { id: 'c1-3', name: 'Audio', nameVi: 'Âm thanh', icon: '', slug: 'audio', parentId: 'c1', productCount: 63 },
  ]},
  { id: 'c2', name: 'Fashion', nameVi: 'Thời trang', icon: '', slug: 'fashion', children: [
    { id: 'c2-1', name: "Men's Wear", nameVi: 'Thời trang nam', icon: '', slug: 'mens-wear', parentId: 'c2', productCount: 234 },
    { id: 'c2-2', name: "Women's Wear", nameVi: 'Thời trang nữ', icon: '', slug: 'womens-wear', parentId: 'c2', productCount: 312 },
  ]},
  { id: 'c3', name: 'Home & Living', nameVi: 'Nhà cửa & Đời sống', icon: '', slug: 'home-living', productCount: 189 },
  { id: 'c4', name: 'Sports', nameVi: 'Thể thao', icon: '', slug: 'sports', productCount: 121 },
  { id: 'c5', name: 'Books', nameVi: 'Sách', icon: '', slug: 'books', productCount: 445 },
  { id: 'c6', name: 'Collectibles', nameVi: 'Đồ sưu tầm', icon: '', slug: 'collectibles', productCount: 78 },
]

export const PRODUCTS: Product[] = [
  {
    id: 'p1', name: 'Sony WH-1000XM5 Wireless Headphones', nameVi: 'Tai nghe không dây Sony WH-1000XM5',
    description: 'Industry-leading noise cancellation with Dual Noise Sensor technology. Premium sound quality with 30-hour battery life.',
    descriptionVi: 'Công nghệ chống ồn hàng đầu ngành với Dual Noise Sensor. Âm thanh cao cấp, pin 30 giờ.',
    price: 279.99, originalPrice: 349.99,
    images: [
      'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600&h=600&fit=crop&auto=format',
      'https://images.unsplash.com/photo-1484704849700-f032a568e944?w=600&h=600&fit=crop&auto=format',
      'https://images.unsplash.com/photo-1583394838336-acd977736f90?w=600&h=600&fit=crop&auto=format',
    ],
    category: 'Audio', categoryId: 'c1-3', location: 'ho-chi-minh', sku: 'SNY-WH1000XM5-BK', stock: 24,
    status: 'active', sellerId: 'u2', sellerName: 'TechZone Store', sellerTrust: 'gold',
    sellerRating: 4.8, rating: 4.7, reviewCount: 348, weight: 0.25, enableAuction: true, createdAt: '2024-01-15'
  },
  {
    id: 'p2', name: 'Apple MacBook Air M3 13"', nameVi: 'Apple MacBook Air M3 13"',
    description: 'Supercharged by M3 chip. Remarkably thin and light with up to 18 hours of battery life.',
    descriptionVi: 'Chip M3 mạnh mẽ. Thiết kế siêu mỏng nhẹ, pin lên đến 18 giờ.',
    price: 1099.00, images: [
      'https://images.unsplash.com/photo-1517336714731-489689fd1ca8?w=600&h=600&fit=crop&auto=format',
      'https://images.unsplash.com/photo-1611186871525-2e69c63fcdb9?w=600&h=600&fit=crop&auto=format',
    ],
    category: 'Laptops', categoryId: 'c1-2', location: 'ho-chi-minh', sku: 'APL-MBA-M3-13-SLV', stock: 8,
    status: 'active', sellerId: 'u2', sellerName: 'TechZone Store', sellerTrust: 'gold',
    sellerRating: 4.8, rating: 4.9, reviewCount: 521, weight: 1.24, enableAuction: false, createdAt: '2024-02-01'
  },
  {
    id: 'p3', name: 'Nike Air Max 270', nameVi: 'Giày Nike Air Max 270',
    description: "Inspired by running shoes from the '90s. The Nike Air Max 270 delivers a soft, cushioned ride.",
    descriptionVi: 'Lấy cảm hứng từ giày chạy bộ thập niên 90. Nike Air Max 270 mang lại cảm giác êm ái.',
    price: 149.99, originalPrice: 180.00,
    images: ['https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=600&h=600&fit=crop&auto=format'],
    category: "Sports", categoryId: 'c4', location: 'da-nang', sku: 'NKE-AM270-WHT-42', stock: 45,
    status: 'active', sellerId: 'u3', sellerName: 'SportsPro VN', sellerTrust: 'silver',
    sellerRating: 4.5, rating: 4.6, reviewCount: 892, weight: 0.78, enableAuction: false, createdAt: '2024-01-20'
  },
  {
    id: 'p4', name: 'Vintage Mechanical Watch', nameVi: 'Đồng hồ cơ vintage',
    description: 'Handcrafted Swiss mechanical movement. Sapphire crystal glass, genuine leather strap.',
    descriptionVi: 'Máy cơ Thụy Sĩ thủ công. Kính sapphire, dây da thật.',
    price: 899.00,
    images: ['https://images.unsplash.com/photo-1524592094714-0f0654e20314?w=600&h=600&fit=crop&auto=format'],
    category: 'Collectibles', categoryId: 'c6', location: 'ha-noi', sku: 'VTG-MECH-001', stock: 3,
    status: 'active', sellerId: 'u4', sellerName: 'Antique Gallery', sellerTrust: 'platinum',
    sellerRating: 4.9, rating: 4.8, reviewCount: 67, weight: 0.12, enableAuction: true, createdAt: '2023-12-10'
  },
  {
    id: 'p5', name: 'Ergonomic Office Chair', nameVi: 'Ghế văn phòng công thái học',
    description: 'Full lumbar support, adjustable armrests, breathable mesh back. Ideal for long work sessions.',
    descriptionVi: 'Đỡ lưng toàn diện, tay vịn điều chỉnh được, lưng lưới thoáng khí. Phù hợp làm việc lâu.',
    price: 349.00, originalPrice: 420.00,
    images: ['https://images.unsplash.com/photo-1592078615290-033ee584e267?w=600&h=600&fit=crop&auto=format'],
    category: 'Home & Living', categoryId: 'c3', location: 'can-tho', sku: 'ERGO-CHAIR-BLK', stock: 18,
    status: 'active', sellerId: 'u5', sellerName: 'HomeOffice Plus', sellerTrust: 'silver',
    sellerRating: 4.4, rating: 4.5, reviewCount: 234, weight: 15.5, enableAuction: false, createdAt: '2024-01-08'
  },
  {
    id: 'p6', name: 'Rare Comic Book Collection', nameVi: 'Bộ sưu tập truyện tranh hiếm',
    description: 'Original 1970s Marvel comics, near-mint condition. Certificate of authenticity included.',
    descriptionVi: 'Truyện tranh Marvel thập niên 1970 bản gốc, tình trạng gần như mới. Có chứng nhận xác thực.',
    price: 2500.00,
    images: ['https://images.unsplash.com/photo-1531956531700-dc0ee0f1f9a5?w=600&h=600&fit=crop&auto=format'],
    category: 'Collectibles', categoryId: 'c6', location: 'overseas', sku: 'COMIC-MRVL-70S', stock: 1,
    status: 'active', sellerId: 'u4', sellerName: 'Antique Gallery', sellerTrust: 'platinum',
    sellerRating: 4.9, rating: 5.0, reviewCount: 12, weight: 0.45, enableAuction: true, createdAt: '2024-01-25'
  },
  {
    id: 'p7', name: 'Performance Running Shoes', nameVi: 'Giày chạy bộ hiệu năng cao',
    description: 'Lightweight daily running shoes with responsive cushioning and a breathable upper.',
    descriptionVi: 'Giày chạy bộ hằng ngày nhẹ, đệm phản hồi tốt và thân giày thoáng khí.',
    price: 129.00, originalPrice: 155.00,
    images: ['https://images.unsplash.com/photo-1637437757614-6491c8e915b5?crop=entropy&cs=tinysrgb&fit=max&fm=jpg&q=80&w=1080'],
    category: 'Sports', categoryId: 'c4', location: 'da-nang', sku: 'RUN-PERF-WHT-42', stock: 32,
    status: 'active', sellerId: 'u3', sellerName: 'SportsPro VN', sellerTrust: 'silver',
    sellerRating: 4.5, rating: 4.7, reviewCount: 186, weight: 0.62, enableAuction: false, createdAt: '2024-02-12'
  },
  {
    id: 'p8', name: 'Modern Desk Lamp', nameVi: 'Đèn bàn hiện đại',
    description: 'Compact desk lamp with warm adjustable lighting for home offices and reading corners.',
    descriptionVi: 'Đèn bàn nhỏ gọn với ánh sáng ấm có thể điều chỉnh, phù hợp góc làm việc và đọc sách.',
    price: 79.00,
    images: ['https://images.unsplash.com/photo-1667312939978-64cf31718a6e?crop=entropy&cs=tinysrgb&fit=max&fm=jpg&q=80&w=1080'],
    category: 'Home & Living', categoryId: 'c3', location: 'can-tho', sku: 'LAMP-DESK-WRM', stock: 27,
    status: 'active', sellerId: 'u5', sellerName: 'HomeOffice Plus', sellerTrust: 'silver',
    sellerRating: 4.4, rating: 4.6, reviewCount: 93, weight: 1.8, enableAuction: false, createdAt: '2024-02-08'
  },
  {
    id: 'p9', name: 'True Wireless Earbuds', nameVi: 'Tai nghe true wireless',
    description: 'Compact wireless earbuds with clear calls, balanced sound, and a pocket charging case.',
    descriptionVi: 'Tai nghe không dây nhỏ gọn, đàm thoại rõ, âm thanh cân bằng và hộp sạc bỏ túi.',
    price: 119.00,
    images: ['https://images.unsplash.com/photo-1590658268037-6bf12165a8df?crop=entropy&cs=tinysrgb&fit=max&fm=jpg&q=80&w=1080'],
    category: 'Audio', categoryId: 'c1-3', location: 'ho-chi-minh', sku: 'TWS-EARBUD-BLK', stock: 40,
    status: 'active', sellerId: 'u2', sellerName: 'TechZone Store', sellerTrust: 'gold',
    sellerRating: 4.8, rating: 4.6, reviewCount: 214, weight: 0.08, enableAuction: false, createdAt: '2024-02-16'
  },
]

export const AUCTIONS: Auction[] = [
  {
    id: 'a1', productId: 'p1', product: PRODUCTS[0],
    sellerId: 'u2', sellerName: 'TechZone Store',
    status: 'active', startingPrice: 150.00, currentBid: 217.50, minIncrement: 5.00,
    startTime: new Date(Date.now() - 3600000 * 12).toISOString(),
    endTime: new Date(Date.now() + 3600000 * 2).toISOString(),
    bidCount: 18, visibility: 'public',
    bids: [
      { id: 'b1', bidderId: 'u10', bidderName: 'nguy***an', amount: 217.50, timestamp: new Date(Date.now() - 300000).toISOString() },
      { id: 'b2', bidderId: 'u11', bidderName: 'tran***ng', amount: 212.50, timestamp: new Date(Date.now() - 900000).toISOString() },
      { id: 'b3', bidderId: 'u10', bidderName: 'nguy***an', amount: 207.50, timestamp: new Date(Date.now() - 1800000).toISOString() },
      { id: 'b4', bidderId: 'u12', bidderName: 'le***thu', amount: 200.00, timestamp: new Date(Date.now() - 3600000).toISOString() },
      { id: 'b5', bidderId: 'u13', bidderName: 'pham***duc', amount: 185.00, timestamp: new Date(Date.now() - 7200000).toISOString() },
    ]
  },
  {
    id: 'a2', productId: 'p4', product: PRODUCTS[3],
    sellerId: 'u4', sellerName: 'Antique Gallery',
    status: 'active', startingPrice: 500.00, currentBid: 780.00, minIncrement: 20.00,
    startTime: new Date(Date.now() - 3600000 * 48).toISOString(),
    endTime: new Date(Date.now() + 3600000 * 0.5).toISOString(),
    bidCount: 32, visibility: 'public',
    bids: [
      { id: 'b6', bidderId: 'u14', bidderName: 'hoang***tri', amount: 780.00, timestamp: new Date(Date.now() - 120000).toISOString() },
      { id: 'b7', bidderId: 'u15', bidderName: 'nguyen***bao', amount: 760.00, timestamp: new Date(Date.now() - 600000).toISOString() },
    ]
  },
  {
    id: 'a3', productId: 'p6', product: PRODUCTS[5],
    sellerId: 'u4', sellerName: 'Antique Gallery',
    status: 'scheduled', startingPrice: 2000.00, currentBid: 2000.00, minIncrement: 50.00,
    startTime: new Date(Date.now() + 3600000 * 24).toISOString(),
    endTime: new Date(Date.now() + 3600000 * 96).toISOString(),
    bidCount: 0, visibility: 'public', bids: []
  },
  {
    id: 'a4', productId: 'p3', product: PRODUCTS[2],
    sellerId: 'u3', sellerName: 'SportsPro VN',
    status: 'ended', startingPrice: 80.00, currentBid: 148.00, minIncrement: 5.00,
    startTime: new Date(Date.now() - 3600000 * 72).toISOString(),
    endTime: new Date(Date.now() - 3600000 * 1).toISOString(),
    bidCount: 24, winnerId: 'u1', winnerName: 'Minh Tran', visibility: 'public',
    bids: [
      { id: 'b8', bidderId: 'u1', bidderName: 'minh***an', amount: 148.00, timestamp: new Date(Date.now() - 3660000).toISOString() },
    ]
  },
  {
    id: 'a5', productId: 'p2', product: PRODUCTS[1],
    sellerId: 'u2', sellerName: 'TechZone Store',
    status: 'pending_approval', startingPrice: 750.00, currentBid: 750.00, minIncrement: 20.00,
    startTime: new Date(Date.now() + 3600000 * 24).toISOString(),
    endTime: new Date(Date.now() + 3600000 * 72).toISOString(),
    bidCount: 0, visibility: 'public', bids: []
  },
]

export const ORDERS: Order[] = [
  {
    id: 'ORD-20240215-001', buyerId: 'u1', buyerName: 'Minh Tran', sellerId: 'u2', sellerName: 'TechZone Store',
    items: [{ productId: 'p1', productName: 'Sony WH-1000XM5 Wireless Headphones', image: PRODUCTS[0].images[0], quantity: 1, unitPrice: 279.99, subtotal: 279.99 }],
    subtotal: 279.99, tax: 22.40, discount: 0, shipping: 8.99, total: 311.38,
    status: 'delivered', refundStatus: 'none', paymentStatus: 'protection_period',
    shippingAddress: '123 Nguyen Hue, District 1, Ho Chi Minh City', paymentMethod: 'Visa ending 4242',
    shipment: {
      id: 'SHP-001', carrier: 'GHN', trackingNumber: 'GHN123456789VN', status: 'delivered',
      estimatedDelivery: '2024-02-18',
      events: [
        { status: 'pending', description: 'Order picked up by carrier', timestamp: '2024-02-15T14:00:00Z' },
        { status: 'in_transit', description: 'Package in transit - HCM Sorting Center', location: 'Ho Chi Minh City', timestamp: '2024-02-16T08:00:00Z' },
        { status: 'out_for_delivery', description: 'Out for delivery', location: 'District 1', timestamp: '2024-02-18T09:30:00Z' },
        { status: 'delivered', description: 'Package delivered successfully', location: 'District 1', timestamp: '2024-02-18T14:22:00Z' },
      ]
    },
    createdAt: '2024-02-15T10:30:00Z', updatedAt: '2024-02-18T14:22:00Z',
    deliveredAt: new Date(Date.now() - 3 * 86400000).toISOString(),
    protectionEndsAt: new Date(Date.now() + 4 * 86400000).toISOString(),
    disputeStatus: 'none', reviewed: false
  },
  {
    id: 'ORD-20240220-002', buyerId: 'u1', buyerName: 'Minh Tran', sellerId: 'u3', sellerName: 'SportsPro VN',
    items: [{ productId: 'p3', productName: 'Nike Air Max 270', image: PRODUCTS[2].images[0], quantity: 2, unitPrice: 149.99, subtotal: 299.98 }],
    subtotal: 299.98, tax: 24.00, discount: 30.00, shipping: 0, total: 293.98,
    status: 'processing', refundStatus: 'none', paymentStatus: 'held',
    shippingAddress: '123 Nguyen Hue, District 1, Ho Chi Minh City', paymentMethod: 'Mastercard ending 5555',
    createdAt: '2024-02-20T16:45:00Z', updatedAt: '2024-02-21T09:00:00Z', disputeStatus: 'none', reviewed: false
  },
  {
    id: 'ORD-20240101-003', buyerId: 'u1', buyerName: 'Minh Tran', sellerId: 'u5', sellerName: 'HomeOffice Plus',
    items: [{ productId: 'p5', productName: 'Ergonomic Office Chair', image: PRODUCTS[4].images[0], quantity: 1, unitPrice: 349.00, subtotal: 349.00 }],
    subtotal: 349.00, tax: 27.92, discount: 0, shipping: 15.00, total: 391.92,
    status: 'delivered', refundStatus: 'initiated', paymentStatus: 'on_hold',
    shippingAddress: '123 Nguyen Hue, District 1, Ho Chi Minh City', paymentMethod: 'Visa ending 4242',
    createdAt: '2024-01-01T12:00:00Z', updatedAt: '2024-01-02T08:00:00Z',
    deliveredAt: '2024-01-05T10:00:00Z', protectionEndsAt: '2024-01-12T10:00:00Z',
    disputeStatus: 'reviewing', disputeReason: 'Item received damaged', reviewed: false
  },
]

export const USERS: User[] = [
  { id: 'u1', fullName: 'Minh Tran', email: 'minh.tran@email.com', phone: '0901234567', idNumber: '079201012345', gender: 'male', dateOfBirth: '1995-03-15', address: '123 Nguyen Hue, Q1, HCM', role: 'buyer', trustLevel: 'silver', reputationScore: 72, avatar: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=80&h=80&fit=crop&auto=format', status: 'active', createdAt: '2023-01-10' },
  { id: 'u2', fullName: 'Anh Nguyen', email: 'anh.nguyen@techzone.vn', phone: '0912345678', idNumber: '079198056789', gender: 'male', dateOfBirth: '1990-07-22', address: '45 Le Loi, Q1, HCM', role: 'seller', trustLevel: 'gold', reputationScore: 91, avatar: 'https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?w=80&h=80&fit=crop&auto=format', status: 'active', createdAt: '2022-06-01' },
  { id: 'u3', fullName: 'Thu Hoang', email: 'thu.hoang@sportspro.vn', phone: '0923456789', idNumber: '079199034567', gender: 'female', dateOfBirth: '1992-11-08', address: '78 Hai Ba Trung, Q3, HCM', role: 'seller', trustLevel: 'silver', reputationScore: 68, avatar: 'https://images.unsplash.com/photo-1494790108755-2616b8cc6a8b?w=80&h=80&fit=crop&auto=format', status: 'active', createdAt: '2022-09-15' },
  { id: 'u9', fullName: 'Admin User', email: 'admin@nexus.vn', phone: '0900000001', idNumber: '079200000001', gender: 'male', dateOfBirth: '1988-01-01', address: 'Nexus HQ', role: 'admin', trustLevel: 'platinum', reputationScore: 100, avatar: 'https://images.unsplash.com/photo-1560250097-0b93528c311a?w=80&h=80&fit=crop&auto=format', status: 'active', createdAt: '2022-01-01' },
]

export const DEMO_BUYER: User = USERS[0]
export const DEMO_SELLER: User = USERS[1]
export const DEMO_ADMIN: User = USERS[3]
export const DEMO_SUPPORT: User = { id: 'u8', fullName: 'Support Agent', email: 'support@nexus.vn', phone: '0900000002', idNumber: '079200000002', gender: 'female', dateOfBirth: '1993-05-12', address: 'Nexus HQ', role: 'support', trustLevel: 'gold', reputationScore: 85, avatar: 'https://images.unsplash.com/photo-1438761681033-6461ffad8d80?w=80&h=80&fit=crop&auto=format', status: 'active', createdAt: '2022-03-01' }

export const NOTIFICATIONS: Notification[] = [
  { id: 'n1', type: 'auction', title: 'You have been outbid!', titleVi: 'Bạn đã bị vượt giá!', body: 'Someone placed a higher bid on Sony WH-1000XM5. Current bid: $217.50', bodyVi: 'Có người đặt giá cao hơn trên Sony WH-1000XM5. Giá hiện tại: $217.50', read: false, timestamp: new Date(Date.now() - 300000).toISOString() },
  { id: 'n2', type: 'order', title: 'Order delivered', titleVi: 'Đơn hàng đã giao', body: 'Your order ORD-20240215-001 has been delivered.', bodyVi: 'Đơn hàng ORD-20240215-001 đã được giao.', read: false, timestamp: new Date(Date.now() - 3600000).toISOString() },
  { id: 'n3', type: 'system', title: 'System maintenance scheduled', titleVi: 'Lịch bảo trì hệ thống', body: 'Nexus will undergo maintenance on March 1 at 2 AM.', bodyVi: 'Nexus sẽ bảo trì vào ngày 1/3 lúc 2 giờ sáng.', read: true, timestamp: new Date(Date.now() - 86400000).toISOString() },
]

export const ROLES: Role[] = [
  { id: 'r1', name: 'Admin', description: 'Full system access', userCount: 3, permissions: [
    { module: 'USER', read: true, write: true, delete: true, admin: true },
    { module: 'ROLE', read: true, write: true, delete: true, admin: true },
    { module: 'PRODUCT', read: true, write: true, delete: true, admin: true },
  ]},
  { id: 'r2', name: 'Seller', description: 'Sell products and manage auctions', userCount: 128, permissions: [
    { module: 'PRODUCT', read: true, write: true, delete: true, admin: false },
    { module: 'AUCTION', read: true, write: true, delete: false, admin: false },
    { module: 'ORDER', read: true, write: true, delete: false, admin: false },
  ]},
  { id: 'r3', name: 'Buyer', description: 'Browse and purchase products', userCount: 4821, permissions: [
    { module: 'PRODUCT', read: true, write: false, delete: false, admin: false },
    { module: 'ORDER', read: true, write: true, delete: false, admin: false },
  ]},
  { id: 'r4', name: 'Support', description: 'Handle customer support tickets', userCount: 12, permissions: [
    { module: 'USER', read: true, write: false, delete: false, admin: false },
    { module: 'ORDER', read: true, write: true, delete: false, admin: false },
    { module: 'SHIPPING', read: true, write: true, delete: false, admin: false },
  ]},
]

export const EVENT_LOGS: EventLog[] = [
  { id: 'el1', type: 'AUCTION', action: 'BID_PLACED', message: 'Bid of $217.50 placed on auction A-001', operator: 'nguy***an', targetId: 'a1', targetType: 'auction', timestamp: new Date(Date.now() - 300000).toISOString() },
  { id: 'el2', type: 'ORDER', action: 'ORDER_CANCELLED', message: 'Order ORD-20240101-003 cancelled by buyer', operator: 'minh.tran@email.com', targetId: 'ORD-20240101-003', targetType: 'order', timestamp: new Date(Date.now() - 86400000).toISOString() },
  { id: 'el3', type: 'USER', action: 'USER_LOGIN', message: 'User login from 192.168.1.1', operator: 'admin@nexus.vn', timestamp: new Date(Date.now() - 3600000).toISOString() },
  { id: 'el4', type: 'SYSTEM', action: 'CONFIG_UPDATED', message: 'Auction min duration updated to 60 min', operator: 'admin@nexus.vn', timestamp: new Date(Date.now() - 7200000).toISOString() },
]

export const SYSTEM_SERVICES: SystemService[] = [
  { name: 'API Gateway', status: 'up', latency: 12, lastCheck: new Date().toISOString() },
  { name: 'Message Broker (RabbitMQ)', status: 'up', latency: 3, lastCheck: new Date().toISOString() },
  { name: 'Database (PostgreSQL)', status: 'up', latency: 8, lastCheck: new Date().toISOString() },
  { name: 'Redis Cache', status: 'up', latency: 1, lastCheck: new Date().toISOString() },
  { name: 'Email Service', status: 'up', latency: 18, lastCheck: new Date().toISOString() },
  { name: 'Payment Gateway', status: 'up', latency: 45, lastCheck: new Date().toISOString() },
  { name: 'Search Engine (Elasticsearch)', status: 'up', latency: 22, lastCheck: new Date().toISOString() },
  { name: 'CDN', status: 'up', latency: 6, lastCheck: new Date().toISOString() },
]
