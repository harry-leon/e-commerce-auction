import {
  AdminAuditLogsPage, AdminAuctionsPage, AdminCategoriesPage, AdminDashboardPage, AdminEventLogsPage,
  AdminOrdersPage, AdminProductsPage, AdminReputationPage, AdminRolesPage, AdminShipmentsPage,
  AdminSystemConfigPage, AdminSystemHealthPage, AdminUserDetailPage, AdminUsersPage, AdminWarehousesPage,
} from '../pages/Admin/AdminPages'
import { AuctionDetailPage, AuctionListPage, AuctionPaymentPage, MyBidsPage } from '../pages/Auction/AuctionPages'
import { ForgotPasswordPage, LoginPage, RegisterPage } from '../pages/Auth/AuthPages'
import {
  CartPage, CheckoutPage, HomePage, NotificationPage, OrderDetailPage, OrderHistoryPage, PaymentResultPage,
  ProductDetailPage, ProfilePage, ReputationPage, SearchPage, SellerShopPage,
} from '../pages/Buyer/BuyerPages'
import { AdminProfilePage, SellerProfilePage, SupportProfilePage } from '../pages/Profile/ProfilePages'
import {
  CreateAuctionPage, CreateProductPage, SellerAuctionListPage, SellerBalancePage, SellerDashboardPage,
  SellerInventoryPage, SellerNotificationPage, SellerOrdersPage, SellerProductListPage, SellerReputationPage,
} from '../pages/Seller/SellerPages'
import { SupportDashboardPage, SupportOrderLookupPage, SupportUserLookupPage } from '../pages/Support/SupportPages'
import { useApp } from '../context'

export function AppRouter() {
  const { page } = useApp()

  switch (page) {
    case 'login': return <LoginPage />
    case 'register': return <RegisterPage />
    case 'forgot-password': return <ForgotPasswordPage />
    case 'home': return <HomePage />
    case 'search': return <SearchPage />
    case 'product-detail': return <ProductDetailPage />
    case 'cart': return <CartPage />
    case 'checkout': return <CheckoutPage />
    case 'payment-result': return <PaymentResultPage />
    case 'order-history': return <OrderHistoryPage />
    case 'order-detail': return <OrderDetailPage />
    case 'profile': return <ProfilePage />
    case 'seller-shop': return <SellerShopPage />
    case 'my-reputation':
    case 'public-reputation': return <SellerShopPage />
    case 'notifications': return <NotificationPage />
    case 'auction-list': return <AuctionListPage />
    case 'auction-detail': return <AuctionDetailPage />
    case 'my-bids': return <MyBidsPage />
    case 'auction-payment': return <AuctionPaymentPage />
    case 'seller-dashboard': return <SellerDashboardPage />
    case 'seller-products': return <SellerProductListPage />
    case 'seller-create-product': return <CreateProductPage />
    case 'seller-auctions': return <SellerAuctionListPage />
    case 'seller-create-auction': return <CreateAuctionPage />
    case 'seller-orders': return <SellerOrdersPage />
    case 'seller-balance': return <SellerBalancePage />
    case 'seller-inventory': return <SellerInventoryPage />
    case 'seller-reputation': return <SellerReputationPage />
    case 'seller-notifications': return <SellerNotificationPage />
    case 'seller-profile': return <SellerProfilePage />
    case 'admin-dashboard': return <AdminDashboardPage />
    case 'admin-users': return <AdminUsersPage />
    case 'admin-user-detail': return <AdminUserDetailPage />
    case 'admin-roles': return <AdminRolesPage />
    case 'admin-categories': return <AdminCategoriesPage />
    case 'admin-products': return <AdminProductsPage />
    case 'admin-orders': return <AdminOrdersPage />
    case 'admin-auctions': return <AdminAuctionsPage />
    case 'admin-warehouses': return <AdminWarehousesPage />
    case 'admin-shipments': return <AdminShipmentsPage />
    case 'admin-reputation': return <AdminReputationPage />
    case 'admin-event-logs': return <AdminEventLogsPage />
    case 'admin-audit-logs': return <AdminAuditLogsPage />
    case 'admin-system-config': return <AdminSystemConfigPage />
    case 'admin-system-health': return <AdminSystemHealthPage />
    case 'admin-profile': return <AdminProfilePage />
    case 'support-dashboard': return <SupportDashboardPage />
    case 'support-order-lookup': return <SupportOrderLookupPage />
    case 'support-user-lookup': return <SupportUserLookupPage />
    case 'support-profile': return <SupportProfilePage />
    default: return <HomePage />
  }
}
