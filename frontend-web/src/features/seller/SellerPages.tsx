import { useState, useEffect, useRef } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate, formatDateTime } from '../../i18n'
import { SellerLayout } from '../../components/Layout'
import {
    Button,
    Input,
    Textarea,
    Select,
    Tabs,
    Modal,
    ConfirmModal,
    AlertBanner,
    EmptyState,
    StatusBadge,
    StarRating,
    ReputationBar,
    DataTable,
    Toggle,
    Breadcrumb,
    Plus,
    Pencil,
    Eye,
    Trash2,
    TrendingUp,
    Package,
    Gavel,
    AlertTriangle,
    CheckCircle,
    Star,
    Camera,
    Bell,
} from '../../components/UI'
import { PRODUCTS, ORDERS, NOTIFICATIONS } from '../../mockData'
import type { Auction, Order, OrderStatus, ProductStatus } from '../../types'
import { getEffectivePaymentStatus } from '../../payment'
import { ordersApi } from '../../api/orders'
import { auctionsApi, cacheAuctionProduct } from '../../api/auctions'
import {
    api,
    ApiClientError,
    errorMessage,
    getProductStock,
    setProductStock,
    type ApiCategory,
    type ApiProduct,
    type Envelope,
} from '../../api/client'
import {
    adjustStock,
    createInventoryIdempotencyKey,
    getInventoryLedger,
    inboundStock,
    listInventory,
    listWarehouses,
    reconcileInventory,
    type InventoryLedgerResponse,
    type InventoryReconciliationResponse,
    type InventoryStockResponse,
    type LedgerMovementType,
    type WarehouseResponse,
} from '../../api/inventory'
import { validateMinPrice } from '../../utils/pricingEngine'
import { useLoad, clearLoadCache } from '../../hooks/useLoad'

const AVAILABLE_BALANCE_VND = 210_000_000
const VIETNAMESE_BANKS = [
    ['agribank', 'Agribank — Ngân hàng Nông nghiệp và Phát triển Nông thôn'],
    ['vietcombank', 'Vietcombank — Ngân hàng TMCP Ngoại thương Việt Nam'],
    ['vietinbank', 'VietinBank — Ngân hàng TMCP Công thương Việt Nam'],
    ['bidv', 'BIDV — Ngân hàng TMCP Đầu tư và Phát triển Việt Nam'],
    ['acb', 'ACB — Ngân hàng TMCP Á Châu'],
    ['mbbank', 'MB Bank — Ngân hàng TMCP Quân đội'],
    ['techcombank', 'Techcombank — Ngân hàng TMCP Kỹ thương Việt Nam'],
    ['vpbank', 'VPBank — Ngân hàng TMCP Việt Nam Thịnh Vượng'],
    ['tpbank', 'TPBank — Ngân hàng TMCP Tiên Phong'],
    ['sacombank', 'Sacombank — Ngân hàng TMCP Sài Gòn Thương Tín'],
    ['hdbank', 'HDBank — Ngân hàng TMCP Phát triển TP.HCM'],
    ['vib', 'VIB — Ngân hàng TMCP Quốc tế Việt Nam'],
    ['shb', 'SHB — Ngân hàng TMCP Sài Gòn – Hà Nội'],
    ['seabank', 'SeABank — Ngân hàng TMCP Đông Nam Á'],
    ['ocb', 'OCB — Ngân hàng TMCP Phương Đông'],
    ['msb', 'MSB — Ngân hàng TMCP Hàng Hải Việt Nam'],
    ['eximbank', 'Eximbank — Ngân hàng TMCP Xuất Nhập khẩu Việt Nam'],
    ['namabank', 'Nam A Bank — Ngân hàng TMCP Nam Á'],
    ['vietabank', 'VietABank — Ngân hàng TMCP Việt Á'],
    ['pvcombank', 'PVcomBank — Ngân hàng TMCP Đại Chúng Việt Nam'],
    ['baovietbank', 'BAOVIET Bank — Ngân hàng TMCP Bảo Việt'],
    ['ncb', 'NCB — Ngân hàng TMCP Quốc Dân'],
    ['kienlongbank', 'KienlongBank — Ngân hàng TMCP Kiên Long'],
    ['bacabank', 'Bac A Bank — Ngân hàng TMCP Bắc Á'],
    ['vietbank', 'VietBank — Ngân hàng TMCP Việt Nam Thương Tín'],
    ['abbank', 'ABBank — Ngân hàng TMCP An Bình'],
    ['saigonbank', 'Saigonbank — Ngân hàng TMCP Sài Gòn Công Thương'],
    ['pgbank', 'PGBank — Ngân hàng TMCP Thịnh vượng và Phát triển'],
    ['bvbank', 'BVBank — Ngân hàng TMCP Bản Việt'],
    ['lpbank', 'LPBank — Ngân hàng TMCP Lộc Phát Việt Nam'],
    ['scb', 'SCB — Ngân hàng TMCP Sài Gòn'],
    ['cimb', 'CIMB Bank Việt Nam'],
    ['hsbc', 'HSBC Việt Nam'],
    ['standard-chartered', 'Standard Chartered Việt Nam'],
    ['shinhan', 'Shinhan Bank Việt Nam'],
    ['woori', 'Woori Bank Việt Nam'],
    ['public-bank', 'Public Bank Việt Nam'],
    ['hong-leong', 'Hong Leong Bank Việt Nam'],
    ['uob', 'UOB Việt Nam'],
    ['kbank', 'Kasikornbank Việt Nam'],
].map(([value, label]) => ({ value, label }))

const formatVnd = (amount: number) => `${amount.toLocaleString('vi-VN')} ₫`

// ─── Seller Dashboard ──────────────────────────────────────────────────────────
export function SellerDashboardPage() {
    const { lang, navigate } = useApp()
    const KPIs = [
        {
            icon: <TrendingUp size={20} />,
            label: tr(lang, 'revenueThisMonth'),
            value: '$12,480',
            change: '+18%',
            positive: true,
        },
        {
            icon: <Package size={20} />,
            label: tr(lang, 'orders'),
            value: '148',
            change: '+5%',
            positive: true,
        },
        {
            icon: <Gavel size={20} />,
            label: tr(lang, 'runningAuctions'),
            value: '2',
            change: '',
            positive: true,
        },
        {
            icon: <Star size={20} />,
            label: tr(lang, 'reputation'),
            value: '91 pts',
            change: 'Gold',
            positive: true,
        },
    ]
    const statusLabel = (s: string) =>
        ({
            PENDING_PAYMENT: tr(lang, 'statusPending'),
            PAID: tr(lang, 'statusPaid'),
            PROCESSING: tr(lang, 'statusProcessing'),
            SHIPPED: tr(lang, 'statusShipped'),
            DELIVERED: tr(lang, 'statusDelivered'),
            COMPLETED: tr(lang, 'statusCompleted'),
            CANCELLED: tr(lang, 'statusCancelled'),
            REFUNDED: tr(lang, 'statusRefunded'),
        })[s] || s

    return (
        <SellerLayout title={tr(lang, 'sellerDashboard')}>
            {/* KPIs */}
            <div className="grid grid-cols-4 gap-5 mb-7">
                {KPIs.map((k) => (
                    <div key={k.label} className="nexus-card p-5">
                        <div className="flex items-center gap-3 mb-3">
                            <span className="text-gray-500">{k.icon}</span>
                            <span className="text-sm text-gray-500">{k.label}</span>
                        </div>
                        <p className="text-2xl font-black text-gray-900">{k.value}</p>
                        {k.change && (
                            <p
                                className={`text-xs font-semibold mt-1 ${k.positive ? 'text-success' : 'text-danger'}`}
                            >
                                {k.change}
                            </p>
                        )}
                    </div>
                ))}
            </div>

            <div className="grid grid-cols-[1fr_300px] gap-6">
                {/* Revenue chart placeholder */}
                <div className="nexus-card p-5">
                    <div className="flex items-center justify-between mb-4">
                        <h3 className="font-bold">
                            {lang === 'en' ? 'Revenue Overview' : 'Tổng quan doanh thu'}
                        </h3>
                        <select className="nexus-input text-xs py-1.5 w-28">
                            <option>{lang === 'en' ? 'Last 30 days' : '30 ngày qua'}</option>
                            <option>{lang === 'en' ? 'Last 90 days' : '90 ngày qua'}</option>
                        </select>
                    </div>
                    {/* Fake bar chart */}
                    <div className="flex items-end gap-2 h-36">
                        {[40, 65, 45, 80, 55, 90, 70, 85, 60, 95, 75, 88].map((h, i) => (
                            <div key={i} className="flex-1 flex flex-col items-center gap-1">
                                <div
                                    className="w-full rounded-t-sm bg-primary"
                                    style={{
                                        height: `${h}%`,
                                        opacity: i === 11 ? 1 : 0.5 + i * 0.04,
                                    }}
                                />
                            </div>
                        ))}
                    </div>
                    <div className="flex justify-between text-xs text-gray-400 mt-2">
                        <span>Jan</span>
                        <span>Jun</span>
                        <span>Dec</span>
                    </div>
                </div>

                {/* Low stock */}
                <div className="nexus-card p-5">
                    <div className="flex items-center gap-2 mb-4">
                        <AlertTriangle size={16} className="text-gray-500" />
                        <h3 className="font-bold text-gray-900">{tr(lang, 'lowStock')}</h3>
                    </div>
                    <div className="space-y-3">
                        {PRODUCTS.filter((p) => p.stock <= 5).map((p) => (
                            <div key={p.id} className="flex items-center gap-3">
                                <img
                                    src={p.images[0]}
                                    alt=""
                                    className="w-10 h-10 rounded-lg object-cover"
                                />
                                <div className="flex-1 min-w-0">
                                    <p className="text-xs font-semibold truncate">
                                        {lang === 'vi' ? p.nameVi : p.name}
                                    </p>
                                    <p className="text-xs text-danger font-bold">
                                        {lang === 'en' ? `${p.stock} left` : `Còn ${p.stock}`}
                                    </p>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>

            {/* Recent orders */}
            <div className="nexus-card mt-6">
                <div className="flex items-center justify-between p-5 pb-0">
                    <h3 className="font-bold">{tr(lang, 'recentOrders')}</h3>
                    <Button variant="ghost" size="sm" onClick={() => navigate('seller-orders')}>
                        {lang === 'en' ? 'View All' : 'Xem tất cả'}
                    </Button>
                </div>
                <div className="p-5">
                    <DataTable
                        columns={[
                            {
                                key: 'id',
                                label: lang === 'en' ? 'Order ID' : 'Mã đơn',
                                render: (r: any) => (
                                    <span className="font-mono text-xs">{r.id}</span>
                                ),
                            },
                            { key: 'buyerName', label: lang === 'en' ? 'Buyer' : 'Người mua' },
                            {
                                key: 'total',
                                label: lang === 'en' ? 'Total' : 'Tổng',
                                render: (r: any) => (
                                    <span className="font-bold text-primary">
                                        {formatCurrency(r.total, lang)}
                                    </span>
                                ),
                            },
                            {
                                key: 'status',
                                label: lang === 'en' ? 'Status' : 'Trạng thái',
                                render: (r: any) => (
                                    <StatusBadge status={r.status} label={statusLabel(r.status)} />
                                ),
                            },
                            {
                                key: 'createdAt',
                                label: lang === 'en' ? 'Date' : 'Ngày',
                                render: (r: any) => (
                                    <span className="text-xs text-gray-500">
                                        {formatDate(r.createdAt, lang)}
                                    </span>
                                ),
                            },
                        ]}
                        data={ORDERS.slice(0, 5)}
                        rowKey={(r) => r.id}
                        onRowClick={() => navigate('seller-orders')}
                    />
                </div>
            </div>
        </SellerLayout>
    )
}

// ─── Seller Balance ────────────────────────────────────────────────────────────
export function SellerBalancePage() {
    const { lang, showToast } = useApp()
    const [withdrawOpen, setWithdrawOpen] = useState(false)
    const [withdrawAmount, setWithdrawAmount] = useState('')
    const [withdrawBank, setWithdrawBank] = useState('')
    const [withdrawAccountNumber, setWithdrawAccountNumber] = useState('')
    const transactions = [
        {
            id: 'BAL-001',
            description:
                lang === 'en'
                    ? 'Payment released · ORD-20240215-001'
                    : 'Giải ngân · ORD-20240215-001',
            date: '2024-02-25',
            amount: 7_785_000,
            status: 'released',
        },
        {
            id: 'BAL-002',
            description: lang === 'en' ? 'Withdrawal · Vietcombank' : 'Rút tiền · Vietcombank',
            date: '2024-02-22',
            amount: -50_000_000,
            status: 'completed',
        },
        {
            id: 'BAL-003',
            description:
                lang === 'en'
                    ? 'Payment released · ORD-20240211-008'
                    : 'Giải ngân · ORD-20240211-008',
            date: '2024-02-18',
            amount: 24_500_000,
            status: 'released',
        },
    ]

    return (
        <SellerLayout title={lang === 'en' ? 'Balance' : 'Số dư'}>
            <div className="grid grid-cols-3 gap-5 mb-6">
                <div className="nexus-card p-5">
                    <p className="text-sm text-gray-500">
                        {lang === 'en' ? 'Available balance' : 'Số dư khả dụng'}
                    </p>
                    <p className="text-3xl font-black text-gray-900 mt-2">
                        {formatVnd(AVAILABLE_BALANCE_VND)}
                    </p>
                    <Button
                        variant="primary"
                        className="mt-4"
                        onClick={() => setWithdrawOpen(true)}
                    >
                        {lang === 'en' ? 'Request Withdrawal' : 'Yêu cầu rút tiền'}
                    </Button>
                </div>
                <div className="nexus-card p-5">
                    <p className="text-sm text-gray-500">
                        {lang === 'en' ? 'Buyer protection' : 'Đang bảo vệ Buyer'}
                    </p>
                    <p className="text-3xl font-black text-gray-900 mt-2">
                        {formatVnd(36_250_000)}
                    </p>
                    <p className="text-xs text-gray-400 mt-2">
                        {lang === 'en'
                            ? 'Releases automatically after the protection period.'
                            : 'Tự động giải ngân sau thời gian bảo vệ.'}
                    </p>
                </div>
                <div className="nexus-card p-5">
                    <p className="text-sm text-gray-500">
                        {lang === 'en' ? 'On hold' : 'Đang tạm giữ'}
                    </p>
                    <p className="text-3xl font-black text-gray-900 mt-2">{formatVnd(9_800_000)}</p>
                    <p className="text-xs text-gray-400 mt-2">
                        {lang === 'en'
                            ? 'Pending dispute resolution.'
                            : 'Đang chờ xử lý khiếu nại.'}
                    </p>
                </div>
            </div>

            <AlertBanner
                type="info"
                message={
                    lang === 'en'
                        ? 'Only released payments are added to your available balance. You do not need to request payment release.'
                        : 'Chỉ các khoản đã giải ngân mới được cộng vào số dư khả dụng. Bạn không cần gửi yêu cầu giải ngân.'
                }
            />

            <div className="nexus-card mt-6">
                <div className="p-5 border-b border-border font-bold">
                    {lang === 'en' ? 'Balance activity' : 'Lịch sử số dư'}
                </div>
                <div className="p-5">
                    {transactions.map((transaction) => (
                        <div
                            key={transaction.id}
                            className="flex items-center justify-between py-4 border-b border-border last:border-0"
                        >
                            <div>
                                <p className="text-sm font-semibold">{transaction.description}</p>
                                <p className="text-xs text-gray-400 mt-1">
                                    {formatDate(transaction.date, lang)}
                                </p>
                            </div>
                            <div className="text-right">
                                <p
                                    className={`font-bold ${transaction.amount < 0 ? 'text-gray-700' : 'text-success'}`}
                                >
                                    {transaction.amount > 0 ? '+' : '−'}
                                    {formatVnd(Math.abs(transaction.amount))}
                                </p>
                                <StatusBadge
                                    status={transaction.status}
                                    label={
                                        transaction.status === 'released'
                                            ? lang === 'en'
                                                ? 'Released'
                                                : 'Đã giải ngân'
                                            : lang === 'en'
                                              ? 'Completed'
                                              : 'Hoàn tất'
                                    }
                                />
                            </div>
                        </div>
                    ))}
                </div>
            </div>

            <Modal
                open={withdrawOpen}
                onClose={() => setWithdrawOpen(false)}
                title={lang === 'en' ? 'Withdrawal request' : 'Yêu cầu rút tiền'}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setWithdrawOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            disabled={
                                !withdrawAmount ||
                                Number(withdrawAmount) <= 0 ||
                                Number(withdrawAmount) > AVAILABLE_BALANCE_VND ||
                                !withdrawBank ||
                                !withdrawAccountNumber.trim()
                            }
                            onClick={() => {
                                setWithdrawOpen(false)
                                setWithdrawAmount('')
                                setWithdrawBank('')
                                setWithdrawAccountNumber('')
                                showToast({
                                    type: 'success',
                                    message:
                                        lang === 'en'
                                            ? 'Withdrawal request submitted for Admin review.'
                                            : 'Đã gửi yêu cầu rút tiền để Admin kiểm tra.',
                                })
                            }}
                        >
                            {lang === 'en' ? 'Submit Request' : 'Gửi yêu cầu'}
                        </Button>
                    </>
                }
            >
                <AlertBanner
                    type="info"
                    message={
                        lang === 'en'
                            ? 'Admin will verify the request before transferring funds to your bank account.'
                            : 'Admin sẽ kiểm tra yêu cầu trước khi chuyển tiền vào tài khoản ngân hàng.'
                    }
                />
                <div className="mt-4 space-y-4">
                    <Input
                        label={lang === 'en' ? 'Amount (VND)' : 'Số tiền (VND)'}
                        type="number"
                        min="10000"
                        max={AVAILABLE_BALANCE_VND}
                        step="1000"
                        value={withdrawAmount}
                        onChange={(e) => setWithdrawAmount(e.target.value)}
                        placeholder="0"
                        suffix="VND"
                        helper={`${lang === 'en' ? 'Available' : 'Khả dụng'}: ${formatVnd(AVAILABLE_BALANCE_VND)}`}
                    />
                    <Select
                        label={lang === 'en' ? 'Bank' : 'Ngân hàng'}
                        value={withdrawBank}
                        onChange={(e) => setWithdrawBank(e.target.value)}
                        options={[
                            {
                                value: '',
                                label: lang === 'en' ? 'Select a bank' : 'Chọn ngân hàng',
                            },
                            ...VIETNAMESE_BANKS,
                        ]}
                    />
                    <Input
                        label={lang === 'en' ? 'Account number' : 'Số tài khoản'}
                        inputMode="numeric"
                        value={withdrawAccountNumber}
                        onChange={(e) =>
                            setWithdrawAccountNumber(e.target.value.replace(/\D/g, ''))
                        }
                        placeholder={lang === 'en' ? 'Enter account number' : 'Nhập số tài khoản'}
                    />
                </div>
            </Modal>
        </SellerLayout>
    )
}

// ─── Seller Product List ───────────────────────────────────────────────────────
/**
 * Danh sách sản phẩm của người bán.
 * Chức năng: Hiển thị danh sách sản phẩm của seller, hỗ trợ tìm kiếm, lọc theo trạng thái (Active, Draft, Inactive) và xóa sản phẩm.
 * Luồng & Xử lý:
 * - Khi seller có ID dạng UUID, ưu tiên nạp /products/seller/{sellerId} để lấy đầy đủ cả sản phẩm DRAFT lẫn ACTIVE.
 * - Hỗ trợ nạp và kết hợp các sản phẩm đã tạo cục bộ (localStorage 'nexus_seller_created_products') giúp sản phẩm vừa tạo xuất hiện ngay lập tức trong phiên làm việc.
 */
export function SellerProductListPage() {
    const { lang, navigate, showToast, user } = useApp()
    const [search, setSearch] = useState('')
    const [statusFilter, setStatusFilter] = useState<ProductStatus | 'all'>('all')
    const [deleteId, setDeleteId] = useState<string | null>(null)
    const [refreshKey, setRefreshKey] = useState(0)

    // Kiểm tra xem seller có ID dạng UUID hợp lệ từ backend hay không
    const isUuid = Boolean(
        user?.id &&
            /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user.id),
    )
    const productUrl = isUuid
        ? `/products/seller/${user?.id}?v=${refreshKey}`
        : `/products?v=${refreshKey}`

    // Tải danh sách sản phẩm của người bán từ Catalog Service kết nối DB Supabase
    const { data: prodEnvelope, loading: loadingProducts } = useLoad<Envelope<ApiProduct[]> | ApiProduct[]>(productUrl)

    /**
     * Chức năng: Dọn sạch hoàn toàn các bản ghi rác/mock lưu tạm từ các phiên bản cũ trong localStorage.
     * Quy tắc & lý do: Đảm bảo người dùng chỉ tương tác với dữ liệu thật trong cơ sở dữ liệu Supabase.
     */
    useEffect(() => {
        try {
            localStorage.removeItem('nexus_seller_created_products')
        } catch {}
    }, [])

    /**
     * Chức năng: Ánh xạ danh sách sản phẩm thật 100% của người bán từ Catalog backend.
     * Quy tắc: Chuẩn hóa bóc tách cả ApiResponse { data: [...] } và mảng trần; không dùng mock hay localStorage.
     */
    const rawList = (prodEnvelope as any)?.data ?? prodEnvelope
    const apiProducts = Array.isArray(rawList)
        ? rawList.map((p: ApiProduct) => ({
              id: p.id,
              name: p.name,
              nameVi: p.name,
              price: p.skus?.[0]?.price ?? 0,
              originalPrice: p.skus?.[0]?.originalPrice ?? null,
              sku: p.skus?.[0]?.skuCode ?? '',
              // Lấy tồn kho thực tế: ưu tiên từ SKU API, nếu Catalog Service chưa có trường stock thì tra cứu cache người bán đã nhập hoặc trả về 0; không hardcode 10
              stock: (p.skus?.[0] as any)?.stock ?? getProductStock(p.skus?.[0]?.skuCode, p.id) ?? 0,
              images:
                  p.images && p.images.length > 0
                      ? p.images.map((img) => img.url)
                      : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
              status: (p.status ? p.status.toLowerCase() : 'draft') as ProductStatus,
          }))
        : []

    // Sử dụng trực tiếp danh sách sản phẩm thật từ Catalog Service
    const combinedProducts = apiProducts

    const statusLabel = (s: string) =>
        ({ draft: tr(lang, 'draft'), active: tr(lang, 'active'), inactive: tr(lang, 'inactive') })[
            s
        ] || s
    const filtered = combinedProducts.filter(
        (p) =>
            (statusFilter === 'all' || p.status === statusFilter) &&
            (!search || p.name.toLowerCase().includes(search.toLowerCase())),
    )

    return (
        <SellerLayout title={tr(lang, 'products')}>
            <div className="flex items-center justify-between mb-5">
                <div className="flex gap-3">
                    <Input
                        icon={
                            <svg
                                width="14"
                                height="14"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <circle cx="11" cy="11" r="8" />
                                <line x1="21" y1="21" x2="16.65" y2="16.65" />
                            </svg>
                        }
                        placeholder={lang === 'en' ? 'Search products...' : 'Tìm sản phẩm...'}
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        className="w-64"
                    />
                    <select
                        value={statusFilter}
                        onChange={(e) => setStatusFilter(e.target.value as any)}
                        className="nexus-input py-2 text-sm w-36"
                    >
                        <option value="all">{tr(lang, 'all')}</option>
                        <option value="active">{tr(lang, 'active')}</option>
                        <option value="draft">{tr(lang, 'draft')}</option>
                        <option value="inactive">{tr(lang, 'inactive')}</option>
                    </select>
                </div>
                <Button
                    variant="primary"
                    icon={<Plus size={14} />}
                    onClick={() => navigate('seller-create-product')}
                >
                    {tr(lang, 'createProduct')}
                </Button>
            </div>

            <div className="nexus-card">
                {loadingProducts ? (
                    <div className="py-12 text-center text-gray-400">
                        <div
                            className="spinner-border text-primary mb-2"
                            style={{ color: '#ee4d2d' }}
                            role="status"
                        >
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p>
                            {lang === 'en'
                                ? 'Loading products...'
                                : 'Đang tải danh sách sản phẩm...'}
                        </p>
                    </div>
                ) : filtered.length === 0 ? (
                    <div className="p-8">
                        <EmptyState
                            icon={<Package size={20} />}
                            title={tr(lang, 'noData')}
                            action={
                                <Button
                                    variant="primary"
                                    onClick={() => navigate('seller-create-product')}
                                >
                                    {tr(lang, 'createProduct')}
                                </Button>
                            }
                        />
                    </div>
                ) : (
                    <DataTable
                        columns={[
                            {
                                key: 'image',
                                label: '',
                                width: '60px',
                                render: (r: any) => (
                                    <img
                                        src={r.images[0]}
                                        alt=""
                                        className="w-10 h-10 object-cover rounded-lg"
                                    />
                                ),
                            },
                            {
                                key: 'name',
                                label: lang === 'en' ? 'Product' : 'Sản phẩm',
                                render: (r: any) => (
                                    <div>
                                        <p className="font-semibold text-sm line-clamp-1">
                                            {lang === 'vi' ? r.nameVi : r.name}
                                        </p>
                                        <p className="text-xs text-gray-400">{r.sku}</p>
                                    </div>
                                ),
                            },
                            {
                                key: 'price',
                                label: tr(lang, 'price'),
                                render: (r: any) => (
                                    <div>
                                        <div className="font-bold text-primary">
                                            {formatCurrency(r.price, lang)}
                                        </div>
                                        {r.originalPrice && r.originalPrice > r.price && (
                                            <div className="flex items-center gap-1 text-[11px] text-gray-400">
                                                <span className="line-through">{formatCurrency(r.originalPrice, lang)}</span>
                                                <span className="text-amber-600 font-medium">
                                                    -{Math.round(((r.originalPrice - r.price) / r.originalPrice) * 100)}%
                                                </span>
                                            </div>
                                        )}
                                        {r.originalPrice && r.originalPrice < r.price && (
                                            <div className="text-[10px] text-gray-400">
                                                {lang === 'en' ? 'Cost' : 'Vốn'}: {formatCurrency(r.originalPrice, lang)}
                                            </div>
                                        )}
                                    </div>
                                ),
                            },
                            {
                                key: 'stock',
                                label: tr(lang, 'stock'),
                                render: (r: any) => (
                                    <span
                                        className={
                                            r.stock <= 3 ? 'text-danger font-bold' : 'text-gray-700'
                                        }
                                    >
                                        {r.stock}
                                    </span>
                                ),
                            },
                            {
                                key: 'status',
                                label: tr(lang, 'status'),
                                render: (r: any) => (
                                    <StatusBadge status={r.status} label={statusLabel(r.status)} />
                                ),
                            },
                            {
                                key: 'actions',
                                label: tr(lang, 'actions'),
                                render: (r: any) => (
                                    <div className="flex gap-1">
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                navigate('seller-edit-product', { id: r.id })
                                            }}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-500"
                                        >
                                            <Pencil size={14} />
                                        </button>
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                setDeleteId(r.id)
                                            }}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger"
                                        >
                                            <Trash2 size={14} />
                                        </button>
                                    </div>
                                ),
                            },
                        ]}
                        data={filtered}
                        rowKey={(r) => r.id}
                        onRowClick={(r) => navigate('seller-edit-product', { id: r.id })}
                    />
                )}
            </div>

            <ConfirmModal
                open={!!deleteId}
                onClose={() => setDeleteId(null)}
                onConfirm={async () => {
                    if (!deleteId) return
                    try {
                        await api(`/products/${deleteId}`, { method: 'DELETE' })
                        clearLoadCache()
                        showToast({
                            type: 'success',
                            message:
                                lang === 'en' ? 'Product deleted.' : 'Đã xóa sản phẩm thành công.',
                        })
                    } catch (err) {
                        showToast({
                            type: 'error',
                            message:
                                errorMessage(err) ||
                                (lang === 'en' ? 'Failed to delete product.' : 'Xóa sản phẩm thất bại.'),
                        })
                    }
                    setRefreshKey((k) => k + 1)
                    setDeleteId(null)
                }}
                title={tr(lang, 'confirmDeleteTitle')}
                message={tr(lang, 'confirmDeleteMsg')}
                danger
            />
        </SellerLayout>
    )
}

// ─── Create / Edit Product ─────────────────────────────────────────────────────
/**
 * Tạo hoặc chỉnh sửa sản phẩm người bán.
 * Chức năng: Thu thập thông tin sản phẩm (tên, giá, SKU, kho hàng, danh mục, trạng thái), gọi API Catalog Service
 * và lưu trữ dự phòng vào localStorage để sản phẩm hiển thị ngay trên danh sách người bán kể cả khi thao tác bằng tài khoản Demo.
 */
export function CreateProductPage() {
    const { lang, page, navigate, showToast, pageParams } = useApp()
    const searchId = typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('id') : null
    const editProductId = (pageParams?.id as string) || (searchId as string) || undefined
    const isEdit = Boolean(editProductId) || page === 'seller-edit-product' || page === 'seller-update-product'
    const [saving, setSaving] = useState(false)
    const [loadingDetail, setLoadingDetail] = useState(false)
    const [name, setName] = useState('')
    // Giá bán niêm yết trên sàn (Selling price đến tay khách hàng)
    const [price, setPrice] = useState('')
    // Giá gốc / Giá nhập từ doanh nghiệp (Original / Cost price)
    const [originalPrice, setOriginalPrice] = useState('')
    const [stock, setStock] = useState('')
    // Mã SKU chuẩn doanh nghiệp (Stock Keeping Unit định danh sản phẩm & tồn kho)
    const [sku, setSku] = useState('')
    const [enableAuction, setEnableAuction] = useState(false)
    const [nameError, setNameError] = useState('')
    const [categoryId, setCategoryId] = useState('')

    // Tải danh mục thực tế từ Catalog Service (/api/v1/categories)
    const { data: catEnvelope } = useLoad<Envelope<ApiCategory[]> | ApiCategory[]>('/categories')
    const rawCats = (catEnvelope as any)?.data ?? catEnvelope
    const categoriesList: ApiCategory[] = Array.isArray(rawCats) ? rawCats : []

    // Tự động gán categoryId ban đầu từ danh mục đầu tiên của Catalog Service khi tải xong nếu chưa có
    useEffect(() => {
        if (categoriesList.length > 0 && !categoryId) {
            setCategoryId(categoriesList[0].id)
        }
    }, [categoriesList, categoryId])

    /**
     * Chức năng: Khi ở chế độ Chỉnh sửa (editProductId hợp lệ), tải dữ liệu sản phẩm từ Catalog backend
     * Đầu vào: editProductId từ pageParams.id
     * Kết quả: Điền tự động tên, giá bán, giá vốn, mã SKU, tồn kho, danh mục và cờ auctionEnabled vào form
     */
    useEffect(() => {
        if (!editProductId) return
        let active = true
        setLoadingDetail(true)
        api<any>(`/products/${editProductId}`)
            .then((res) => {
                if (!active) return
                const data = res?.data ?? res
                if (data) {
                    setName(data.name || '')
                    if (data.categoryId) setCategoryId(data.categoryId)
                    if (data.auctionEnabled !== undefined) setEnableAuction(Boolean(data.auctionEnabled))
                    const skuObj = data.skus?.[0]
                    if (skuObj) {
                        setPrice(skuObj.price !== undefined ? String(skuObj.price) : '')
                        setOriginalPrice(skuObj.originalPrice !== undefined ? String(skuObj.originalPrice) : '')
                        setSku(skuObj.skuCode || '')
                        const loadedStock = skuObj.stock ?? getProductStock(skuObj.skuCode, editProductId) ?? 0
                        setStock(String(loadedStock))
                    }
                }
            })
            .catch((err) => {
                console.warn('Failed to load product detail for edit:', err)
                showToast({
                    type: 'error',
                    message: lang === 'en' ? 'Unable to load product for editing.' : 'Không thể tải thông tin sản phẩm để chỉnh sửa.',
                })
            })
            .finally(() => {
                if (active) setLoadingDetail(false)
            })
        return () => {
            active = false
        }
    }, [editProductId])

    /**
     * Chức năng: Lưu sản phẩm mới hoặc cập nhật sản phẩm đã có vào Catalog Service backend.
     * Đầu vào: Tên sản phẩm, giá, số lượng, SKU, danh mục, cho phép đấu giá.
     * Kết quả: Tạo hoặc cập nhật bản ghi Product trong nexus_catalog, lưu UUID thật vào cache và chuyển trang.
     * Quy tắc & lý do:
     * - categoryId bắt buộc phải là UUID thật trong bảng categories của PostgreSQL.
     * - Nếu API backend thất bại, hiển thị toast thông báo lỗi rõ ràng và không điều hướng; không tạo thành công giả.
     */
    const save = async () => {
        if (!name) {
            setNameError(tr(lang, 'required'))
            return
        }
        setSaving(true)
        setNameError('')

        const generatedSku = sku.trim() || `SKU-${Date.now().toString().slice(-6)}`
        const numericPrice = Number(price) || 100
        // Giá gốc/nhập do doanh nghiệp quy định; nếu không nhập thì dùng luôn giá bán
        const numericOriginalPrice = Number(originalPrice) > 0 ? Number(originalPrice) : numericPrice

        // Ràng buộc Min Price: Seller không được đặt giá bán thấp hơn giá vốn/giá nhập tối thiểu
        const minPriceCheck = validateMinPrice(numericPrice, numericOriginalPrice, lang)
        if (!minPriceCheck.valid) {
            showToast({
                type: 'error',
                message: minPriceCheck.message || (lang === 'en' ? 'Selling price cannot be lower than cost price.' : 'Giá bán không được thấp hơn giá nhập tối thiểu.'),
            })
            setSaving(false)
            return
        }

        // Chức năng: Lưu trữ chính xác số lượng tồn kho do người bán nhập; nếu bỏ trống thì mặc định là 0, không tự động đổi thành 10
        const numericStock = stock.trim() !== '' ? Math.max(0, parseInt(stock, 10) || 0) : 0
        setProductStock(generatedSku, numericStock)
        const selectedCatId = categoryId || categoriesList[0]?.id || '00000000-0000-0000-0000-000000000001'

        const payload = {
            name,
            description: name,
            categoryId: selectedCatId,
            auctionEnabled: enableAuction,
            skus: [
                {
                    skuCode: generatedSku,
                    price: numericPrice,
                    originalPrice: numericOriginalPrice,
                    status: 'ACTIVE',
                },
            ],
            images: [
                {
                    url: 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600&h=600&fit=crop&auto=format',
                    sortOrder: 0,
                },
            ],
        }

        try {
            if (isEdit && editProductId) {
                // Chế độ Cập nhật sản phẩm có sẵn: Gọi PUT /products/{id}
                await api<any>(`/products/${editProductId}`, {
                    method: 'PUT',
                    body: JSON.stringify(payload),
                })
                setProductStock(editProductId, numericStock)
                setProductStock(generatedSku, numericStock)
            } else {
                // Chế độ Tạo mới sản phẩm: Gọi POST /products
                const res = await api<any>('/products', {
                    method: 'POST',
                    body: JSON.stringify(payload),
                })
                if (res?.data?.id) {
                    setProductStock(res.data.id, numericStock)
                    setProductStock(generatedSku, numericStock)
                }
            }

            // Làm mới bộ nhớ đệm useLoad để các màn hình người bán hiển thị sản phẩm vừa lưu
            clearLoadCache()
        } catch (err) {
            console.warn('API save/update product failed:', err)
            showToast({
                type: 'error',
                message:
                    errorMessage(err) ||
                    (lang === 'en' ? 'Failed to save product.' : 'Không thể lưu sản phẩm lên hệ thống.'),
            })
            setSaving(false)
            return
        }

        showToast({
            type: 'success',
            message: isEdit
                ? (lang === 'en' ? 'Product updated successfully!' : 'Đã cập nhật sản phẩm thành công!')
                : (lang === 'en' ? 'Product saved!' : 'Đã lưu sản phẩm thành công!'),
        })
        navigate('seller-products')
        setSaving(false)
    }

    const pageHeading = isEdit
        ? (lang === 'en' ? 'Edit Product' : 'Chỉnh sửa sản phẩm')
        : tr(lang, 'createProduct')

    return (
        <SellerLayout title={pageHeading}>
            <Breadcrumb
                items={[
                    { label: tr(lang, 'products'), page: 'seller-products' },
                    { label: pageHeading },
                ]}
            />
            <div className="mt-5 grid grid-cols-[1fr_300px] gap-6">
                <div className="space-y-5">
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Basic Information' : 'Thông tin cơ bản'}
                        </h3>
                        <div className="space-y-4">
                            <Input
                                label={`${tr(lang, 'productName')} (EN)`}
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                error={nameError}
                            />
                            <Input
                                label={`${tr(lang, 'productName')} (VI)`}
                                placeholder={
                                    lang === 'en' ? 'Vietnamese name...' : 'Tên tiếng Việt...'
                                }
                            />
                            <Textarea label={`${tr(lang, 'productDescription')} (EN)`} rows={4} />
                            <Textarea label={`${tr(lang, 'productDescription')} (VI)`} rows={4} />
                        </div>
                    </div>
                    <div className="nexus-card p-5">
                        <div className="flex items-center justify-between mb-4">
                            <h3 className="font-bold">
                                {lang === 'en' ? 'Pricing & SKU Inventory' : 'Quản lý Giá & SKU Tồn kho'}
                            </h3>
                            <span className="text-xs text-gray-500 bg-gray-100 px-2.5 py-1 rounded-full">
                                {lang === 'en' ? 'Enterprise Dynamic Pricing' : 'Giá động theo doanh nghiệp'}
                            </span>
                        </div>
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                            <Input
                                label={lang === 'en' ? 'Original / Cost Price ($)' : 'Giá gốc / Giá nhập ($)'}
                                type="number"
                                value={originalPrice}
                                onChange={(e) => setOriginalPrice(e.target.value)}
                                placeholder="0.00"
                            />
                            <Input
                                label={lang === 'en' ? 'Selling Price ($)' : 'Giá bán trên sàn ($)'}
                                type="number"
                                value={price}
                                onChange={(e) => setPrice(e.target.value)}
                                placeholder="0.00"
                            />
                            <Input
                                label={lang === 'en' ? 'Initial Stock' : 'Số lượng tồn kho'}
                                type="number"
                                value={stock}
                                onChange={(e) => setStock(e.target.value)}
                                placeholder="0"
                            />
                        </div>

                        {/* Phân tích chênh lệch giá động giữa Giá bán và Giá nhập */}
                        {Number(price) > 0 && Number(originalPrice) > 0 && (
                            <div className="mt-3 p-3 rounded-lg bg-gray-50 border border-gray-100 flex items-center justify-between text-xs">
                                <span className="text-gray-600">
                                    {lang === 'en' ? 'Price Margin Analysis:' : 'Phân tích biên độ giá:'}
                                </span>
                                {Number(price) >= Number(originalPrice) ? (
                                    <span className="font-semibold text-emerald-600">
                                        {lang === 'en' ? 'Gross Markup' : 'Lợi nhuận gộp'}: +{formatCurrency(Number(price) - Number(originalPrice), lang)} (+{(((Number(price) - Number(originalPrice)) / Number(originalPrice)) * 100).toFixed(1)}%)
                                    </span>
                                ) : (
                                    <span className="font-semibold text-amber-600">
                                        {lang === 'en' ? 'Promotional Discount' : 'Chiết khấu khuyến mãi'}: -{formatCurrency(Number(originalPrice) - Number(price), lang)} (-{(((Number(originalPrice) - Number(price)) / Number(originalPrice)) * 100).toFixed(1)}%)
                                    </span>
                                )}
                            </div>
                        )}

                        <div className="mt-4">
                            <Input
                                label={lang === 'en' ? 'Enterprise SKU Code' : 'Mã SKU Doanh nghiệp'}
                                value={sku}
                                onChange={(e) => setSku(e.target.value)}
                                placeholder="VD: TGDD-IP15PM-256G-TITAN hoặc MSI-MOD15-F1MG"
                            />
                            <p className="text-[11px] text-gray-500 mt-1">
                                {lang === 'en'
                                    ? 'Stock Keeping Unit (SKU) must be assigned by the enterprise/merchant to identify variants and accurately track warehouse inventory.'
                                    : 'Mã SKU chuẩn được cấp từ doanh nghiệp để định danh biến thể và tra cứu chính xác số lượng tồn kho theo thời gian thực.'}
                            </p>
                        </div>
                    </div>
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">{tr(lang, 'uploadImages')}</h3>
                        <div className="border-2 border-dashed border-border rounded-xl p-8 text-center text-gray-400 hover:border-primary hover:bg-primary-light/20 transition-colors cursor-pointer">
                            <Camera size={24} className="mx-auto mb-2 text-gray-500" />
                            <p className="text-sm font-medium">{tr(lang, 'dragDrop')}</p>
                            <p className="text-xs mt-1">
                                {lang === 'en'
                                    ? 'PNG, JPG up to 5MB · Max 5 images'
                                    : 'PNG, JPG tối đa 5MB · Tối đa 5 ảnh'}
                            </p>
                        </div>
                    </div>
                </div>
                <div className="space-y-4">
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Publish Settings' : 'Cài đặt đăng bán'}
                        </h3>
                        <div className="space-y-3">
                            <div>
                                <label className="text-xs font-semibold uppercase tracking-wider text-gray-500 block mb-1.5">
                                    {tr(lang, 'status')}
                                </label>
                                <div className="flex items-center gap-2">
                                    <StatusBadge status="draft" label={tr(lang, 'draft')} />
                                    <span className="text-xs text-gray-500">
                                        {lang === 'en' ? 'Requires Admin approval' : 'Chờ Admin duyệt'}
                                    </span>
                                </div>
                            </div>
                            <p className="text-xs text-gray-400 leading-relaxed mt-1">
                                {lang === 'en'
                                    ? 'Requires Admin approval to publish. You can still run an auction immediately if "Allow Auction" is enabled.'
                                    : 'Cần Admin phê duyệt để mở bán. Vẫn có thể đấu giá ngay nếu bật "Cho phép đấu giá".'}
                            </p>
                        </div>
                    </div>
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">{tr(lang, 'category')}</h3>
                        <Select
                            label=""
                            value={categoryId}
                            onChange={(e) => setCategoryId(e.target.value)}
                            options={
                                categoriesList.length > 0
                                    ? categoriesList.map((cat) => ({
                                          value: cat.id,
                                          label: cat.nameVi || cat.name,
                                      }))
                                    : [
                                          {
                                              value: '00000000-0000-0000-0000-000000000001',
                                              label: 'Electronics / Điện tử',
                                          },
                                          {
                                              value: '22222222-2222-2222-2222-222222222222',
                                              label: 'Fashion / Thời trang',
                                          },
                                      ]
                            }
                        />
                    </div>
                    <div className="nexus-card p-5">
                        <Toggle
                            label={tr(lang, 'enableAuction')}
                            checked={enableAuction}
                            onChange={setEnableAuction}
                        />
                        {enableAuction && (
                            <p className="text-xs text-gray-500 mt-2">
                                {lang === 'en'
                                    ? 'This product can be listed in auction sessions.'
                                    : 'Sản phẩm này có thể được đưa vào phiên đấu giá.'}
                            </p>
                        )}
                    </div>
                    <Button
                        variant="primary"
                        size="lg"
                        className="w-full"
                        loading={saving || loadingDetail}
                        onClick={save}
                    >
                        {isEdit
                            ? (lang === 'en' ? 'Update Product' : 'Cập nhật sản phẩm')
                            : tr(lang, 'save')}
                    </Button>
                    <Button
                        variant="outline"
                        size="lg"
                        className="w-full"
                        onClick={() => navigate('seller-products')}
                    >
                        {tr(lang, 'cancel')}
                    </Button>
                </div>
            </div>
        </SellerLayout>
    )
}

// ─── Seller Auction List ───────────────────────────────────────────────────────
/**
 * Chức năng: Màn hình danh sách phiên đấu giá của Người bán (Seller Auction List).
 *            Hiển thị các phiên đấu giá do chính người bán quản lý kèm trạng thái vòng đời
 *            (Chờ duyệt, Đã lên lịch, Đang diễn ra, Đã kết thúc, Đã hủy).
 *            Cung cấp thao tác chỉnh sửa thông số phiên trước giờ mở và hủy phiên theo quyền.
 * Đầu vào: user context, createdAuctions, auctionReviews, query parameters.
 * Kết quả: Bảng dữ liệu phiên đấu giá chuẩn hóa; cho phép chuyển trang chỉnh sửa hoặc mở modal hủy phiên.
 * Luồng xử lý:
 *   1. Tải danh sách các phiên đấu giá của người bán hiện tại (sellerId).
 *   2. Hợp nhất dữ liệu mới từ createdAuctions và trạng thái xét duyệt auctionReviews.
 *   3. Lọc theo tab trạng thái được chọn (Tất cả, Chờ duyệt, Đang diễn ra, Lên lịch, Đã kết thúc, Đã hủy).
 *   4. Khi người bán bấm nút sửa (Pencil): chỉ cho phép nếu phiên chưa mở (startTime > now), điều hướng tới màn hình seller-create-auction kèm ID phiên.
 *   5. Khi người bán bấm nút hủy (Trash2): mở modal xác nhận yêu cầu nhập lý do hủy, sau đó gọi auctionsApi.cancelAuction.
 * Quy tắc & lý do: Người bán chỉ có quyền hủy/sửa phiên trước khi phiên bắt đầu; khi phiên đã ACTIVE thì chỉ có Quản trị viên mới được can thiệp cưỡng chế.
 * Lỗi & giới hạn: Hiển thị thông báo toast lỗi khi thao tác hủy không thành công hoặc người bán vi phạm thời gian mở phiên.
 */
export function SellerAuctionListPage() {
    const { lang, navigate, showToast, auctionReviews, createdAuctions, user } = useApp()
    const [tab, setTab] = useState('all')
    const [cancelOpen, setCancelOpen] = useState(false)
    const [cancellingAuctionId, setCancellingAuctionId] = useState<string | null>(null)
    const [cancelReason, setCancelReason] = useState('')
    const [cancelReasonError, setCancelReasonError] = useState('')
    const [cancelling, setCancelling] = useState(false)
    const [reviewedAuction, setReviewedAuction] = useState<Auction | null>(null)
    const [detailAuction, setDetailAuction] = useState<Auction | null>(null)
    const [detailLoading, setDetailLoading] = useState(false)
    const detailRequestId = useRef(0)
    const [liveAuctions, setLiveAuctions] = useState<Auction[]>([])

    // Tải danh sách phiên đấu giá từ API Adapter (có tự động fallback sang localStorage/mock)
    const currentSellerId = user?.id || 'u2'

    /**
     * Chức năng: Nạp danh sách sản phẩm của Seller vào cache để auction hiển thị đúng tên/SKU thật.
     * Đầu vào: Danh sách sản phẩm từ Catalog Service (useLoad /products/seller/{sellerId}).
     * Luồng: Mỗi sản phẩm hợp lệ được lưu vào auctionProductCache với key = productId (UUID).
     *        validateAndNormalizeAuction sau đó tra cache này thay vì hiển thị "Auction Product".
     */
    const isSellerUuid = Boolean(
        user?.id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user.id),
    )
    const { data: sellerProductsEnvRaw } = useLoad<Envelope<ApiProduct[]> | ApiProduct[]>(
        isSellerUuid ? `/products/seller/${user?.id}` : '/products',
    )
    // Đồng bộ danh sách sản phẩm vào cache để auction list hiển thị đúng tên/SKU thật
    useEffect(() => {
        const rawProducts = (sellerProductsEnvRaw as Envelope<ApiProduct[]> | undefined)?.data ?? sellerProductsEnvRaw
        if (!Array.isArray(rawProducts)) return
        rawProducts.forEach((p) => {
            if (!p?.id || !p?.name) return
            cacheAuctionProduct(p.id, {
                name: p.name,
                nameVi: p.name,
                sku: p.skus?.[0]?.skuCode,
                images: p.images?.map((img) => img.url),
            })
        })
    }, [sellerProductsEnvRaw])

    const refreshAuctions = async () => {
        try {
            const res = await auctionsApi.getAuctions({
                sellerId: currentSellerId,
                sellerName: user?.fullName,
                excludeDemoData: true,
                extraAuctions: createdAuctions,
                reviews: auctionReviews,
                limit: 100,
            })
            setLiveAuctions(res.items)
        } catch {
            // Khi API lỗi ngoài nhóm fallback, chỉ giữ dữ liệu người dùng vừa tạo trong Context; không dựng fixture mặc định.
            const fallback = createdAuctions
                .filter((a) => a.sellerId === currentSellerId)
                .map((a) => ({
                    ...a,
                    status: (auctionReviews[a.id]?.status as any) || a.status,
                }))
            setLiveAuctions(fallback)
        }
    }

    useEffect(() => {
        refreshAuctions()
        // Đồng bộ lại status với Auction Service để phiên quá giờ không bị giữ nhãn Scheduled trên trang đang mở.
        const refreshTimer = window.setInterval(refreshAuctions, 5000)
        return () => window.clearInterval(refreshTimer)
    }, [currentSellerId, createdAuctions, auctionReviews])

    const sellerAuctions = liveAuctions.map((auction) => ({
        ...auction,
        status: (auctionReviews[auction.id]?.status as any) || auction.status,
    }))

    const filtered = tab === 'all' ? sellerAuctions : sellerAuctions.filter((a) => a.status === tab)

    /** Tải snapshot mới nhất để Seller xem thông số và lịch sử bid nếu Auction Service có trả dữ liệu đó. */
    const openAuctionDetails = async (auction: Auction) => {
        const requestId = ++detailRequestId.current
        setDetailAuction(auction)
        setDetailLoading(true)
        try {
            const details = await auctionsApi.getAuctionById(auction.id, createdAuctions, auctionReviews)
            if (requestId === detailRequestId.current) setDetailAuction(details)
        } catch {
            // Giữ dữ liệu hàng hiện tại nếu endpoint chi tiết tạm lỗi; không thay bằng dữ liệu giả.
            if (requestId === detailRequestId.current) setDetailAuction(auction)
        } finally {
            if (requestId === detailRequestId.current) setDetailLoading(false)
        }
    }

    const statusLabel = (s: string) =>
        ({
            pending_approval: lang === 'en' ? 'Pending approval' : 'Chờ Admin duyệt',
            scheduled: tr(lang, 'statusScheduled'),
            active: tr(lang, 'statusActive'),
            paused: lang === 'en' ? 'Paused by Admin' : 'Được Admin tạm dừng',
            ended: tr(lang, 'statusEnded'),
            settled: tr(lang, 'statusSettled'),
            cancelled: tr(lang, 'statusCancelled'),
            failed: tr(lang, 'statusFailed'),
            rejected: lang === 'en' ? 'Rejected' : 'Bị từ chối',
        })[s] || s

    // Xử lý xác nhận hủy phiên đấu giá từ người bán
    const handleConfirmCancel = async () => {
        if (!cancellingAuctionId) return
        if (!cancelReason.trim()) {
            setCancelReasonError(
                lang === 'en' ? 'Cancellation reason is required' : 'Vui lòng nhập lý do hủy phiên',
            )
            return
        }

        setCancelling(true)
        try {
            await auctionsApi.cancelAuction(cancellingAuctionId, cancelReason.trim(), 'seller')
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Auction cancelled successfully.'
                        : 'Đã hủy phiên đấu giá thành công.',
            })
            setCancelOpen(false)
            setCancellingAuctionId(null)
            setCancelReason('')
            setCancelReasonError('')
            await refreshAuctions()
        } catch (err: any) {
            showToast({
                type: 'error',
                message: err?.message || (lang === 'en' ? 'Failed to cancel auction' : 'Không thể hủy phiên đấu giá'),
            })
        } finally {
            setCancelling(false)
        }
    }

    return (
        <SellerLayout title={tr(lang, 'auctions')}>
            <div className="flex items-center justify-between mb-5">
                <Tabs
                    tabs={[
                        { id: 'all', label: tr(lang, 'all') },
                        {
                            id: 'pending_approval',
                            label: lang === 'en' ? 'Pending approval' : 'Chờ duyệt',
                            count: sellerAuctions.filter((a) => a.status === 'pending_approval').length,
                        },
                        {
                            id: 'scheduled',
                            label: tr(lang, 'statusScheduled'),
                            count: sellerAuctions.filter((a) => a.status === 'scheduled').length,
                        },
                        {
                            id: 'active',
                            label: tr(lang, 'statusActive'),
                            count: sellerAuctions.filter((a) => a.status === 'active').length,
                        },
                        { id: 'ended', label: tr(lang, 'statusEnded') },
                        { id: 'cancelled', label: tr(lang, 'statusCancelled') },
                    ]}
                    active={tab}
                    onChange={setTab}
                />
                <Button
                    variant="primary"
                    icon={<Plus size={14} />}
                    onClick={() => navigate('seller-create-auction')}
                >
                    {tr(lang, 'createAuction')}
                </Button>
            </div>
            <div className="nexus-card">
                <DataTable
                    columns={[
                        {
                            key: 'product',
                            label: lang === 'en' ? 'Product' : 'Sản phẩm',
                            render: (r: any) => (
                                <div className="flex items-center gap-3">
                                    <img
                                        src={r.product?.images?.[0] || 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=100'}
                                        alt=""
                                        className="w-10 h-10 object-cover rounded-lg"
                                    />
                                    <div>
                                        <p className="text-sm font-semibold line-clamp-1">
                                            {lang === 'vi' ? r.product?.nameVi || r.product?.name : r.product?.name}
                                        </p>
                                        <p className="text-xs text-gray-400">SKU: {r.product?.sku || 'N/A'}</p>
                                    </div>
                                </div>
                            ),
                        },
                        {
                            key: 'startingPrice',
                            label: tr(lang, 'startingPrice'),
                            render: (r: any) => (
                                <span className="text-sm font-medium text-gray-700">
                                    {formatCurrency(r.startingPrice, lang)}
                                </span>
                            ),
                        },
                        {
                            key: 'currentBid',
                            label: tr(lang, 'currentBid'),
                            render: (r: any) => (
                                <span className="font-bold text-auction">
                                    {formatCurrency(r.currentBid, lang)}
                                </span>
                            ),
                        },
                        {
                            key: 'bidCount',
                            label: lang === 'en' ? 'Bids' : 'Lượt đặt',
                            render: (r: any) => <span className="font-semibold">{r.bidCount}</span>,
                        },
                        {
                            key: 'startTime',
                            label: tr(lang, 'startTime'),
                            render: (r: any) => (
                                <span className="text-xs text-gray-500">
                                    {formatDateTime(r.startTime, lang)}
                                </span>
                            ),
                        },
                        {
                            key: 'endTime',
                            label: tr(lang, 'endTime'),
                            render: (r: any) => (
                                <span className="text-xs text-gray-500">
                                    {formatDateTime(r.endTime, lang)}
                                </span>
                            ),
                        },
                        {
                            key: 'status',
                            label: tr(lang, 'status'),
                            render: (r: any) => (
                                <StatusBadge status={r.status} label={statusLabel(r.status)} />
                            ),
                        },
                        {
                            key: 'actions',
                            label: tr(lang, 'actions'),
                            render: (r: any) => {
                                const isBeforeStart = new Date(r.startTime).getTime() > Date.now()
                                const isEditable = ['pending_approval', 'scheduled', 'draft'].includes(r.status) && isBeforeStart
                                const isCancellable = ['pending_approval', 'scheduled', 'draft'].includes(r.status) && isBeforeStart

                                return (
                                    <div className="flex gap-1 items-center">
                                        <button
                                            type="button"
                                            title={tr(lang, 'viewAuctionDetails')}
                                            aria-label={tr(lang, 'viewAuctionDetails')}
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                void openAuctionDetails(r)
                                            }}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-600 hover:bg-gray-100 rounded"
                                        >
                                            <Eye size={14} />
                                        </button>
                                        {isEditable && (
                                            <button
                                                title={lang === 'en' ? 'Edit auction' : 'Chỉnh sửa phiên'}
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    navigate('seller-create-auction', { id: r.id })
                                                }}
                                                className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-primary hover:bg-primary/10 rounded"
                                            >
                                                <Pencil size={14} />
                                            </button>
                                        )}
                                        {isCancellable && (
                                            <button
                                                title={lang === 'en' ? 'Cancel auction' : 'Hủy phiên đấu giá'}
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    setCancellingAuctionId(r.id)
                                                    setCancelReason('')
                                                    setCancelReasonError('')
                                                    setCancelOpen(true)
                                                }}
                                                className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger hover:bg-red-50 rounded"
                                            >
                                                <Trash2 size={14} />
                                            </button>
                                        )}
                                        {auctionReviews[r.id] && (
                                            <Button
                                                variant="outline"
                                                size="sm"
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    setReviewedAuction(r)
                                                }}
                                            >
                                                {lang === 'en' ? 'View Result' : 'Xem kết quả'}
                                            </Button>
                                        )}
                                    </div>
                                )
                            },
                        },
                    ]}
                    data={filtered}
                    rowKey={(r) => r.id}
                />
            </div>

            {/* Modal Seller xem thông số và các mức giá đã nhận; lịch sử phụ thuộc dữ liệu do API chi tiết trả về. */}
            <Modal
                open={!!detailAuction}
                onClose={() => {
                    detailRequestId.current += 1
                    setDetailAuction(null)
                    setDetailLoading(false)
                }}
                title={tr(lang, 'auctionDetails')}
                size="lg"
            >
                {detailAuction && (
                    <div className="space-y-4">
                        <div className="flex items-start gap-3 rounded-xl bg-gray-50 p-4">
                            <img
                                src={detailAuction.product?.images?.[0] || 'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=120'}
                                alt=""
                                className="h-16 w-16 rounded-lg border object-cover"
                            />
                            <div className="min-w-0">
                                <h3 className="font-semibold text-gray-900">
                                    {lang === 'vi' ? detailAuction.product?.nameVi || detailAuction.product?.name : detailAuction.product?.name}
                                </h3>
                                <p className="mt-1 text-xs text-gray-500">
                                    SKU: <span className="font-mono text-gray-700">{detailAuction.product?.sku || 'N/A'}</span>
                                </p>
                                <div className="mt-2">
                                    <StatusBadge status={detailAuction.status} label={statusLabel(detailAuction.status)} />
                                </div>
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3 text-sm">
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'startingPrice')}</span>
                                <span className="font-semibold">{formatCurrency(detailAuction.startingPrice, lang)}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'currentBid')}</span>
                                <span className="font-bold text-auction">{formatCurrency(detailAuction.currentBid, lang)}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'minIncrement')}</span>
                                <span className="font-semibold">{formatCurrency(detailAuction.minIncrement, lang)}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'totalBidCount')}</span>
                                <span className="font-semibold">{detailAuction.bidCount}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'auctionVisibility')}</span>
                                <span className="font-semibold capitalize">{detailAuction.visibility}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'startTime')}</span>
                                <span className="font-medium">{formatDateTime(detailAuction.startTime, lang)}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">{tr(lang, 'endTime')}</span>
                                <span className="font-medium">{formatDateTime(detailAuction.endTime, lang)}</span>
                            </div>
                        </div>

                        <section>
                            <h4 className="mb-2 font-semibold">{tr(lang, 'bidHistory')}</h4>
                            {detailLoading ? (
                                <div className="flex items-center gap-2 py-3 text-sm text-gray-500" role="status">
                                    <div
                                        className="spinner-border spinner-border-sm text-primary"
                                        style={{ color: '#ee4d2d' }}
                                        role="status"
                                    >
                                        <span className="visually-hidden">Loading...</span>
                                    </div>
                                    <span>{tr(lang, 'loadingAuctionDetails')}</span>
                                </div>
                            ) : detailAuction.bids?.length ? (
                                <div className="max-h-56 overflow-y-auto rounded-lg border">
                                    <table className="w-full text-sm">
                                        <thead className="bg-gray-50 text-gray-600">
                                            <tr>
                                                <th className="p-3 text-left">{tr(lang, 'bidAmount')}</th>
                                                <th className="p-3 text-right">{tr(lang, 'bidTime')}</th>
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-gray-100">
                                            {[...detailAuction.bids]
                                                .sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())
                                                .map((bid) => (
                                                    <tr key={bid.id}>
                                                        <td className="p-3 font-semibold text-auction">{formatCurrency(bid.amount, lang)}</td>
                                                        <td className="p-3 text-right text-gray-500">{formatDateTime(bid.timestamp, lang)}</td>
                                                    </tr>
                                                ))}
                                        </tbody>
                                    </table>
                                </div>
                            ) : (
                                <p className="rounded-lg bg-gray-50 p-3 text-sm text-gray-500">
                                    {tr(lang, 'bidHistoryUnavailable')}
                                </p>
                            )}
                        </section>
                    </div>
                )}
            </Modal>

            {/* Modal Hủy phiên đấu giá của Người bán */}
            <Modal
                open={cancelOpen}
                onClose={() => {
                    setCancelOpen(false)
                    setCancellingAuctionId(null)
                    setCancelReason('')
                    setCancelReasonError('')
                }}
                title={tr(lang, 'cancelAuction')}
                size="sm"
                footer={
                    <>
                        <Button
                            variant="outline"
                            onClick={() => {
                                setCancelOpen(false)
                                setCancellingAuctionId(null)
                                setCancelReason('')
                                setCancelReasonError('')
                            }}
                        >
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="danger"
                            loading={cancelling}
                            onClick={handleConfirmCancel}
                        >
                            {tr(lang, 'confirm')}
                        </Button>
                    </>
                }
            >
                <div className="space-y-3">
                    <p className="text-sm text-gray-600">
                        {lang === 'en'
                            ? 'Are you sure you want to cancel this auction? It can only be cancelled before it officially opens.'
                            : 'Bạn có chắc chắn muốn hủy phiên đấu giá này không? Phiên chỉ có thể hủy trước giờ mở chính thức.'}
                    </p>
                    <Textarea
                        label={`${tr(lang, 'cancelReason')} *`}
                        rows={3}
                        value={cancelReason}
                        onChange={(e) => {
                            setCancelReason(e.target.value)
                            if (e.target.value.trim()) setCancelReasonError('')
                        }}
                        error={cancelReasonError}
                        placeholder={
                            lang === 'en' ? 'Reason for cancellation...' : 'Lý do hủy phiên đấu giá...'
                        }
                    />
                </div>
            </Modal>

            {/* Modal Xem kết quả phê duyệt của Admin */}
            <Modal
                open={!!reviewedAuction}
                onClose={() => setReviewedAuction(null)}
                title={lang === 'en' ? 'Auction review result' : 'Kết quả phê duyệt đấu giá'}
                size="sm"
            >
                {reviewedAuction && auctionReviews[reviewedAuction.id] && (
                    <div className="space-y-4">
                        <div className="flex items-center gap-3">
                            {auctionReviews[reviewedAuction.id]?.status === 'scheduled' ? (
                                <CheckCircle size={22} className="text-gray-500" />
                            ) : (
                                <AlertTriangle size={22} className="text-gray-500" />
                            )}
                            <div>
                                <p className="font-bold">
                                    {auctionReviews[reviewedAuction.id]?.status === 'scheduled'
                                        ? lang === 'en'
                                            ? 'Approved'
                                            : 'Đã phê duyệt'
                                        : lang === 'en'
                                          ? 'Rejected'
                                          : 'Bị từ chối'}
                                </p>
                                <p className="text-xs text-gray-400">
                                    {new Date(
                                        auctionReviews[reviewedAuction.id]?.reviewedAt || '',
                                    ).toLocaleString()}
                                </p>
                            </div>
                        </div>
                        <div className="p-3 bg-gray-50 rounded-lg">
                            <p className="text-sm font-semibold">
                                {lang === 'vi'
                                    ? reviewedAuction.product.nameVi
                                    : reviewedAuction.product.name}
                            </p>
                            <p className="text-xs text-gray-500 mt-1">
                                {lang === 'en' ? 'Starting price' : 'Giá khởi điểm'}:{' '}
                                {formatCurrency(reviewedAuction.startingPrice, lang)}
                            </p>
                        </div>
                        {auctionReviews[reviewedAuction.id]?.status === 'scheduled' ? (
                            <AlertBanner
                                type="success"
                                message={
                                    lang === 'en'
                                        ? 'Your auction was approved and scheduled. It will become visible to Buyers at the configured start time.'
                                        : 'Phiên đấu giá đã được phê duyệt và lên lịch. Phiên sẽ hiển thị cho Buyer vào thời gian bắt đầu đã thiết lập.'
                                }
                            />
                        ) : (
                            <div>
                                <p className="text-sm font-semibold mb-1">
                                    {lang === 'en' ? 'Admin reason' : 'Lý do từ Admin'}
                                </p>
                                <p className="text-sm text-gray-600">
                                    {auctionReviews[reviewedAuction.id]?.reason}
                                </p>
                                <Button
                                    variant="primary"
                                    size="sm"
                                    className="mt-4"
                                    onClick={() => {
                                        setReviewedAuction(null)
                                        navigate('seller-create-auction', {
                                            id: reviewedAuction.id,
                                        })
                                    }}
                                >
                                    {lang === 'en' ? 'Edit and Resubmit' : 'Chỉnh sửa và gửi lại'}
                                </Button>
                            </div>
                        )}
                    </div>
                )}
            </Modal>
        </SellerLayout>
    )
}

// ─── Create & Edit Auction ────────────────────────────────────────────────────────────
/**
 * Chức năng: Màn hình khởi tạo hoặc chỉnh sửa phiên đấu giá dành cho Người bán.
 *            Cung cấp biểu mẫu thiết lập sản phẩm, giá khởi điểm, bước giá, khung thời gian bắt đầu/kết thúc
 *            và chế độ hiển thị (công khai / hạn chế).
 * Đầu vào: pageParams.id (nếu chỉnh sửa phiên có sẵn), danh sách sản phẩm của người bán.
 * Kết quả: Gọi API tạo mới (auctionsApi.createAuction) hoặc cập nhật (auctionsApi.updateAuction).
 * Luồng xử lý:
 *   1. Kiểm tra tham số pageParams.id để xác định chế độ Tạo mới hoặc Chỉnh sửa.
 *   2. Nếu là Chỉnh sửa: Tải dữ liệu phiên hiện tại, điền trước vào form.
 *      - Kiểm tra điều kiện có thể sửa: Nếu phiên đã ACTIVE hoặc đã qua startTime, chặn chỉnh sửa và báo lỗi.
 *   3. Kiểm tra validation toàn diện trước khi gửi:
 *      - Sản phẩm: bắt buộc phải chọn sản phẩm hợp lệ có enableAuction.
 *      - Giá khởi điểm: số dương > 0.
 *      - Bước giá: số dương >= 1,000 VND (hoặc cấu hình tối thiểu).
 *      - Thời gian bắt đầu: định dạng hợp lệ và phải lớn hơn thời gian hiện tại (startTime > now).
 *      - Thời gian kết thúc: phải sau thời gian bắt đầu (endTime > startTime).
 *      - Thời lượng phiên: tối thiểu 60 phút (1 giờ) và tối đa 168 giờ (7 ngày).
 *   4. Gửi request qua API Adapter, lưu trữ dữ liệu bền vững và điều hướng về seller-auctions.
 * Quy tắc & lý do: Đảm bảo giao diện tuân thủ chặt chẽ API Contract và Accept Criteria của LD-S2-2.
 * Lỗi & giới hạn: Hiển thị lỗi đỏ chi tiết ngay dưới từng ô nhập liệu (inline error) và toast thông báo chung.
 */
export function CreateAuctionPage() {
    const { lang, navigate, showToast, submitAuction, pageParams, createdAuctions, user } = useApp()
    const editAuctionId = (pageParams?.id as string) || undefined
    const isEdit = Boolean(editAuctionId)

    const [saving, setSaving] = useState(false)
    const [loadingData, setLoadingData] = useState(false)
    const [notEditableError, setNotEditableError] = useState('')

    // Dữ liệu form
    const [productId, setProductId] = useState('')
    const [startingPrice, setStartingPrice] = useState('')
    const [minIncrement, setMinIncrement] = useState('1000')
    const [startTime, setStartTime] = useState('')
    const [endTime, setEndTime] = useState('')
    const [visibility, setVisibility] = useState<'public' | 'restricted'>('public')

    // Bảng lỗi validation từng trường
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

    /**
     * Chức năng: Chuẩn bị danh sách sản phẩm của người bán để chọn khi tạo phiên đấu giá.
     * Đầu vào: user context, API Catalog (/products/seller/{sellerId} hoặc /products).
     * Kết quả: availableProducts chứa danh sách sản phẩm hợp lệ 100% từ database Supabase.
     * Luồng xử lý:
     *   1. Xóa bỏ hoàn toàn dữ liệu tạm trong localStorage ('nexus_seller_created_products') để ngăn ngừa việc dùng ID giả dạng prod-...
     *   2. Gọi API Catalog Service qua useLoad để lấy danh sách sản phẩm thật từ database.
     *   3. Lọc các sản phẩm cho phép đấu giá (auctionEnabled !== false).
     *   4. Khi offline/sandbox test suite thì fallback sang PRODUCTS.
     * Quy tắc & lý do: Đảm bảo chỉ dùng ID sản phẩm (UUID) thật từ cơ sở dữ liệu Supabase, không để xảy ra lỗi Jackson deserialize UUID.
     * Lỗi & giới hạn: Tự động bind sản phẩm đầu tiên khi tải xong để tránh form bị rỗng.
     */
    useEffect(() => {
        try {
            localStorage.removeItem('nexus_seller_created_products')
        } catch {}
    }, [])

    const isSellerUuid = Boolean(
        user?.id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user.id),
    )
    const { data: sellerProductsEnvelope, loading: loadingProducts } = useLoad<Envelope<ApiProduct[]> | ApiProduct[]>(
        isSellerUuid ? `/products/seller/${user?.id}` : '/products',
    )
    const sellerProductsRaw = (sellerProductsEnvelope as Envelope<ApiProduct[]> | undefined)?.data ?? sellerProductsEnvelope

    const apiProducts = Array.isArray(sellerProductsRaw)
        ? sellerProductsRaw
            .filter((p: any) => p.auctionEnabled !== false)
            .map((product) => ({
                id: product.id,
                name: product.name,
                nameVi: product.name,
                sku: product.skus?.[0]?.skuCode || '',
                skuId: product.skus?.[0]?.id || undefined,
            }))
        : []

    const fallbackCatalog = PRODUCTS.filter((p) => p.enableAuction).map((p) => ({
        id: p.id,
        name: p.name,
        nameVi: p.nameVi,
        sku: p.sku,
        skuId: undefined as string | undefined,
    }))

    // Sử dụng 100% danh sách sản phẩm thật từ Catalog Service Supabase DB
    const availableProducts = apiProducts.length > 0 ? apiProducts : fallbackCatalog

    // Xóa productId nếu đang chứa ID rác cũ dạng prod-... hoặc không tồn tại trong danh sách
    useEffect(() => {
        if (productId && (productId.startsWith('prod-') || (availableProducts.length > 0 && !availableProducts.some((p) => p.id === productId)))) {
            setProductId('')
        }
    }, [availableProducts, productId])

    // Nạp dữ liệu khi ở chế độ chỉnh sửa phiên có sẵn
    useEffect(() => {
        if (!editAuctionId) return

        let mounted = true
        setLoadingData(true)

        const loadTargetAuction = async () => {
            try {
                let auction: Auction | null = null
                try {
                    auction = await auctionsApi.getAuctionById(editAuctionId, createdAuctions)
                } catch {
                    auction = createdAuctions.find((a) => a.id === editAuctionId) || null
                }

                if (!mounted) return

                if (!auction) {
                    showToast({
                        type: 'error',
                        message: lang === 'en' ? 'Auction not found' : 'Không tìm thấy phiên đấu giá',
                    })
                    navigate('seller-auctions')
                    return
                }

                // Kiểm tra điều kiện chỉnh sửa: chỉ cho phép khi chưa mở (startTime > now) và trạng thái scheduled/draft/pending
                const isPastStart = new Date(auction.startTime).getTime() <= Date.now()
                if (['active', 'ended', 'settled', 'cancelled'].includes(auction.status) || isPastStart) {
                    setNotEditableError(
                        lang === 'en'
                            ? 'This auction has already started or ended and cannot be edited.'
                            : 'Phiên đấu giá đã mở hoặc đã kết thúc, không thể chỉnh sửa.',
                    )
                }

                setProductId(auction.productId)
                setStartingPrice(String(auction.startingPrice))
                setMinIncrement(String(auction.minIncrement))

                // Định dạng Date string cho thẻ input datetime-local (YYYY-MM-DDTHH:mm)
                const formatForInput = (isoStr: string) => {
                    const d = new Date(isoStr)
                    if (isNaN(d.getTime())) return ''
                    const pad = (n: number) => String(n).padStart(2, '0')
                    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
                }

                setStartTime(formatForInput(auction.startTime))
                setEndTime(formatForInput(auction.endTime))
                setVisibility(auction.visibility || 'public')
            } finally {
                if (mounted) setLoadingData(false)
            }
        }

        loadTargetAuction()

        return () => {
            mounted = false
        }
    }, [editAuctionId, createdAuctions])

    // Xóa lỗi validation khi người dùng chỉnh sửa giá trị trường
    const handleFieldChange = (field: string, value: string, setter: (v: any) => void) => {
        setter(value)
        if (fieldErrors[field]) {
            setFieldErrors((prev) => {
                const updated = { ...prev }
                delete updated[field]
                return updated
            })
        }
    }

    // Kiểm tra tính hợp lệ toàn bộ form
    const validateForm = (): boolean => {
        const errors: Record<string, string> = {}

        if (!productId) {
            errors.productId =
                lang === 'en'
                    ? 'Please select a product for the auction'
                    : 'Vui lòng chọn sản phẩm cho phiên đấu giá'
        } else if (productId.startsWith('prod-')) {
            errors.productId =
                lang === 'en'
                    ? 'Selected product ID is invalid. Please choose a real product from the list.'
                    : 'Mã sản phẩm không hợp lệ. Vui lòng chọn sản phẩm thật từ danh sách.'
        }

        const startPriceNum = Number(startingPrice)
        if (!startingPrice || isNaN(startPriceNum) || startPriceNum <= 0) {
            errors.startingPrice =
                lang === 'en'
                    ? 'Starting price must be greater than 0'
                    : 'Giá khởi điểm phải lớn hơn 0'
        }

        const minIncNum = Number(minIncrement)
        if (!minIncrement || isNaN(minIncNum) || minIncNum <= 0) {
            errors.minIncrement =
                lang === 'en'
                    ? 'Bid increment must be greater than 0'
                    : 'Bước giá phải lớn hơn 0'
        } else if (minIncNum < 1000) {
            errors.minIncrement =
                lang === 'en'
                    ? 'Minimum bid increment is 1,000 đ'
                    : 'Bước giá tối thiểu phải từ 1,000 đ'
        }

        if (!startTime) {
            errors.startTime =
                lang === 'en'
                    ? 'Please specify start time'
                    : 'Vui lòng chọn thời gian bắt đầu'
        } else {
            const startDate = new Date(startTime)
            if (isNaN(startDate.getTime())) {
                errors.startTime =
                    lang === 'en' ? 'Invalid start time' : 'Thời gian bắt đầu không hợp lệ'
            } else if (startDate.getTime() <= Date.now()) {
                errors.startTime =
                    lang === 'en'
                        ? 'Start time must be greater than the current time'
                        : 'Thời gian bắt đầu phải lớn hơn thời gian hiện tại'
            }
        }

        if (!endTime) {
            errors.endTime =
                lang === 'en'
                    ? 'Please specify end time'
                    : 'Vui lòng chọn thời gian kết thúc'
        } else if (startTime) {
            const startDate = new Date(startTime)
            const endDate = new Date(endTime)
            if (isNaN(endDate.getTime())) {
                errors.endTime =
                    lang === 'en' ? 'Invalid end time' : 'Thời gian kết thúc không hợp lệ'
            } else if (endDate.getTime() <= startDate.getTime()) {
                errors.endTime =
                    lang === 'en'
                        ? 'End time must be after start time'
                        : 'Thời gian kết thúc phải sau thời gian bắt đầu'
            } else {
                const duration = endDate.getTime() - startDate.getTime()
                if (duration < 3600000) {
                    errors.endTime =
                        lang === 'en'
                            ? 'Auction duration must be at least 1 hour'
                            : 'Thời lượng phiên tối thiểu là 1 giờ'
                } else if (duration > 7 * 86400000) {
                    errors.endTime =
                        lang === 'en'
                            ? 'Auction duration cannot exceed 7 days'
                            : 'Thời lượng phiên tối đa là 7 ngày'
                }
            }
        }

        setFieldErrors(errors)
        return Object.keys(errors).length === 0
    }

    const save = async () => {
        if (notEditableError) {
            showToast({
                type: 'error',
                message: notEditableError,
            })
            return
        }

        if (!validateForm()) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Please correct the errors in the form'
                        : 'Vui lòng kiểm tra lại các trường thông tin lỗi',
            })
            return
        }

        setSaving(true)
        try {
            if (isEdit && editAuctionId) {
                // Luồng cập nhật phiên đấu giá
                await auctionsApi.updateAuction(editAuctionId, {
                    startingPrice: Number(startingPrice),
                    minIncrement: Number(minIncrement),
                    startTime: new Date(startTime).toISOString(),
                    endTime: new Date(endTime).toISOString(),
                    visibility,
                })

                showToast({
                    type: 'success',
                    message:
                        lang === 'en'
                            ? 'Auction updated successfully.'
                            : 'Cập nhật phiên đấu giá thành công.',
                })
            } else {
                // Luồng tạo mới phiên đấu giá
                const selectedProd = availableProducts.find((p) => p.id === productId)
                // Chỉ gửi skuId nếu có định dạng UUID hợp lệ; không gửi skuCode chuỗi để tránh lỗi Jackson
                const isUuidVal = (val?: string | null) =>
                    Boolean(val && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(val))
                const validSkuId = isUuidVal((selectedProd as any)?.skuId)
                    ? (selectedProd as any).skuId
                    : undefined

                // Lưu metadata sản phẩm vào cache trước khi tạo phiên,
                // để auction list có thể hiển thị đúng tên/SKU thay vì fallback "Auction Product".
                if (selectedProd && productId) {
                    cacheAuctionProduct(productId, {
                        name: selectedProd.name,
                        nameVi: selectedProd.nameVi || selectedProd.name,
                        sku: selectedProd.sku,
                    })
                }

                const created = await auctionsApi.createAuction({
                    productId,
                    skuId: validSkuId,
                    startingPrice: Number(startingPrice),
                    minIncrement: Number(minIncrement),
                    startTime: new Date(startTime).toISOString(),
                    endTime: new Date(endTime).toISOString(),
                    visibility,
                })

                // Làm mới cache để danh sách phiên đấu giá cập nhật ngay từ backend
                clearLoadCache()

                // Đồng bộ context state
                submitAuction(created)

                showToast({
                    type: 'success',
                    message:
                        created.status === 'scheduled'
                            ? tr(lang, 'auctionCreated')
                            : tr(lang, 'auctionSubmittedForApproval'),
                })
            }

            navigate('seller-auctions')
        } catch (err: any) {
            if (err instanceof ApiClientError) {
                const apiFieldErrors = err.getFieldErrors()
                if (Object.keys(apiFieldErrors).length > 0) {
                    setFieldErrors(apiFieldErrors)
                }
                showToast({
                    type: 'error',
                    message:
                        err.code === 'INVALID_AUCTION_RESPONSE'
                            ? tr(lang, 'auctionCreateInvalidResponse')
                            : err.message,
                })
            } else {
                showToast({
                    type: 'error',
                    message:
                        err?.message ||
                        (lang === 'en'
                            ? 'An error occurred while saving auction.'
                            : 'Đã xảy ra lỗi khi lưu phiên đấu giá.'),
                })
            }
        } finally {
            setSaving(false)
        }
    }

    return (
        <SellerLayout title={isEdit ? tr(lang, 'editAuction') : tr(lang, 'createAuction')}>
            <Breadcrumb
                items={[
                    { label: tr(lang, 'auctions'), page: 'seller-auctions' },
                    { label: isEdit ? tr(lang, 'editAuction') : tr(lang, 'createAuction') },
                ]}
            />
            <div className="mt-5 max-w-2xl">
                <div className="space-y-5">
                    {notEditableError ? (
                        <AlertBanner type="error" message={notEditableError} />
                    ) : (
                        <AlertBanner
                            type="info"
                            message={
                                lang === 'en'
                                    ? 'Auctions can only be modified or cancelled before their scheduled opening time.'
                                    : 'Phiên đấu giá chỉ có thể chỉnh sửa thông số hoặc hủy bỏ trước giờ mở phiên chính thức.'
                            }
                        />
                    )}

                    {/* Khối chọn sản phẩm */}
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Select Product' : 'Chọn sản phẩm'}
                        </h3>
                        <Select
                            label={lang === 'en' ? 'Product' : 'Sản phẩm'}
                            value={productId}
                            disabled={isEdit || Boolean(notEditableError) || (loadingProducts && availableProducts.length === 0)}
                            error={fieldErrors.productId}
                            onChange={(e) => handleFieldChange('productId', e.target.value, setProductId)}
                            options={[
                                {
                                    value: '',
                                    label: lang === 'en' ? 'Select a product' : 'Chọn sản phẩm',
                                },
                                ...availableProducts.map((p) => ({
                                    value: p.id,
                                    label: `${lang === 'vi' ? p.nameVi : p.name} (SKU: ${p.sku})`,
                                })),
                            ]}
                        />
                        {isEdit && (
                            <p className="text-xs text-gray-400 mt-2">
                                {lang === 'en' ? 'Product cannot be changed during edit.' : 'Không thể thay đổi sản phẩm khi đang chỉnh sửa phiên.'}
                            </p>
                        )}
                    </div>

                    {/* Khối thiết lập thông số đấu giá */}
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Auction Settings' : 'Cài đặt đấu giá'}
                        </h3>
                        <div className="grid grid-cols-2 gap-4">
                            <Input
                                label={tr(lang, 'startingPrice')}
                                type="number"
                                disabled={Boolean(notEditableError)}
                                value={startingPrice}
                                error={fieldErrors.startingPrice}
                                onChange={(e) => handleFieldChange('startingPrice', e.target.value, setStartingPrice)}
                                placeholder="100000"
                                helper={lang === 'en' ? 'Must be greater than 0' : 'Giá khởi điểm phải lớn hơn 0'}
                            />
                            <Input
                                label={tr(lang, 'minIncrement')}
                                type="number"
                                disabled={Boolean(notEditableError)}
                                value={minIncrement}
                                error={fieldErrors.minIncrement}
                                onChange={(e) => handleFieldChange('minIncrement', e.target.value, setMinIncrement)}
                                placeholder="1000"
                                helper={lang === 'en' ? 'Min 1,000 đ' : 'Bước giá tối thiểu 1,000 đ'}
                            />
                            <Input
                                label={tr(lang, 'startTime')}
                                type="datetime-local"
                                disabled={Boolean(notEditableError)}
                                value={startTime}
                                error={fieldErrors.startTime}
                                onChange={(e) => handleFieldChange('startTime', e.target.value, setStartTime)}
                                helper={lang === 'en' ? 'Must be after current time' : 'Phải sau thời gian hiện tại'}
                            />
                            <Input
                                label={tr(lang, 'endTime')}
                                type="datetime-local"
                                disabled={Boolean(notEditableError)}
                                value={endTime}
                                error={fieldErrors.endTime}
                                onChange={(e) => handleFieldChange('endTime', e.target.value, setEndTime)}
                                helper={lang === 'en' ? 'Duration: 1 hour to 7 days' : 'Thời lượng: 1 giờ đến 7 ngày'}
                            />
                        </div>
                    </div>

                    {/* Chế độ hiển thị */}
                    <div className="nexus-card p-5">
                        <Select
                            label={lang === 'en' ? 'Visibility' : 'Chế độ hiển thị'}
                            value={visibility}
                            disabled={Boolean(notEditableError)}
                            onChange={(e) =>
                                setVisibility(e.target.value as 'public' | 'restricted')
                            }
                            options={[
                                { value: 'public', label: lang === 'en' ? 'Public (All buyers)' : 'Công khai (Tất cả người mua)' },
                                {
                                    value: 'restricted',
                                    label: lang === 'en' ? 'Private (Invited only)' : 'Riêng tư (Chỉ người được mời)',
                                },
                            ]}
                        />
                    </div>

                    <div className="flex gap-3">
                        <Button
                            variant="outline"
                            size="lg"
                            onClick={() => navigate('seller-auctions')}
                        >
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            size="lg"
                            loading={saving}
                            disabled={Boolean(notEditableError) || loadingData}
                            onClick={save}
                        >
                            {isEdit
                                ? lang === 'en'
                                    ? 'Save Changes'
                                    : 'Lưu thay đổi'
                                : lang === 'en'
                                  ? 'Create Auction'
                                  : 'Khởi tạo phiên'}
                        </Button>
                    </div>
                </div>
            </div>
        </SellerLayout>
    )
}

// ─── Seller Orders ───
/**
 * Màn hình Quản lý đơn hàng của Seller
 *
 * Chức năng:
 * - Thống kê số lượng đơn hàng theo các trạng thái (Tổng, Đang xử lý, Đang giao, Đã giao, Doanh thu tạm tính).
 * - Tra cứu, tìm kiếm theo mã đơn hoặc người mua; lọc theo trạng thái.
 * - Cập nhật trạng thái xử lý đơn hàng (Chuyển sang Đang đóng gói / Đang giao / Giao thành công / Hủy).
 * - Xem chi tiết đơn hàng (thông tin người mua, địa chỉ nhận hàng, danh sách sản phẩm cần đóng gói).
 *
 * Luồng dữ liệu:
 * - Gọi `ordersApi.getOrders({ role: 'seller', status, search })`.
 * - Tự động ưu tiên REST API/DB và fallback kho mock data bền vững trong localStorage.
 * - Thao tác cập nhật trạng thái gọi `ordersApi.updateOrderStatus` và đồng bộ lại bảng dữ liệu.
 */
export function SellerOrdersPage() {
    const { lang, showToast, user } = useApp()
    const [orders, setOrders] = useState<Order[]>([])
    const [loading, setLoading] = useState<boolean>(true)
    const [search, setSearch] = useState<string>('')
    const [statusFilter, setStatusFilter] = useState<string>('all')
    const [selectedOrder, setSelectedOrder] = useState<Order | null>(null)
    const [updatingId, setUpdatingId] = useState<string | null>(null)

    const statusLabel = (s: string) =>
        ({
            PENDING_PAYMENT: tr(lang, 'statusPending'),
            PAID: tr(lang, 'statusPaid'),
            PROCESSING: tr(lang, 'statusProcessing'),
            SHIPPED: tr(lang, 'statusShipped'),
            DELIVERED: tr(lang, 'statusDelivered'),
            COMPLETED: tr(lang, 'statusCompleted'),
            CANCELLED: tr(lang, 'statusCancelled'),
            REFUNDED: tr(lang, 'statusRefunded'),
        })[s] || s

    // Nhãn hiển thị trạng thái thanh toán — dùng ngôn ngữ tự nhiên, không dùng thuật ngữ kỹ thuật.
    const paymentLabel = (s: string) =>
        ({
            held: lang === 'en' ? 'Holding' : 'Đang giữ',
            protection_period: lang === 'en' ? 'Buyer protection' : 'Bảo vệ người mua',
            released: lang === 'en' ? 'Released to seller' : 'Đã vào tài khoản',
            on_hold: lang === 'en' ? 'On hold' : 'Tạm giữ',
            refunded: lang === 'en' ? 'Refunded' : 'Đã hoàn tiền',
            partial_refund: lang === 'en' ? 'Partial refund' : 'Hoàn một phần',
        })[s] || s

    // Nạp danh sách đơn hàng cho người bán
    const loadSellerOrders = async () => {
        setLoading(true)
        try {
            const data = await ordersApi.getOrders({
                role: 'seller',
                userId: user?.id,
                status: statusFilter,
                search: search,
            })
            setOrders(data)
        } catch {
            setOrders([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadSellerOrders()
    }, [statusFilter, search])

    // Xử lý chuyển đổi trạng thái đơn hàng từ dropdown
    const handleStatusChange = async (orderId: string, newStatus: string) => {
        if (!newStatus || newStatus === 'none') return
        setUpdatingId(orderId)
        try {
            await ordersApi.updateOrderStatus(
                orderId,
                newStatus as OrderStatus,
                'Seller status update',
                'seller',
            )
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? `Order ${orderId} updated to ${statusLabel(newStatus)}.`
                        : `Đã cập nhật đơn ${orderId} sang ${statusLabel(newStatus)}.`,
            })
            await loadSellerOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err.message ||
                    (lang === 'en'
                        ? 'Failed to update order status.'
                        : 'Lỗi cập nhật trạng thái đơn.'),
            })
        } finally {
            setUpdatingId(null)
        }
    }

    // Thống kê nhanh số lượng đơn
    const totalRevenue = orders
        .filter((o) => !['CANCELLED', 'REFUNDED'].includes(o.status))
        .reduce((sum, o) => sum + o.total, 0)
    const processingCount = orders.filter((o) => o.status === 'PROCESSING').length
    const shippedCount = orders.filter((o) => o.status === 'SHIPPED').length

    return (
        <SellerLayout title={tr(lang, 'orders')}>
            {/* Thẻ thống kê */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
                <div className="nexus-card p-4">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'Total Orders' : 'Tổng số đơn hàng'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-gray-900">{orders.length}</p>
                </div>
                <div className="nexus-card p-4 border-l-4 border-yellow-500">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'Need Fulfillment' : 'Đang đóng gói xử lý'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-yellow-600">{processingCount}</p>
                </div>
                <div className="nexus-card p-4 border-l-4 border-blue-500">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'In Delivery' : 'Đang vận chuyển'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-blue-600">{shippedCount}</p>
                </div>
                <div className="nexus-card p-4 border-l-4 border-emerald-500">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'Expected Earnings' : 'Doanh thu tạm tính'}
                    </p>
                    <p className="text-xl font-bold mt-1 text-emerald-600">
                        {formatCurrency(totalRevenue, lang)}
                    </p>
                </div>
            </div>

            {/* Bộ lọc và bảng dữ liệu */}
            <div className="nexus-card">
                <div className="p-4 sm:p-5 border-b border-border flex flex-wrap items-center justify-between gap-3">
                    <div className="flex flex-wrap items-center gap-3">
                        <Input
                            placeholder={
                                lang === 'en'
                                    ? 'Search order ID or buyer...'
                                    : 'Tìm mã đơn hoặc người mua...'
                            }
                            value={search}
                            onChange={(e) => setSearch(e.target.value)}
                            className="w-64"
                        />
                        <select
                            className="nexus-input text-sm py-2 w-44"
                            value={statusFilter}
                            onChange={(e) => setStatusFilter(e.target.value)}
                        >
                            <option value="all">{tr(lang, 'all')}</option>
                            <option value="PENDING_PAYMENT">{tr(lang, 'statusPending')}</option>
                            <option value="PAID">{tr(lang, 'statusPaid')}</option>
                            <option value="PROCESSING">{tr(lang, 'statusProcessing')}</option>
                            <option value="SHIPPED">{tr(lang, 'statusShipped')}</option>
                            <option value="DELIVERED">{tr(lang, 'statusDelivered')}</option>
                            <option value="COMPLETED">{tr(lang, 'statusCompleted')}</option>
                            <option value="CANCELLED">{tr(lang, 'statusCancelled')}</option>
                            <option value="REFUNDED">{tr(lang, 'statusRefunded')}</option>
                        </select>
                    </div>
                    <span className="text-xs text-gray-400">
                        {lang === 'en'
                            ? `${orders.length} orders found`
                            : `Tìm thấy ${orders.length} đơn hàng`}
                    </span>
                </div>

                {loading ? (
                    <div className="py-12 text-center text-gray-400">
                        <div
                            className="spinner-border text-primary mb-2"
                            style={{ color: '#ee4d2d' }}
                            role="status"
                        >
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p>
                            {lang === 'en' ? 'Loading orders...' : 'Đang tải danh sách đơn hàng...'}
                        </p>
                    </div>
                ) : (
                    <DataTable
                        columns={[
                            {
                                key: 'id',
                                label: lang === 'en' ? 'Order' : 'Đơn hàng',
                                render: (r: Order) => (
                                    <div className="flex flex-col">
                                        <span className="font-mono text-xs font-bold text-gray-900">
                                            {r.id}
                                        </span>
                                        <span className="text-[11px] text-gray-400">
                                            {formatDate(r.createdAt, lang)}
                                        </span>
                                    </div>
                                ),
                            },
                            {
                                key: 'buyerName',
                                label: lang === 'en' ? 'Buyer & Address' : 'Người mua & Địa chỉ',
                                render: (r: Order) => (
                                    <div>
                                        <p className="font-medium text-sm text-gray-800">
                                            {r.buyerName}
                                        </p>
                                        <p
                                            className="text-xs text-gray-500 line-clamp-1 max-w-[200px]"
                                            title={r.shippingAddress}
                                        >
                                            {r.shippingAddress}
                                        </p>
                                    </div>
                                ),
                            },
                            {
                                key: 'items',
                                label: lang === 'en' ? 'Items' : 'Sản phẩm',
                                render: (r: Order) => (
                                    <div className="flex items-center gap-2">
                                        {r.items[0]?.image && (
                                            <img
                                                src={r.items[0].image}
                                                alt=""
                                                className="w-8 h-8 object-cover rounded border border-border shrink-0"
                                            />
                                        )}
                                        <div className="text-xs">
                                            <span className="font-medium text-gray-800 line-clamp-1 max-w-[150px]">
                                                {r.items[0]?.productName || 'N/A'}
                                            </span>
                                            {r.items.length > 1 && (
                                                <span className="text-gray-400 text-[11px]">
                                                    +{r.items.length - 1}{' '}
                                                    {lang === 'en' ? 'more' : 'khác'}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                ),
                            },
                            {
                                key: 'total',
                                label: lang === 'en' ? 'Total' : 'Tổng',
                                render: (r: Order) => (
                                    <span className="font-bold text-primary">
                                        {formatCurrency(r.total, lang)}
                                    </span>
                                ),
                            },
                            {
                                key: 'status',
                                label: tr(lang, 'status'),
                                render: (r: Order) => (
                                    <StatusBadge status={r.status} label={statusLabel(r.status)} />
                                ),
                            },
                            {
                                key: 'paymentStatus',
                                label: lang === 'en' ? 'Payment' : 'Thanh toán',
                                render: (r: Order) => {
                                    const status = getEffectivePaymentStatus(r)
                                    return (
                                        <StatusBadge status={status} label={paymentLabel(status)} />
                                    )
                                },
                            },
                            {
                                key: 'actions',
                                label: lang === 'en' ? 'Actions' : 'Thao tác',
                                render: (r: Order) => (
                                    <div
                                        className="flex items-center gap-2"
                                        onClick={(e) => e.stopPropagation()}
                                    >
                                        <Button
                                            variant="ghost"
                                            size="sm"
                                            onClick={() => setSelectedOrder(r)}
                                            className="text-xs px-2 py-1"
                                        >
                                            {lang === 'en' ? 'View' : 'Chi tiết'}
                                        </Button>

                                        {/* Dropdown cập nhật trạng thái hợp lệ theo state machine.
                                            DELIVERED bị disable vì bước tiếp (COMPLETED) do Buyer xác nhận,
                                            CANCELLED/COMPLETED/REFUNDED là trạng thái cuối không thể chuyển tiếp. */}
                                        {[
                                            'DELIVERED',
                                            'CANCELLED',
                                            'COMPLETED',
                                            'REFUNDED',
                                        ].includes(r.status) ? (
                                            <span className="text-xs text-gray-400 italic px-2">
                                                {r.status === 'DELIVERED'
                                                    ? lang === 'en'
                                                        ? 'Awaiting buyer confirmation'
                                                        : 'Chờ Buyer xác nhận'
                                                    : lang === 'en'
                                                      ? 'Final status'
                                                      : 'Trạng thái cuối'}
                                            </span>
                                        ) : (
                                            <select
                                                className="nexus-input text-xs py-1 px-2 w-32 font-medium"
                                                value=""
                                                disabled={updatingId === r.id}
                                                onChange={(e) =>
                                                    handleStatusChange(r.id, e.target.value)
                                                }
                                            >
                                                <option value="">
                                                    {lang === 'en'
                                                        ? 'Change status'
                                                        : 'Đổi trạng thái'}
                                                </option>
                                                {r.status === 'PAID' && (
                                                    <option value="PROCESSING">
                                                        {lang === 'en'
                                                            ? 'To Processing'
                                                            : 'Sang Đóng gói'}
                                                    </option>
                                                )}
                                                {['PAID', 'PROCESSING'].includes(r.status) && (
                                                    <option value="SHIPPED">
                                                        {lang === 'en'
                                                            ? 'To Shipped'
                                                            : 'Sang Đang giao'}
                                                    </option>
                                                )}
                                                {r.status === 'SHIPPED' && (
                                                    <option value="DELIVERED">
                                                        {lang === 'en'
                                                            ? 'To Delivered'
                                                            : 'Sang Đã giao'}
                                                    </option>
                                                )}
                                                {['PENDING_PAYMENT', 'PAID', 'PROCESSING'].includes(
                                                    r.status,
                                                ) && (
                                                    <option value="CANCELLED">
                                                        {lang === 'en'
                                                            ? 'Cancel Order'
                                                            : 'Hủy đơn hàng'}
                                                    </option>
                                                )}
                                            </select>
                                        )}
                                    </div>
                                ),
                            },
                        ]}
                        data={orders}
                        rowKey={(r) => r.id}
                        onRowClick={(r) => setSelectedOrder(r)}
                    />
                )}
            </div>

            {/* Modal xem chi tiết đơn hàng dành cho Seller */}
            <Modal
                open={!!selectedOrder}
                onClose={() => setSelectedOrder(null)}
                title={
                    lang === 'en'
                        ? `Order Detail: ${selectedOrder?.id}`
                        : `Chi tiết đơn hàng: ${selectedOrder?.id}`
                }
                size="lg"
            >
                {selectedOrder && (
                    <div className="space-y-5">
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 p-4 bg-gray-50 rounded-xl">
                            <div>
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Buyer Name' : 'Người mua'}
                                </p>
                                <p className="font-semibold text-gray-900 mt-0.5">
                                    {selectedOrder.buyerName}
                                </p>
                            </div>
                            <div>
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Payment status' : 'Tình trạng thanh toán'}
                                </p>
                                <div className="mt-1">
                                    <StatusBadge
                                        status={getEffectivePaymentStatus(selectedOrder)}
                                        label={paymentLabel(
                                            getEffectivePaymentStatus(selectedOrder),
                                        )}
                                    />
                                </div>
                            </div>
                            <div>
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Order Status' : 'Trạng thái đơn'}
                                </p>
                                <div className="mt-1">
                                    <StatusBadge
                                        status={selectedOrder.status}
                                        label={statusLabel(selectedOrder.status)}
                                    />
                                </div>
                            </div>
                        </div>

                        <div>
                            <p className="text-xs text-gray-500 mb-1">
                                {tr(lang, 'shippingAddress')}
                            </p>
                            <p className="text-sm font-medium text-gray-800 bg-white border border-border p-3 rounded-lg">
                                {selectedOrder.shippingAddress}
                            </p>
                        </div>

                        <div>
                            <p className="text-sm font-semibold mb-3">
                                {lang === 'en'
                                    ? 'Packing Items'
                                    : 'Danh sách sản phẩm cần đóng gói'}
                            </p>
                            <div className="border border-border rounded-xl divide-y divide-border/60 overflow-hidden">
                                {selectedOrder.items.map((item) => (
                                    <div
                                        key={item.productId}
                                        className="flex items-center justify-between p-3 gap-3"
                                    >
                                        <div className="flex items-center gap-3">
                                            <img
                                                src={item.image}
                                                alt=""
                                                className="w-12 h-12 object-cover rounded-lg border border-border"
                                            />
                                            <div>
                                                <p className="text-sm font-medium text-gray-900">
                                                    {item.productName}
                                                </p>
                                                <p className="text-xs text-gray-500">
                                                    ×{item.quantity} ·{' '}
                                                    {formatCurrency(item.unitPrice, lang)}
                                                </p>
                                            </div>
                                        </div>
                                        <span className="font-bold text-sm text-primary">
                                            {formatCurrency(item.subtotal, lang)}
                                        </span>
                                    </div>
                                ))}
                            </div>
                        </div>

                        <div className="flex justify-between items-center pt-3 border-t border-border">
                            <span className="text-sm text-gray-600 font-medium">
                                {tr(lang, 'total')}
                            </span>
                            <span className="text-xl font-bold text-primary">
                                {formatCurrency(selectedOrder.total, lang)}
                            </span>
                        </div>
                    </div>
                )}
            </Modal>
        </SellerLayout>
    )
}

// ─── Seller Inventory ──────────────────────────────────────────────────────────
interface SellerSkuOption {
    skuId: string
    skuCode: string
    productName: string
}

/**
 * Màn hình Quản lý Tồn kho & Sổ cái của Seller kết nối trực tiếp API Fulfillment Service (/api/v1/warehouses, /api/v1/inventory).
 * Luồng xử lý:
 * 1. Tải danh sách kho hoạt động, SKU thuộc seller từ Catalog, tồn kho phân trang (backend tự giới hạn phạm vi Seller) và sổ cái biến động.
 * 2. Hỗ trợ lọc theo kho/mã SKU/loại biến động, nhập kho (Inbound), điều chỉnh tồn (targetTotalQty / deltaQty / unavailableDeltaQty) và đối soát sổ cái.
 * 3. Mỗi lần mở modal nhập/điều chỉnh sinh một Idempotency-Key giữ cố định khi bấm Thử lại để tránh tăng/giảm kho hai lần.
 */
export function SellerInventoryPage() {
    const { lang, user, showToast } = useApp()
    const [warehouses, setWarehouses] = useState<WarehouseResponse[]>([])
    const [stocks, setStocks] = useState<InventoryStockResponse[]>([])
    const [ledger, setLedger] = useState<InventoryLedgerResponse[]>([])
    const [reconciliations, setReconciliations] = useState<InventoryReconciliationResponse[]>([])
    const [skuCatalog, setSkuCatalog] = useState<Record<string, SellerSkuOption>>({})
    const [selectedWarehouseId, setSelectedWarehouseId] = useState('')
    const [skuSearch, setSkuSearch] = useState('')
    const [movementFilter, setMovementFilter] = useState<'' | LedgerMovementType>('')
    const [loading, setLoading] = useState(true)
    const [loadError, setLoadError] = useState('')
    const [reconciling, setReconciling] = useState(false)
    const [reloadTick, setReloadTick] = useState(0)

    // Trạng thái modal Nhập kho (Inbound)
    const [inboundOpen, setInboundOpen] = useState(false)
    const [inboundSkuId, setInboundSkuId] = useState('')
    const [inboundSkuCode, setInboundSkuCode] = useState('')
    const [inboundWarehouseId, setInboundWarehouseId] = useState('')
    const [inboundQty, setInboundQty] = useState('10')
    const [inboundReason, setInboundReason] = useState('')
    const [inboundIdemKey, setInboundIdemKey] = useState('')
    const [inboundSubmitting, setInboundSubmitting] = useState(false)
    const [inboundError, setInboundError] = useState('')

    // Trạng thái modal Điều chỉnh tồn (Adjust)
    const [adjustOpen, setAdjustOpen] = useState(false)
    const [adjustTarget, setAdjustTarget] = useState<InventoryStockResponse | null>(null)
    const [adjustMode, setAdjustMode] = useState<'delta' | 'target'>('delta')
    const [adjustValue, setAdjustValue] = useState('0')
    const [adjustUnavailableDelta, setAdjustUnavailableDelta] = useState('0')
    const [adjustReason, setAdjustReason] = useState('')
    const [adjustIdemKey, setAdjustIdemKey] = useState('')
    const [adjustSubmitting, setAdjustSubmitting] = useState(false)
    const [adjustError, setAdjustError] = useState('')

    useEffect(() => {
        const controller = new AbortController()
        let active = true
        async function fetchAll() {
            setLoading(true)
            setLoadError('')
            try {
                const trimmedSearch = skuSearch.trim()
                const catalogPath = user?.id
                    ? `/products/seller/${encodeURIComponent(user.id)}?page=0&size=50`
                    : '/products?page=0&size=50'

                const [whRes, invRes, ledRes, prodRes] = await Promise.all([
                    listWarehouses({ activeOnly: true, size: 50 }, controller.signal),
                    listInventory(
                        {
                            warehouseId: selectedWarehouseId || undefined,
                            skuCode: trimmedSearch || undefined,
                            size: 50,
                        },
                        controller.signal,
                    ),
                    getInventoryLedger(
                        {
                            warehouseId: selectedWarehouseId || undefined,
                            skuCode: trimmedSearch || undefined,
                            movementType: movementFilter || undefined,
                            size: 30,
                        },
                        controller.signal,
                    ),
                    api<Envelope<ApiProduct[]>>(catalogPath, { signal: controller.signal }).catch(
                        () => ({ success: true, data: [] as ApiProduct[] }),
                    ),
                ])

                if (!active) return

                const skuMap: Record<string, SellerSkuOption> = {}
                for (const product of prodRes?.data || []) {
                    for (const sku of product.skus || []) {
                        if (!sku?.id) continue
                        skuMap[sku.id] = {
                            skuId: sku.id,
                            skuCode: sku.skuCode,
                            productName: product.name,
                        }
                    }
                }

                setWarehouses(whRes.items)
                setStocks(invRes.items)
                setLedger(ledRes.items)
                setSkuCatalog(skuMap)
                if (!inboundWarehouseId && whRes.items.length > 0) {
                    setInboundWarehouseId(whRes.items[0].id)
                }
                const skuKeys = Object.keys(skuMap)
                if (!inboundSkuId && skuKeys.length > 0) {
                    setInboundSkuId(skuKeys[0])
                    setInboundSkuCode(skuMap[skuKeys[0]].skuCode)
                }
            } catch (err) {
                if (!active || (err instanceof Error && err.name === 'AbortError')) return
                setLoadError(
                    err instanceof ApiClientError
                        ? `${err.code}: ${err.message}`
                        : errorMessage(err),
                )
            } finally {
                if (active) setLoading(false)
            }
        }
        void fetchAll()
        return () => {
            active = false
            controller.abort()
        }
    }, [user?.id, selectedWarehouseId, skuSearch, movementFilter, reloadTick])

    const totalStock = stocks.reduce((sum, s) => sum + s.totalQty, 0)
    const totalAvailable = stocks.reduce((sum, s) => sum + s.availableQty, 0)
    const totalReserved = stocks.reduce((sum, s) => sum + s.reservedQty, 0)
    const totalUnavailable = stocks.reduce((sum, s) => sum + s.unavailableQty, 0)

    const openInboundModal = (prefillSku?: InventoryStockResponse) => {
        const firstSkuId = prefillSku?.skuId || Object.keys(skuCatalog)[0] || ''
        setInboundSkuId(firstSkuId)
        setInboundSkuCode(prefillSku?.skuCode || skuCatalog[firstSkuId]?.skuCode || '')
        setInboundWarehouseId(
            prefillSku?.warehouseId || selectedWarehouseId || warehouses[0]?.id || '',
        )
        setInboundQty('10')
        setInboundReason('')
        setInboundError('')
        setInboundIdemKey(createInventoryIdempotencyKey('seller-inb'))
        setInboundOpen(true)
    }

    const handleInboundSubmit = async () => {
        const qty = Number(inboundQty)
        if (!Number.isInteger(qty) || qty < 1) {
            setInboundError(
                lang === 'en' ? 'Quantity must be at least 1' : 'Số lượng nhập phải tối thiểu là 1',
            )
            return
        }
        if (!inboundSkuId && !inboundSkuCode.trim()) {
            setInboundError(
                lang === 'en'
                    ? 'Please choose a SKU or enter skuCode'
                    : 'Vui lòng chọn SKU hoặc nhập mã skuCode',
            )
            return
        }
        setInboundSubmitting(true)
        setInboundError('')
        try {
            await inboundStock(
                {
                    skuId: inboundSkuId || undefined,
                    skuCode: inboundSkuCode.trim() || undefined,
                    warehouseId: inboundWarehouseId || undefined,
                    quantity: qty,
                    reason: inboundReason.trim() || 'Nhập kho từ cổng Seller',
                },
                inboundIdemKey,
            )
            setInboundOpen(false)
            setReloadTick((t) => t + 1)
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Stock inbound recorded successfully.'
                        : 'Nhập kho thành công và đã ghi sổ cái.',
            })
        } catch (err) {
            setInboundError(
                err instanceof ApiClientError ? `${err.code}: ${err.message}` : errorMessage(err),
            )
        } finally {
            setInboundSubmitting(false)
        }
    }

    const openAdjustModal = (row: InventoryStockResponse) => {
        setAdjustTarget(row)
        setAdjustMode('delta')
        setAdjustValue('0')
        setAdjustUnavailableDelta('0')
        setAdjustReason('')
        setAdjustError('')
        setAdjustIdemKey(createInventoryIdempotencyKey('seller-adj'))
        setAdjustOpen(true)
    }

    const handleAdjustSubmit = async () => {
        if (!adjustTarget) return
        const parsedValue = Number(adjustValue)
        const parsedUnavailable = Number(adjustUnavailableDelta || '0')
        if (!Number.isInteger(parsedValue) || !Number.isInteger(parsedUnavailable)) {
            setAdjustError(lang === 'en' ? 'Quantity must be an integer' : 'Số lượng phải là số nguyên')
            return
        }
        if (!adjustReason.trim()) {
            setAdjustError(lang === 'en' ? 'Reason is required' : 'Vui lòng nhập lý do điều chỉnh')
            return
        }
        setAdjustSubmitting(true)
        setAdjustError('')
        try {
            await adjustStock(
                {
                    skuId: adjustTarget.skuId,
                    warehouseId: adjustTarget.warehouseId,
                    deltaQty: adjustMode === 'delta' ? parsedValue : undefined,
                    targetTotalQty: adjustMode === 'target' ? parsedValue : undefined,
                    unavailableDeltaQty: parsedUnavailable !== 0 ? parsedUnavailable : undefined,
                    reason: adjustReason.trim(),
                },
                adjustIdemKey,
            )
            setAdjustOpen(false)
            setReloadTick((t) => t + 1)
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Inventory adjusted and reconciled with ledger.'
                        : 'Điều chỉnh tồn kho thành công.',
            })
        } catch (err) {
            setAdjustError(
                err instanceof ApiClientError ? `${err.code}: ${err.message}` : errorMessage(err),
            )
        } finally {
            setAdjustSubmitting(false)
        }
    }

    const handleReconcile = async () => {
        setReconciling(true)
        try {
            const list = await reconcileInventory({
                warehouseId: selectedWarehouseId || undefined,
            })
            setReconciliations(list)
            const unmatched = list.filter((item) => !item.matched || !item.nonNegative).length
            showToast({
                type: unmatched === 0 ? 'success' : 'warning',
                message:
                    unmatched === 0
                        ? lang === 'en'
                            ? `Reconciled ${list.length} SKU records: 100% matched with ledger.`
                            : `Đối soát ${list.length} bản ghi SKU: khớp sổ cái 100%.`
                        : lang === 'en'
                          ? `Detected ${unmatched} mismatched records.`
                          : `Phát hiện ${unmatched} bản ghi lệch sổ cái.`,
            })
        } catch (err) {
            showToast({
                type: 'error',
                message:
                    err instanceof ApiClientError
                        ? `${err.code}: ${err.message}`
                        : errorMessage(err),
            })
        } finally {
            setReconciling(false)
        }
    }

    const skuOptions = Object.values(skuCatalog)

    return (
        <SellerLayout title={tr(lang, 'inventory')}>
            {loadError && (
                <div className="mb-4">
                    <AlertBanner type="error" message={loadError} />
                </div>
            )}
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
                {[
                    [
                        lang === 'en' ? 'Total Stock' : 'Tổng tồn kho',
                        totalStock,
                        'bg-primary-light text-primary',
                    ],
                    [tr(lang, 'totalAvailable'), totalAvailable, 'bg-success-light text-success'],
                    [tr(lang, 'totalReserved'), totalReserved, 'bg-warning-light text-warning'],
                    [tr(lang, 'totalUnavailable'), totalUnavailable, 'bg-gray-100 text-gray-700'],
                ].map(([label, value, cls]) => (
                    <div key={String(label)} className={`nexus-card p-5 ${cls}`}>
                        <p className="text-sm font-semibold mb-1">{label}</p>
                        <p className="text-3xl font-black">{value}</p>
                    </div>
                ))}
            </div>

            <div className="nexus-card p-5 mb-5 flex flex-wrap items-end gap-3 justify-between">
                <div className="flex flex-wrap items-end gap-3 flex-1">
                    <div className="w-56">
                        <Select
                            label={lang === 'en' ? 'Warehouse' : 'Kho hàng'}
                            value={selectedWarehouseId}
                            onChange={(e) => setSelectedWarehouseId(e.target.value)}
                            options={[
                                {
                                    value: '',
                                    label: lang === 'en' ? 'All warehouses' : 'Tất cả kho hàng',
                                },
                                ...warehouses.map((w) => ({
                                    value: w.id,
                                    label: `${w.code} — ${w.name}`,
                                })),
                            ]}
                        />
                    </div>
                    <div className="w-56">
                        <Input
                            label={lang === 'en' ? 'Filter by SKU Code' : 'Lọc theo mã SKU'}
                            value={skuSearch}
                            onChange={(e) => setSkuSearch(e.target.value)}
                            placeholder="VD: NX-KB-01"
                        />
                    </div>
                </div>
                <div className="flex items-center gap-2">
                    <Button
                        variant="outline"
                        loading={reconciling}
                        onClick={() => void handleReconcile()}
                    >
                        <CheckCircle size={15} />
                        {lang === 'en' ? 'Reconcile Ledger' : 'Đối soát sổ cái'}
                    </Button>
                    <Button variant="primary" onClick={() => openInboundModal()}>
                        <Plus size={15} />
                        {lang === 'en' ? 'Inbound Stock' : 'Nhập kho'}
                    </Button>
                </div>
            </div>

            {reconciliations.length > 0 && (
                <div className="nexus-card mb-5">
                    <div className="p-4 border-b border-border flex items-center justify-between">
                        <span className="font-bold text-sm">
                            {lang === 'en'
                                ? 'Ledger Reconciliation Result'
                                : 'Kết quả đối soát tồn kho & sổ cái'}
                        </span>
                        <span className="text-xs text-gray-500">
                            {reconciliations.filter((r) => r.matched).length}/
                            {reconciliations.length}{' '}
                            {lang === 'en' ? 'matched' : 'bản ghi khớp'}
                        </span>
                    </div>
                    <div className="p-4 grid gap-2 text-xs">
                        {reconciliations.map((rec) => (
                            <div
                                key={`${rec.skuId}-${rec.warehouseId}`}
                                className="flex flex-wrap items-center justify-between gap-2 p-2.5 rounded-lg bg-surface-alt border border-border"
                            >
                                <span className="font-mono">
                                    {rec.warehouseCode} ·{' '}
                                    {skuCatalog[rec.skuId]?.skuCode || rec.skuId.slice(0, 8)}
                                </span>
                                <span>
                                    total={rec.totalQty} (ledger={rec.ledgerTotalQty}) · reserved=
                                    {rec.reservedQty} (ledger={rec.ledgerReservedQty}, pending=
                                    {rec.activeReservationQty})
                                </span>
                                <StatusBadge
                                    status={rec.matched && rec.nonNegative ? 'active' : 'cancelled'}
                                    label={
                                        rec.matched && rec.nonNegative
                                            ? lang === 'en'
                                                ? 'Matched'
                                                : 'Khớp sổ cái'
                                            : lang === 'en'
                                              ? 'Mismatch'
                                              : 'Lệch'
                                    }
                                />
                            </div>
                        ))}
                    </div>
                </div>
            )}

            <div className="nexus-card">
                <div className="p-5 border-b border-border flex items-center justify-between">
                    <span className="font-bold">
                        {lang === 'en' ? 'Inventory by SKU & Warehouse' : 'Tồn kho theo SKU & Kho'}
                    </span>
                    <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setReloadTick((t) => t + 1)}
                    >
                        {lang === 'en' ? 'Refresh' : 'Tải lại'}
                    </Button>
                </div>
                <DataTable
                    loading={loading}
                    columns={[
                        {
                            key: 'sku',
                            label: 'SKU',
                            render: (r: InventoryStockResponse) => {
                                const info = skuCatalog[r.skuId]
                                return (
                                    <div>
                                        <p className="font-mono text-xs font-bold">
                                            {r.skuCode || info?.skuCode || r.skuId.slice(0, 8)}
                                        </p>
                                        {info?.productName && (
                                            <p className="text-xs text-gray-500">
                                                {info.productName}
                                            </p>
                                        )}
                                    </div>
                                )
                            },
                        },
                        {
                            key: 'warehouse',
                            label: lang === 'en' ? 'Warehouse' : 'Kho hàng',
                            render: (r: InventoryStockResponse) => (
                                <div>
                                    <p className="font-mono text-xs font-semibold">
                                        {r.warehouseCode}
                                    </p>
                                    {r.warehouseName && (
                                        <p className="text-xs text-gray-500">{r.warehouseName}</p>
                                    )}
                                </div>
                            ),
                        },
                        {
                            key: 'totalQty',
                            label: lang === 'en' ? 'Total' : 'Tổng tồn',
                            render: (r: InventoryStockResponse) => (
                                <span className="font-bold">{r.totalQty}</span>
                            ),
                        },
                        {
                            key: 'availableQty',
                            label: tr(lang, 'totalAvailable'),
                            render: (r: InventoryStockResponse) => (
                                <span className="text-success font-semibold">{r.availableQty}</span>
                            ),
                        },
                        {
                            key: 'reservedQty',
                            label: tr(lang, 'totalReserved'),
                            render: (r: InventoryStockResponse) => (
                                <span className="text-warning font-semibold">{r.reservedQty}</span>
                            ),
                        },
                        {
                            key: 'unavailableQty',
                            label: tr(lang, 'totalUnavailable'),
                            render: (r: InventoryStockResponse) => (
                                <span className="text-gray-500">{r.unavailableQty}</span>
                            ),
                        },
                        {
                            key: 'actions',
                            label: '',
                            render: (r: InventoryStockResponse) => (
                                <div className="flex items-center gap-2 justify-end">
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={() => openInboundModal(r)}
                                    >
                                        {lang === 'en' ? 'Inbound' : 'Nhập thêm'}
                                    </Button>
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={() => openAdjustModal(r)}
                                    >
                                        {lang === 'en' ? 'Adjust' : 'Điều chỉnh'}
                                    </Button>
                                </div>
                            ),
                        },
                    ]}
                    data={stocks}
                    rowKey={(r) => `${r.skuId}-${r.warehouseId}`}
                />
            </div>

            <div className="nexus-card mt-5">
                <div className="p-5 border-b border-border flex flex-wrap items-center justify-between gap-3">
                    <span className="font-bold">
                        {lang === 'en' ? 'Immutable Inventory Ledger' : 'Sổ cái biến động kho'}
                    </span>
                    <div className="w-48">
                        <Select
                            value={movementFilter}
                            onChange={(e) =>
                                setMovementFilter(e.target.value as '' | LedgerMovementType)
                            }
                            options={[
                                { value: '', label: lang === 'en' ? 'All movements' : 'Tất cả loại' },
                                { value: 'INBOUND', label: 'INBOUND' },
                                { value: 'OUTBOUND', label: 'OUTBOUND' },
                                { value: 'RESERVE', label: 'RESERVE' },
                                { value: 'RELEASE', label: 'RELEASE' },
                                { value: 'ADJUSTMENT', label: 'ADJUSTMENT' },
                            ]}
                        />
                    </div>
                </div>
                <div className="p-5">
                    {ledger.length === 0 ? (
                        <EmptyState
                            icon={<Package size={28} />}
                            title={
                                lang === 'en' ? 'No ledger movements yet' : 'Chưa có biến động kho'
                            }
                            subtitle={
                                lang === 'en'
                                    ? 'Inbound or stock adjustments will appear here immutably.'
                                    : 'Mọi thao tác nhập kho, giữ hàng, xuất kho hoặc kiểm kê sẽ được ghi nhận tại đây.'
                            }
                        />
                    ) : (
                        ledger.map((entry) => {
                            const isDecrease =
                                entry.movementType === 'OUTBOUND' ||
                                entry.movementType === 'RESERVE' ||
                                entry.quantity < 0
                            const displayQty =
                                entry.movementType === 'OUTBOUND' ||
                                entry.movementType === 'RESERVE'
                                    ? -Math.abs(entry.quantity)
                                    : entry.quantity
                            return (
                                <div
                                    key={entry.id}
                                    className="flex flex-wrap items-center justify-between gap-2 py-3 border-b border-border last:border-0 text-sm"
                                >
                                    <div className="flex flex-wrap items-center gap-3">
                                        <span className="text-gray-400 text-xs">
                                            {formatDateTime(entry.createdAt, lang)}
                                        </span>
                                        <span className="badge badge-gray text-xs">
                                            {entry.warehouseCode || entry.warehouseId.slice(0, 8)}
                                        </span>
                                        <span className="font-mono text-xs text-gray-700">
                                            {skuCatalog[entry.skuId]?.skuCode ||
                                                entry.skuId.slice(0, 8)}
                                        </span>
                                        <StatusBadge
                                            status={
                                                entry.movementType === 'INBOUND' ||
                                                entry.movementType === 'RELEASE'
                                                    ? 'active'
                                                    : entry.movementType === 'RESERVE'
                                                      ? 'pending'
                                                      : 'info'
                                            }
                                            label={entry.movementType}
                                        />
                                    </div>
                                    <div className="flex items-center gap-4">
                                        <span className="text-xs text-gray-500">
                                            {entry.reason || entry.refType || '—'}
                                            {entry.refId ? ` · #${entry.refId.slice(0, 8)}` : ''}
                                        </span>
                                        <span
                                            className={`font-bold ${isDecrease ? 'text-danger' : 'text-success'}`}
                                        >
                                            {displayQty > 0 ? `+${displayQty}` : displayQty}
                                        </span>
                                    </div>
                                </div>
                            )
                        })
                    )}
                </div>
            </div>

            <Modal
                open={inboundOpen}
                onClose={() => setInboundOpen(false)}
                title={lang === 'en' ? 'Inbound Stock to Warehouse' : 'Nhập kho hàng hóa'}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setInboundOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            loading={inboundSubmitting}
                            onClick={() => void handleInboundSubmit()}
                        >
                            {lang === 'en' ? 'Confirm Inbound' : 'Xác nhận nhập kho'}
                        </Button>
                    </>
                }
            >
                <div className="flex flex-col gap-3">
                    {inboundError && <AlertBanner type="error" message={inboundError} />}
                    {skuOptions.length > 0 && (
                        <Select
                            label={lang === 'en' ? 'Choose Your SKU' : 'Chọn SKU của bạn'}
                            value={inboundSkuId}
                            onChange={(e) => {
                                const id = e.target.value
                                setInboundSkuId(id)
                                if (skuCatalog[id]) setInboundSkuCode(skuCatalog[id].skuCode)
                            }}
                            options={[
                                {
                                    value: '',
                                    label:
                                        lang === 'en'
                                            ? '-- Enter skuCode manually --'
                                            : '-- Nhập tay theo mã skuCode --',
                                },
                                ...skuOptions.map((s) => ({
                                    value: s.skuId,
                                    label: `${s.skuCode} — ${s.productName}`,
                                })),
                            ]}
                        />
                    )}
                    <Input
                        label={lang === 'en' ? 'SKU Code' : 'Mã SKU (skuCode)'}
                        value={inboundSkuCode}
                        onChange={(e) => {
                            setInboundSkuCode(e.target.value)
                            if (skuInboundMatch(skuOptions, e.target.value)) {
                                setInboundSkuId(skuInboundMatch(skuOptions, e.target.value)!)
                            } else {
                                setInboundSkuId('')
                            }
                        }}
                        placeholder="VD: NX-KB-01"
                    />
                    <Select
                        label={lang === 'en' ? 'Target Warehouse' : 'Kho nhập'}
                        value={inboundWarehouseId}
                        onChange={(e) => setInboundWarehouseId(e.target.value)}
                        options={warehouses.map((w) => ({
                            value: w.id,
                            label: `${w.code} — ${w.name}`,
                        }))}
                    />
                    <Input
                        label={lang === 'en' ? 'Inbound Quantity' : 'Số lượng nhập'}
                        type="number"
                        min={1}
                        value={inboundQty}
                        onChange={(e) => setInboundQty(e.target.value)}
                    />
                    <Textarea
                        label={tr(lang, 'reason')}
                        rows={2}
                        value={inboundReason}
                        onChange={(e) => setInboundReason(e.target.value)}
                        placeholder="VD: Nhập lô hàng tháng 11"
                    />
                    <p className="text-[11px] font-mono text-gray-400">
                        Idempotency-Key: {inboundIdemKey}
                    </p>
                </div>
            </Modal>

            <Modal
                open={adjustOpen}
                onClose={() => setAdjustOpen(false)}
                title={
                    lang === 'en'
                        ? `Adjust Stock (${adjustTarget?.warehouseCode || ''})`
                        : `Điều chỉnh tồn kho (${adjustTarget?.warehouseCode || ''})`
                }
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setAdjustOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            loading={adjustSubmitting}
                            onClick={() => void handleAdjustSubmit()}
                        >
                            {tr(lang, 'save')}
                        </Button>
                    </>
                }
            >
                <div className="flex flex-col gap-3">
                    {adjustError && <AlertBanner type="error" message={adjustError} />}
                    {adjustTarget && (
                        <div className="p-3 rounded-lg bg-surface-alt border border-border text-xs">
                            <p className="font-mono font-bold">
                                {adjustTarget.skuCode ||
                                    skuCatalog[adjustTarget.skuId]?.skuCode ||
                                    adjustTarget.skuId}
                            </p>
                            <p className="text-gray-600 mt-1">
                                Total: <b>{adjustTarget.totalQty}</b> · Available:{' '}
                                <b>{adjustTarget.availableQty}</b> · Reserved:{' '}
                                <b>{adjustTarget.reservedQty}</b> · Unavailable:{' '}
                                <b>{adjustTarget.unavailableQty}</b>
                            </p>
                        </div>
                    )}
                    <Select
                        label={lang === 'en' ? 'Adjustment Mode' : 'Kiểu điều chỉnh'}
                        value={adjustMode}
                        onChange={(e) => setAdjustMode(e.target.value as 'delta' | 'target')}
                        options={[
                            {
                                value: 'delta',
                                label:
                                    lang === 'en'
                                        ? 'Relative delta (±deltaQty)'
                                        : 'Thay đổi tương đối (±deltaQty)',
                            },
                            {
                                value: 'target',
                                label:
                                    lang === 'en'
                                        ? 'Exact stocktake count (targetTotalQty)'
                                        : 'Đặt tổng tồn thực tế (targetTotalQty)',
                            },
                        ]}
                    />
                    <Input
                        label={
                            adjustMode === 'delta'
                                ? lang === 'en'
                                    ? 'Delta Quantity (±)'
                                    : 'Số lượng tăng/giảm (±)'
                                : lang === 'en'
                                  ? 'Target Total Quantity'
                                  : 'Tổng tồn mới sau kiểm kê'
                        }
                        type="number"
                        value={adjustValue}
                        onChange={(e) => setAdjustValue(e.target.value)}
                    />
                    <Input
                        label={
                            lang === 'en'
                                ? 'Damaged / Unavailable Delta (±)'
                                : 'Thay đổi hàng hỏng/cách ly (±)'
                        }
                        type="number"
                        value={adjustUnavailableDelta}
                        onChange={(e) => setAdjustUnavailableDelta(e.target.value)}
                    />
                    <Textarea
                        label={tr(lang, 'reason')}
                        rows={2}
                        value={adjustReason}
                        onChange={(e) => setAdjustReason(e.target.value)}
                        placeholder="VD: Kiểm kê thực tế định kỳ"
                    />
                    <p className="text-[11px] font-mono text-gray-400">
                        Idempotency-Key: {adjustIdemKey}
                    </p>
                </div>
            </Modal>
        </SellerLayout>
    )
}

function skuInboundMatch(options: SellerSkuOption[], rawCode: string): string | undefined {
    const normalized = rawCode.trim().toLowerCase()
    if (!normalized) return undefined
    return options.find((o) => o.skuCode.toLowerCase() === normalized)?.skuId
}

// ─── Seller Reputation ─────────────────────────────────────────────────────────
export function SellerReputationPage() {
    const { lang, user } = useApp()
    if (!user) return null

    return (
        <SellerLayout title={lang === 'en' ? 'Reputation' : 'Uy tín'}>
            <div className="max-w-4xl">
                <div className="grid grid-cols-2 gap-5 mb-6">
                    <div className="nexus-card p-6">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Seller reputation score' : 'Điểm uy tín người bán'}
                        </h3>
                        <ReputationBar score={user.reputationScore} />
                    </div>
                    <div className="nexus-card p-6">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Performance' : 'Hiệu suất'}
                        </h3>
                        <div className="grid grid-cols-2 gap-4">
                            <div className="p-4 bg-gray-50 rounded-xl">
                                <p className="text-2xl font-black">98%</p>
                                <p className="text-xs text-gray-500 mt-1">
                                    {lang === 'en' ? 'Successful orders' : 'Đơn thành công'}
                                </p>
                            </div>
                            <div className="p-4 bg-gray-50 rounded-xl">
                                <p className="text-2xl font-black">4.8</p>
                                <p className="text-xs text-gray-500 mt-1">
                                    {lang === 'en' ? 'Buyer rating' : 'Đánh giá Buyer'}
                                </p>
                            </div>
                        </div>
                    </div>
                </div>
                <div className="nexus-card p-5">
                    <h3 className="font-bold mb-4">
                        {lang === 'en' ? 'Recent buyer feedback' : 'Phản hồi gần đây từ Buyer'}
                    </h3>
                    {[
                        {
                            buyer: 'Minh Tran',
                            rating: 5,
                            comment:
                                lang === 'en'
                                    ? 'Product matched the description and shipped quickly.'
                                    : 'Sản phẩm đúng mô tả và giao hàng nhanh.',
                        },
                        {
                            buyer: 'Lan Nguyen',
                            rating: 4,
                            comment:
                                lang === 'en'
                                    ? 'Good communication throughout the order.'
                                    : 'Trao đổi tốt trong suốt quá trình đặt hàng.',
                        },
                    ].map((feedback) => (
                        <div
                            key={feedback.buyer}
                            className="py-4 border-b border-border last:border-0"
                        >
                            <div className="flex items-center justify-between mb-2">
                                <span className="font-semibold text-sm">{feedback.buyer}</span>
                                <StarRating rating={feedback.rating} size={14} />
                            </div>
                            <p className="text-sm text-gray-600">{feedback.comment}</p>
                        </div>
                    ))}
                </div>
            </div>
        </SellerLayout>
    )
}

// ─── Seller Notifications ──────────────────────────────────────────────────────
export function SellerNotificationPage() {
    const { lang } = useApp()
    const [activeTab, setActiveTab] = useState('history')
    const [notifications, setNotifications] = useState(NOTIFICATIONS)

    return (
        <SellerLayout title={lang === 'en' ? 'Notifications' : 'Thông báo'}>
            <div className="max-w-4xl">
                <div className="flex items-center justify-between mb-5">
                    <p className="text-sm text-gray-500">
                        {lang === 'en'
                            ? 'Order, auction, payment, and system updates for your seller account.'
                            : 'Cập nhật đơn hàng, đấu giá, thanh toán và hệ thống cho tài khoản người bán.'}
                    </p>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={() =>
                            setNotifications((current) =>
                                current.map((notification) => ({ ...notification, read: true })),
                            )
                        }
                    >
                        {tr(lang, 'markAllRead')}
                    </Button>
                </div>

                <div className="nexus-card">
                    <Tabs
                        tabs={[
                            {
                                id: 'history',
                                label: lang === 'en' ? 'Notifications' : 'Thông báo',
                                count: notifications.filter((notification) => !notification.read)
                                    .length,
                            },
                            { id: 'preferences', label: tr(lang, 'notifPreferences') },
                        ]}
                        active={activeTab}
                        onChange={setActiveTab}
                    />

                    {activeTab === 'history' ? (
                        <div>
                            {notifications.map((notification) => (
                                <button
                                    key={notification.id}
                                    onClick={() =>
                                        setNotifications((current) =>
                                            current.map((item) =>
                                                item.id === notification.id
                                                    ? { ...item, read: true }
                                                    : item,
                                            ),
                                        )
                                    }
                                    className={`w-full flex items-start gap-4 p-4 text-left border-b border-border last:border-0 transition-colors ${notification.read ? 'hover:bg-gray-50' : 'bg-gray-50'}`}
                                >
                                    <span className="w-9 h-9 rounded-lg bg-gray-100 text-gray-500 flex items-center justify-center shrink-0">
                                        <Bell size={17} />
                                    </span>
                                    <span className="flex-1 min-w-0">
                                        <span className="flex items-center gap-2">
                                            <span className="text-sm font-semibold text-gray-900">
                                                {lang === 'vi'
                                                    ? notification.titleVi
                                                    : notification.title}
                                            </span>
                                            {!notification.read && (
                                                <span className="w-1.5 h-1.5 rounded-full bg-gray-500" />
                                            )}
                                        </span>
                                        <span className="block text-sm text-gray-500 mt-1">
                                            {lang === 'vi'
                                                ? notification.bodyVi
                                                : notification.body}
                                        </span>
                                        <span className="block text-xs text-gray-400 mt-1">
                                            {new Date(notification.timestamp).toLocaleString()}
                                        </span>
                                    </span>
                                </button>
                            ))}
                        </div>
                    ) : (
                        <div className="p-5 space-y-4">
                            {[
                                lang === 'en' ? 'Order updates' : 'Cập nhật đơn hàng',
                                lang === 'en'
                                    ? 'Payment and balance updates'
                                    : 'Cập nhật thanh toán và số dư',
                                lang === 'en' ? 'Auction activity' : 'Hoạt động đấu giá',
                                lang === 'en' ? 'System announcements' : 'Thông báo hệ thống',
                            ].map((label, index) => (
                                <div
                                    key={label}
                                    className="flex items-center justify-between py-2 border-b border-border last:border-0"
                                >
                                    <span className="text-sm font-medium text-gray-700">
                                        {label}
                                    </span>
                                    <Toggle checked={index < 3} onChange={() => {}} />
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>
        </SellerLayout>
    )
}
