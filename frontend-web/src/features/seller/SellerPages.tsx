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
import { auctionsApi } from '../../api/auctions'
import {
    api,
    ApiClientError,
    errorMessage,
    formatSkuDisplay,
    readSession,
    type ApiCategory,
    type ApiPricePreview,
    type ApiProduct,
    type Envelope,
} from '../../api/client'
import {
    adjustStock,
    getInventoryLedger,
    getSellerSkuDetail,
    inboundStock,
    listSellerSkus,
    type InventoryLedgerResponse,
    type InventoryStockResponse,
    type SellerSkuDetailResponse,
    type SellerSkuResponse,
    type SellerSkuStatus,
} from '../../api/inventory'
import { useLoad } from '../../hooks/useLoad'

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
        user?.id && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user.id),
    )
    const productUrl = isUuid
        ? `/products/seller/${user?.id}?v=${refreshKey}`
        : `/products?v=${refreshKey}`

    // Tải danh sách sản phẩm của người bán từ Catalog Service
    const { data: prodEnvelope } = useLoad<Envelope<ApiProduct[]> | ApiProduct[]>(productUrl)

    // Đọc danh sách sản phẩm đã tạo cục bộ để hỗ trợ tài khoản demo hoặc đồng bộ ngay sau khi tạo
    const [localProducts, setLocalProducts] = useState<any[]>(() => {
        try {
            return JSON.parse(localStorage.getItem('nexus_seller_created_products') || '[]')
        } catch {
            return []
        }
    })

    /**
     * Chức năng: Ánh xạ danh sách sản phẩm thật của người bán từ Catalog backend.
     * Quy tắc: Chuẩn hóa bóc tách cả ApiResponse { data: [...] } và mảng trần; kết hợp sản phẩm tạo cục bộ.
     */
    const rawList = (prodEnvelope as any)?.data ?? prodEnvelope
    const apiProducts = Array.isArray(rawList)
        ? rawList.map((p: ApiProduct) => ({
              id: p.id,
              name: p.name,
              nameVi: p.name,
              price: p.skus?.[0]?.price ?? p.minPrice ?? 0,
              sku: formatSkuDisplay(p.skus?.[0]?.id, p.skus?.[0]?.skuCode),
              hasFulfillmentCode: Boolean(p.skus?.[0]?.skuCode),
              stock: (p.skus?.[0] as any)?.stock ?? 10,
              images:
                  p.images && p.images.length > 0
                      ? p.images.map((img) => img.url)
                      : ['https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=600'],
              status: (p.status ? p.status.toLowerCase() : 'draft') as ProductStatus,
          }))
        : []

    // Gộp sản phẩm từ backend và sản phẩm tạo cục bộ (ưu tiên bản ghi cục bộ mới hơn, loại bỏ trùng lặp id/sku)
    const combinedProducts = [
        ...localProducts,
        ...apiProducts.filter(
            (ap) =>
                !localProducts.some(
                    (lp) => lp.id === ap.id || (lp.sku && ap.sku && lp.sku === ap.sku),
                ),
        ),
    ]

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
                {filtered.length === 0 ? (
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
                                    <span className="font-bold text-primary">
                                        {formatCurrency(r.price, lang)}
                                    </span>
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
                                                navigate('seller-create-product', { id: r.id })
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
                        onRowClick={(r) => navigate('seller-create-product', { id: r.id })}
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
                    } catch {
                        // Bỏ qua lỗi xóa backend nếu là bản ghi cục bộ
                    }
                    const updated = localProducts.filter((p) => p.id !== deleteId)
                    setLocalProducts(updated)
                    try {
                        localStorage.setItem(
                            'nexus_seller_created_products',
                            JSON.stringify(updated),
                        )
                    } catch {}
                    showToast({
                        type: 'success',
                        message: lang === 'en' ? 'Product deleted.' : 'Đã xóa sản phẩm thành công.',
                    })
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
interface ProductSkuFormRow {
    id?: string
    attributes: string
    originalPrice: string
    status: 'ACTIVE' | 'DRAFT' | 'INACTIVE'
}

const DEFAULT_PRODUCT_IMAGE =
    'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=600&h=600&fit=crop&auto=format'

/**
 * Tạo mới hoặc chỉnh sửa sản phẩm Catalog (HG-S2-6 & dynamic pricing V6/V7).
 * Luồng:
 * 1. Nếu có pageParams.id, tải chi tiết sản phẩm hiện tại (bao gồm sku.id) để giữ nguyên liên kết với Fulfillment seller_skus.
 * 2. Không cho nhập skuCode thủ công tại Catalog; người bán nhập thuộc tính biến thể + giá nhập tối thiểu (originalPrice).
 * 3. Gọi GET /api/v1/pricing-rules/preview để xem trước giá bán dự kiến trên sàn.
 * 4. Gửi POST /products hoặc PUT /products/{id} kèm sku.id (nếu sửa) và originalPrice; lưu cục bộ chỉ khi chạy Demo offline.
 */
export function CreateProductPage() {
    const { lang, navigate, showToast, pageParams } = useApp()
    const editProductId =
        typeof pageParams?.id === 'string' && pageParams.id ? pageParams.id : undefined

    const [saving, setSaving] = useState(false)
    const [loadingProduct, setLoadingProduct] = useState(false)
    const [name, setName] = useState('')
    const [description, setDescription] = useState('')
    const [status, setStatus] = useState('active')
    const [enableAuction, setEnableAuction] = useState(false)
    const [nameError, setNameError] = useState('')
    const [priceError, setPriceError] = useState('')
    const [categoryId, setCategoryId] = useState('11111111-1111-1111-1111-111111111111')
    const [imageUrl, setImageUrl] = useState(DEFAULT_PRODUCT_IMAGE)
    const [skus, setSkus] = useState<ProductSkuFormRow[]>([
        { attributes: '', originalPrice: '', status: 'ACTIVE' },
    ])
    const [pricePreview, setPricePreview] = useState<ApiPricePreview | null>(null)

    // Tải danh mục thực tế từ Catalog Service (/api/v1/categories)
    const { data: catEnvelope } = useLoad<Envelope<ApiCategory[]> | ApiCategory[]>('/categories')
    const rawCats = (catEnvelope as any)?.data ?? catEnvelope
    const categoriesList: ApiCategory[] = Array.isArray(rawCats) ? rawCats : []

    // Đồng bộ categoryId mặc định sang danh mục thật đầu tiên khi tạo mới để tránh gửi ID giả 1111... gây lỗi 404.
    useEffect(() => {
        if (!editProductId && categoriesList.length > 0 && !categoriesList.some((c) => c.id === categoryId)) {
            setCategoryId(categoriesList[0].id)
        }
    }, [editProductId, categoriesList, categoryId])

    // Khi sửa sản phẩm, nạp dữ liệu thật từ Catalog để giữ nguyên từng sku.id khi PUT.
    useEffect(() => {
        if (!editProductId) return
        const controller = new AbortController()
        setLoadingProduct(true)
        api<Envelope<ApiProduct>>(`/products/${encodeURIComponent(editProductId)}`, {
            signal: controller.signal,
        })
            .then((res) => {
                const p = (res as any)?.data ?? res
                if (!p?.id) return
                setName(p.name || '')
                setDescription(p.description || '')
                if (p.categoryId) setCategoryId(p.categoryId)
                setStatus((p.status || 'active').toLowerCase())
                setEnableAuction(Boolean(p.auctionEnabled))
                if (Array.isArray(p.images) && p.images[0]?.url) {
                    setImageUrl(p.images[0].url)
                }
                if (Array.isArray(p.skus) && p.skus.length > 0) {
                    setSkus(
                        p.skus.map((s: ApiProduct['skus'][number]) => ({
                            id: s.id,
                            attributes: s.attributes ?? '',
                            originalPrice: String(s.originalPrice ?? s.price ?? 0),
                            status:
                                s.status === 'DRAFT' || s.status === 'INACTIVE'
                                    ? s.status
                                    : 'ACTIVE',
                        })),
                    )
                }
            })
            .catch((err) => {
                if ((err as Error)?.name !== 'AbortError') {
                    showToast({ type: 'error', message: errorMessage(err) })
                }
            })
            .finally(() => setLoadingProduct(false))
        return () => controller.abort()
    }, [editProductId, showToast])

    // Xem trước giá bán động theo giá nhập tối thiểu của biến thể đầu tiên.
    const firstOriginalPrice = skus[0]?.originalPrice ?? ''
    useEffect(() => {
        const numeric = Number(firstOriginalPrice)
        if (!firstOriginalPrice || !Number.isFinite(numeric) || numeric < 0) {
            setPricePreview(null)
            return
        }
        const controller = new AbortController()
        const timer = window.setTimeout(() => {
            const q = new URLSearchParams({ originalPrice: String(numeric) })
            if (categoryId) q.set('categoryId', categoryId)
            api<Envelope<ApiPricePreview>>(`/pricing-rules/preview?${q.toString()}`, {
                signal: controller.signal,
            })
                .then((res) => {
                    const data = (res as any)?.data ?? res
                    if (data && typeof data.sellingPrice === 'number') {
                        setPricePreview(data)
                    }
                })
                .catch(() => {
                    // API preview không khả dụng khi offline: bỏ qua nhẹ nhàng.
                })
        }, 250)
        return () => {
            window.clearTimeout(timer)
            controller.abort()
        }
    }, [firstOriginalPrice, categoryId])

    const updateSkuRow = (index: number, patch: Partial<ProductSkuFormRow>) => {
        setSkus((prev) => prev.map((row, idx) => (idx === index ? { ...row, ...patch } : row)))
    }

    const addSkuRow = () => {
        setSkus((prev) => [...prev, { attributes: '', originalPrice: '', status: 'ACTIVE' }])
    }

    const removeSkuRow = (index: number) => {
        setSkus((prev) => (prev.length > 1 ? prev.filter((_, idx) => idx !== index) : prev))
    }

    // Lưu sản phẩm lên Catalog Service (POST tạo mới hoặc PUT giữ nguyên sku.id).
    const save = async () => {
        if (!name.trim()) {
            setNameError(tr(lang, 'required'))
            return
        }
        setNameError('')

        const invalidPrice = skus.some((row) => {
            const val = Number(row.originalPrice)
            return row.originalPrice.trim() === '' || !Number.isFinite(val) || val < 0
        })
        if (invalidPrice || skus.length === 0) {
            setPriceError(
                lang === 'en'
                    ? 'Please enter a valid minimum price (>= 0) for each SKU variant.'
                    : 'Vui lòng nhập giá nhập tối thiểu hợp lệ (>= 0) cho từng biến thể SKU.',
            )
            return
        }
        setPriceError('')
        setSaving(true)

        const normalizedImage = imageUrl.trim() || DEFAULT_PRODUCT_IMAGE
        const payload = {
            name: name.trim(),
            description: description.trim() || name.trim(),
            categoryId: categoryId || '11111111-1111-1111-1111-111111111111',
            auctionEnabled: enableAuction,
            skus: skus.map((row) => ({
                ...(row.id ? { id: row.id } : {}),
                ...(row.attributes.trim() ? { attributes: row.attributes.trim() } : {}),
                originalPrice: Number(row.originalPrice),
                status: row.status,
            })),
            images: [{ url: normalizedImage, sortOrder: 0 }],
        }

        const hasSession = Boolean(readSession())
        let savedId = editProductId || `prod-${Date.now()}`
        let savedSkuId = skus[0]?.id || ''
        let computedSellingPrice = pricePreview?.sellingPrice ?? Number(skus[0].originalPrice)

        try {
            const res = await api<Envelope<ApiProduct>>(
                editProductId ? `/products/${encodeURIComponent(editProductId)}` : '/products',
                {
                    method: editProductId ? 'PUT' : 'POST',
                    body: JSON.stringify(payload),
                },
            )
            const data: ApiProduct | undefined = (res as any)?.data ?? (res as any)
            if (data?.id) {
                savedId = data.id
                savedSkuId = data.skus?.[0]?.id || savedSkuId
                computedSellingPrice = data.skus?.[0]?.price ?? computedSellingPrice
            }
            // Dọn bản ghi cục bộ trùng ID để giao diện luôn đọc trực tiếp từ Catalog backend.
            try {
                const existing = JSON.parse(
                    localStorage.getItem('nexus_seller_created_products') || '[]',
                )
                const cleaned = Array.isArray(existing)
                    ? existing.filter((item: any) => item?.id !== savedId)
                    : []
                localStorage.setItem('nexus_seller_created_products', JSON.stringify(cleaned))
            } catch {
                // Bỏ qua lỗi localStorage
            }
        } catch (err) {
            if (hasSession) {
                showToast({ type: 'error', message: errorMessage(err) })
                setSaving(false)
                return
            }
            // Chỉ fallback localStorage khi chạy tài khoản Demo không có phiên API thật.
            console.warn('Demo offline mode preserving product locally:', err)
            try {
                const existing = JSON.parse(
                    localStorage.getItem('nexus_seller_created_products') || '[]',
                )
                const localRecord = {
                    id: savedId,
                    name: name.trim(),
                    nameVi: name.trim(),
                    price: computedSellingPrice,
                    sku: formatSkuDisplay(savedSkuId || savedId),
                    stock: 0,
                    images: [normalizedImage],
                    status: (status.toLowerCase() as ProductStatus) || 'active',
                    createdAt: new Date().toISOString(),
                }
                const nextList = [
                    localRecord,
                    ...(Array.isArray(existing)
                        ? existing.filter((item: any) => item?.id !== savedId)
                        : []),
                ]
                localStorage.setItem('nexus_seller_created_products', JSON.stringify(nextList))
            } catch {
                // Bỏ qua lỗi localStorage
            }
        }

        showToast({
            type: 'success',
            message: editProductId
                ? lang === 'en'
                    ? 'Product updated (SKU IDs preserved).'
                    : 'Đã cập nhật sản phẩm (giữ nguyên SKU ID).'
                : lang === 'en'
                  ? 'Product saved! Open Inventory to inbound stock and generate warehouse SKU code.'
                  : 'Đã tạo sản phẩm! Vào Kho hàng để nhập hàng và cấp mã SKU kho.',
        })
        navigate('seller-products')
        setSaving(false)
    }

    const pageTitle = editProductId
        ? lang === 'en'
            ? 'Edit Product'
            : 'Chỉnh sửa sản phẩm'
        : tr(lang, 'createProduct')

    return (
        <SellerLayout title={pageTitle}>
            <Breadcrumb
                items={[
                    { label: tr(lang, 'products'), page: 'seller-products' },
                    { label: pageTitle },
                ]}
            />
            {loadingProduct ? (
                <div className="nexus-card p-8 mt-5 text-center text-sm text-gray-500">
                    {lang === 'en' ? 'Loading product...' : 'Đang tải thông tin sản phẩm...'}
                </div>
            ) : (
                <div className="mt-5 grid grid-cols-1 lg:grid-cols-[1fr_300px] gap-6">
                    <div className="space-y-5">
                        <div className="nexus-card p-5">
                            <h3 className="font-bold mb-4">
                                {lang === 'en' ? 'Basic Information' : 'Thông tin cơ bản'}
                            </h3>
                            <div className="space-y-4">
                                <Input
                                    label={tr(lang, 'productName')}
                                    value={name}
                                    onChange={(e) => setName(e.target.value)}
                                    error={nameError}
                                    placeholder={
                                        lang === 'en'
                                            ? 'e.g. Nexus Wireless Headphones'
                                            : 'Ví dụ: Tai nghe không dây Nexus'
                                    }
                                />
                                <Textarea
                                    label={tr(lang, 'productDescription')}
                                    rows={4}
                                    value={description}
                                    onChange={(e) => setDescription(e.target.value)}
                                    placeholder={
                                        lang === 'en'
                                            ? 'Describe product features, warranty, specs...'
                                            : 'Mô tả thông số, chất liệu, bảo hành...'
                                    }
                                />
                            </div>
                        </div>

                        <div className="nexus-card p-5">
                            <div className="flex items-center justify-between gap-3 mb-3">
                                <h3 className="font-bold">
                                    {lang === 'en'
                                        ? 'SKU Variants & Minimum Price'
                                        : 'Biến thể SKU & Giá nhập tối thiểu'}
                                </h3>
                                <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={addSkuRow}
                                >
                                    <Plus size={14} />{' '}
                                    {lang === 'en' ? 'Add Variant' : 'Thêm biến thể'}
                                </Button>
                            </div>

                            <div className="mb-4">
                                <AlertBanner
                                    type="info"
                                    message={
                                        lang === 'en'
                                            ? 'Catalog stores variant attributes & minimum price only. Warehouse SKU codes (FSKU-...) are auto-generated by Fulfillment on first stock inbound.'
                                            : 'Catalog chỉ lưu thuộc tính biến thể & giá nhập tối thiểu (Min Price). Mã SKU kho (FSKU-...) được Fulfillment Service tự cấp khi bạn nhập hàng lần đầu.'
                                    }
                                />
                            </div>

                            <div className="space-y-3">
                                {skus.map((row, idx) => (
                                    <div
                                        key={row.id || `new-${idx}`}
                                        className="border border-border rounded-xl p-3.5 bg-gray-50/50 space-y-3"
                                    >
                                        <div className="flex items-center justify-between gap-2">
                                            <div className="flex items-center gap-2 text-xs font-semibold text-gray-600">
                                                <span>
                                                    {lang === 'en'
                                                        ? `Variant #${idx + 1}`
                                                        : `Biến thể #${idx + 1}`}
                                                </span>
                                                {row.id && (
                                                    <span className="font-mono bg-white border border-border px-2 py-0.5 rounded text-[11px] text-primary">
                                                        Catalog ID: {row.id.slice(0, 8)}...
                                                    </span>
                                                )}
                                            </div>
                                            {skus.length > 1 && (
                                                <button
                                                    type="button"
                                                    onClick={() => removeSkuRow(idx)}
                                                    className="text-xs text-danger hover:underline flex items-center gap-1"
                                                >
                                                    <Trash2 size={13} />{' '}
                                                    {lang === 'en' ? 'Remove' : 'Xóa'}
                                                </button>
                                            )}
                                        </div>
                                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                                            <Input
                                                label={
                                                    lang === 'en'
                                                        ? 'Attributes (Color / Size)'
                                                        : 'Thuộc tính (Màu / Size)'
                                                }
                                                value={row.attributes}
                                                onChange={(e) =>
                                                    updateSkuRow(idx, {
                                                        attributes: e.target.value,
                                                    })
                                                }
                                                placeholder={
                                                    lang === 'en'
                                                        ? 'Black / Size M'
                                                        : 'Đen / Size L'
                                                }
                                            />
                                            <Input
                                                label={
                                                    lang === 'en'
                                                        ? 'Min Price / Original Price'
                                                        : 'Giá nhập tối thiểu (Min Price)'
                                                }
                                                type="number"
                                                min={0}
                                                value={row.originalPrice}
                                                onChange={(e) =>
                                                    updateSkuRow(idx, {
                                                        originalPrice: e.target.value,
                                                    })
                                                }
                                                placeholder="150000"
                                            />
                                            <Select
                                                label={tr(lang, 'status')}
                                                value={row.status}
                                                onChange={(e) =>
                                                    updateSkuRow(idx, {
                                                        status: e.target
                                                            .value as ProductSkuFormRow['status'],
                                                    })
                                                }
                                                options={[
                                                    { value: 'ACTIVE', label: 'ACTIVE' },
                                                    { value: 'DRAFT', label: 'DRAFT' },
                                                    { value: 'INACTIVE', label: 'INACTIVE' },
                                                ]}
                                            />
                                        </div>
                                    </div>
                                ))}
                            </div>

                            {priceError && (
                                <p className="text-xs text-danger font-medium mt-2">{priceError}</p>
                            )}

                            {pricePreview && (
                                <div className="mt-4 rounded-xl border border-primary/20 bg-primary-light/30 px-4 py-3 text-xs flex flex-wrap items-center justify-between gap-2">
                                    <div>
                                        <span className="text-gray-600">
                                            {lang === 'en'
                                                ? 'Estimated Marketplace Selling Price:'
                                                : 'Giá bán dự kiến trên sàn (theo Pricing Rule):'}{' '}
                                        </span>
                                        <strong className="text-primary text-sm ml-1">
                                            {formatCurrency(pricePreview.sellingPrice, lang)}
                                        </strong>
                                    </div>
                                    {pricePreview.appliedRuleName && (
                                        <span className="text-gray-500">
                                            Rule: {pricePreview.appliedRuleName}
                                        </span>
                                    )}
                                </div>
                            )}
                        </div>

                        <div className="nexus-card p-5">
                            <h3 className="font-bold mb-4">{tr(lang, 'uploadImages')}</h3>
                            <Input
                                label={lang === 'en' ? 'Primary Image URL' : 'Đường dẫn ảnh chính'}
                                value={imageUrl}
                                onChange={(e) => setImageUrl(e.target.value)}
                                placeholder="https://..."
                            />
                            <div className="mt-3 border-2 border-dashed border-border rounded-xl p-6 text-center text-gray-400">
                                <Camera size={22} className="mx-auto mb-1.5 text-gray-500" />
                                <p className="text-xs">
                                    {lang === 'en'
                                        ? 'Paste image URL above · Max 5 images'
                                        : 'Dán URL ảnh sản phẩm phía trên · Tối đa 5 ảnh'}
                                </p>
                            </div>
                        </div>
                    </div>

                    <div className="space-y-4">
                        <div className="nexus-card p-5">
                            <h3 className="font-bold mb-4">
                                {lang === 'en' ? 'Publish Settings' : 'Cài đặt đăng'}
                            </h3>
                            <Select
                                label={tr(lang, 'status')}
                                value={status}
                                onChange={(e) => setStatus(e.target.value)}
                                options={[
                                    { value: 'active', label: tr(lang, 'active') },
                                    { value: 'draft', label: tr(lang, 'draft') },
                                    { value: 'inactive', label: tr(lang, 'inactive') },
                                ]}
                            />
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
                                                  value: '11111111-1111-1111-1111-111111111111',
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
                            loading={saving}
                            onClick={save}
                        >
                            {tr(lang, 'save')}
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
            )}
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
            const details = await auctionsApi.getAuctionById(
                auction.id,
                createdAuctions,
                auctionReviews,
            )
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
                message:
                    err?.message ||
                    (lang === 'en' ? 'Failed to cancel auction' : 'Không thể hủy phiên đấu giá'),
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
                            count: sellerAuctions.filter((a) => a.status === 'pending_approval')
                                .length,
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
                                        src={
                                            r.product?.images?.[0] ||
                                            'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=100'
                                        }
                                        alt=""
                                        className="w-10 h-10 object-cover rounded-lg"
                                    />
                                    <div>
                                        <p className="text-sm font-semibold line-clamp-1">
                                            {lang === 'vi'
                                                ? r.product?.nameVi || r.product?.name
                                                : r.product?.name}
                                        </p>
                                        <p className="text-xs text-gray-400">
                                            SKU: {r.product?.sku || 'N/A'}
                                        </p>
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
                                const isEditable =
                                    ['pending_approval', 'scheduled', 'draft'].includes(r.status) &&
                                    isBeforeStart
                                const isCancellable =
                                    ['pending_approval', 'scheduled', 'draft'].includes(r.status) &&
                                    isBeforeStart

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
                                                title={
                                                    lang === 'en'
                                                        ? 'Edit auction'
                                                        : 'Chỉnh sửa phiên'
                                                }
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
                                                title={
                                                    lang === 'en'
                                                        ? 'Cancel auction'
                                                        : 'Hủy phiên đấu giá'
                                                }
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
                                src={
                                    detailAuction.product?.images?.[0] ||
                                    'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=120'
                                }
                                alt=""
                                className="h-16 w-16 rounded-lg border object-cover"
                            />
                            <div className="min-w-0">
                                <h3 className="font-semibold text-gray-900">
                                    {lang === 'vi'
                                        ? detailAuction.product?.nameVi ||
                                          detailAuction.product?.name
                                        : detailAuction.product?.name}
                                </h3>
                                <p className="mt-1 text-xs text-gray-500">
                                    SKU: {detailAuction.product?.sku || 'N/A'} · ID:{' '}
                                    {detailAuction.id}
                                </p>
                                <div className="mt-2">
                                    <StatusBadge
                                        status={detailAuction.status}
                                        label={statusLabel(detailAuction.status)}
                                    />
                                </div>
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3 text-sm">
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'startingPrice')}
                                </span>
                                <span className="font-semibold">
                                    {formatCurrency(detailAuction.startingPrice, lang)}
                                </span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'currentBid')}
                                </span>
                                <span className="font-bold text-auction">
                                    {formatCurrency(detailAuction.currentBid, lang)}
                                </span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'minIncrement')}
                                </span>
                                <span className="font-semibold">
                                    {formatCurrency(detailAuction.minIncrement, lang)}
                                </span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'totalBidCount')}
                                </span>
                                <span className="font-semibold">{detailAuction.bidCount}</span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'auctionVisibility')}
                                </span>
                                <span className="font-semibold capitalize">
                                    {detailAuction.visibility}
                                </span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'startTime')}
                                </span>
                                <span className="font-medium">
                                    {formatDateTime(detailAuction.startTime, lang)}
                                </span>
                            </div>
                            <div className="rounded-lg bg-gray-50 p-3">
                                <span className="block text-xs text-gray-500">
                                    {tr(lang, 'endTime')}
                                </span>
                                <span className="font-medium">
                                    {formatDateTime(detailAuction.endTime, lang)}
                                </span>
                            </div>
                        </div>

                        <section>
                            <h4 className="mb-2 font-semibold">{tr(lang, 'bidHistory')}</h4>
                            {detailLoading ? (
                                <p className="text-sm text-gray-500" role="status">
                                    {tr(lang, 'loadingAuctionDetails')}
                                </p>
                            ) : detailAuction.bids?.length ? (
                                <div className="max-h-56 overflow-y-auto rounded-lg border">
                                    <table className="w-full text-sm">
                                        <thead className="bg-gray-50 text-gray-600">
                                            <tr>
                                                <th className="p-3 text-left">
                                                    {tr(lang, 'bidAmount')}
                                                </th>
                                                <th className="p-3 text-right">
                                                    {tr(lang, 'bidTime')}
                                                </th>
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-gray-100">
                                            {[...detailAuction.bids]
                                                .sort(
                                                    (a, b) =>
                                                        new Date(b.timestamp).getTime() -
                                                        new Date(a.timestamp).getTime(),
                                                )
                                                .map((bid) => (
                                                    <tr key={bid.id}>
                                                        <td className="p-3 font-semibold text-auction">
                                                            {formatCurrency(bid.amount, lang)}
                                                        </td>
                                                        <td className="p-3 text-right text-gray-500">
                                                            {formatDateTime(bid.timestamp, lang)}
                                                        </td>
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
                        <Button variant="danger" loading={cancelling} onClick={handleConfirmCancel}>
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
                            lang === 'en'
                                ? 'Reason for cancellation...'
                                : 'Lý do hủy phiên đấu giá...'
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
    const { lang, navigate, showToast, submitAuction, pageParams, createdAuctions } = useApp()
    const editAuctionId = (pageParams?.id as string) || undefined
    const isEdit = Boolean(editAuctionId)

    const [saving, setSaving] = useState(false)
    const [loadingData, setLoadingData] = useState(false)
    const [notEditableError, setNotEditableError] = useState('')
    const [repError, setRepError] = useState(false)
    const [overLimit, setOverLimit] = useState(false)

    // Dữ liệu form
    const [productId, setProductId] = useState('')
    const [startingPrice, setStartingPrice] = useState('')
    const [minIncrement, setMinIncrement] = useState('1000')
    const [startTime, setStartTime] = useState('')
    const [endTime, setEndTime] = useState('')
    const [visibility, setVisibility] = useState<'public' | 'restricted'>('public')

    // Bảng lỗi validation từng trường
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

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
                        message:
                            lang === 'en' ? 'Auction not found' : 'Không tìm thấy phiên đấu giá',
                    })
                    navigate('seller-auctions')
                    return
                }

                // Kiểm tra điều kiện chỉnh sửa: chỉ cho phép khi chưa mở (startTime > now) và trạng thái scheduled/draft/pending
                const isPastStart = new Date(auction.startTime).getTime() <= Date.now()
                if (
                    ['active', 'ended', 'settled', 'cancelled'].includes(auction.status) ||
                    isPastStart
                ) {
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
                lang === 'en' ? 'Bid increment must be greater than 0' : 'Bước giá phải lớn hơn 0'
        } else if (minIncNum < 1000) {
            errors.minIncrement =
                lang === 'en'
                    ? 'Minimum bid increment is 1,000 đ'
                    : 'Bước giá tối thiểu phải từ 1,000 đ'
        }

        if (!startTime) {
            errors.startTime =
                lang === 'en' ? 'Please specify start time' : 'Vui lòng chọn thời gian bắt đầu'
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
                lang === 'en' ? 'Please specify end time' : 'Vui lòng chọn thời gian kết thúc'
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
                const created = await auctionsApi.createAuction({
                    productId,
                    startingPrice: Number(startingPrice),
                    minIncrement: Number(minIncrement),
                    startTime: new Date(startTime).toISOString(),
                    endTime: new Date(endTime).toISOString(),
                    visibility,
                })

                // Đồng bộ context state
                submitAuction(created)

                showToast({
                    type: 'success',
                    message: tr(lang, 'auctionSubmittedForApproval'),
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
                    message: err.message,
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

    const availableProducts = PRODUCTS.filter((p) => p.enableAuction)

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
                    {repError && (
                        <AlertBanner
                            type="error"
                            message={
                                lang === 'en'
                                    ? 'Reputation score below 50. You cannot create auctions.'
                                    : 'Điểm uy tín dưới 50. Bạn không thể tạo phiên đấu giá.'
                            }
                        />
                    )}
                    {overLimit && (
                        <AlertBanner
                            type="warning"
                            message={
                                lang === 'en'
                                    ? 'You already have 5 running auctions. Please wait until one ends.'
                                    : 'Bạn đã có 5 phiên đấu giá đang chạy. Vui lòng chờ một phiên kết thúc.'
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
                            disabled={isEdit || Boolean(notEditableError)}
                            error={fieldErrors.productId}
                            onChange={(e) =>
                                handleFieldChange('productId', e.target.value, setProductId)
                            }
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
                                {lang === 'en'
                                    ? 'Product cannot be changed during edit.'
                                    : 'Không thể thay đổi sản phẩm khi đang chỉnh sửa phiên.'}
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
                                onChange={(e) =>
                                    handleFieldChange(
                                        'startingPrice',
                                        e.target.value,
                                        setStartingPrice,
                                    )
                                }
                                placeholder="100000"
                                helper={
                                    lang === 'en'
                                        ? 'Must be greater than 0'
                                        : 'Giá khởi điểm phải lớn hơn 0'
                                }
                            />
                            <Input
                                label={tr(lang, 'minIncrement')}
                                type="number"
                                disabled={Boolean(notEditableError)}
                                value={minIncrement}
                                error={fieldErrors.minIncrement}
                                onChange={(e) =>
                                    handleFieldChange(
                                        'minIncrement',
                                        e.target.value,
                                        setMinIncrement,
                                    )
                                }
                                placeholder="1000"
                                helper={
                                    lang === 'en' ? 'Min 1,000 đ' : 'Bước giá tối thiểu 1,000 đ'
                                }
                            />
                            <Input
                                label={tr(lang, 'startTime')}
                                type="datetime-local"
                                disabled={Boolean(notEditableError)}
                                value={startTime}
                                error={fieldErrors.startTime}
                                onChange={(e) =>
                                    handleFieldChange('startTime', e.target.value, setStartTime)
                                }
                                helper={
                                    lang === 'en'
                                        ? 'Must be greater than current time'
                                        : 'Lớn hơn thời gian hiện tại'
                                }
                            />
                            <Input
                                label={tr(lang, 'endTime')}
                                type="datetime-local"
                                disabled={Boolean(notEditableError)}
                                value={endTime}
                                error={fieldErrors.endTime}
                                onChange={(e) =>
                                    handleFieldChange('endTime', e.target.value, setEndTime)
                                }
                                helper={
                                    lang === 'en'
                                        ? 'Duration: 1 hour to 7 days'
                                        : 'Thời lượng: 1 giờ đến 7 ngày'
                                }
                            />
                        </div>
                        <p className="text-xs text-gray-400 mt-2">{tr(lang, 'auctionDuration')}</p>
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
                                {
                                    value: 'public',
                                    label:
                                        lang === 'en'
                                            ? 'Public (All buyers)'
                                            : 'Công khai (Tất cả người mua)',
                                },
                                {
                                    value: 'restricted',
                                    label: lang === 'en' ? 'Restricted' : 'Hạn chế',
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

                    {/* Demo error toggles */}
                    <div className="mt-4 p-3 bg-gray-50 rounded-xl text-xs text-gray-400 space-y-1">
                        <p>Demo: trigger error states</p>
                        <button
                            onClick={() => setRepError((e) => !e)}
                            className="text-primary hover:underline"
                        >
                            Toggle reputation error
                        </button>{' '}
                        ·{' '}
                        <button
                            onClick={() => setOverLimit((e) => !e)}
                            className="text-primary hover:underline"
                        >
                            Toggle over-limit error
                        </button>
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
                        <div className="inline-block animate-spin w-8 h-8 border-4 border-primary border-t-transparent rounded-full mb-2" />
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

interface CatalogSkuChoice {
    skuId: string
    productId: string
    productName: string
    attributes: string
    originalPrice: number
}

interface SessionInboundRecord {
    id: string
    createdAt: string
    skuId: string
    skuCode: string
    warehouseCode: string
    quantity: number
    totalQty: number
}

/**
 * Trang Quản lý Tồn kho Seller kết nối API thật của Fulfillment Service và Catalog Service.
 * Luồng chính:
 * 1. Nạp danh sách `seller_skus` (GET /api/v1/inventory/skus) theo bộ lọc từ khóa/trạng thái.
 * 2. Nạp danh mục sản phẩm của Seller để hiển thị tên sản phẩm, thuộc tính biến thể cạnh `skuId` và `FSKU-...`.
 * 3. Cho phép Seller nhập kho (`POST /api/v1/inventory/inbound`) theo `skuId` Catalog; hiển thị ngay mã `FSKU-...` được cấp/tái sử dụng.
 * 4. Xem chi tiết tồn kho từng kho (`GET /api/v1/inventory/skus/{id}`); chỉ Admin mới gọi `/inventory/adjust` và `/inventory/ledger` để tránh 403 cho Seller.
 */
export function SellerInventoryPage() {
    const { lang, user, showToast } = useApp()
    const isAdmin = user?.role === 'admin'

    const [skuRows, setSkuRows] = useState<SellerSkuResponse[]>([])
    const [totalElements, setTotalElements] = useState(0)
    const [loading, setLoading] = useState(true)
    const [loadError, setLoadError] = useState<string | null>(null)
    const [keyword, setKeyword] = useState('')
    const [statusFilter, setStatusFilter] = useState<'' | SellerSkuStatus>('')
    const [refreshKey, setRefreshKey] = useState(0)

    const [catalogSkus, setCatalogSkus] = useState<CatalogSkuChoice[]>([])
    const [catalogSkuMap, setCatalogSkuMap] = useState<Record<string, CatalogSkuChoice>>({})

    // Modal Nhập kho (POST /api/v1/inventory/inbound)
    const [inboundOpen, setInboundOpen] = useState(false)
    const [selectedCatalogKey, setSelectedCatalogKey] = useState('__custom__')
    const [inboundSkuId, setInboundSkuId] = useState('')
    const [inboundWarehouseId, setInboundWarehouseId] = useState('')
    const [inboundQty, setInboundQty] = useState('10')
    const [inboundUnitCost, setInboundUnitCost] = useState('')
    const [inboundRefId, setInboundRefId] = useState('')
    const [inboundSubmitting, setInboundSubmitting] = useState(false)
    const [inboundError, setInboundError] = useState<string | null>(null)
    const [lastInbound, setLastInbound] = useState<InventoryStockResponse | null>(null)
    const [sessionInbounds, setSessionInbounds] = useState<SessionInboundRecord[]>([])

    // Modal Chi tiết từng kho (GET /api/v1/inventory/skus/{id})
    const [detailOpen, setDetailOpen] = useState(false)
    const [detailLoading, setDetailLoading] = useState(false)
    const [detailError, setDetailError] = useState<string | null>(null)
    const [detailData, setDetailData] = useState<SellerSkuDetailResponse | null>(null)

    // Modal Điều chỉnh tồn kho & Sổ cái (chỉ Admin có quyền INVENTORY.MANAGE / INVENTORY.VIEW)
    const [adjustTarget, setAdjustTarget] = useState<SellerSkuResponse | null>(null)
    const [adjustWarehouseId, setAdjustWarehouseId] = useState('')
    const [adjustDeltaQty, setAdjustDeltaQty] = useState('')
    const [adjustReason, setAdjustReason] = useState('')
    const [adjustRefId, setAdjustRefId] = useState('')
    const [adjustSubmitting, setAdjustSubmitting] = useState(false)
    const [adjustError, setAdjustError] = useState<string | null>(null)
    const [ledgerRows, setLedgerRows] = useState<InventoryLedgerResponse[]>([])
    const [ledgerLoading, setLedgerLoading] = useState(false)

    // Nạp danh sách sản phẩm/SKU của Seller từ Catalog để đối chiếu tên biến thể và điền nhanh dropdown nhập kho.
    useEffect(() => {
        const controller = new AbortController()
        const isUuid =
            !!user?.id &&
            /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(user.id)
        const endpoint = isUuid
            ? `/products/seller/${encodeURIComponent(user.id)}?size=100`
            : '/products?size=100'

        api<Envelope<ApiProduct[]>>(endpoint, { signal: controller.signal })
            .then((res) => {
                if (controller.signal.aborted) return
                const products = Array.isArray(res?.data) ? res.data : []
                const choices: CatalogSkuChoice[] = []
                const map: Record<string, CatalogSkuChoice> = {}
                for (const product of products) {
                    for (const sku of product.skus || []) {
                        if (!sku?.id) continue
                        const item: CatalogSkuChoice = {
                            skuId: sku.id,
                            productId: product.id,
                            productName: product.name,
                            attributes: sku.attributes || 'Standard',
                            originalPrice: sku.originalPrice ?? sku.price ?? 0,
                        }
                        choices.push(item)
                        map[sku.id] = item
                    }
                }
                setCatalogSkus(choices)
                setCatalogSkuMap(map)
            })
            .catch(() => {
                // Catalog lookup là dữ liệu bổ trợ hiển thị; lỗi mạng không chặn thao tác trực tiếp theo skuId.
            })

        return () => controller.abort()
    }, [user?.id, refreshKey])

    // Nạp danh sách SKU kho (seller_skus) từ Fulfillment Service.
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setLoadError(null)

        listSellerSkus(
            {
                keyword: keyword.trim() || undefined,
                status: statusFilter || undefined,
                page: 0,
                size: 100,
            },
            controller.signal,
        )
            .then((res) => {
                if (controller.signal.aborted) return
                setSkuRows(res.items)
                setTotalElements(res.total)
            })
            .catch((err) => {
                if (controller.signal.aborted) return
                setLoadError(errorMessage(err))
                setSkuRows([])
                setTotalElements(0)
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })

        return () => controller.abort()
    }, [keyword, statusFilter, refreshKey])

    // Chỉ Admin mới gọi GET /api/v1/inventory/ledger (Seller không có INVENTORY.VIEW sẽ nhận 403 nếu gọi).
    useEffect(() => {
        if (!isAdmin) {
            setLedgerRows([])
            return
        }
        const controller = new AbortController()
        setLedgerLoading(true)

        getInventoryLedger({ page: 0, size: 20 }, controller.signal)
            .then((rows) => {
                if (!controller.signal.aborted) setLedgerRows(rows)
            })
            .catch(() => {
                if (!controller.signal.aborted) setLedgerRows([])
            })
            .finally(() => {
                if (!controller.signal.aborted) setLedgerLoading(false)
            })

        return () => controller.abort()
    }, [isAdmin, refreshKey])

    const existingSkuMap = new Map<string, SellerSkuResponse>()
    for (const row of skuRows) {
        existingSkuMap.set(row.skuId, row)
    }

    const totalStock = skuRows.reduce((sum, r) => sum + (r.totalQty || 0), 0)
    const totalAvailable = skuRows.reduce((sum, r) => sum + (r.availableQty || 0), 0)
    const totalReserved = skuRows.reduce((sum, r) => sum + (r.reservedQty || 0), 0)

    const openInboundModal = (prefillSkuId = '', prefillWarehouseId = '') => {
        setInboundError(null)
        const initialKey =
            prefillSkuId && catalogSkuMap[prefillSkuId]
                ? prefillSkuId
                : catalogSkus.length > 0 && !prefillSkuId
                  ? catalogSkus[0].skuId
                  : '__custom__'
        const resolvedSkuId = prefillSkuId || (initialKey !== '__custom__' ? initialKey : '')
        setSelectedCatalogKey(initialKey)
        setInboundSkuId(resolvedSkuId)
        setInboundWarehouseId(prefillWarehouseId)
        setInboundQty('10')
        setInboundUnitCost('')
        setInboundRefId('')
        setInboundOpen(true)
    }

    const handleInboundSubmit = async (e: React.FormEvent) => {
        e.preventDefault()
        setInboundError(null)
        const trimmedSkuId = inboundSkuId.trim()
        const qty = Number(inboundQty)
        if (!trimmedSkuId) {
            setInboundError(
                lang === 'en'
                    ? 'Catalog SKU ID (UUID) is required.'
                    : 'Vui lòng chọn hoặc nhập Catalog skuId (UUID).',
            )
            return
        }
        if (!Number.isInteger(qty) || qty < 1) {
            setInboundError(
                lang === 'en'
                    ? 'Inbound quantity must be a positive integer.'
                    : 'Số lượng nhập kho phải là số nguyên dương.',
            )
            return
        }
        const parsedCost = inboundUnitCost.trim() ? Number(inboundUnitCost.trim()) : null
        if (parsedCost !== null && (Number.isNaN(parsedCost) || parsedCost < 0)) {
            setInboundError(
                lang === 'en' ? 'Unit cost must be >= 0.' : 'Giá vốn nhập kho không được âm.',
            )
            return
        }

        setInboundSubmitting(true)
        try {
            const stock = await inboundStock({
                skuId: trimmedSkuId,
                warehouseId: inboundWarehouseId.trim() || null,
                quantity: qty,
                refId: inboundRefId.trim() || null,
                unitCost: parsedCost,
            })
            const assignedCode = formatSkuDisplay(stock.skuId, stock.skuCode)
            setLastInbound(stock)
            setSessionInbounds((prev) => [
                {
                    id: `${stock.skuId}-${Date.now()}`,
                    createdAt: new Date().toISOString(),
                    skuId: stock.skuId,
                    skuCode: assignedCode,
                    warehouseCode: stock.warehouseCode || 'WH_001',
                    quantity: qty,
                    totalQty: stock.totalQty,
                },
                ...prev,
            ])
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? `Inbound recorded for ${assignedCode} (+${qty}, total ${stock.totalQty}).`
                        : `Nhập kho thành công mã ${assignedCode} (+${qty}, tổng tồn ${stock.totalQty}).`,
            })
            setInboundOpen(false)
            setRefreshKey((k) => k + 1)
        } catch (err) {
            setInboundError(errorMessage(err))
        } finally {
            setInboundSubmitting(false)
        }
    }

    const handleOpenDetail = async (row: SellerSkuResponse) => {
        const targetId = row.sellerSkuId || row.id
        setDetailOpen(true)
        setDetailLoading(true)
        setDetailError(null)
        setDetailData(null)
        try {
            const detail = await getSellerSkuDetail(targetId)
            setDetailData(detail)
        } catch (err) {
            setDetailError(errorMessage(err))
        } finally {
            setDetailLoading(false)
        }
    }

    const handleAdjustSubmit = async (e: React.FormEvent) => {
        e.preventDefault()
        if (!adjustTarget) return
        setAdjustError(null)
        const delta = Number(adjustDeltaQty)
        if (!Number.isInteger(delta) || delta === 0) {
            setAdjustError(
                lang === 'en'
                    ? 'Delta quantity must be a non-zero integer.'
                    : 'Số lượng điều chỉnh phải là số nguyên khác 0.',
            )
            return
        }
        if (!adjustReason.trim()) {
            setAdjustError(
                lang === 'en'
                    ? 'Adjustment reason is required.'
                    : 'Vui lòng nhập lý do điều chỉnh tồn kho.',
            )
            return
        }
        setAdjustSubmitting(true)
        try {
            const updated = await adjustStock({
                skuId: adjustTarget.skuId,
                warehouseId: adjustWarehouseId.trim() || null,
                deltaQty: delta,
                reason: adjustReason.trim(),
                refId: adjustRefId.trim() || null,
            })
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? `Adjusted ${formatSkuDisplay(updated.skuId, updated.skuCode)} to ${updated.totalQty}.`
                        : `Đã điều chỉnh ${formatSkuDisplay(updated.skuId, updated.skuCode)} còn ${updated.totalQty}.`,
            })
            setAdjustTarget(null)
            setRefreshKey((k) => k + 1)
        } catch (err) {
            setAdjustError(errorMessage(err))
        } finally {
            setAdjustSubmitting(false)
        }
    }

    const selectedExisting = inboundSkuId.trim()
        ? existingSkuMap.get(inboundSkuId.trim())
        : undefined

    const skuSelectOptions = [
        ...catalogSkus.map((item) => {
            const registered = existingSkuMap.get(item.skuId)
            const suffix = registered
                ? ` · ${registered.skuCode}`
                : lang === 'en'
                  ? ' · First inbound (auto FSKU-...)'
                  : ' · Nhập lần đầu (sinh FSKU-...)'
            return {
                value: item.skuId,
                label: `${item.productName} — ${item.attributes}${suffix}`,
            }
        }),
        {
            value: '__custom__',
            label:
                lang === 'en'
                    ? 'Enter Catalog SKU UUID manually'
                    : 'Nhập trực tiếp Catalog skuId (UUID)',
        },
    ]

    return (
        <SellerLayout title={tr(lang, 'inventory')}>
            {/* Thẻ thống kê tổng hợp từ seller_skus */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5 mb-6">
                {[
                    [
                        lang === 'en' ? 'Fulfillment SKUs' : 'Mã SKU Fulfillment',
                        totalElements,
                        'bg-gray-100 text-gray-800',
                    ],
                    [
                        lang === 'en' ? 'Total Stock' : 'Tổng tồn kho',
                        totalStock,
                        'bg-primary-light text-primary',
                    ],
                    [tr(lang, 'totalAvailable'), totalAvailable, 'bg-success-light text-success'],
                    [tr(lang, 'totalReserved'), totalReserved, 'bg-warning-light text-warning'],
                ].map(([l, v, cls]) => (
                    <div key={String(l)} className={`nexus-card p-5 ${cls}`}>
                        <p className="text-sm font-semibold mb-1">{l}</p>
                        <p className="text-3xl font-black">{v}</p>
                    </div>
                ))}
            </div>

            {/* Banner xác nhận mã FSKU-... vừa được Fulfillment cấp/tái sử dụng khi nhập kho */}
            {lastInbound && (
                <div className="mb-5">
                    <AlertBanner
                        type="success"
                        message={
                            lang === 'en'
                                ? `Fulfillment SKU ${formatSkuDisplay(lastInbound.skuId, lastInbound.skuCode)} at ${lastInbound.warehouseCode}: total ${lastInbound.totalQty}, available ${lastInbound.availableQty} (Catalog skuId: ${lastInbound.skuId}).`
                                : `Mã Fulfillment ${formatSkuDisplay(lastInbound.skuId, lastInbound.skuCode)} tại kho ${lastInbound.warehouseCode}: tổng tồn ${lastInbound.totalQty}, khả dụng ${lastInbound.availableQty} (Catalog skuId: ${lastInbound.skuId}).`
                        }
                        onClose={() => setLastInbound(null)}
                    />
                </div>
            )}

            {loadError && (
                <div className="mb-5">
                    <AlertBanner type="error" message={loadError} />
                </div>
            )}

            {/* Bảng danh sách seller_skus kèm thanh lọc & nút Nhập kho */}
            <div className="nexus-card">
                <div className="p-5 border-b border-border flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <h2 className="font-bold text-gray-900">
                            {lang === 'en'
                                ? 'Fulfillment Seller SKUs & Inventory'
                                : 'Kho hàng theo Fulfillment SKU (seller_skus)'}
                        </h2>
                        <p className="text-xs text-gray-500 mt-0.5">
                            {lang === 'en'
                                ? 'Catalog owns skuId (UUID). Fulfillment generates & reuses FSKU-XXXXXXXX on first inbound.'
                                : 'Catalog quản lý skuId (UUID). Fulfillment tự cấp và tái sử dụng mã FSKU-XXXXXXXX từ lần nhập kho đầu tiên.'}
                        </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-3">
                        <Input
                            aria-label={
                                lang === 'en'
                                    ? 'Filter by FSKU code or Catalog skuId'
                                    : 'Tìm theo FSKU hoặc Catalog skuId'
                            }
                            placeholder={
                                lang === 'en'
                                    ? 'Search FSKU-... or skuId'
                                    : 'Tìm FSKU-... hoặc skuId'
                            }
                            value={keyword}
                            onChange={(e) => setKeyword(e.target.value)}
                            className="w-56"
                        />
                        <Select
                            aria-label={lang === 'en' ? 'SKU status filter' : 'Lọc trạng thái SKU'}
                            value={statusFilter}
                            onChange={(e) =>
                                setStatusFilter(e.target.value as '' | SellerSkuStatus)
                            }
                            options={[
                                {
                                    value: '',
                                    label: lang === 'en' ? 'All statuses' : 'Tất cả trạng thái',
                                },
                                { value: 'ACTIVE', label: 'ACTIVE' },
                                { value: 'INACTIVE', label: 'INACTIVE' },
                            ]}
                        />
                        <Button icon={<Plus size={15} />} onClick={() => openInboundModal()}>
                            {lang === 'en' ? 'Inbound Stock' : 'Nhập kho SKU'}
                        </Button>
                    </div>
                </div>

                {!loading && skuRows.length === 0 ? (
                    <EmptyState
                        icon={<Package size={22} />}
                        title={
                            lang === 'en'
                                ? 'No Fulfillment SKUs registered yet'
                                : 'Chưa có SKU nào được nhập kho Fulfillment'
                        }
                        subtitle={
                            lang === 'en'
                                ? 'Inbound stock for a Catalog SKU ID to automatically generate its FSKU-XXXXXXXX code.'
                                : 'Thực hiện nhập kho theo Catalog skuId để Fulfillment tự động sinh mã FSKU-XXXXXXXX.'
                        }
                        action={
                            <Button icon={<Plus size={15} />} onClick={() => openInboundModal()}>
                                {lang === 'en' ? 'Inbound First SKU' : 'Nhập kho lần đầu'}
                            </Button>
                        }
                    />
                ) : (
                    <DataTable
                        loading={loading}
                        columns={[
                            {
                                key: 'skuCode',
                                label: lang === 'en' ? 'Fulfillment Code' : 'Mã Fulfillment',
                                render: (r) => (
                                    <div>
                                        <span className="font-mono text-xs font-bold px-2 py-1 rounded bg-primary-light text-primary">
                                            {r.skuCode}
                                        </span>
                                        <div className="text-[11px] text-gray-400 font-mono mt-1">
                                            skuId: {r.skuId}
                                        </div>
                                    </div>
                                ),
                            },
                            {
                                key: 'product',
                                label: lang === 'en' ? 'Catalog Product' : 'Sản phẩm Catalog',
                                render: (r) => {
                                    const match = catalogSkuMap[r.skuId]
                                    return match ? (
                                        <div>
                                            <div className="font-semibold text-sm text-gray-900">
                                                {match.productName}
                                            </div>
                                            <div className="text-xs text-gray-500">
                                                {match.attributes} ·{' '}
                                                {formatCurrency(match.originalPrice, lang)}
                                            </div>
                                        </div>
                                    ) : (
                                        <span className="text-xs text-gray-400 font-mono">
                                            {formatSkuDisplay(r.skuId, r.skuCode)}
                                        </span>
                                    )
                                },
                            },
                            {
                                key: 'status',
                                label: lang === 'en' ? 'Status' : 'Trạng thái',
                                render: (r) => (
                                    <StatusBadge status={r.status.toLowerCase()} label={r.status} />
                                ),
                            },
                            {
                                key: 'warehouseCount',
                                label: lang === 'en' ? 'Warehouses' : 'Số kho',
                                render: (r) => (
                                    <span className="text-sm font-medium">{r.warehouseCount}</span>
                                ),
                            },
                            {
                                key: 'totalQty',
                                label: lang === 'en' ? 'Total' : 'Tổng',
                                render: (r) => <span className="font-bold">{r.totalQty}</span>,
                            },
                            {
                                key: 'availableQty',
                                label: tr(lang, 'totalAvailable'),
                                render: (r) => (
                                    <span className="text-success font-semibold">
                                        {r.availableQty}
                                    </span>
                                ),
                            },
                            {
                                key: 'reservedQty',
                                label: tr(lang, 'totalReserved'),
                                render: (r) => (
                                    <span className="text-warning font-semibold">
                                        {r.reservedQty}
                                    </span>
                                ),
                            },
                            {
                                key: 'unavailableQty',
                                label: tr(lang, 'totalUnavailable'),
                                render: (r) => (
                                    <span className="text-gray-500">{r.unavailableQty}</span>
                                ),
                            },
                            {
                                key: 'actions',
                                label: '',
                                render: (r) => (
                                    <div className="flex items-center justify-end gap-2">
                                        <Button
                                            variant="outline"
                                            size="sm"
                                            icon={<Eye size={14} />}
                                            onClick={() => handleOpenDetail(r)}
                                        >
                                            {lang === 'en' ? 'Details' : 'Chi tiết'}
                                        </Button>
                                        <Button
                                            variant="secondary"
                                            size="sm"
                                            icon={<Plus size={14} />}
                                            onClick={() => openInboundModal(r.skuId)}
                                        >
                                            {lang === 'en' ? 'Inbound' : 'Nhập thêm'}
                                        </Button>
                                        {isAdmin && (
                                            <Button
                                                variant="outline"
                                                size="sm"
                                                onClick={() => {
                                                    setAdjustError(null)
                                                    setAdjustWarehouseId('')
                                                    setAdjustDeltaQty('')
                                                    setAdjustReason('')
                                                    setAdjustRefId('')
                                                    setAdjustTarget(r)
                                                }}
                                            >
                                                {lang === 'en' ? 'Adjust' : 'Điều chỉnh'}
                                            </Button>
                                        )}
                                    </div>
                                ),
                            },
                        ]}
                        data={skuRows}
                        rowKey={(r) => r.sellerSkuId || r.id || r.skuId}
                    />
                )}
            </div>

            {/* Sổ cái tồn kho: Admin xem từ /inventory/ledger; Seller xem nhật ký nhập kho trong phiên để tránh gọi API bị 403 */}
            <div className="nexus-card mt-5">
                <div className="p-5 border-b border-border flex items-center justify-between">
                    <span className="font-bold">
                        {isAdmin
                            ? lang === 'en'
                                ? 'Inventory Audit Ledger'
                                : 'Sổ cái biến động kho (Ledger)'
                            : lang === 'en'
                              ? 'Session Inbound Activity'
                              : 'Nhật ký nhập kho trong phiên'}
                    </span>
                    <span className="text-xs text-gray-500">
                        {isAdmin
                            ? 'GET /api/v1/inventory/ledger'
                            : lang === 'en'
                              ? 'Audit ledger is restricted to warehouse admins (INVENTORY.VIEW)'
                              : 'Sổ cái toàn hệ thống chỉ dành cho Admin/Kho (INVENTORY.VIEW)'}
                    </span>
                </div>
                <div className="p-5">
                    {isAdmin ? (
                        ledgerLoading ? (
                            <p className="text-sm text-gray-500">
                                {lang === 'en' ? 'Loading ledger...' : 'Đang tải sổ cái...'}
                            </p>
                        ) : ledgerRows.length === 0 ? (
                            <p className="text-sm text-gray-500">
                                {lang === 'en'
                                    ? 'No ledger entries found.'
                                    : 'Chưa có bản ghi biến động kho.'}
                            </p>
                        ) : (
                            ledgerRows.map((entry) => (
                                <div
                                    key={entry.id}
                                    className="flex items-center justify-between py-3 border-b border-border last:border-0 text-sm"
                                >
                                    <div className="flex flex-wrap items-center gap-4">
                                        <span className="text-gray-400 text-xs">
                                            {formatDateTime(entry.createdAt, lang)}
                                        </span>
                                        <span className="font-mono text-xs text-gray-700">
                                            {formatSkuDisplay(
                                                entry.skuId,
                                                existingSkuMap.get(entry.skuId)?.skuCode,
                                            )}
                                        </span>
                                        <span className="badge badge-gray text-xs">
                                            {entry.movementType}
                                        </span>
                                    </div>
                                    <div className="flex items-center gap-4">
                                        <span className="text-xs text-gray-500">
                                            {entry.refType}
                                            {entry.refId ? ` · ${entry.refId}` : ''}
                                        </span>
                                        <span
                                            className={`font-bold ${entry.quantity < 0 ? 'text-danger' : 'text-success'}`}
                                        >
                                            {entry.quantity > 0 ? '+' : ''}
                                            {entry.quantity}
                                        </span>
                                    </div>
                                </div>
                            ))
                        )
                    ) : sessionInbounds.length === 0 ? (
                        <p className="text-sm text-gray-500">
                            {lang === 'en'
                                ? 'No inbound transactions recorded in this session yet. Click "Inbound Stock" to receive goods and view assigned FSKU codes.'
                                : 'Chưa có phiếu nhập kho nào trong phiên này. Nhấn "Nhập kho SKU" để nhập hàng và nhận mã FSKU.'}
                        </p>
                    ) : (
                        sessionInbounds.map((entry) => (
                            <div
                                key={entry.id}
                                className="flex items-center justify-between py-3 border-b border-border last:border-0 text-sm"
                            >
                                <div className="flex flex-wrap items-center gap-4">
                                    <span className="text-gray-400 text-xs">
                                        {formatDateTime(entry.createdAt, lang)}
                                    </span>
                                    <span className="font-mono text-xs font-semibold text-primary">
                                        {entry.skuCode}
                                    </span>
                                    <span className="badge badge-green text-xs">INBOUND</span>
                                    <span className="text-xs text-gray-500">
                                        {entry.warehouseCode} · skuId: {entry.skuId}
                                    </span>
                                </div>
                                <span className="font-bold text-success">
                                    +{entry.quantity} (Tổng: {entry.totalQty})
                                </span>
                            </div>
                        ))
                    )}
                </div>
            </div>

            {/* Modal Nhập kho POST /api/v1/inventory/inbound */}
            <Modal
                open={inboundOpen}
                onClose={() => !inboundSubmitting && setInboundOpen(false)}
                title={
                    lang === 'en'
                        ? 'Inbound Stock to Fulfillment Warehouse'
                        : 'Nhập kho Fulfillment theo Catalog skuId'
                }
                footer={
                    <>
                        <Button
                            variant="outline"
                            onClick={() => setInboundOpen(false)}
                            disabled={inboundSubmitting}
                        >
                            {lang === 'en' ? 'Cancel' : 'Hủy'}
                        </Button>
                        <Button
                            type="submit"
                            form="inbound-stock-form"
                            loading={inboundSubmitting}
                            icon={<CheckCircle size={15} />}
                        >
                            {lang === 'en' ? 'Confirm Inbound' : 'Xác nhận nhập kho'}
                        </Button>
                    </>
                }
            >
                <form id="inbound-stock-form" onSubmit={handleInboundSubmit} className="space-y-4">
                    {inboundError && <AlertBanner type="error" message={inboundError} />}

                    {catalogSkus.length > 0 && (
                        <Select
                            label={
                                lang === 'en'
                                    ? 'Select Catalog SKU'
                                    : 'Chọn biến thể SKU từ Catalog'
                            }
                            value={selectedCatalogKey}
                            onChange={(e) => {
                                const val = e.target.value
                                setSelectedCatalogKey(val)
                                if (val !== '__custom__') {
                                    setInboundSkuId(val)
                                }
                            }}
                            options={skuSelectOptions}
                        />
                    )}

                    <Input
                        label={lang === 'en' ? 'Catalog SKU ID (UUID)' : 'Catalog skuId (UUID)'}
                        value={inboundSkuId}
                        onChange={(e) => {
                            setInboundSkuId(e.target.value)
                            setSelectedCatalogKey(
                                catalogSkuMap[e.target.value] ? e.target.value : '__custom__',
                            )
                        }}
                        placeholder="00000000-0000-0000-0000-000000000000"
                        required
                    />

                    {inboundSkuId.trim() && (
                        <div className="p-3 rounded-lg bg-gray-50 border border-border text-xs text-gray-700">
                            {selectedExisting ? (
                                <span>
                                    {lang === 'en'
                                        ? `Existing Fulfillment code will be reused: `
                                        : `SKU đã đăng ký kho, sẽ tái sử dụng mã: `}
                                    <strong className="font-mono text-primary">
                                        {selectedExisting.skuCode}
                                    </strong>
                                </span>
                            ) : (
                                <span>
                                    {lang === 'en'
                                        ? 'First inbound for this Catalog skuId: Fulfillment will generate a unique FSKU-XXXXXXXX code.'
                                        : 'Lần nhập kho đầu tiên cho skuId này: Fulfillment sẽ tự động sinh mã FSKU-XXXXXXXX duy nhất.'}
                                </span>
                            )}
                        </div>
                    )}

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <Input
                            label={lang === 'en' ? 'Quantity *' : 'Số lượng nhập *'}
                            type="number"
                            min={1}
                            value={inboundQty}
                            onChange={(e) => setInboundQty(e.target.value)}
                            required
                        />
                        <Input
                            label={
                                lang === 'en'
                                    ? 'Unit Cost (optional)'
                                    : 'Giá vốn / đơn vị (tùy chọn)'
                            }
                            type="number"
                            min={0}
                            value={inboundUnitCost}
                            onChange={(e) => setInboundUnitCost(e.target.value)}
                            placeholder="1500000"
                        />
                    </div>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <Input
                            label={
                                lang === 'en'
                                    ? 'Warehouse ID (blank = WH_001)'
                                    : 'Mã kho UUID (trống = WH_001)'
                            }
                            value={inboundWarehouseId}
                            onChange={(e) => setInboundWarehouseId(e.target.value)}
                            placeholder="Optional UUID"
                        />
                        <Input
                            label={
                                lang === 'en' ? 'Reference ID (UUID)' : 'Mã tham chiếu phiếu (UUID)'
                            }
                            value={inboundRefId}
                            onChange={(e) => setInboundRefId(e.target.value)}
                            placeholder="Optional UUID"
                        />
                    </div>
                </form>
            </Modal>

            {/* Modal Chi tiết tồn theo kho GET /api/v1/inventory/skus/{id} */}
            <Modal
                open={detailOpen}
                onClose={() => setDetailOpen(false)}
                title={
                    detailData
                        ? `${detailData.skuCode} · ${lang === 'en' ? 'Warehouse Breakdown' : 'Chi tiết tồn kho'}`
                        : lang === 'en'
                          ? 'Seller SKU Details'
                          : 'Chi tiết Fulfillment SKU'
                }
                size="lg"
                footer={
                    <Button variant="outline" onClick={() => setDetailOpen(false)}>
                        {lang === 'en' ? 'Close' : 'Đóng'}
                    </Button>
                }
            >
                {detailLoading && (
                    <p className="text-sm text-gray-500">
                        {lang === 'en' ? 'Loading warehouse stock...' : 'Đang tải tồn từng kho...'}
                    </p>
                )}
                {detailError && <AlertBanner type="error" message={detailError} />}
                {detailData && (
                    <div className="space-y-4">
                        <div className="grid grid-cols-2 gap-3 p-4 rounded-lg bg-gray-50 border border-border text-xs">
                            <div>
                                <div className="text-gray-500">Fulfillment Code</div>
                                <div className="font-mono font-bold text-primary text-sm">
                                    {detailData.skuCode}
                                </div>
                            </div>
                            <div>
                                <div className="text-gray-500">Status</div>
                                <div className="mt-0.5">
                                    <StatusBadge
                                        status={detailData.status.toLowerCase()}
                                        label={detailData.status}
                                    />
                                </div>
                            </div>
                            <div>
                                <div className="text-gray-500">Catalog skuId</div>
                                <div className="font-mono break-all">{detailData.skuId}</div>
                            </div>
                            <div>
                                <div className="text-gray-500">Seller SKU Record ID</div>
                                <div className="font-mono break-all">{detailData.sellerSkuId}</div>
                            </div>
                        </div>

                        <DataTable
                            columns={[
                                {
                                    key: 'warehouseCode',
                                    label: lang === 'en' ? 'Warehouse' : 'Kho',
                                    render: (w) => (
                                        <div>
                                            <div className="font-bold text-sm">
                                                {w.warehouseCode}
                                            </div>
                                            <div className="text-[11px] text-gray-400 font-mono">
                                                {w.warehouseId}
                                            </div>
                                        </div>
                                    ),
                                },
                                {
                                    key: 'totalQty',
                                    label: lang === 'en' ? 'Total' : 'Tổng',
                                    render: (w) => <span className="font-bold">{w.totalQty}</span>,
                                },
                                {
                                    key: 'availableQty',
                                    label: tr(lang, 'totalAvailable'),
                                    render: (w) => (
                                        <span className="text-success font-semibold">
                                            {w.availableQty}
                                        </span>
                                    ),
                                },
                                {
                                    key: 'reservedQty',
                                    label: tr(lang, 'totalReserved'),
                                    render: (w) => (
                                        <span className="text-warning font-semibold">
                                            {w.reservedQty}
                                        </span>
                                    ),
                                },
                                {
                                    key: 'unavailableQty',
                                    label: tr(lang, 'totalUnavailable'),
                                    render: (w) => <span>{w.unavailableQty}</span>,
                                },
                                {
                                    key: 'inboundAction',
                                    label: '',
                                    render: (w) => (
                                        <Button
                                            size="sm"
                                            variant="secondary"
                                            onClick={() => {
                                                setDetailOpen(false)
                                                openInboundModal(detailData.skuId, w.warehouseId)
                                            }}
                                        >
                                            {lang === 'en' ? 'Inbound' : 'Nhập kho'}
                                        </Button>
                                    ),
                                },
                            ]}
                            data={detailData.warehouses || []}
                            rowKey={(w) => `${w.warehouseId}-${w.skuId}`}
                        />
                    </div>
                )}
            </Modal>

            {/* Modal Điều chỉnh tồn kho (chỉ Admin) */}
            <Modal
                open={Boolean(adjustTarget)}
                onClose={() => !adjustSubmitting && setAdjustTarget(null)}
                title={
                    adjustTarget
                        ? `${lang === 'en' ? 'Adjust Stock' : 'Điều chỉnh tồn kho'} · ${adjustTarget.skuCode}`
                        : ''
                }
                footer={
                    <>
                        <Button
                            variant="outline"
                            onClick={() => setAdjustTarget(null)}
                            disabled={adjustSubmitting}
                        >
                            {lang === 'en' ? 'Cancel' : 'Hủy'}
                        </Button>
                        <Button type="submit" form="adjust-stock-form" loading={adjustSubmitting}>
                            {lang === 'en' ? 'Apply Adjustment' : 'Lưu điều chỉnh'}
                        </Button>
                    </>
                }
            >
                <form id="adjust-stock-form" onSubmit={handleAdjustSubmit} className="space-y-4">
                    {adjustError && <AlertBanner type="error" message={adjustError} />}
                    <Input
                        label={
                            lang === 'en'
                                ? 'Delta Quantity (+/- integer) *'
                                : 'Chênh lệch kiểm kê (+/- số nguyên) *'
                        }
                        type="number"
                        value={adjustDeltaQty}
                        onChange={(e) => setAdjustDeltaQty(e.target.value)}
                        placeholder="-2 or +5"
                        required
                    />
                    <Input
                        label={lang === 'en' ? 'Reason *' : 'Lý do điều chỉnh *'}
                        value={adjustReason}
                        onChange={(e) => setAdjustReason(e.target.value)}
                        placeholder={
                            lang === 'en' ? 'Cycle count reconciliation' : 'Kiểm kê định kỳ'
                        }
                        required
                    />
                    <Input
                        label={
                            lang === 'en'
                                ? 'Warehouse ID (blank = WH_001)'
                                : 'Mã kho UUID (trống = WH_001)'
                        }
                        value={adjustWarehouseId}
                        onChange={(e) => setAdjustWarehouseId(e.target.value)}
                    />
                    <Input
                        label={lang === 'en' ? 'Reference ID (UUID)' : 'Mã phiếu tham chiếu (UUID)'}
                        value={adjustRefId}
                        onChange={(e) => setAdjustRefId(e.target.value)}
                    />
                </form>
            </Modal>
        </SellerLayout>
    )
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
