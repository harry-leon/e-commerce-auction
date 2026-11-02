import {
    AdminAuditLogsPage,
    AdminAuctionsPage,
    AdminDashboardPage,
    AdminEventLogsPage,
    AdminOrdersPage,
    AdminProductsPage,
    AdminReputationPage,
    AdminRolesPage,
    AdminShipmentsPage,
    AdminSystemConfigPage,
    AdminSystemHealthPage,
    AdminUserDetailPage,
    AdminUsersPage,
    AdminWarehousesPage,
} from '../features/admin/AdminPages'
import {
    AuctionDetailPage,
    AuctionListPage,
    AuctionPaymentPage,
    MyBidsPage,
} from '../features/auction/AuctionPages'
import { ForgotPasswordPage, LoginPage, RegisterPage } from '../features/auth/AuthPages'
import {
    CartPage,
    CheckoutPage,
    HomePage,
    NotificationPage,
    OrderDetailPage,
    OrderHistoryPage,
    PaymentResultPage,
    SandboxPaymentPage,
    SellerShopPage,
} from '../features/buyer/BuyerPages'
import {
    CreateAuctionPage,
    CreateProductPage,
    SellerAuctionListPage,
    SellerBalancePage,
    SellerDashboardPage,
    SellerInventoryPage,
    SellerNotificationPage,
    SellerOrdersPage,
    SellerProductListPage,
    SellerReputationPage,
} from '../features/seller/SellerPages'
import {
    SupportDashboardPage,
    SupportOrderLookupPage,
    SupportUserLookupPage,
} from '../features/support/SupportPages'
import { SearchPage } from '../features/catalog/SearchPage'
import { ProductDetailPage } from '../features/catalog/ProductDetailPage'
import { ProfilePage } from '../features/profile/ProfilePage'
import { AdminCategoriesPage } from '../features/admin/AdminCategoriesPage'
import { useEffect } from 'react'
import { useApp } from '../context'
import { DEMO_ADMIN, DEMO_BUYER, DEMO_SELLER, DEMO_SUPPORT } from '../mockData'
import type { User } from '../types'

/**
 * Khởi tạo user mock khi mở URL demo trực tiếp, ví dụ `/admin-dashboard` ở Vite dev.
 * Chỉ thay state trong bộ nhớ của trình duyệt để route guard render đúng giao diện; không ghi token,
 * không gọi backend và khi tải lại URL demo sẽ khởi tạo lại từ route/query.
 */
function DemoRouteBootstrap({
    user,
    setUser,
}: {
    user: User
    setUser: (user: User | null) => void
}) {
    useEffect(() => {
        setUser(user)
    }, [setUser, user])

    return null
}

/** Chỉ công nhận ba role demo có dữ liệu mock sẵn; query khác vẫn đi theo cơ chế bảo vệ route bình thường. */
function demoUserFor(role: unknown): User | null {
    if (role === 'buyer') return DEMO_BUYER
    if (role === 'seller') return DEMO_SELLER
    if (role === 'admin') return DEMO_ADMIN
    return null
}

/**
 * Chức năng: Khởi tạo vai trò demo khi dev màn hình Buyer và Dashboard không qua Keycloak IAM.
 * Đầu vào và kết quả: Nhận `page` hiện tại; trả về đối tượng `User` (DEMO_BUYER/ADMIN/SELLER/SUPPORT) hoặc null.
 * Luồng xử lý: Kiểm tra biến môi trường dev; nếu đúng, gán quyền demo tương ứng cho route.
 * Quy tắc và lý do: Màn home, cart, checkout, payment-result dùng DEMO_BUYER; production tắt hoàn toàn để bảo vệ bảo mật.
 * Lỗi và giới hạn: Dữ liệu demo chỉ lưu trong React state, không có JWT thật trên server backend.
 */
function demoUserForDashboard(page: string): User | null {
    if (!import.meta.env.DEV) return null
    if (page === 'home' || page === 'cart' || page === 'checkout' || page === 'payment-result')
        return DEMO_BUYER
    if (page.startsWith('admin-')) return DEMO_ADMIN
    if (page.startsWith('seller-')) return DEMO_SELLER
    if (page.startsWith('support-')) return DEMO_SUPPORT
    return null
}

/**
 * Chọn màn hình từ route state, yêu cầu đăng nhập cho trang riêng và kiểm tra vai trò cho các khu vực.
 * Đây là bảo vệ điều hướng UI; API backend vẫn xác minh JWT và quyền cho từng thao tác.
 * Search/ProductDetail/Profile chỉ trỏ tới module gọi API riêng; không giữ bản màn trùng trong BuyerPages.
 * Các màn của sprint sau vẫn có thể dùng demo hiện có.
 */
export function AppRouter() {
    const { page, user, setUser, pageParams: routeParams } = useApp()
    /**
     * Danh sách các trang công khai (public pages) không yêu cầu đăng nhập:
     * - Bao gồm auth, home, search, product-detail, cart, sandbox-payment, payment-result.
     * - Khách vãng lai xem giỏ hàng hoặc nhận kết quả thanh toán callback từ sandbox không bị đá về Login.
     */
    const publicPages = [
        'login',
        'register',
        'forgot-password',
        'home',
        'search',
        'product-detail',
        'cart',
        'sandbox-payment',
        'payment-result',
    ]
    const demoUser = demoUserFor(routeParams.demo) || demoUserForDashboard(page)

    // URL demo chỉ nâng quyền khi chưa có phiên thật; phiên đã đăng nhập vẫn giữ quyền backend cấp.
    if (!user && demoUser) return <DemoRouteBootstrap user={demoUser} setUser={setUser} />
    if (!user && !publicPages.includes(page)) return <LoginPage />
    if (page.startsWith('admin-') && user?.role !== 'admin')
        return <p role="alert">Access denied</p>
    if (page.startsWith('seller-') && user?.role !== 'seller' && user?.role !== 'admin')
        return <p role="alert">Access denied</p>
    if (page.startsWith('support-') && user?.role !== 'support' && user?.role !== 'admin')
        return <p role="alert">Access denied</p>

    // key theo tham số URL buộc catalog/chi tiết khởi tạo lại bộ lọc hoặc ảnh khi điều hướng sang dữ liệu khác.
    // Trang 'home' hiển thị HomePage (Banner Hero, Đấu giá, Danh mục theo Figma); trang 'search' dùng SearchPage gọi API catalog thật.
    switch (page) {
        case 'login':
            return <LoginPage />
        case 'register':
            return <RegisterPage />
        case 'forgot-password':
            return <ForgotPasswordPage />
        case 'home':
            return <HomePage />
        case 'search':
            return <SearchPage key={JSON.stringify(routeParams)} />
        case 'product-detail':
            return <ProductDetailPage key={String(routeParams.id)} />
        case 'cart':
            return <CartPage />
        case 'checkout':
            return <CheckoutPage />
        case 'sandbox-payment':
            return <SandboxPaymentPage />
        case 'payment-result':
            return <PaymentResultPage />
        case 'order-history':
            return <OrderHistoryPage />
        case 'order-detail':
            return <OrderDetailPage />
        case 'profile':
            return <ProfilePage />
        case 'seller-shop':
            return <SellerShopPage />
        case 'my-reputation':
        case 'public-reputation':
            return <SellerShopPage />
        case 'notifications':
            return <NotificationPage />
        case 'auction-list':
            return <AuctionListPage />
        case 'auction-detail':
            return <AuctionDetailPage />
        case 'my-bids':
            return <MyBidsPage />
        case 'auction-payment':
            return <AuctionPaymentPage />
        case 'seller-dashboard':
            return <SellerDashboardPage />
        case 'seller-products':
            return <SellerProductListPage />
        case 'seller-create-product':
            return <CreateProductPage />
        case 'seller-auctions':
            return <SellerAuctionListPage />
        case 'seller-create-auction':
            return <CreateAuctionPage />
        case 'seller-orders':
            return <SellerOrdersPage />
        case 'seller-balance':
            return <SellerBalancePage />
        case 'seller-inventory':
            return <SellerInventoryPage />
        case 'seller-reputation':
            return <SellerReputationPage />
        case 'seller-notifications':
            return <SellerNotificationPage />
        case 'seller-profile':
            return <ProfilePage />
        case 'admin-dashboard':
            return <AdminDashboardPage />
        case 'admin-users':
            return <AdminUsersPage />
        case 'admin-user-detail':
            return <AdminUserDetailPage />
        case 'admin-roles':
            return <AdminRolesPage />
        case 'admin-categories':
            return <AdminCategoriesPage />
        case 'admin-products':
            return <AdminProductsPage />
        case 'admin-orders':
            return <AdminOrdersPage />
        case 'admin-auctions':
            return <AdminAuctionsPage />
        case 'admin-warehouses':
            return <AdminWarehousesPage />
        case 'admin-shipments':
            return <AdminShipmentsPage />
        case 'admin-reputation':
            return <AdminReputationPage />
        case 'admin-event-logs':
            return <AdminEventLogsPage />
        case 'admin-audit-logs':
            return <AdminAuditLogsPage />
        case 'admin-system-config':
            return <AdminSystemConfigPage />
        case 'admin-system-health':
            return <AdminSystemHealthPage />
        case 'admin-profile':
            return <ProfilePage />
        case 'support-dashboard':
            return <SupportDashboardPage />
        case 'support-order-lookup':
            return <SupportOrderLookupPage />
        case 'support-user-lookup':
            return <SupportUserLookupPage />
        case 'support-profile':
            return <ProfilePage />
        default:
            return <p role="alert">Page not found</p>
    }
}
