import { Component, type ReactNode, type ErrorInfo } from 'react'
import { AppProvider, useApp } from './context'
import { Gavel, ToastContainer } from './components/UI'

class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null }
  static getDerivedStateFromError(error: Error) { return { error } }
  componentDidCatch(error: Error, info: ErrorInfo) { console.error('[Nexus Error]', error, info) }
  render() {
    const { error } = this.state
    if (error) return (
      <div style={{ minHeight: '100vh', background: '#F9FAFB', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '2rem' }}>
        <div style={{ background: '#fff', border: '1px solid #E5E7EB', borderRadius: 12, padding: '2rem', maxWidth: 560, width: '100%', boxShadow: '0 4px 24px rgba(0,0,0,.08)' }}>
          <div style={{ color: '#DC2626', fontWeight: 700, fontSize: 18, marginBottom: 8 }}>Application Error / Lỗi ứng dụng</div>
          <pre style={{ background: '#FEF2F2', border: '1px solid #FECACA', borderRadius: 8, padding: '1rem', fontSize: 12, overflowX: 'auto', color: '#7F1D1D', whiteSpace: 'pre-wrap' }}>
            {(error as Error).message}
            {'\n\n'}
            {(error as Error).stack}
          </pre>
          <button onClick={() => this.setState({ error: null })}
            style={{ marginTop: 16, background: '#4F46E5', color: '#fff', border: 'none', borderRadius: 8, padding: '8px 20px', fontWeight: 600, cursor: 'pointer' }}>
            Retry / Thử lại
          </button>
        </div>
      </div>
    )
    return this.props.children
  }
}

// Auth pages
import { LoginPage, RegisterPage, ForgotPasswordPage } from './pages/AuthPages'

// Buyer pages
import { HomePage, SearchPage, ProductDetailPage, CartPage, CheckoutPage, PaymentResultPage, OrderHistoryPage, OrderDetailPage, ProfilePage, ReputationPage, NotificationPage } from './pages/BuyerPages'

// Auction pages
import { AuctionListPage, AuctionDetailPage, MyBidsPage, AuctionPaymentPage } from './pages/AuctionPages'

// Seller pages
import { SellerDashboardPage, SellerBalancePage, SellerNotificationPage, SellerReputationPage, SellerProductListPage, CreateProductPage, SellerAuctionListPage, CreateAuctionPage, SellerOrdersPage, SellerInventoryPage } from './pages/SellerPages'

// Admin pages
import {
  AdminDashboardPage, AdminUsersPage, AdminUserDetailPage, AdminRolesPage, AdminCategoriesPage,
  AdminOrdersPage, AdminAuctionsPage, AdminReputationPage, AdminEventLogsPage, AdminAuditLogsPage,
  AdminSystemConfigPage, AdminSystemHealthPage, AdminProductsPage, AdminWarehousesPage, AdminShipmentsPage
} from './pages/AdminPages'

// Support pages
import { SupportDashboardPage, SupportOrderLookupPage, SupportUserLookupPage } from './pages/SupportPages'
import { AdminProfilePage, SellerProfilePage, SupportProfilePage } from './pages/ProfilePages'

import { DEMO_BUYER, DEMO_SELLER, DEMO_ADMIN, DEMO_SUPPORT } from './mockData'
import type { User } from './types'
import { useApp as _useApp } from './context'

function Router() {
  const { page } = useApp()
  switch (page) {
    // Auth
    case 'login': return <LoginPage />
    case 'register': return <RegisterPage />
    case 'forgot-password': return <ForgotPasswordPage />

    // Buyer / Public
    case 'home': return <HomePage />
    case 'search': return <SearchPage />
    case 'product-detail': return <ProductDetailPage />
    case 'cart': return <CartPage />
    case 'checkout': return <CheckoutPage />
    case 'payment-result': return <PaymentResultPage />
    case 'order-history': return <OrderHistoryPage />
    case 'order-detail': return <OrderDetailPage />
    case 'profile': return <ProfilePage />
    case 'my-reputation': return <ReputationPage />
    case 'public-reputation': return <ReputationPage />
    case 'notifications': return <NotificationPage />

    // Auction
    case 'auction-list': return <AuctionListPage />
    case 'auction-detail': return <AuctionDetailPage />
    case 'my-bids': return <MyBidsPage />
    case 'auction-payment': return <AuctionPaymentPage />

    // Seller
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

    // Admin
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

    // Support
    case 'support-dashboard': return <SupportDashboardPage />
    case 'support-order-lookup': return <SupportOrderLookupPage />
    case 'support-user-lookup': return <SupportUserLookupPage />
    case 'support-profile': return <SupportProfilePage />

    default: return <HomePage />
  }
}

function DemoNav() {
  const { user, setUser, navigate } = useApp()
  const roles: [User, string, string][] = [
    [DEMO_BUYER, 'Buyer', 'home'],
    [DEMO_SELLER, 'Seller', 'seller-dashboard'],
    [DEMO_ADMIN, 'Admin', 'admin-dashboard'],
    [DEMO_SUPPORT, 'Support', 'support-dashboard'],
  ]
  return (
    <div className="fixed bottom-5 left-1/2 -translate-x-1/2 z-50 flex items-center gap-2 bg-gray-900 text-white rounded-xl px-4 py-2.5 shadow-lg text-xs">
      <span className="text-gray-400 font-medium mr-1">Demo:</span>
      {roles.map(([u, label, startPage]) => (
        <button key={label} onClick={() => { setUser(u); navigate(startPage as any) }}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl font-semibold transition-colors ${user?.id === u.id ? 'bg-primary text-white' : 'text-gray-300 hover:bg-gray-700'}`}>
          <img src={u.avatar} alt="" className="w-5 h-5 rounded-full object-cover" />
          {label}
        </button>
      ))}
      <div className="w-px h-5 bg-gray-700 mx-1" />
      <button onClick={() => navigate('auction-detail', { id: 'a1' })} className="px-3 py-1.5 rounded-xl text-gray-300 hover:bg-gray-700 font-semibold">
        <span className="inline-flex items-center gap-1.5"><Gavel size={13} />Auction</span>
      </button>
    </div>
  )
}

function AppInner() {
  const { toasts, dismissToast } = useApp()
  return (
    <>
      <Router />
      <ToastContainer toasts={toasts} onDismiss={dismissToast} />
      <DemoNav />
    </>
  )
}

export default function App() {
  return (
    <ErrorBoundary>
      <AppProvider>
        <ErrorBoundary>
          <AppInner />
        </ErrorBoundary>
      </AppProvider>
    </ErrorBoundary>
  )
}
