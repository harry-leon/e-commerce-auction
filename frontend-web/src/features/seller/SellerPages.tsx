import { useState, useEffect } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate } from '../../i18n'
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
    Pagination,
    Toggle,
    Breadcrumb,
    Plus,
    Pencil,
    Trash2,
    Download,
    TrendingUp,
    Package,
    Gavel,
    Tag,
    AlertTriangle,
    CheckCircle,
    Star,
    Camera,
    Bell,
    Search,
} from '../../components/UI'
import { PRODUCTS, AUCTIONS, ORDERS, NOTIFICATIONS } from '../../mockData'
import type { Auction, Order, OrderStatus, ProductStatus } from '../../types'
import { getEffectivePaymentStatus } from '../../payment'
import { ordersApi } from '../../api/orders'

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
            pending: tr(lang, 'statusPending'),
            paid: tr(lang, 'statusPaid'),
            processing: tr(lang, 'statusProcessing'),
            shipped: tr(lang, 'statusShipped'),
            delivered: tr(lang, 'statusDelivered'),
            completed: tr(lang, 'statusCompleted'),
            cancelled: tr(lang, 'statusCancelled'),
            refunded: tr(lang, 'statusRefunded'),
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
                        onRowClick={(r) => navigate('seller-orders')}
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
export function SellerProductListPage() {
    const { lang, navigate, showToast } = useApp()
    const [search, setSearch] = useState('')
    const [statusFilter, setStatusFilter] = useState<ProductStatus | 'all'>('all')
    const [deleteId, setDeleteId] = useState<string | null>(null)

    const statusLabel = (s: string) =>
        ({ draft: tr(lang, 'draft'), active: tr(lang, 'active'), inactive: tr(lang, 'inactive') })[
        s
        ] || s
    const filtered = PRODUCTS.filter(
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
                onConfirm={() =>
                    showToast({
                        type: 'success',
                        message: lang === 'en' ? 'Product deleted.' : 'Đã xóa sản phẩm.',
                    })
                }
                title={tr(lang, 'confirmDeleteTitle')}
                message={tr(lang, 'confirmDeleteMsg')}
                danger
            />
        </SellerLayout>
    )
}

// ─── Create / Edit Product ─────────────────────────────────────────────────────
export function CreateProductPage() {
    const { lang, navigate, showToast } = useApp()
    const [saving, setSaving] = useState(false)
    const [name, setName] = useState('')
    const [price, setPrice] = useState('')
    const [stock, setStock] = useState('')
    const [sku, setSku] = useState('')
    const [status, setStatus] = useState('active')
    const [enableAuction, setEnableAuction] = useState(false)
    const [nameError, setNameError] = useState('')

    const save = async () => {
        if (!name) {
            setNameError(tr(lang, 'required'))
            return
        }
        setSaving(true)
        await new Promise((r) => setTimeout(r, 800))
        setSaving(false)
        showToast({
            type: 'success',
            message: lang === 'en' ? 'Product saved!' : 'Đã lưu sản phẩm!',
        })
        navigate('seller-products')
    }

    return (
        <SellerLayout title={tr(lang, 'createProduct')}>
            <Breadcrumb
                items={[
                    { label: tr(lang, 'products'), page: 'seller-products' },
                    { label: tr(lang, 'createProduct') },
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
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Pricing & Inventory' : 'Giá & Kho hàng'}
                        </h3>
                        <div className="grid grid-cols-3 gap-4">
                            <Input
                                label={`${tr(lang, 'price')} ($)`}
                                type="number"
                                value={price}
                                onChange={(e) => setPrice(e.target.value)}
                                placeholder="0.00"
                            />
                            <Input
                                label={tr(lang, 'stock')}
                                type="number"
                                value={stock}
                                onChange={(e) => setStock(e.target.value)}
                                placeholder="0"
                            />
                            <Input
                                label={tr(lang, 'sku')}
                                value={sku}
                                onChange={(e) => setSku(e.target.value)}
                                placeholder="SKU-001"
                            />
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
                            value="c1-3"
                            onChange={() => { }}
                            options={[
                                { value: 'c1-3', label: 'Electronics > Audio' },
                                { value: 'c1-1', label: 'Electronics > Smartphones' },
                                { value: 'c2-1', label: 'Fashion > Men' },
                            ]}
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
        </SellerLayout>
    )
}

// ─── Seller Auction List ───────────────────────────────────────────────────────
/** Review có thể chưa tồn tại trong state; khi hiển thị kết quả dùng optional access và giữ trạng thái gốc nếu chưa duyệt. */
export function SellerAuctionListPage() {
    const { lang, navigate, showToast, auctionReviews, createdAuctions } = useApp()
    const [tab, setTab] = useState('all')
    const [cancelOpen, setCancelOpen] = useState(false)
    const [reviewedAuction, setReviewedAuction] = useState<Auction | null>(null)

    const sellerAuctions = [...createdAuctions, ...AUCTIONS]
        .filter((auction) => auction.sellerId === 'u2')
        .map((auction) => ({
            ...auction,
            status: auctionReviews[auction.id]?.status || auction.status,
        }))
    const filtered = tab === 'all' ? sellerAuctions : sellerAuctions.filter((a) => a.status === tab)
    const statusLabel = (s: string) =>
        ({
            pending_approval: lang === 'en' ? 'Pending approval' : 'Chờ Admin duyệt',
            scheduled: tr(lang, 'statusScheduled'),
            active: tr(lang, 'statusActive'),
            ended: tr(lang, 'statusEnded'),
            settled: tr(lang, 'statusSettled'),
            cancelled: tr(lang, 'statusCancelled'),
            failed: tr(lang, 'statusFailed'),
            rejected: lang === 'en' ? 'Rejected' : 'Bị từ chối',
        })[s] || s

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
                            id: 'active',
                            label: tr(lang, 'statusActive'),
                            count: sellerAuctions.filter((a) => a.status === 'active').length,
                        },
                        { id: 'scheduled', label: tr(lang, 'statusScheduled') },
                        { id: 'ended', label: tr(lang, 'statusEnded') },
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
                                        src={r.product.images[0]}
                                        alt=""
                                        className="w-10 h-10 object-cover rounded-lg"
                                    />
                                    <p className="text-sm font-semibold line-clamp-1">
                                        {lang === 'vi' ? r.product.nameVi : r.product.name}
                                    </p>
                                </div>
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
                            key: 'endTime',
                            label: lang === 'en' ? 'End Time' : 'Kết thúc',
                            render: (r: any) => (
                                <span className="text-xs text-gray-500">
                                    {formatDate(r.endTime, lang)}
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
                                    {['pending_approval', 'scheduled'].includes(r.status) && (
                                        <button className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5">
                                            <Pencil size={14} />
                                        </button>
                                    )}
                                    {['pending_approval', 'scheduled', 'active'].includes(
                                        r.status,
                                    ) && (
                                            <button
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    setCancelOpen(true)
                                                }}
                                                className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger"
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
                            ),
                        },
                    ]}
                    data={filtered}
                    rowKey={(r) => r.id}
                />
            </div>
            <Modal
                open={cancelOpen}
                onClose={() => setCancelOpen(false)}
                title={tr(lang, 'cancelAuction')}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setCancelOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="danger"
                            onClick={() => {
                                setCancelOpen(false)
                                showToast({
                                    type: 'success',
                                    message:
                                        lang === 'en'
                                            ? 'Auction cancelled.'
                                            : 'Đã hủy phiên đấu giá.',
                                })
                            }}
                        >
                            {tr(lang, 'confirm')}
                        </Button>
                    </>
                }
            >
                <Textarea
                    label={tr(lang, 'cancelReason')}
                    rows={3}
                    placeholder={
                        lang === 'en' ? 'Reason for cancellation...' : 'Lý do hủy phiên đấu giá...'
                    }
                />
            </Modal>
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

// ─── Create Auction ────────────────────────────────────────────────────────────
export function CreateAuctionPage() {
    const { lang, navigate, showToast, user, submitAuction } = useApp()
    const [saving, setSaving] = useState(false)
    const [repError, setRepError] = useState(false)
    const [overLimit, setOverLimit] = useState(false)
    const [productId, setProductId] = useState('')
    const [startingPrice, setStartingPrice] = useState('')
    const [minIncrement, setMinIncrement] = useState('')
    const [startTime, setStartTime] = useState('')
    const [endTime, setEndTime] = useState('')
    const [visibility, setVisibility] = useState<'public' | 'restricted'>('public')

    const save = async () => {
        const product = PRODUCTS.find((item) => item.id === productId)
        if (!product || !startingPrice || !minIncrement) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'Select a product and enter the auction prices.'
                        : 'Vui lòng chọn sản phẩm và nhập giá đấu giá.',
            })
            return
        }
        setSaving(true)
        await new Promise((r) => setTimeout(r, 800))
        submitAuction({
            id: `auction-${Date.now()}`,
            productId: product.id,
            product,
            sellerId: user?.id || product.sellerId,
            sellerName: user?.fullName || product.sellerName,
            status: 'pending_approval',
            startingPrice: Number(startingPrice),
            currentBid: Number(startingPrice),
            minIncrement: Number(minIncrement),
            startTime: startTime
                ? new Date(startTime).toISOString()
                : new Date(Date.now() + 86400000).toISOString(),
            endTime: endTime
                ? new Date(endTime).toISOString()
                : new Date(Date.now() + 3 * 86400000).toISOString(),
            bidCount: 0,
            bids: [],
            visibility,
        })
        setSaving(false)
        showToast({
            type: 'success',
            message:
                lang === 'en'
                    ? 'Auction submitted for Admin approval.'
                    : 'Đã gửi phiên đấu giá để Admin phê duyệt.',
        })
        navigate('seller-auctions')
    }

    return (
        <SellerLayout title={tr(lang, 'createAuction')}>
            <Breadcrumb
                items={[
                    { label: tr(lang, 'auctions'), page: 'seller-auctions' },
                    { label: tr(lang, 'createAuction') },
                ]}
            />
            <div className="mt-5 max-w-2xl">
                <div className="space-y-5">
                    <AlertBanner
                        type="info"
                        message={
                            lang === 'en'
                                ? 'New auctions must be approved by Admin before they are scheduled or visible to buyers.'
                                : 'Phiên đấu giá mới phải được Admin phê duyệt trước khi được lên lịch hoặc hiển thị cho Buyer.'
                        }
                    />
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
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Select Product' : 'Chọn sản phẩm'}
                        </h3>
                        <Select
                            label={lang === 'en' ? 'Product' : 'Sản phẩm'}
                            value={productId}
                            onChange={(e) => setProductId(e.target.value)}
                            options={[
                                {
                                    value: '',
                                    label: lang === 'en' ? 'Select a product' : 'Chọn sản phẩm',
                                },
                                ...PRODUCTS.filter((p) => p.enableAuction).map((p) => ({
                                    value: p.id,
                                    label: lang === 'vi' ? p.nameVi : p.name,
                                })),
                            ]}
                        />
                    </div>
                    <div className="nexus-card p-5">
                        <h3 className="font-bold mb-4">
                            {lang === 'en' ? 'Auction Settings' : 'Cài đặt đấu giá'}
                        </h3>
                        <div className="grid grid-cols-2 gap-4">
                            <Input
                                label={`${tr(lang, 'startingPrice')} ($)`}
                                type="number"
                                value={startingPrice}
                                onChange={(e) => setStartingPrice(e.target.value)}
                                placeholder="100.00"
                            />
                            <Input
                                label={`${tr(lang, 'minIncrement')} ($)`}
                                type="number"
                                value={minIncrement}
                                onChange={(e) => setMinIncrement(e.target.value)}
                                placeholder="5.00"
                                helper={lang === 'en' ? 'Min $1.00' : 'Tối thiểu $1.00'}
                            />
                            <Input
                                label={tr(lang, 'startTime')}
                                type="datetime-local"
                                value={startTime}
                                onChange={(e) => setStartTime(e.target.value)}
                            />
                            <Input
                                label={tr(lang, 'endTime')}
                                type="datetime-local"
                                value={endTime}
                                onChange={(e) => setEndTime(e.target.value)}
                            />
                        </div>
                        <p className="text-xs text-gray-400 mt-2">{tr(lang, 'auctionDuration')}</p>
                    </div>
                    <div className="nexus-card p-5">
                        <Select
                            label={lang === 'en' ? 'Visibility' : 'Hiển thị'}
                            value={visibility}
                            onChange={(e) =>
                                setVisibility(e.target.value as 'public' | 'restricted')
                            }
                            options={[
                                { value: 'public', label: lang === 'en' ? 'Public' : 'Công khai' },
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
                        <Button variant="primary" size="lg" loading={saving} onClick={save}>
                            {lang === 'en' ? 'Submit for Approval' : 'Gửi phê duyệt'}
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

// ─── Seller Orders ─────────────────────────────────────────────────────────────
/**
 * Màn hình Quản lý đơn hàng của Seller (SRS 3.2.1 & ORDER-02).
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
    const { lang, showToast } = useApp()
    const [orders, setOrders] = useState<Order[]>([])
    const [loading, setLoading] = useState<boolean>(true)
    const [search, setSearch] = useState<string>('')
    const [statusFilter, setStatusFilter] = useState<string>('all')
    const [selectedOrder, setSelectedOrder] = useState<Order | null>(null)
    const [updatingId, setUpdatingId] = useState<string | null>(null)

    const statusLabel = (s: string) =>
        ({
            pending: tr(lang, 'statusPending'),
            paid: tr(lang, 'statusPaid'),
            processing: tr(lang, 'statusProcessing'),
            shipped: tr(lang, 'statusShipped'),
            delivered: tr(lang, 'statusDelivered'),
            completed: tr(lang, 'statusCompleted'),
            cancelled: tr(lang, 'statusCancelled'),
            refunded: tr(lang, 'statusRefunded'),
        })[s] || s

    const paymentLabel = (s: string) =>
        ({
            held: lang === 'en' ? 'Held by Nexus' : 'Nexus đang giữ',
            protection_period: lang === 'en' ? 'Buyer protection' : 'Bảo vệ Buyer',
            released: lang === 'en' ? 'In seller balance' : 'Trong số dư',
            on_hold: lang === 'en' ? 'Dispute hold' : 'Giữ do khiếu nại',
            refunded: lang === 'en' ? 'Refunded' : 'Đã hoàn tiền',
            partial_refund: lang === 'en' ? 'Partial refund' : 'Hoàn một phần',
        })[s] || s

    // Nạp danh sách đơn hàng cho người bán
    const loadSellerOrders = async () => {
        setLoading(true)
        try {
            const data = await ordersApi.getOrders({
                role: 'seller',
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
            await ordersApi.updateOrderStatus(orderId, newStatus as OrderStatus, 'Seller status update')
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
                message: err.message || (lang === 'en' ? 'Failed to update order status.' : 'Lỗi cập nhật trạng thái đơn.'),
            })
        } finally {
            setUpdatingId(null)
        }
    }

    // Thống kê nhanh số lượng đơn
    const totalRevenue = orders
        .filter((o) => !['cancelled', 'refunded'].includes(o.status))
        .reduce((sum, o) => sum + o.total, 0)
    const processingCount = orders.filter((o) => o.status === 'processing').length
    const shippedCount = orders.filter((o) => o.status === 'shipped').length
    const deliveredCount = orders.filter((o) => ['delivered', 'completed'].includes(o.status)).length

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
                            placeholder={lang === 'en' ? 'Search order ID or buyer...' : 'Tìm mã đơn hoặc người mua...'}
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
                            <option value="pending">{tr(lang, 'statusPending')}</option>
                            <option value="paid">{tr(lang, 'statusPaid')}</option>
                            <option value="processing">{tr(lang, 'statusProcessing')}</option>
                            <option value="shipped">{tr(lang, 'statusShipped')}</option>
                            <option value="delivered">{tr(lang, 'statusDelivered')}</option>
                            <option value="completed">{tr(lang, 'statusCompleted')}</option>
                            <option value="cancelled">{tr(lang, 'statusCancelled')}</option>
                            <option value="refunded">{tr(lang, 'statusRefunded')}</option>
                        </select>
                    </div>
                    <span className="text-xs text-gray-400">
                        {lang === 'en' ? `${orders.length} orders found` : `Tìm thấy ${orders.length} đơn hàng`}
                    </span>
                </div>

                {loading ? (
                    <div className="py-12 text-center text-gray-400">
                        <div className="inline-block animate-spin w-8 h-8 border-4 border-primary border-t-transparent rounded-full mb-2" />
                        <p>{lang === 'en' ? 'Loading orders...' : 'Đang tải danh sách đơn hàng...'}</p>
                    </div>
                ) : (
                    <DataTable
                        columns={[
                            {
                                key: 'id',
                                label: lang === 'en' ? 'Order' : 'Đơn hàng',
                                render: (r: Order) => (
                                    <div className="flex flex-col">
                                        <span className="font-mono text-xs font-bold text-gray-900">{r.id}</span>
                                        <span className="text-[11px] text-gray-400">{formatDate(r.createdAt, lang)}</span>
                                    </div>
                                ),
                            },
                            {
                                key: 'buyerName',
                                label: lang === 'en' ? 'Buyer & Address' : 'Người mua & Địa chỉ',
                                render: (r: Order) => (
                                    <div>
                                        <p className="font-medium text-sm text-gray-800">{r.buyerName}</p>
                                        <p className="text-xs text-gray-500 line-clamp-1 max-w-[200px]" title={r.shippingAddress}>
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
                                                    +{r.items.length - 1} {lang === 'en' ? 'more' : 'khác'}
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
                                    return <StatusBadge status={status} label={paymentLabel(status)} />
                                },
                            },
                            {
                                key: 'actions',
                                label: lang === 'en' ? 'Actions' : 'Thao tác',
                                render: (r: Order) => (
                                    <div className="flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
                                        <Button
                                            variant="ghost"
                                            size="sm"
                                            onClick={() => setSelectedOrder(r)}
                                            className="text-xs px-2 py-1"
                                        >
                                            {lang === 'en' ? 'View' : 'Chi tiết'}
                                        </Button>

                                        {/* Dropdown cập nhật trạng thái hợp lệ */}
                                        <select
                                            className="nexus-input text-xs py-1 px-2 w-32 font-medium"
                                            value=""
                                            disabled={updatingId === r.id || ['cancelled', 'completed', 'refunded'].includes(r.status)}
                                            onChange={(e) => handleStatusChange(r.id, e.target.value)}
                                        >
                                            <option value="">{lang === 'en' ? 'Change status' : 'Đổi trạng thái'}</option>
                                            {r.status === 'paid' && (
                                                <option value="processing">{lang === 'en' ? 'To Processing' : 'Sang Đóng gói'}</option>
                                            )}
                                            {['paid', 'processing'].includes(r.status) && (
                                                <option value="shipped">{lang === 'en' ? 'To Shipped' : 'Sang Đang giao'}</option>
                                            )}
                                            {r.status === 'shipped' && (
                                                <option value="delivered">{lang === 'en' ? 'To Delivered' : 'Sang Đã giao'}</option>
                                            )}
                                            {['pending', 'paid', 'processing'].includes(r.status) && (
                                                <option value="cancelled">{lang === 'en' ? 'Cancel Order' : 'Hủy đơn hàng'}</option>
                                            )}
                                        </select>
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
                title={lang === 'en' ? `Order Detail: ${selectedOrder?.id}` : `Chi tiết đơn hàng: ${selectedOrder?.id}`}
                size="lg"
            >
                {selectedOrder && (
                    <div className="space-y-5">
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 p-4 bg-gray-50 rounded-xl">
                            <div>
                                <p className="text-xs text-gray-500">{lang === 'en' ? 'Buyer Name' : 'Người mua'}</p>
                                <p className="font-semibold text-gray-900 mt-0.5">{selectedOrder.buyerName}</p>
                            </div>
                            <div>
                                <p className="text-xs text-gray-500">{lang === 'en' ? 'Payment Escrow' : 'Ký quỹ thanh toán'}</p>
                                <div className="mt-1">
                                    <StatusBadge
                                        status={getEffectivePaymentStatus(selectedOrder)}
                                        label={paymentLabel(getEffectivePaymentStatus(selectedOrder))}
                                    />
                                </div>
                            </div>
                            <div>
                                <p className="text-xs text-gray-500">{lang === 'en' ? 'Order Status' : 'Trạng thái đơn'}</p>
                                <div className="mt-1">
                                    <StatusBadge
                                        status={selectedOrder.status}
                                        label={statusLabel(selectedOrder.status)}
                                    />
                                </div>
                            </div>
                        </div>

                        <div>
                            <p className="text-xs text-gray-500 mb-1">{tr(lang, 'shippingAddress')}</p>
                            <p className="text-sm font-medium text-gray-800 bg-white border border-border p-3 rounded-lg">
                                {selectedOrder.shippingAddress}
                            </p>
                        </div>

                        <div>
                            <p className="text-sm font-semibold mb-3">{lang === 'en' ? 'Packing Items' : 'Danh sách sản phẩm cần đóng gói'}</p>
                            <div className="border border-border rounded-xl divide-y divide-border/60 overflow-hidden">
                                {selectedOrder.items.map((item) => (
                                    <div key={item.productId} className="flex items-center justify-between p-3 gap-3">
                                        <div className="flex items-center gap-3">
                                            <img
                                                src={item.image}
                                                alt=""
                                                className="w-12 h-12 object-cover rounded-lg border border-border"
                                            />
                                            <div>
                                                <p className="text-sm font-medium text-gray-900">{item.productName}</p>
                                                <p className="text-xs text-gray-500">
                                                    ×{item.quantity} · {formatCurrency(item.unitPrice, lang)}
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
                            <span className="text-sm text-gray-600 font-medium">{tr(lang, 'total')}</span>
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
export function SellerInventoryPage() {
    const { lang } = useApp()
    return (
        <SellerLayout title={tr(lang, 'inventory')}>
            <div className="grid grid-cols-3 gap-5 mb-6">
                {[
                    [
                        lang === 'en' ? 'Total Stock' : 'Tổng tồn kho',
                        318,
                        'bg-primary-light text-primary',
                    ],
                    [tr(lang, 'totalAvailable'), 280, 'bg-success-light text-success'],
                    [tr(lang, 'totalReserved'), 38, 'bg-warning-light text-warning'],
                ].map(([l, v, cls]) => (
                    <div key={String(l)} className={`nexus-card p-5 ${cls}`}>
                        <p className="text-sm font-semibold mb-1">{l}</p>
                        <p className="text-3xl font-black">{v}</p>
                    </div>
                ))}
            </div>
            <div className="nexus-card">
                <div className="p-5 border-b border-border font-bold">
                    {lang === 'en' ? 'Inventory by SKU' : 'Kho hàng theo SKU'}
                </div>
                <DataTable
                    columns={[
                        {
                            key: 'sku',
                            label: 'SKU',
                            render: (r: any) => <span className="font-mono text-xs">{r.sku}</span>,
                        },
                        {
                            key: 'name',
                            label: lang === 'en' ? 'Product' : 'Sản phẩm',
                            render: (r: any) => (
                                <span className="font-semibold text-sm">
                                    {lang === 'vi' ? r.nameVi : r.name}
                                </span>
                            ),
                        },
                        {
                            key: 'stock',
                            label: lang === 'en' ? 'Total' : 'Tổng',
                            render: (r: any) => <span className="font-bold">{r.stock}</span>,
                        },
                        {
                            key: 'available',
                            label: tr(lang, 'totalAvailable'),
                            render: (r: any) => (
                                <span className="text-success font-semibold">{r.stock - 2}</span>
                            ),
                        },
                        {
                            key: 'reserved',
                            label: tr(lang, 'totalReserved'),
                            render: () => <span className="text-warning font-semibold">2</span>,
                        },
                        {
                            key: 'unavailable',
                            label: tr(lang, 'totalUnavailable'),
                            render: () => <span className="text-gray-400">0</span>,
                        },
                        {
                            key: 'actions',
                            label: '',
                            render: () => (
                                <Button variant="outline" size="sm">
                                    {lang === 'en' ? 'Adjust' : 'Điều chỉnh'}
                                </Button>
                            ),
                        },
                    ]}
                    data={PRODUCTS}
                    rowKey={(r) => r.id}
                />
            </div>
            <div className="nexus-card mt-5">
                <div className="p-5 border-b border-border font-bold">
                    {lang === 'en' ? 'Inventory Ledger' : 'Lịch sử biến động kho'}
                </div>
                <div className="p-5">
                    {[
                        {
                            date: '2024-02-15',
                            sku: 'SNY-WH1000XM5-BK',
                            type: 'sale',
                            qty: -1,
                            notes: 'Order ORD-20240215-001',
                        },
                        {
                            date: '2024-02-14',
                            sku: 'NKE-AM270-WHT-42',
                            type: 'sale',
                            qty: -2,
                            notes: 'Order ORD-20240220-002',
                        },
                        {
                            date: '2024-02-10',
                            sku: 'SNY-WH1000XM5-BK',
                            type: 'restock',
                            qty: +10,
                            notes: 'Manual restock',
                        },
                    ].map((entry, i) => (
                        <div
                            key={i}
                            className="flex items-center justify-between py-3 border-b border-border last:border-0 text-sm"
                        >
                            <div className="flex items-center gap-4">
                                <span className="text-gray-400 text-xs w-20">{entry.date}</span>
                                <span className="font-mono text-xs text-gray-600">{entry.sku}</span>
                                <span className="badge badge-gray text-xs">{entry.type}</span>
                            </div>
                            <div className="flex items-center gap-4">
                                <span className="text-xs text-gray-500">{entry.notes}</span>
                                <span
                                    className={`font-bold ${entry.qty < 0 ? 'text-danger' : 'text-success'}`}
                                >
                                    {entry.qty > 0 ? '+' : ''}
                                    {entry.qty}
                                </span>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
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
                                    <Toggle checked={index < 3} onChange={() => { }} />
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>
        </SellerLayout>
    )
}
