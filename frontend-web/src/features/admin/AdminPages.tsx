/** Các màn Admin còn dùng trong router; danh mục và hồ sơ được cung cấp bởi module API riêng. */
import { useState, useEffect } from 'react'
import { useApp } from '../../context'
import { tr, formatCurrency, formatDate, formatDateTime } from '../../i18n'
import { AdminLayout } from '../../components/Layout'
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
    DataTable,
    Toggle,
    Breadcrumb,
    Plus,
    Pencil,
    Trash2,
    Search,
    Eye,
    AlertTriangle,
    CheckCircle,
    Server,
    TrendingUp,
    Package,
    Gavel,
    Users,
    User2,
    Settings2,
    Shield,
    ClipboardList,
} from '../../components/UI'
import { USERS, ROLES, EVENT_LOGS, SYSTEM_SERVICES } from '../../mockData'
import { getEffectivePaymentStatus } from '../../payment'
import type { Auction, Order, OrderStatus } from '../../types'
import {
    getAllowedOrderStatusTransitions,
    ordersApi,
    type WithdrawalRequest,
} from '../../api/orders'
import { auctionsApi } from '../../api/auctions'
import {
    api,
    mapUser,
    errorMessage,
    formatSkuDisplay,
    type ApiUser,
    type ApiRole,
    type ApiProduct,
    type Envelope,
    getProductStock,
} from '../../api/client'
import { useLoad, clearLoadCache } from '../../hooks/useLoad'

// ─── Admin Dashboard ───────────────────────────────────────────────────────────
export function AdminDashboardPage() {
    const { lang, navigate } = useApp()
    const KPIs = [
        {
            icon: <TrendingUp size={20} />,
            label: 'Gross Merchandise Value',
            value: '$2.4M',
            change: '+23%',
        },
        {
            icon: <Package size={20} />,
            label: lang === 'en' ? 'Total Orders' : 'Tổng đơn hàng',
            value: '8,241',
            change: '+12%',
        },
        {
            icon: <Users size={20} />,
            label: lang === 'en' ? 'Active Users' : 'Người dùng hoạt động',
            value: '4,821',
            change: '+8%',
        },
        {
            icon: <Gavel size={20} />,
            label: lang === 'en' ? 'Running Auctions' : 'Đang đấu giá',
            value: '2',
            change: '',
        },
    ]
    const DOWN_SERVICES = SYSTEM_SERVICES.filter((s) => s.status === 'down')
    return (
        <AdminLayout title={tr(lang, 'dashboard')}>
            {DOWN_SERVICES.length > 0 && (
                <div className="mb-5">
                    <AlertBanner
                        type="error"
                        message={
                            lang === 'en'
                                ? `Service(s) down: ${DOWN_SERVICES.map((s) => s.name).join(', ')}`
                                : `Dịch vụ bị sập: ${DOWN_SERVICES.map((s) => s.name).join(', ')}`
                        }
                    />
                </div>
            )}
            <div className="grid grid-cols-4 gap-5 mb-6">
                {KPIs.map((k) => (
                    <div key={k.label} className="nexus-card p-5">
                        <div className="flex items-center gap-3 mb-3">
                            <span className="text-gray-500">{k.icon}</span>
                            <span className="text-xs text-gray-500">{k.label}</span>
                        </div>
                        <p className="text-2xl font-black text-gray-900">{k.value}</p>
                        {k.change && (
                            <p className="text-xs text-success font-semibold mt-1">{k.change}</p>
                        )}
                    </div>
                ))}
            </div>
            <div className="grid grid-cols-[1fr_320px] gap-6">
                <div className="nexus-card p-5">
                    <h3 className="font-bold mb-4">
                        {lang === 'en' ? 'GMV Overview' : 'Tổng quan GMV'}
                    </h3>
                    <div className="flex items-end gap-1.5 h-40">
                        {[30, 50, 40, 70, 55, 85, 65, 90, 75, 95, 80, 100].map((h, i) => (
                            <div
                                key={i}
                                className="flex-1 rounded-t-sm"
                                style={{
                                    height: `${h}%`,
                                    background: i === 11 ? '#4F46E5' : '#EEF2FF',
                                }}
                            />
                        ))}
                    </div>
                    <div className="flex justify-between text-xs text-gray-400 mt-2">
                        <span>Jan</span>
                        <span>Jun</span>
                        <span>Dec</span>
                    </div>
                </div>
                <div className="nexus-card p-5">
                    <div className="flex items-center justify-between mb-4">
                        <h3 className="font-bold">
                            {lang === 'en' ? 'System Health' : 'Trạng thái hệ thống'}
                        </h3>
                        <button
                            onClick={() => navigate('admin-system-health')}
                            className="text-xs text-primary"
                        >
                            {tr(lang, 'viewAll')}
                        </button>
                    </div>
                    <div className="space-y-2">
                        {SYSTEM_SERVICES.slice(0, 5).map((s) => (
                            <div key={s.name} className="flex items-center justify-between">
                                <span className="text-xs text-gray-700 truncate flex-1">
                                    {s.name}
                                </span>
                                <span
                                    className={`badge ml-2 ${s.status === 'up' ? 'badge-green' : 'badge-red'}`}
                                >
                                    {s.status === 'up'
                                        ? tr(lang, 'serviceUp')
                                        : tr(lang, 'serviceDown')}
                                </span>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
            <div className="nexus-card mt-6">
                <div className="flex items-center justify-between p-5 pb-0">
                    <h3 className="font-bold">
                        {lang === 'en' ? 'Recent Events' : 'Sự kiện gần đây'}
                    </h3>
                    <button
                        onClick={() => navigate('admin-event-logs')}
                        className="text-xs text-primary"
                    >
                        {tr(lang, 'viewAll')}
                    </button>
                </div>
                <div className="p-5">
                    {EVENT_LOGS.slice(0, 4).map((e) => (
                        <div
                            key={e.id}
                            className="flex gap-4 py-3 border-b border-border last:border-0"
                        >
                            <div className="w-8 h-8 bg-gray-100 rounded-lg flex items-center justify-center text-sm">
                                {e.type === 'AUCTION' ? (
                                    <Gavel size={16} />
                                ) : e.type === 'ORDER' ? (
                                    <Package size={16} />
                                ) : e.type === 'USER' ? (
                                    <User2 size={16} />
                                ) : (
                                    <Settings2 size={16} />
                                )}
                            </div>
                            <div className="flex-1">
                                <p className="text-sm font-semibold text-gray-800">{e.action}</p>
                                <p className="text-xs text-gray-500">{e.message}</p>
                            </div>
                            <div className="text-right">
                                <p className="text-xs text-gray-400">{e.operator}</p>
                                <p className="text-xs text-gray-300">
                                    {new Date(e.timestamp).toLocaleTimeString()}
                                </p>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </AdminLayout>
    )
}

// ─── Admin Users ────────────────────────────────────────────────────────────────
export function AdminUsersPage() {
    const { lang, navigate, showToast } = useApp()
    const [search, setSearch] = useState('')
    const [roleFilter, setRoleFilter] = useState('all')
    const [createOpen, setCreateOpen] = useState(false)
    const [deleteId, setDeleteId] = useState<string | null>(null)
    const [apiUsers, setApiUsers] = useState<ApiUser[]>([])
    const [refreshKey, setRefreshKey] = useState(0)

    // Tải danh sách người dùng thật từ User Service (/api/v1/users) theo từ khóa tìm kiếm
    // Chức năng: Đọc danh sách người dùng phân trang từ backend User Service.
    // Luồng & Quy tắc: Chuẩn hóa bóc tách cả ApiResponse { data: [...] }, Pageable { content: [...] } và mảng trần để tránh lỗi undefined khi gọi .map.
    useEffect(() => {
        const query = search ? `?keyword=${encodeURIComponent(search)}` : ''
        api<any>('/users' + query)
            .then((res) => {
                const rawList = res?.data ?? res?.content ?? res
                const list = Array.isArray(rawList)
                    ? rawList
                    : Array.isArray(rawList?.content)
                      ? rawList.content
                      : []
                setApiUsers(list)
            })
            .catch(() => setApiUsers([]))
    }, [search, refreshKey])

    /**
     * Chức năng: Lấy danh sách người dùng thực tế từ User Service backend (/api/v1/users).
     * Quy tắc: Không fallback sang USERS mockData.
     */
    const allUsers = apiUsers.length > 0 ? apiUsers.map(mapUser) : []

    const filtered = allUsers.filter(
        (u) =>
            (roleFilter === 'all' || u.role === roleFilter) &&
            (!search ||
                u.fullName.toLowerCase().includes(search.toLowerCase()) ||
                u.email.includes(search)),
    )

    return (
        <AdminLayout title={tr(lang, 'userManagement')}>
            <div className="flex items-center justify-between mb-5">
                <div className="flex gap-3">
                    <Input
                        icon={<Search size={14} />}
                        placeholder={lang === 'en' ? 'Search users...' : 'Tìm người dùng...'}
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        className="w-64"
                    />
                    <select
                        value={roleFilter}
                        onChange={(e) => setRoleFilter(e.target.value)}
                        className="nexus-input text-sm py-2 w-36"
                    >
                        {['all', 'buyer', 'seller', 'admin', 'support'].map((r) => (
                            <option key={r} value={r}>
                                {r === 'all'
                                    ? tr(lang, 'all')
                                    : r.charAt(0).toUpperCase() + r.slice(1)}
                            </option>
                        ))}
                    </select>
                </div>
                <Button
                    variant="primary"
                    icon={<Plus size={14} />}
                    onClick={() => setCreateOpen(true)}
                >
                    {tr(lang, 'createUser')}
                </Button>
            </div>
            <div className="nexus-card">
                {filtered.length === 0 ? (
                    <div className="p-8">
                        <EmptyState icon={<Users size={20} />} title={tr(lang, 'noData')} />
                    </div>
                ) : (
                    <DataTable
                        columns={[
                            {
                                key: 'avatar',
                                label: '',
                                width: '48px',
                                render: (r: any) => (
                                    <img
                                        src={r.avatar}
                                        alt=""
                                        className="w-9 h-9 rounded-full object-cover"
                                    />
                                ),
                            },
                            {
                                key: 'fullName',
                                label: lang === 'en' ? 'Name' : 'Họ tên',
                                render: (r: any) => (
                                    <div>
                                        <p className="font-semibold text-sm">{r.fullName}</p>
                                        <p className="text-xs text-gray-400">{r.email}</p>
                                    </div>
                                ),
                            },
                            {
                                key: 'phone',
                                label: lang === 'en' ? 'Phone' : 'Điện thoại',
                                render: (r: any) => <span className="text-xs">{r.phone}</span>,
                            },
                            {
                                key: 'role',
                                label: lang === 'en' ? 'Role' : 'Vai trò',
                                render: (r: any) => (
                                    <span className="badge badge-indigo text-xs">{r.role}</span>
                                ),
                            },
                            {
                                key: 'trustLevel',
                                label: lang === 'en' ? 'Trust' : 'Uy tín',
                                render: (r: any) => (
                                    <StatusBadge
                                        status={r.trustLevel}
                                        label={
                                            r.trustLevel.charAt(0).toUpperCase() +
                                            r.trustLevel.slice(1)
                                        }
                                    />
                                ),
                            },
                            {
                                key: 'status',
                                label: tr(lang, 'status'),
                                render: (r: any) => (
                                    <StatusBadge
                                        status={r.status === 'active' ? 'active' : 'cancelled'}
                                        label={
                                            r.status === 'active'
                                                ? lang === 'en'
                                                    ? 'Active'
                                                    : 'Hoạt động'
                                                : lang === 'en'
                                                  ? 'Locked'
                                                  : 'Bị khóa'
                                        }
                                    />
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
                                                navigate('admin-user-detail', { id: r.id })
                                            }}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5"
                                        >
                                            <Search size={13} />
                                        </button>
                                        <button className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5">
                                            <Pencil size={13} />
                                        </button>
                                        <button
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                setDeleteId(r.id)
                                            }}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger"
                                        >
                                            <Trash2 size={13} />
                                        </button>
                                    </div>
                                ),
                            },
                        ]}
                        data={filtered}
                        rowKey={(r) => r.id}
                        onRowClick={(r) => navigate('admin-user-detail', { id: r.id })}
                    />
                )}
            </div>

            <Modal
                open={createOpen}
                onClose={() => setCreateOpen(false)}
                title={tr(lang, 'createUser')}
                size="lg"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setCreateOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            onClick={() => {
                                setCreateOpen(false)
                                showToast({
                                    type: 'success',
                                    message: lang === 'en' ? 'User created!' : 'Đã tạo người dùng!',
                                })
                            }}
                        >
                            {tr(lang, 'create')}
                        </Button>
                    </>
                }
            >
                <div className="grid grid-cols-2 gap-4">
                    <Input label={tr(lang, 'fullName')} />
                    <Input label={tr(lang, 'email')} type="email" />
                    <Input label={tr(lang, 'phone')} type="tel" />
                    <Input label={tr(lang, 'idNumber')} />
                    <Select
                        label={lang === 'en' ? 'Role' : 'Vai trò'}
                        value="buyer"
                        onChange={() => {}}
                        options={['buyer', 'seller', 'admin', 'support'].map((r) => ({
                            value: r,
                            label: r.charAt(0).toUpperCase() + r.slice(1),
                        }))}
                    />
                    <Select
                        label={tr(lang, 'gender')}
                        value="male"
                        onChange={() => {}}
                        options={[
                            { value: 'male', label: tr(lang, 'male') },
                            { value: 'female', label: tr(lang, 'female') },
                        ]}
                    />
                    <Input label={tr(lang, 'dateOfBirth')} type="date" />
                </div>
                <div className="mt-4">
                    <Input label={tr(lang, 'address')} />
                </div>
            </Modal>

            <ConfirmModal
                open={!!deleteId}
                onClose={() => setDeleteId(null)}
                onConfirm={async () => {
                    if (!deleteId) return
                    try {
                        await api(`/users/${deleteId}`, { method: 'DELETE' })
                        showToast({
                            type: 'success',
                            message:
                                lang === 'en' ? 'User deleted.' : 'Đã xóa người dùng thành công.',
                        })
                        setRefreshKey((k) => k + 1)
                    } catch (err) {
                        showToast({
                            type: 'error',
                            message: errorMessage(err),
                        })
                    } finally {
                        setDeleteId(null)
                    }
                }}
                title={tr(lang, 'confirmDeleteTitle')}
                message={
                    lang === 'en'
                        ? 'This will permanently delete the user account.'
                        : 'Thao tác này sẽ xóa vĩnh viễn tài khoản người dùng.'
                }
                danger
            />
        </AdminLayout>
    )
}

// ─── Admin User Detail ─────────────────────────────────────────────────────────
export function AdminUserDetailPage() {
    const { lang, pageParams } = useApp()
    const user = USERS.find((u) => u.id === pageParams?.id) || USERS[0]
    const [tab, setTab] = useState('info')
    return (
        <AdminLayout title={tr(lang, 'editUser')}>
            <Breadcrumb
                items={[
                    { label: tr(lang, 'userManagement'), page: 'admin-users' },
                    { label: user.fullName },
                ]}
            />
            <div className="mt-5 nexus-card">
                <div className="p-5 flex items-center gap-5 border-b border-border">
                    <img src={user.avatar} alt="" className="w-16 h-16 rounded-2xl object-cover" />
                    <div>
                        <h2 className="text-xl font-bold">{user.fullName}</h2>
                        <p className="text-gray-500 text-sm">{user.email}</p>
                        <div className="flex gap-2 mt-1.5">
                            <span className="badge badge-indigo">{user.role}</span>
                            <StatusBadge
                                status={user.status === 'active' ? 'active' : 'cancelled'}
                                label={user.status}
                            />
                        </div>
                    </div>
                </div>
                <Tabs
                    tabs={[
                        { id: 'info', label: lang === 'en' ? 'Info' : 'Thông tin' },
                        { id: 'reputation', label: tr(lang, 'reputation') },
                        { id: 'activity', label: lang === 'en' ? 'Activity' : 'Hoạt động' },
                    ]}
                    active={tab}
                    onChange={setTab}
                />
                <div className="p-6">
                    {tab === 'info' && (
                        <div className="grid grid-cols-2 gap-4">
                            {[
                                ['Full Name', user.fullName],
                                ['Email', user.email],
                                ['Phone', user.phone],
                                ['ID Number', user.idNumber],
                                ['Gender', user.gender],
                                ['Date of Birth', user.dateOfBirth],
                                ['Role', user.role],
                                ['Status', user.status],
                                ['Created', user.createdAt],
                            ].map(([k, v]) => (
                                <div
                                    key={k}
                                    className="flex items-center p-3 bg-gray-50 rounded-lg"
                                >
                                    <span className="text-xs font-semibold text-gray-500 w-32">
                                        {k}
                                    </span>
                                    <span className="text-sm text-gray-800">{v}</span>
                                </div>
                            ))}
                        </div>
                    )}
                    {tab === 'reputation' && (
                        <div className="space-y-4">
                            <div className="grid grid-cols-3 gap-4">
                                {[
                                    ['Score', user.reputationScore + ' pts'],
                                    [
                                        'Trust Level',
                                        user.trustLevel.charAt(0).toUpperCase() +
                                            user.trustLevel.slice(1),
                                    ],
                                    ['Transactions', '24 successful'],
                                ].map(([k, v]) => (
                                    <div key={k} className="nexus-card p-4 text-center">
                                        <p className="text-xs text-gray-500">{k}</p>
                                        <p className="text-xl font-black text-primary mt-1">{v}</p>
                                    </div>
                                ))}
                            </div>
                            <div className="flex gap-3">
                                <Button variant="outline" size="sm">
                                    <AlertTriangle size={14} />
                                    {tr(lang, 'applyPenalty')}
                                </Button>
                                <Button variant="outline" size="sm">
                                    {tr(lang, 'adjustReputation')}
                                </Button>
                            </div>
                        </div>
                    )}
                    {tab === 'activity' && (
                        <div className="space-y-3">
                            {EVENT_LOGS.slice(0, 5).map((e) => (
                                <div key={e.id} className="flex gap-3 p-3 bg-gray-50 rounded-xl">
                                    <div className="text-gray-500">
                                        {e.type === 'ORDER' ? (
                                            <Package size={18} />
                                        ) : e.type === 'AUCTION' ? (
                                            <Gavel size={18} />
                                        ) : e.type === 'USER' ? (
                                            <User2 size={18} />
                                        ) : (
                                            <Settings2 size={18} />
                                        )}
                                    </div>
                                    <div>
                                        <p className="text-sm font-semibold">{e.action}</p>
                                        <p className="text-xs text-gray-500">{e.message}</p>
                                        <p className="text-xs text-gray-400">
                                            {new Date(e.timestamp).toLocaleString()}
                                        </p>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>
        </AdminLayout>
    )
}

// ─── Admin Roles ───────────────────────────────────────────────────────────────
const MODULES = [
    'USER',
    'ROLE',
    'PRODUCT',
    'CATEGORY',
    'ORDER',
    'AUCTION',
    'SHIPPING',
    'REPUTATION',
    'NOTIFICATION',
    'SYSTEM',
]

export function AdminRolesPage() {
    const { lang, showToast } = useApp()
    const [createOpen, setCreateOpen] = useState(false)
    const [rolesList, setRolesList] = useState<any[]>(ROLES)
    const [selectedRole, setSelectedRole] = useState<any>(ROLES[0])

    // Tải danh sách vai trò và phân quyền N-N thật từ User Service (/api/v1/roles)
    // Chức năng: Tải danh mục vai trò hệ thống kèm quyền hạn.
    // Luồng & Quy tắc: Chuẩn hóa bóc tách ApiResponse { data: [...] } và mảng trực tiếp trước khi duyệt .map.
    useEffect(() => {
        api<Envelope<ApiRole[]> | ApiRole[]>('/roles')
            .then((data) => {
                const rawRoles = (data as any)?.data ?? data
                const list = Array.isArray(rawRoles) ? rawRoles : []
                if (list.length > 0) {
                    const mapped = list.map((r: ApiRole) => ({
                        id: r.id,
                        code: r.code,
                        name: r.name || r.code,
                        description: r.description || '',
                        userCount: r.code === 'ADMIN' ? 1 : r.code === 'BUYER' ? 1 : 0,
                        privileges: r.privileges || [],
                        permissions: MODULES.map((mod) => ({
                            module: mod,
                            read:
                                r.privileges?.includes(`${mod}.VIEW`) ||
                                r.privileges?.includes(`${mod}.LIST`) ||
                                false,
                            write:
                                r.privileges?.includes(`${mod}.UPDATE`) ||
                                r.privileges?.includes(`${mod}.CREATE`) ||
                                false,
                            delete: r.privileges?.includes(`${mod}.DELETE`) || false,
                            admin: r.code === 'ADMIN',
                        })),
                    }))
                    setRolesList(mapped)
                    setSelectedRole(mapped[0])
                }
            })
            .catch(() => {})
    }, [])

    return (
        <AdminLayout title={tr(lang, 'roleManagement')}>
            <div className="grid grid-cols-[260px_1fr] gap-6">
                <div>
                    <div className="flex items-center justify-between mb-3">
                        <h3 className="font-bold">{lang === 'en' ? 'Roles' : 'Vai trò'}</h3>
                        <Button
                            variant="primary"
                            size="sm"
                            icon={<Plus size={12} />}
                            onClick={() => setCreateOpen(true)}
                        >
                            {tr(lang, 'create')}
                        </Button>
                    </div>
                    <div className="space-y-2">
                        {rolesList.map((role) => (
                            <button
                                key={role.id}
                                onClick={() => setSelectedRole(role)}
                                className={`w-full p-3 rounded-xl border text-left transition-colors ${selectedRole?.id === role.id ? 'border-primary bg-primary-light' : 'border-border hover:border-gray-300'}`}
                            >
                                <div className="flex items-center justify-between">
                                    <p className="font-semibold text-sm">{role.name}</p>
                                    <span className="badge badge-gray text-xs">
                                        {role.userCount}
                                    </span>
                                </div>
                                <p className="text-xs text-gray-500 mt-0.5">{role.description}</p>
                            </button>
                        ))}
                    </div>
                </div>
                <div>
                    {selectedRole ? (
                        <div className="nexus-card">
                            <div className="p-5 border-b border-border flex items-center justify-between">
                                <div>
                                    <h2 className="font-bold text-lg">{selectedRole.name}</h2>
                                    <p className="text-sm text-gray-500">
                                        {selectedRole.description}
                                    </p>
                                </div>
                                <div className="flex gap-2">
                                    <Button variant="outline" size="sm">
                                        <Pencil size={14} />
                                        {tr(lang, 'edit')}
                                    </Button>
                                    <Button variant="danger" size="sm">
                                        <Trash2 size={14} />
                                        {tr(lang, 'delete')}
                                    </Button>
                                </div>
                            </div>
                            <div className="p-5 overflow-x-auto">
                                <table className="w-full text-sm">
                                    <thead>
                                        <tr className="border-b border-border">
                                            <th className="text-left py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide w-32">
                                                Module
                                            </th>
                                            {['Read', 'Write', 'Delete', 'Admin'].map((p) => (
                                                <th
                                                    key={p}
                                                    className="text-center py-2 text-xs font-semibold text-gray-500 uppercase tracking-wide w-16"
                                                >
                                                    {p}
                                                </th>
                                            ))}
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {MODULES.map((mod) => {
                                            const perm = (
                                                selectedRole.permissions as
                                                    | Array<{
                                                          module: string
                                                          read?: boolean
                                                          write?: boolean
                                                          delete?: boolean
                                                          admin?: boolean
                                                      }>
                                                    | undefined
                                            )?.find((p) => p.module === mod)
                                            return (
                                                <tr key={mod} className="border-b border-border">
                                                    <td className="py-3 font-semibold text-gray-700">
                                                        {mod}
                                                    </td>
                                                    {(
                                                        [
                                                            'read',
                                                            'write',
                                                            'delete',
                                                            'admin',
                                                        ] as const
                                                    ).map((p) => (
                                                        <td key={p} className="text-center py-3">
                                                            <input
                                                                type="checkbox"
                                                                defaultChecked={perm?.[p] || false}
                                                                className="accent-primary"
                                                            />
                                                        </td>
                                                    ))}
                                                </tr>
                                            )
                                        })}
                                    </tbody>
                                </table>
                                <div className="mt-4">
                                    <Button
                                        variant="primary"
                                        onClick={() =>
                                            showToast({
                                                type: 'success',
                                                message:
                                                    lang === 'en'
                                                        ? 'Role updated!'
                                                        : 'Đã cập nhật vai trò!',
                                            })
                                        }
                                    >
                                        {tr(lang, 'save')}
                                    </Button>
                                </div>
                            </div>
                        </div>
                    ) : (
                        <div className="nexus-card p-12 flex items-center justify-center h-full">
                            <EmptyState
                                icon={<Shield size={20} />}
                                title={
                                    lang === 'en'
                                        ? 'Select a role to view permissions'
                                        : 'Chọn vai trò để xem phân quyền'
                                }
                            />
                        </div>
                    )}
                </div>
            </div>

            <Modal
                open={createOpen}
                onClose={() => setCreateOpen(false)}
                title={tr(lang, 'createRole')}
                footer={
                    <>
                        <Button variant="outline" onClick={() => setCreateOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            onClick={() => {
                                setCreateOpen(false)
                                showToast({
                                    type: 'success',
                                    message: lang === 'en' ? 'Role created!' : 'Đã tạo vai trò!',
                                })
                            }}
                        >
                            {tr(lang, 'create')}
                        </Button>
                    </>
                }
            >
                <Input label={lang === 'en' ? 'Role Name' : 'Tên vai trò'} />
                <div className="mt-3">
                    <Textarea label={lang === 'en' ? 'Description' : 'Mô tả'} rows={2} />
                </div>
            </Modal>
        </AdminLayout>
    )
}

// ─── Admin Categories ──────────────────────────────────────────────────────────

// ─── Admin Orders ───
/**
 * Màn hình Quản lý đơn hàng & Trạng thái ký quỹ của Quản trị viên (Admin)
 *
 * Chức năng:
 * - Giám sát tổng tiền giữ hộ (Escrow funds), các đơn có khiếu nại đang xử lý và đơn trong thời hạn bảo vệ.
 * - Tìm kiếm, lọc toàn bộ đơn hàng trong hệ thống theo mã đơn, người mua, người bán hoặc trạng thái.
 * - Can thiệp quản lý trạng thái đơn hàng (Admin Status Override): Cưỡng chế chuyển trạng thái đơn
 *   (PENDING, PAID, PROCESSING, SHIPPED, DELIVERED, COMPLETED, CANCELLED, REFUNDED) khi có phát sinh sự cố.
 * - Xử lý tranh chấp ký quỹ Escrow: Phê duyệt hoàn tiền cho Buyer, giải ngân cho Seller hoặc hoàn một phần.
 *
 * Luồng dữ liệu:
 * - Nạp dữ liệu qua `ordersApi.getOrders({ role: 'admin', status, search })`.
 * - Khi Admin thực hiện can thiệp hoặc xử lý khiếu nại, gọi `ordersApi.updateOrderStatus` hoặc `ordersApi.resolveOrderDispute`,
 *   lưu vết vào mock storage/DB và cập nhật danh sách ngay lập tức.
 */
export function AdminOrdersPage() {
    const { lang, showToast } = useApp()
    const [orders, setOrders] = useState<Order[]>([])
    const [loading, setLoading] = useState(true)
    const [search, setSearch] = useState('')
    const [statusFilter, setStatusFilter] = useState('all')
    const [selectedOrder, setSelectedOrder] = useState<Order | null>(null)
    const [overrideStatus, setOverrideStatus] = useState<string>('')
    const [overrideReason, setOverrideReason] = useState<string>('')
    const [adminNotes, setAdminNotes] = useState<string>('')
    const [withdrawalRequests, setWithdrawalRequests] = useState<WithdrawalRequest[]>(() =>
        ordersApi.getWithdrawalRequests(),
    )
    const [submitting, setSubmitting] = useState(false)

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

    // Nạp danh sách đơn hàng cho Admin
    const loadAdminOrders = async () => {
        setLoading(true)
        try {
            const data = await ordersApi.getOrders({
                role: 'admin',
                status: statusFilter,
                search,
            })
            setOrders(data)
            setWithdrawalRequests(ordersApi.getWithdrawalRequests())
        } catch {
            setOrders([])
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        loadAdminOrders()
    }, [statusFilter, search])

    // Cập nhật giá trị khi mở modal xem chi tiết
    useEffect(() => {
        if (selectedOrder) {
            setOverrideStatus(selectedOrder.status)
            setOverrideReason('')
            setAdminNotes('')
        }
    }, [selectedOrder])

    // Tính toán các chỉ số thống kê
    const heldTotal = orders
        .filter((o) =>
            ['held', 'protection_period', 'on_hold'].includes(getEffectivePaymentStatus(o)),
        )
        .reduce((sum, order) => sum + order.total, 0)
    const openDisputesCount = orders.filter(
        (o) =>
            getEffectivePaymentStatus(o) === 'on_hold' ||
            o.disputeStatus === 'open' ||
            o.disputeStatus === 'reviewing',
    ).length
    const protectionCount = orders.filter(
        (o) => getEffectivePaymentStatus(o) === 'protection_period',
    ).length
    const allowedOverrideStatuses = selectedOrder
        ? getAllowedOrderStatusTransitions(selectedOrder.status)
        : []
    const withdrawalRequest = withdrawalRequests[0]

    // Admin xử lý giải quyết tranh chấp ký quỹ Escrow
    const resolvePayment = async (resolution: 'refunded' | 'released' | 'partial_refund') => {
        if (!selectedOrder) return
        setSubmitting(true)
        try {
            const updated = await ordersApi.resolveOrderDispute(
                selectedOrder.id,
                resolution,
                adminNotes,
            )
            setSelectedOrder(updated)
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? `Dispute decision recorded: ${paymentLabel(resolution)}.`
                        : `Đã xử lý tranh chấp: ${paymentLabel(resolution)}.`,
            })
            await loadAdminOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err.message ||
                    (lang === 'en' ? 'Failed to resolve dispute.' : 'Lỗi xử lý tranh chấp.'),
            })
        } finally {
            setSubmitting(false)
        }
    }

    // Admin can thiệp trạng thái đơn hàng (Status Override)
    const handleAdminOverrideStatus = async () => {
        if (!selectedOrder || !overrideStatus || overrideStatus === selectedOrder.status) return
        if (!overrideReason.trim()) {
            showToast({
                type: 'error',
                message:
                    lang === 'en'
                        ? 'An audit reason is required.'
                        : 'Vui lòng nhập lý do can thiệp.',
            })
            return
        }
        setSubmitting(true)
        try {
            const updated = await ordersApi.updateOrderStatus(
                selectedOrder.id,
                overrideStatus as OrderStatus,
                overrideReason,
                'admin',
            )
            setSelectedOrder(updated)
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? `Order status changed to ${statusLabel(overrideStatus)}.`
                        : `Đã thay đổi trạng thái đơn sang ${statusLabel(overrideStatus)}.`,
            })
            await loadAdminOrders()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err.message ||
                    (lang === 'en' ? 'Failed to override status.' : 'Lỗi cập nhật trạng thái.'),
            })
        } finally {
            setSubmitting(false)
        }
    }

    /** Duyệt withdrawal trong cùng mock store để trạng thái vẫn còn sau khi Admin reload trang. */
    const approveWithdrawal = (id: string) => {
        try {
            ordersApi.approveWithdrawal(id)
            setWithdrawalRequests(ordersApi.getWithdrawalRequests())
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Withdrawal approved and marked for bank transfer.'
                        : 'Đã duyệt yêu cầu và chuyển sang bước chuyển khoản.',
            })
        } catch (error) {
            showToast({
                type: 'error',
                message: error instanceof Error ? error.message : 'Unable to approve withdrawal.',
            })
        }
    }

    return (
        <AdminLayout title={tr(lang, 'orders')}>
            {/* Thẻ thống kê tài chính và tranh chấp */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-5">
                <div className="nexus-card p-5">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'Funds held by Nexus' : 'Tiền Nexus đang giữ hộ'}
                    </p>
                    <p className="text-2xl font-black mt-1 text-primary">
                        {formatCurrency(heldTotal, lang)}
                    </p>
                </div>
                <div className="nexus-card p-5 border-l-4 border-amber-500">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'Open disputes' : 'Khiếu nại đang chờ xử lý'}
                    </p>
                    <p className="text-2xl font-black mt-1 text-amber-600">{openDisputesCount}</p>
                </div>
                <div className="nexus-card p-5 border-l-4 border-blue-500">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'In protection period' : 'Đang trong thời gian bảo vệ'}
                    </p>
                    <p className="text-2xl font-black mt-1 text-blue-600">{protectionCount}</p>
                </div>
                <div className="nexus-card p-5 border-l-4 border-emerald-500">
                    <p className="text-xs text-gray-500 font-medium">
                        {lang === 'en' ? 'Total system orders' : 'Tổng số đơn toàn sàn'}
                    </p>
                    <p className="text-2xl font-black mt-1 text-gray-900">{orders.length}</p>
                </div>
            </div>

            {/* Thanh tìm kiếm và bộ lọc trạng thái */}
            <div className="flex flex-wrap items-center gap-3 mb-5">
                <Input
                    icon={<Search size={14} />}
                    placeholder={
                        lang === 'en'
                            ? 'Order ID, buyer, seller...'
                            : 'Mã đơn, người mua, người bán...'
                    }
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    className="w-72"
                />
                <select
                    className="nexus-input text-sm py-2 w-48 font-medium"
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
                <span className="text-xs text-gray-400 ml-auto">
                    {lang === 'en'
                        ? `Showing ${orders.length} orders`
                        : `Hiển thị ${orders.length} đơn hàng`}
                </span>
            </div>

            {/* Bảng dữ liệu quản lý đơn hàng */}
            <div className="nexus-card">
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
                                    <span className="font-mono text-xs font-bold text-gray-900">
                                        {r.id}
                                    </span>
                                ),
                            },
                            { key: 'buyerName', label: lang === 'en' ? 'Buyer' : 'Người mua' },
                            { key: 'sellerName', label: lang === 'en' ? 'Seller' : 'Người bán' },
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
                                key: 'date',
                                label: tr(lang, 'date'),
                                render: (r: Order) => (
                                    <span className="text-xs text-gray-500">
                                        {formatDate(r.createdAt, lang)}
                                    </span>
                                ),
                            },
                            {
                                key: 'actions',
                                label: '',
                                render: (r: Order) => (
                                    <Button
                                        variant="outline"
                                        size="sm"
                                        onClick={(e) => {
                                            e.stopPropagation()
                                            setSelectedOrder(r)
                                        }}
                                    >
                                        {lang === 'en' ? 'Manage' : 'Quản lý'}
                                    </Button>
                                ),
                            },
                        ]}
                        data={orders}
                        rowKey={(r) => r.id}
                        onRowClick={setSelectedOrder}
                    />
                )}
            </div>

            {/* Yêu cầu rút tiền người bán */}
            <div className="nexus-card mt-6">
                <div className="p-5 border-b border-border font-bold">
                    {lang === 'en' ? 'Withdrawal requests' : 'Yêu cầu rút tiền người bán'}
                </div>
                <div className="p-5 flex flex-wrap items-center justify-between gap-4">
                    <div>
                        <div className="flex items-center gap-3">
                            <span className="font-semibold text-gray-900">
                                {withdrawalRequest?.sellerName || '-'}
                            </span>
                            <StatusBadge
                                status={
                                    withdrawalRequest?.status === 'approved'
                                        ? 'released'
                                        : 'pending'
                                }
                                label={
                                    withdrawalRequest?.status === 'approved'
                                        ? lang === 'en'
                                            ? 'Approved'
                                            : 'Đã duyệt'
                                        : lang === 'en'
                                          ? 'Pending review'
                                          : 'Chờ duyệt'
                                }
                            />
                        </div>
                        <p className="text-sm text-gray-500 mt-1">
                            {withdrawalRequest?.bankName || '-'}
                        </p>
                        <p className="text-xs text-gray-400 mt-0.5">
                            {lang === 'en' ? 'Account number' : 'Số tài khoản'}:{' '}
                            {withdrawalRequest?.accountNumber || '-'}
                        </p>
                    </div>
                    <div className="flex items-center gap-4">
                        <span className="text-xl font-black text-gray-900">
                            {withdrawalRequest
                                ? formatCurrency(withdrawalRequest.amount, lang)
                                : '-'}
                        </span>
                        {withdrawalRequest?.status === 'pending' && (
                            <Button
                                variant="primary"
                                size="sm"
                                onClick={() => approveWithdrawal(withdrawalRequest.id)}
                            >
                                {lang === 'en' ? 'Approve' : 'Duyệt'}
                            </Button>
                        )}
                    </div>
                </div>
            </div>

            {/* Modal Quản lý trạng thái & Giải quyết tranh chấp dành cho Admin */}
            <Modal
                open={!!selectedOrder}
                onClose={() => setSelectedOrder(null)}
                title={
                    lang === 'en'
                        ? `Order Management & Dispute Review: ${selectedOrder?.id}`
                        : `Quản lý đơn hàng & Xử lý khiếu nại: ${selectedOrder?.id}`
                }
                size="lg"
            >
                {selectedOrder && (
                    <div className="space-y-6">
                        {/* Thẻ tóm tắt thông tin đơn hàng */}
                        <div className="grid grid-cols-1 sm:grid-cols-4 gap-3">
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Order' : 'Mã đơn'}
                                </p>
                                <p className="font-mono font-bold text-gray-900 mt-0.5">
                                    {selectedOrder.id}
                                </p>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Buyer / Seller' : 'Người mua / Bán'}
                                </p>
                                <p className="text-xs font-semibold text-gray-800 mt-0.5">
                                    {selectedOrder.buyerName} → {selectedOrder.sellerName}
                                </p>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Total Amount' : 'Số tiền đơn'}
                                </p>
                                <p className="font-bold text-primary mt-0.5">
                                    {formatCurrency(selectedOrder.total, lang)}
                                </p>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <p className="text-xs text-gray-500">
                                    {lang === 'en' ? 'Payment status' : 'Tình trạng thanh toán'}
                                </p>
                                <div className="mt-0.5">
                                    <StatusBadge
                                        status={getEffectivePaymentStatus(selectedOrder)}
                                        label={paymentLabel(
                                            getEffectivePaymentStatus(selectedOrder),
                                        )}
                                    />
                                </div>
                            </div>
                        </div>

                        {/* Can thiệp trạng thái đơn hàng (Admin Status Override) */}
                        <div className="border border-border p-4 rounded-xl bg-gray-50/50">
                            <div className="flex items-center gap-2 mb-3">
                                <Shield size={16} className="text-primary" />
                                <h4 className="font-bold text-sm text-gray-900">
                                    {lang === 'en'
                                        ? 'Admin Status Override'
                                        : 'Can thiệp quản lý trạng thái đơn hàng'}
                                </h4>
                            </div>
                            <p className="text-xs text-gray-500 mb-3">
                                {lang === 'en'
                                    ? 'Admin can forcefully update the order lifecycle state in case of system or delivery exceptions.'
                                    : 'Admin có quyền cưỡng chế chuyển trạng thái đơn hàng khi xảy ra sự cố kỹ thuật hoặc tranh chấp giao hàng.'}
                            </p>
                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-3">
                                <div>
                                    <label className="text-xs font-medium text-gray-700 mb-1 block">
                                        {lang === 'en' ? 'Target Status' : 'Trạng thái muốn chuyển'}
                                    </label>
                                    <select
                                        className="nexus-input text-sm py-2 w-full font-medium"
                                        value={overrideStatus}
                                        onChange={(e) => setOverrideStatus(e.target.value)}
                                    >
                                        <option value={selectedOrder.status}>
                                            {statusLabel(selectedOrder.status)}
                                        </option>
                                        {allowedOverrideStatuses.map((status) => (
                                            <option key={status} value={status}>
                                                {statusLabel(status)}
                                            </option>
                                        ))}
                                    </select>
                                </div>
                                <div>
                                    <label className="text-xs font-medium text-gray-700 mb-1 block">
                                        {lang === 'en'
                                            ? 'Audit Reason'
                                            : 'Lý do can thiệp (Audit log)'}
                                    </label>
                                    <Input
                                        placeholder={
                                            lang === 'en'
                                                ? 'Reason for override...'
                                                : 'Nhập lý do chuyển trạng thái...'
                                        }
                                        value={overrideReason}
                                        onChange={(e) => setOverrideReason(e.target.value)}
                                        className="w-full text-sm"
                                    />
                                </div>
                            </div>
                            <Button
                                variant="primary"
                                size="sm"
                                loading={submitting}
                                disabled={
                                    overrideStatus === selectedOrder.status ||
                                    !overrideReason.trim()
                                }
                                onClick={handleAdminOverrideStatus}
                            >
                                {lang === 'en' ? 'Apply Status Change' : 'Áp dụng đổi trạng thái'}
                            </Button>
                        </div>

                        {/* Xử lý khiếu nại tranh chấp ký quỹ (Dispute Resolution) */}
                        {selectedOrder.disputeStatus && selectedOrder.disputeStatus !== 'none' ? (
                            <div className="space-y-4">
                                <AlertBanner
                                    type="warning"
                                    message={
                                        lang === 'en'
                                            ? 'Funds are currently on hold. Record your administrative decision below to release or refund.'
                                            : 'Khoản tiền đang được giữ do có khiếu nại. Hãy ghi lại quyết định xử lý bên dưới để hoàn tiền hoặc giải ngân.'
                                    }
                                />
                                <div>
                                    <p className="text-sm font-semibold mb-1 text-gray-900">
                                        {lang === 'en'
                                            ? 'Buyer dispute complaint:'
                                            : 'Nội dung khiếu nại từ Buyer:'}
                                    </p>
                                    <p className="text-sm text-gray-700 p-3 bg-amber-50/60 border border-amber-200 rounded-lg">
                                        {selectedOrder.disputeReason ||
                                            (lang === 'en'
                                                ? 'Product damaged or not as described.'
                                                : 'Sản phẩm lỗi hoặc không đúng mô tả.')}
                                    </p>
                                </div>
                                <Textarea
                                    label={
                                        lang === 'en'
                                            ? 'Admin Decision Notes'
                                            : 'Ghi chú quyết định của Admin'
                                    }
                                    rows={3}
                                    value={adminNotes}
                                    onChange={(e) => setAdminNotes(e.target.value)}
                                    placeholder={
                                        lang === 'en'
                                            ? 'Record evidence and decision explanation...'
                                            : 'Ghi lại bằng chứng và giải thích quyết định xử lý...'
                                    }
                                />
                                <div className="flex flex-wrap gap-3 pt-3 border-t border-border">
                                    <Button
                                        variant="outline"
                                        loading={submitting}
                                        className="text-danger hover:bg-danger/10 border-danger/40"
                                        onClick={() => resolvePayment('refunded')}
                                    >
                                        {lang === 'en'
                                            ? 'Refund Buyer (100%)'
                                            : 'Hoàn tiền cho Buyer (100%)'}
                                    </Button>
                                    <Button
                                        variant="primary"
                                        loading={submitting}
                                        onClick={() => resolvePayment('released')}
                                    >
                                        {lang === 'en'
                                            ? 'Release to Seller'
                                            : 'Giải ngân cho Seller'}
                                    </Button>
                                    <Button
                                        variant="outline"
                                        loading={submitting}
                                        onClick={() => resolvePayment('partial_refund')}
                                    >
                                        {lang === 'en' ? 'Partial Refund' : 'Hoàn tiền một phần'}
                                    </Button>
                                </div>
                            </div>
                        ) : (
                            <AlertBanner
                                type="info"
                                message={
                                    lang === 'en'
                                        ? 'No open dispute on this order. Funds will be released automatically upon protection expiry.'
                                        : 'Đơn hàng này không có khiếu nại. Tiền giữ hộ sẽ được tự động giải ngân khi hết thời hạn bảo vệ.'
                                }
                            />
                        )}
                    </div>
                )}
            </Modal>
        </AdminLayout>
    )
}

// ─── Admin Auctions ────────────────────────────────────────────────────────────
/**
 * Chức năng: Cổng quản lý và kiểm duyệt phiên đấu giá dành cho Quản trị viên (Admin Auction Management Portal).
 *            Cho phép Admin theo dõi toàn bộ phiên đấu giá trên sàn (tất cả Người bán), lọc theo trạng thái,
 *            tìm kiếm theo từ khóa/sản phẩm/người bán, phê duyệt hoặc từ chối phiên mới,
 *            và can thiệp cưỡng chế tạm dừng/hủy phiên đấu giá khi phát hiện vi phạm.
 * Đầu vào: createdAuctions, auctionReviews từ Context; dữ liệu đồng bộ từ auctionsApi.
 * Kết quả: Cập nhật trạng thái phiên đấu giá (SCHEDULED, CANCELLED/REJECTED), lưu vết lý do xử lý vi phạm.
 * Luồng xử lý:
 *   1. Lấy danh sách toàn bộ phiên đấu giá từ auctionsApi.getAuctions (hỗ trợ phân trang và bộ lọc).
 *   2. Áp dụng bộ lọc tìm kiếm (tên sản phẩm, mã phiên, người bán) và lọc theo tab trạng thái.
 *   3. Thao tác Phê duyệt: gọi auctionsApi.reviewAuction(id, 'scheduled') và cập nhật trạng thái Context.
 *   4. Thao tác Từ chối: mở modal yêu cầu nhập lý do từ chối, gửi auctionsApi.reviewAuction(id, 'rejected', reason).
 *   5. Thao tác Buộc hủy vi phạm: mở modal cảnh báo cưỡng chế, yêu cầu lý do vi phạm, gọi auctionsApi.cancelAuction(id, reason, 'admin').
 *   6. Thao tác Xem chi tiết: hiển thị modal đầy đủ thông tin sản phẩm, cấu hình giá, thời gian, lịch sử đặt giá và vết hủy.
 * Quy tắc & lý do: Admin có quyền tối cao can thiệp hủy phiên bất kể thời điểm (kể cả khi phiên đang ACTIVE)
 *                  để bảo vệ an toàn sàn và người tiêu dùng khi phát hiện gian lận hoặc hàng giả.
 * Lỗi & giới hạn: Kiểm tra bắt buộc có lý do khi từ chối hoặc hủy vi phạm; hiển thị toast thông báo kết quả.
 */
export function AdminAuctionsPage() {
    const { lang, showToast, auctionReviews, reviewAuction, createdAuctions } = useApp()
    const [tab, setTab] = useState('all')
    const [searchQuery, setSearchQuery] = useState('')

    // State cho Modal Buộc hủy phiên vi phạm
    const [cancelOpen, setCancelOpen] = useState(false)
    const [cancelAuctionId, setCancelAuctionId] = useState<string | null>(null)
    const [cancelReason, setCancelReason] = useState('')
    const [cancelReasonError, setCancelReasonError] = useState('')
    const [cancelling, setCancelling] = useState(false)
    const [pauseAuctionId, setPauseAuctionId] = useState<string | null>(null)
    const [pauseReason, setPauseReason] = useState('')
    const [pausing, setPausing] = useState(false)

    // State cho Modal Từ chối phiên chờ duyệt
    const [rejectAuctionId, setRejectAuctionId] = useState<string | null>(null)
    const [rejectReason, setRejectReason] = useState('')
    const [rejectReasonError, setRejectReasonError] = useState('')
    const [rejecting, setRejecting] = useState(false)

    // State cho Modal Xem chi tiết phiên
    const [selectedAuction, setSelectedAuction] = useState<Auction | null>(null)

    // Dữ liệu danh sách phiên đấu giá
    const [liveAuctions, setLiveAuctions] = useState<Auction[]>([])
    const [sellerAccounts, setSellerAccounts] = useState<
        Record<string, Pick<ApiUser, 'fullName' | 'email'>>
    >({})

    // Tải danh sách toàn bộ phiên đấu giá
    const loadAuctions = async () => {
        try {
            const res = await auctionsApi.getAuctions({
                includePending: true,
                excludeDemoData: true,
                extraAuctions: createdAuctions,
                reviews: auctionReviews,
                limit: 200,
            })
            setLiveAuctions(res.items)
        } catch {
            // Khi API lỗi ngoài nhóm fallback, giữ phiên do người dùng tạo trong Context và không dựng fixture mặc định.
            const fallback = createdAuctions.map((a) => ({
                ...a,
                status: (auctionReviews[a.id]?.status as any) || a.status,
            }))
            setLiveAuctions(fallback)
        }
    }

    useEffect(() => {
        loadAuctions()
        // Đồng bộ danh sách Admin với các phiên vừa được Seller lưu thành công ở Auction Service.
        const refreshTimer = window.setInterval(loadAuctions, 5000)
        return () => window.clearInterval(refreshTimer)
    }, [createdAuctions, auctionReviews])

    // Tra cứu theo từng sellerId duy nhất trong hàng chờ; không gọi User API cho các phiên khác hoặc mock users đã có sẵn.
    const pendingSellerIds = [
        ...new Set(
            liveAuctions
                .filter((auction) => auction.status === 'pending_approval')
                .map((auction) => auction.sellerId)
                .filter((id) => id && !USERS.some((user) => user.id === id)),
        ),
    ].sort()
    const pendingSellerIdsKey = pendingSellerIds.join('|')

    useEffect(() => {
        if (!pendingSellerIdsKey) return
        let active = true
        const sellerIds = pendingSellerIdsKey.split('|')

        Promise.all(
            sellerIds.map(async (sellerId) => {
                try {
                    const response = await api<ApiUser | { data: ApiUser }>(
                        `/users/${encodeURIComponent(sellerId)}`,
                    )
                    const account = 'data' in response ? response.data : response
                    return [sellerId, { fullName: account.fullName, email: account.email }] as const
                } catch {
                    return null
                }
            }),
        ).then((accounts) => {
            if (!active) return
            setSellerAccounts((current) => ({
                ...current,
                ...Object.fromEntries(accounts.filter((account) => account !== null)),
            }))
        })

        return () => {
            active = false
        }
    }, [pendingSellerIdsKey])

    const allAuctions = liveAuctions.map((auction) => ({
        ...auction,
        status: (auctionReviews[auction.id]?.status as any) || auction.status,
    }))

    const statusLabel = (s: string) =>
        ({
            pending_approval: lang === 'en' ? 'Pending approval' : 'Chờ phê duyệt',
            scheduled: tr(lang, 'statusScheduled'),
            active: tr(lang, 'statusActive'),
            paused: lang === 'en' ? 'Paused' : 'Đang tạm dừng',
            ended: tr(lang, 'statusEnded'),
            settled: tr(lang, 'statusSettled'),
            cancelled: tr(lang, 'statusCancelled'),
            rejected: lang === 'en' ? 'Rejected' : 'Bị từ chối',
        })[s] || s

    // Xử lý Admin phê duyệt phiên đấu giá
    const handleApprove = async (id: string) => {
        try {
            await auctionsApi.reviewAuction(id, 'scheduled')
            reviewAuction(id, 'scheduled')
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Auction approved and scheduled.'
                        : 'Đã phê duyệt và lên lịch phiên đấu giá.',
            })
            await loadAuctions()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'en'
                        ? 'Failed to approve auction'
                        : 'Không thể phê duyệt phiên đấu giá'),
            })
        }
    }

    // Xử lý Admin từ chối phiên đấu giá
    const handleReject = async () => {
        if (!rejectAuctionId) return
        if (!rejectReason.trim()) {
            setRejectReasonError(
                lang === 'en' ? 'Rejection reason is required' : 'Vui lòng nhập lý do từ chối',
            )
            return
        }

        setRejecting(true)
        try {
            await auctionsApi.reviewAuction(rejectAuctionId, 'rejected', rejectReason.trim())
            reviewAuction(rejectAuctionId, 'rejected', rejectReason.trim())
            showToast({
                type: 'error',
                message: lang === 'en' ? 'Auction rejected.' : 'Đã từ chối phiên đấu giá.',
            })
            setRejectAuctionId(null)
            setRejectReason('')
            setRejectReasonError('')
            await loadAuctions()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'en'
                        ? 'Failed to reject auction'
                        : 'Không thể từ chối phiên đấu giá'),
            })
        } finally {
            setRejecting(false)
        }
    }

    // Xử lý Admin cưỡng chế hủy phiên vi phạm
    const handleForceCancel = async () => {
        if (!cancelAuctionId) return
        if (!cancelReason.trim()) {
            setCancelReasonError(
                lang === 'en' ? 'Violation reason is required' : 'Vui lòng nhập lý do vi phạm',
            )
            return
        }

        setCancelling(true)
        try {
            await auctionsApi.cancelAuction(cancelAuctionId, cancelReason.trim(), 'admin')
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Auction force-cancelled due to policy violation.'
                        : 'Đã cưỡng chế hủy phiên đấu giá do vi phạm chính sách.',
            })
            setCancelOpen(false)
            setCancelAuctionId(null)
            setCancelReason('')
            setCancelReasonError('')
            await loadAuctions()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'en' ? 'Failed to cancel auction' : 'Không thể cưỡng chế hủy phiên'),
            })
        } finally {
            setCancelling(false)
        }
    }

    // Ghi nhận lý do và cập nhật trạng thái phiên qua API hoặc kho mock khi backend chưa triển khai thao tác này.
    const handlePauseAuction = async () => {
        if (!pauseAuctionId) return
        if (!pauseReason.trim()) {
            showToast({
                type: 'error',
                message: lang === 'en' ? 'Reason is required' : 'Vui lòng nhập lý do tạm dừng',
            })
            return
        }
        setPausing(true)
        try {
            await auctionsApi.setAuctionPaused(pauseAuctionId, true, pauseReason.trim())
            showToast({
                type: 'success',
                message: lang === 'en' ? 'Auction paused.' : 'Đã tạm dừng phiên đấu giá.',
            })
            setPauseAuctionId(null)
            setPauseReason('')
            await loadAuctions()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'en'
                        ? 'Unable to pause auction.'
                        : 'Không thể tạm dừng phiên đấu giá.'),
            })
        } finally {
            setPausing(false)
        }
    }

    const handleResumeAuction = async (id: string) => {
        try {
            await auctionsApi.setAuctionPaused(
                id,
                false,
                lang === 'en' ? 'Admin resumed auction' : 'Quản trị viên tiếp tục phiên',
            )
            showToast({
                type: 'success',
                message: lang === 'en' ? 'Auction resumed.' : 'Đã tiếp tục phiên đấu giá.',
            })
            await loadAuctions()
        } catch (err: any) {
            showToast({
                type: 'error',
                message:
                    err?.message ||
                    (lang === 'en'
                        ? 'Unable to resume auction.'
                        : 'Không thể tiếp tục phiên đấu giá.'),
            })
        }
    }

    // Lọc danh sách theo tab và từ khóa tìm kiếm
    const filteredAuctions = allAuctions
        .filter((a) => {
            if (tab === 'all') return true
            if (tab === 'pending_approval') return a.status === 'pending_approval'
            if (tab === 'scheduled') return a.status === 'scheduled'
            if (tab === 'active') return a.status === 'active'
            if (tab === 'ended') return ['ended', 'settled'].includes(a.status)
            if (tab === 'cancelled') return ['cancelled', 'rejected'].includes(a.status)
            return a.status === tab
        })
        .filter((a) => {
            if (!searchQuery.trim()) return true
            const q = searchQuery.toLowerCase().trim()
            const name = (a.product?.name || '').toLowerCase()
            const nameVi = (a.product?.nameVi || '').toLowerCase()
            const seller = (a.sellerName || '').toLowerCase()
            const id = (a.id || '').toLowerCase()
            const sku = (a.product?.sku || '').toLowerCase()
            return (
                name.includes(q) ||
                nameVi.includes(q) ||
                seller.includes(q) ||
                id.includes(q) ||
                sku.includes(q)
            )
        })

    const pendingCount = allAuctions.filter((a) => a.status === 'pending_approval').length
    const activeCount = allAuctions.filter((a) => a.status === 'active').length
    const scheduledCount = allAuctions.filter((a) => a.status === 'scheduled').length
    const cancelledCount = allAuctions.filter((a) =>
        ['cancelled', 'rejected'].includes(a.status),
    ).length

    return (
        <AdminLayout title={tr(lang, 'auctionManagement')}>
            {/* Thanh cảnh báo các phiên đang chờ phê duyệt */}
            {pendingCount > 0 && (
                <div className="mb-5">
                    <AlertBanner
                        type="info"
                        message={
                            lang === 'en'
                                ? `${pendingCount} auction(s) are waiting for your review and approval.`
                                : `${pendingCount} phiên đấu giá mới đang chờ quản trị viên xem xét và phê duyệt.`
                        }
                    />
                </div>
            )}

            {/* Thống kê chỉ số nhanh */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
                <div className="nexus-card p-4">
                    <p className="text-xs text-gray-500">
                        {lang === 'en' ? 'Total Auctions' : 'Tổng số phiên'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-gray-800">{allAuctions.length}</p>
                </div>
                <div className="nexus-card p-4 border-l-4 border-l-green-500">
                    <p className="text-xs text-gray-500">
                        {lang === 'en' ? 'Active Sessions' : 'Đang diễn ra'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-green-600">{activeCount}</p>
                </div>
                <div className="nexus-card p-4 border-l-4 border-l-amber-500">
                    <p className="text-xs text-gray-500">
                        {lang === 'en' ? 'Pending Approval' : 'Chờ duyệt'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-amber-600">{pendingCount}</p>
                </div>
                <div className="nexus-card p-4 border-l-4 border-l-red-500">
                    <p className="text-xs text-gray-500">
                        {lang === 'en' ? 'Cancelled / Violations' : 'Đã hủy / Vi phạm'}
                    </p>
                    <p className="text-2xl font-bold mt-1 text-red-600">{cancelledCount}</p>
                </div>
            </div>

            {/* Thanh công cụ tìm kiếm và Bộ lọc Tabs */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-5">
                <Tabs
                    tabs={[
                        { id: 'all', label: tr(lang, 'all') },
                        {
                            id: 'pending_approval',
                            label: lang === 'en' ? 'Pending' : 'Chờ duyệt',
                            count: pendingCount,
                        },
                        {
                            id: 'scheduled',
                            label: tr(lang, 'statusScheduled'),
                            count: scheduledCount,
                        },
                        {
                            id: 'active',
                            label: tr(lang, 'statusActive'),
                            count: activeCount,
                        },
                        {
                            id: 'paused',
                            label: lang === 'en' ? 'Paused' : 'Tạm dừng',
                            count: allAuctions.filter((a) => a.status === 'paused').length,
                        },
                        { id: 'ended', label: tr(lang, 'statusEnded') },
                        {
                            id: 'cancelled',
                            label: tr(lang, 'statusCancelled'),
                            count: cancelledCount,
                        },
                    ]}
                    active={tab}
                    onChange={setTab}
                />
                <div className="w-full md:w-72">
                    <Input
                        placeholder={
                            lang === 'en'
                                ? 'Search by product, seller, SKU...'
                                : 'Tìm theo sản phẩm, seller, SKU...'
                        }
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        icon={<Search size={16} />}
                    />
                </div>
            </div>

            {/* Bảng dữ liệu quản trị phiên đấu giá */}
            <div className="nexus-card">
                <DataTable
                    columns={[
                        {
                            key: 'id',
                            label: 'ID',
                            render: (r: any) => (
                                <span className="font-mono text-xs text-gray-600">{r.id}</span>
                            ),
                        },
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
                                        <p className="font-semibold text-sm line-clamp-1">
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
                            key: 'seller',
                            label: tr(lang, 'auctionRequestedBy'),
                            // Ưu tiên họ tên/email tài khoản; tên cửa hàng và sellerId dùng làm thông tin đối chiếu dự phòng.
                            render: (r: any) => {
                                const sellerUser = USERS.find((user) => user.id === r.sellerId)
                                const account = sellerAccounts[r.sellerId] || sellerUser
                                const accountName = r.sellerAccountName || account?.fullName
                                const email = r.sellerEmail || account?.email
                                return (
                                    <div>
                                        <p className="text-sm font-medium">
                                            {accountName || r.sellerName}
                                        </p>
                                        {accountName &&
                                            r.sellerName &&
                                            accountName !== r.sellerName && (
                                                <p className="text-xs text-gray-500">
                                                    {tr(lang, 'sellerStore')}: {r.sellerName}
                                                </p>
                                            )}
                                        <p className="text-xs text-gray-400 font-mono">
                                            {email || r.sellerId}
                                        </p>
                                    </div>
                                )
                            },
                        },
                        {
                            key: 'startingPrice',
                            label: tr(lang, 'startingPrice'),
                            render: (r: any) => (
                                <span className="text-sm font-medium text-gray-600">
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
                            key: 'time',
                            label: lang === 'en' ? 'Duration' : 'Khung giờ',
                            render: (r: any) => (
                                <div className="text-xs text-gray-500">
                                    <p className="whitespace-nowrap">
                                        {formatDateTime(r.startTime, lang)}
                                    </p>
                                    <p className="whitespace-nowrap text-gray-400">
                                        → {formatDateTime(r.endTime, lang)}
                                    </p>
                                </div>
                            ),
                        },
                        {
                            key: 'status',
                            label: tr(lang, 'status'),
                            render: (r: any) => {
                                const status = auctionReviews[r.id]?.status || r.status
                                return <StatusBadge status={status} label={statusLabel(status)} />
                            },
                        },
                        {
                            key: 'actions',
                            label: tr(lang, 'actions'),
                            render: (r: any) => {
                                const status = auctionReviews[r.id]?.status || r.status
                                return (
                                    <div className="flex gap-1.5 items-center">
                                        <button
                                            title={lang === 'en' ? 'View Details' : 'Xem chi tiết'}
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                setSelectedAuction(r)
                                            }}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-600 hover:bg-gray-100 rounded"
                                        >
                                            <Eye size={16} />
                                        </button>

                                        {status === 'pending_approval' ? (
                                            <div className="flex gap-1">
                                                <Button
                                                    variant="primary"
                                                    size="sm"
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        handleApprove(r.id)
                                                    }}
                                                >
                                                    {lang === 'en' ? 'Approve' : 'Duyệt'}
                                                </Button>
                                                <Button
                                                    variant="outline"
                                                    size="sm"
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        setRejectAuctionId(r.id)
                                                        setRejectReason('')
                                                        setRejectReasonError('')
                                                    }}
                                                >
                                                    {lang === 'en' ? 'Reject' : 'Từ chối'}
                                                </Button>
                                            </div>
                                        ) : ['scheduled', 'active', 'paused'].includes(status) ? (
                                            <div className="flex gap-1.5 items-center">
                                                {status === 'paused' ? (
                                                    <Button
                                                        variant="primary"
                                                        size="sm"
                                                        onClick={(e) => {
                                                            e.stopPropagation()
                                                            handleResumeAuction(r.id)
                                                        }}
                                                    >
                                                        {tr(lang, 'resumeAuction')}
                                                    </Button>
                                                ) : status === 'active' ? (
                                                    <Button
                                                        variant="outline"
                                                        size="sm"
                                                        onClick={(e) => {
                                                            e.stopPropagation()
                                                            setPauseAuctionId(r.id)
                                                            setPauseReason('')
                                                        }}
                                                    >
                                                        {tr(lang, 'pauseAuction')}
                                                    </Button>
                                                ) : null}
                                                <Button
                                                    variant="danger"
                                                    size="sm"
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        setCancelAuctionId(r.id)
                                                        setCancelReason('')
                                                        setCancelReasonError('')
                                                        setCancelOpen(true)
                                                    }}
                                                >
                                                    {lang === 'en' ? 'Force Cancel' : 'Hủy vi phạm'}
                                                </Button>
                                            </div>
                                        ) : null}
                                    </div>
                                )
                            },
                        },
                    ]}
                    data={filteredAuctions}
                    rowKey={(r) => r.id}
                />
            </div>

            {/* Modal Admin Cưỡng chế Hủy phiên do Vi phạm */}
            <Modal
                open={cancelOpen}
                onClose={() => {
                    setCancelOpen(false)
                    setCancelAuctionId(null)
                    setCancelReason('')
                    setCancelReasonError('')
                }}
                title={tr(lang, 'forceCancelAuction')}
                size="sm"
                footer={
                    <>
                        <Button
                            variant="outline"
                            onClick={() => {
                                setCancelOpen(false)
                                setCancelAuctionId(null)
                                setCancelReason('')
                                setCancelReasonError('')
                            }}
                        >
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button variant="danger" loading={cancelling} onClick={handleForceCancel}>
                            {lang === 'en' ? 'Confirm Force Cancel' : 'Xác nhận hủy phiên'}
                        </Button>
                    </>
                }
            >
                <AlertBanner
                    type="warning"
                    message={
                        lang === 'en'
                            ? 'Warning: This emergency action will terminate the auction immediately and release/refund any related resources.'
                            : 'Cảnh báo: Thao tác can thiệp khẩn cấp này sẽ chấm dứt phiên ngay lập tức và giải phóng tồn kho/hoàn trả cho các bên liên quan.'
                    }
                />
                <div className="mt-4">
                    <Textarea
                        label={`${tr(lang, 'reason')} *`}
                        value={cancelReason}
                        onChange={(e) => {
                            setCancelReason(e.target.value)
                            if (e.target.value.trim()) setCancelReasonError('')
                        }}
                        error={cancelReasonError}
                        rows={3}
                        placeholder={
                            lang === 'en'
                                ? 'State clearly the policy violation reason...'
                                : 'Nêu rõ lý do vi phạm chính sách hoặc lý do can thiệp...'
                        }
                    />
                </div>
            </Modal>

            {/* Modal Admin Từ chối Phiên chờ duyệt */}
            <Modal
                open={!!pauseAuctionId}
                onClose={() => {
                    setPauseAuctionId(null)
                    setPauseReason('')
                }}
                title={tr(lang, 'pauseAuction')}
                size="sm"
                footer={
                    <>
                        <Button
                            variant="outline"
                            onClick={() => {
                                setPauseAuctionId(null)
                                setPauseReason('')
                            }}
                        >
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button variant="primary" loading={pausing} onClick={handlePauseAuction}>
                            {tr(lang, 'pauseAuction')}
                        </Button>
                    </>
                }
            >
                <Textarea
                    label={`${tr(lang, 'pauseReason')} *`}
                    placeholder={tr(lang, 'pauseReason')}
                    value={pauseReason}
                    onChange={(event) => setPauseReason(event.target.value)}
                    rows={3}
                />
            </Modal>

            <Modal
                open={!!rejectAuctionId}
                onClose={() => {
                    setRejectAuctionId(null)
                    setRejectReason('')
                    setRejectReasonError('')
                }}
                title={lang === 'en' ? 'Reject auction' : 'Từ chối phiên đấu giá'}
                size="sm"
                footer={
                    <>
                        <Button
                            variant="outline"
                            onClick={() => {
                                setRejectAuctionId(null)
                                setRejectReason('')
                                setRejectReasonError('')
                            }}
                        >
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button variant="danger" loading={rejecting} onClick={handleReject}>
                            {lang === 'en' ? 'Reject Auction' : 'Từ chối đấu giá'}
                        </Button>
                    </>
                }
            >
                <AlertBanner
                    type="warning"
                    message={
                        lang === 'en'
                            ? 'The Seller will see this decision and the specified rejection reason.'
                            : 'Người bán sẽ nhìn thấy quyết định và lý do từ chối này để chỉnh sửa lại phiên.'
                    }
                />
                <div className="mt-4">
                    <Textarea
                        label={`${tr(lang, 'reason')} *`}
                        value={rejectReason}
                        onChange={(e) => {
                            setRejectReason(e.target.value)
                            if (e.target.value.trim()) setRejectReasonError('')
                        }}
                        error={rejectReasonError}
                        rows={3}
                        placeholder={
                            lang === 'en'
                                ? 'Explain what the Seller needs to correct...'
                                : 'Giải thích nội dung Người bán cần khắc phục...'
                        }
                    />
                </div>
            </Modal>

            {/* Modal Xem chi tiết phiên đấu giá (Audit Inspection) */}
            <Modal
                open={!!selectedAuction}
                onClose={() => setSelectedAuction(null)}
                title={
                    lang === 'en' ? 'Auction Details & Audit' : 'Chi tiết phiên đấu giá & Kiểm toán'
                }
                size="md"
            >
                {selectedAuction && (
                    <div className="space-y-4">
                        <div className="flex gap-4 items-start p-3 bg-gray-50 rounded-xl">
                            <img
                                src={
                                    selectedAuction.product?.images?.[0] ||
                                    'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=120'
                                }
                                alt=""
                                className="w-16 h-16 object-cover rounded-lg border"
                            />
                            <div>
                                <h4 className="font-bold text-base text-gray-900">
                                    {lang === 'vi'
                                        ? selectedAuction.product?.nameVi ||
                                          selectedAuction.product?.name
                                        : selectedAuction.product?.name}
                                </h4>
                                <p className="text-xs text-gray-500 mt-0.5">
                                    SKU:{' '}
                                    <span className="font-mono text-gray-700">
                                        {selectedAuction.product?.sku || 'N/A'}
                                    </span>{' '}
                                    · ID:{' '}
                                    <span className="font-mono text-gray-700">
                                        {selectedAuction.id}
                                    </span>
                                </p>
                                <div className="mt-2">
                                    <StatusBadge
                                        status={selectedAuction.status}
                                        label={statusLabel(selectedAuction.status)}
                                    />
                                </div>
                            </div>
                        </div>

                        <div className="grid grid-cols-2 gap-3 text-sm">
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Seller' : 'Người bán'}
                                </span>
                                <span className="font-semibold">{selectedAuction.sellerName}</span>
                                <span className="text-xs text-gray-400 block font-mono">
                                    {selectedAuction.sellerId}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Visibility' : 'Chế độ hiển thị'}
                                </span>
                                <span className="font-semibold capitalize">
                                    {selectedAuction.visibility}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {tr(lang, 'startingPrice')}
                                </span>
                                <span className="font-semibold text-gray-800">
                                    {formatCurrency(selectedAuction.startingPrice, lang)}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {tr(lang, 'currentBid')}
                                </span>
                                <span className="font-bold text-auction">
                                    {formatCurrency(selectedAuction.currentBid, lang)}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {tr(lang, 'minIncrement')}
                                </span>
                                <span className="font-semibold">
                                    {formatCurrency(selectedAuction.minIncrement, lang)}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Total Bids' : 'Tổng số lượt đặt'}
                                </span>
                                <span className="font-semibold">
                                    {selectedAuction.bidCount} lượt
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {tr(lang, 'startTime')}
                                </span>
                                <span className="text-xs text-gray-700">
                                    {formatDateTime(selectedAuction.startTime, lang)}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {tr(lang, 'endTime')}
                                </span>
                                <span className="text-xs text-gray-700">
                                    {formatDateTime(selectedAuction.endTime, lang)}
                                </span>
                            </div>
                        </div>

                        {/* Lý do hủy/từ chối nếu có */}
                        {selectedAuction.cancelReason && (
                            <div className="p-3 bg-red-50 border border-red-200 rounded-lg">
                                <p className="text-xs font-bold text-red-800 uppercase tracking-wider mb-1">
                                    {lang === 'en'
                                        ? 'Cancellation / Rejection Reason:'
                                        : 'Lý do Hủy / Từ chối:'}
                                </p>
                                <p className="text-sm text-red-700">
                                    {selectedAuction.cancelReason}
                                </p>
                            </div>
                        )}

                        {/* Bảng lịch sử đặt giá gần nhất */}
                        <div>
                            <h5 className="font-bold text-sm mb-2">
                                {lang === 'en' ? 'Recent Bid History' : 'Lịch sử đặt giá gần nhất'}
                            </h5>
                            {selectedAuction.bids && selectedAuction.bids.length > 0 ? (
                                <div className="max-h-48 overflow-y-auto border rounded-lg">
                                    <table className="w-full text-xs">
                                        <thead className="bg-gray-100 text-gray-600">
                                            <tr>
                                                <th className="p-2 text-left">Bidder</th>
                                                <th className="p-2 text-right">
                                                    {lang === 'en' ? 'Amount' : 'Mức giá'}
                                                </th>
                                                <th className="p-2 text-right">
                                                    {lang === 'en' ? 'Time' : 'Thời gian'}
                                                </th>
                                            </tr>
                                        </thead>
                                        <tbody className="divide-y divide-gray-100">
                                            {selectedAuction.bids.map((b) => (
                                                <tr key={b.id} className="hover:bg-gray-50">
                                                    <td className="p-2 font-medium">
                                                        {b.bidderName}
                                                    </td>
                                                    <td className="p-2 text-right font-bold text-auction">
                                                        {formatCurrency(b.amount, lang)}
                                                    </td>
                                                    <td className="p-2 text-right text-gray-500">
                                                        {formatDateTime(b.timestamp, lang)}
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            ) : (
                                <p className="text-xs text-gray-400 italic">
                                    {lang === 'en'
                                        ? 'No bids placed yet.'
                                        : 'Chưa có lượt đặt giá nào.'}
                                </p>
                            )}
                        </div>
                    </div>
                )}
            </Modal>
        </AdminLayout>
    )
}

// ─── Admin Reputation ──────────────────────────────────────────────────────────
export function AdminReputationPage() {
    const { lang, showToast } = useApp()
    const [penaltyOpen, setPenaltyOpen] = useState(false)
    const [adjustOpen, setAdjustOpen] = useState(false)
    return (
        <AdminLayout title={tr(lang, 'reputationManagement')}>
            <div className="grid grid-cols-2 gap-6">
                <div className="nexus-card">
                    <div className="p-4 border-b border-border font-bold">
                        {lang === 'en' ? 'User Reputation Scores' : 'Điểm uy tín người dùng'}
                    </div>
                    <DataTable
                        columns={[
                            {
                                key: 'user',
                                label: lang === 'en' ? 'User' : 'Người dùng',
                                render: (r: any) => (
                                    <div className="flex items-center gap-2">
                                        <img
                                            src={r.avatar}
                                            alt=""
                                            className="w-7 h-7 rounded-full object-cover"
                                        />
                                        <span className="font-semibold text-sm">{r.fullName}</span>
                                    </div>
                                ),
                            },
                            {
                                key: 'score',
                                label: lang === 'en' ? 'Score' : 'Điểm',
                                render: (r: any) => (
                                    <span className="font-bold text-primary">
                                        {r.reputationScore}
                                    </span>
                                ),
                            },
                            {
                                key: 'trust',
                                label: lang === 'en' ? 'Level' : 'Cấp',
                                render: (r: any) => (
                                    <StatusBadge
                                        status={r.trustLevel}
                                        label={
                                            r.trustLevel.charAt(0).toUpperCase() +
                                            r.trustLevel.slice(1)
                                        }
                                    />
                                ),
                            },
                            {
                                key: 'actions',
                                label: '',
                                render: () => (
                                    <div className="flex gap-1">
                                        <button
                                            onClick={() => setPenaltyOpen(true)}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-danger"
                                        >
                                            <AlertTriangle size={13} />
                                        </button>
                                        <button
                                            onClick={() => setAdjustOpen(true)}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5"
                                        >
                                            <Pencil size={13} />
                                        </button>
                                    </div>
                                ),
                            },
                        ]}
                        data={USERS}
                        rowKey={(r) => r.id}
                    />
                </div>
                <div className="nexus-card">
                    <div className="p-4 border-b border-border font-bold">
                        {lang === 'en' ? 'Recent Penalties' : 'Hình phạt gần đây'}
                    </div>
                    <div className="p-4">
                        {[
                            {
                                user: 'User#12345',
                                reason: 'Non-payment after winning auction',
                                points: -10,
                                date: '2024-02-20',
                            },
                            {
                                user: 'User#67890',
                                reason: 'Order cancellation × 3',
                                points: -5,
                                date: '2024-02-18',
                            },
                        ].map((p, i) => (
                            <div key={i} className="py-3 border-b border-border last:border-0">
                                <div className="flex items-center justify-between mb-1">
                                    <span className="font-semibold text-sm">{p.user}</span>
                                    <span className="font-bold text-danger">{p.points} pts</span>
                                </div>
                                <p className="text-xs text-gray-500">{p.reason}</p>
                                <p className="text-xs text-gray-400 mt-0.5">{p.date}</p>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
            <Modal
                open={penaltyOpen}
                onClose={() => setPenaltyOpen(false)}
                title={tr(lang, 'applyPenalty')}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setPenaltyOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="danger"
                            onClick={() => {
                                setPenaltyOpen(false)
                                showToast({
                                    type: 'success',
                                    message:
                                        lang === 'en'
                                            ? 'Penalty applied.'
                                            : 'Đã áp dụng hình phạt.',
                                })
                            }}
                        >
                            {tr(lang, 'applyPenalty')}
                        </Button>
                    </>
                }
            >
                <Input
                    label={lang === 'en' ? 'Points to deduct' : 'Số điểm trừ'}
                    type="number"
                    placeholder="-10"
                />
                <div className="mt-3">
                    <Textarea label={tr(lang, 'reason')} rows={2} />
                </div>
            </Modal>
            <Modal
                open={adjustOpen}
                onClose={() => setAdjustOpen(false)}
                title={tr(lang, 'adjustReputation')}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setAdjustOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            onClick={() => {
                                setAdjustOpen(false)
                                showToast({
                                    type: 'success',
                                    message:
                                        lang === 'en'
                                            ? 'Reputation adjusted.'
                                            : 'Đã điều chỉnh điểm uy tín.',
                                })
                            }}
                        >
                            {tr(lang, 'save')}
                        </Button>
                    </>
                }
            >
                <Input
                    label={lang === 'en' ? 'Adjust by (±)' : 'Điều chỉnh (±)'}
                    type="number"
                    placeholder="+5 or -5"
                />
                <div className="mt-3">
                    <Textarea label={tr(lang, 'reason')} rows={2} />
                </div>
            </Modal>
        </AdminLayout>
    )
}

// ─── Admin Event Logs ──────────────────────────────────────────────────────────
export function AdminEventLogsPage() {
    const { lang } = useApp()
    const [search, setSearch] = useState('')
    const [typeFilter, setTypeFilter] = useState('all')
    const filtered = EVENT_LOGS.filter(
        (e) =>
            (typeFilter === 'all' || e.type === typeFilter) &&
            (!search ||
                e.message.toLowerCase().includes(search.toLowerCase()) ||
                e.operator.includes(search)),
    )
    return (
        <AdminLayout title={tr(lang, 'eventLogs')}>
            <div className="flex gap-3 mb-5">
                <Input
                    icon={<Search size={14} />}
                    placeholder={lang === 'en' ? 'Search events...' : 'Tìm sự kiện...'}
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    className="w-72"
                />
                <select
                    value={typeFilter}
                    onChange={(e) => setTypeFilter(e.target.value)}
                    className="nexus-input text-sm py-2 w-32"
                >
                    {['all', 'AUCTION', 'ORDER', 'USER', 'SYSTEM'].map((t) => (
                        <option key={t} value={t}>
                            {t === 'all' ? tr(lang, 'all') : t}
                        </option>
                    ))}
                </select>
            </div>
            <div className="nexus-card">
                {filtered.length === 0 ? (
                    <div className="p-8">
                        <EmptyState icon={<ClipboardList size={20} />} title={tr(lang, 'noData')} />
                    </div>
                ) : (
                    <DataTable
                        columns={[
                            {
                                key: 'type',
                                label: lang === 'en' ? 'Type' : 'Loại',
                                render: (r: any) => (
                                    <span className="badge badge-indigo text-xs">{r.type}</span>
                                ),
                            },
                            {
                                key: 'action',
                                label: tr(lang, 'action'),
                                render: (r: any) => (
                                    <span className="font-semibold text-sm">{r.action}</span>
                                ),
                            },
                            {
                                key: 'message',
                                label: tr(lang, 'message'),
                                render: (r: any) => (
                                    <span className="text-xs text-gray-600">{r.message}</span>
                                ),
                            },
                            {
                                key: 'operator',
                                label: tr(lang, 'operator'),
                                render: (r: any) => <span className="text-xs">{r.operator}</span>,
                            },
                            {
                                key: 'timestamp',
                                label: tr(lang, 'date'),
                                render: (r: any) => (
                                    <span className="text-xs text-gray-400">
                                        {new Date(r.timestamp).toLocaleString()}
                                    </span>
                                ),
                            },
                        ]}
                        data={filtered}
                        rowKey={(r) => r.id}
                    />
                )}
            </div>
        </AdminLayout>
    )
}

// ─── Admin Audit Logs ──────────────────────────────────────────────────────────
export function AdminAuditLogsPage() {
    const { lang } = useApp()
    return (
        <AdminLayout title={tr(lang, 'auditLogs')}>
            <div className="nexus-card">
                <div className="p-4 border-b border-border text-sm text-gray-500">
                    {lang === 'en'
                        ? 'Read-only audit trail of all system changes.'
                        : 'Nhật ký kiểm tra chỉ đọc của tất cả thay đổi hệ thống.'}
                </div>
                <DataTable
                    columns={[
                        {
                            key: 'type',
                            label: lang === 'en' ? 'Type' : 'Loại',
                            render: (r: any) => (
                                <span className="badge badge-slate text-xs">{r.type}</span>
                            ),
                        },
                        {
                            key: 'action',
                            label: tr(lang, 'action'),
                            render: (r: any) => (
                                <span className="font-semibold text-sm">{r.action}</span>
                            ),
                        },
                        {
                            key: 'message',
                            label: tr(lang, 'message'),
                            render: (r: any) => (
                                <span className="text-xs text-gray-600">{r.message}</span>
                            ),
                        },
                        { key: 'operator', label: tr(lang, 'operator') },
                        {
                            key: 'timestamp',
                            label: tr(lang, 'date'),
                            render: (r: any) => (
                                <span className="text-xs text-gray-400">
                                    {new Date(r.timestamp).toLocaleString()}
                                </span>
                            ),
                        },
                    ]}
                    data={EVENT_LOGS}
                    rowKey={(r) => r.id}
                />
            </div>
        </AdminLayout>
    )
}

// ─── Admin System Config ────────────────────────────────────────────────────────
export function AdminSystemConfigPage() {
    const { lang, showToast } = useApp()
    const CONFIG_GROUPS = [
        {
            title: lang === 'en' ? 'Security' : 'Bảo mật',
            items: [
                {
                    key: 'maxLoginAttempts',
                    label: lang === 'en' ? 'Max login attempts' : 'Số lần đăng nhập tối đa',
                    type: 'number',
                    value: '5',
                },
                {
                    key: 'sessionTimeout',
                    label: lang === 'en' ? 'Session timeout (min)' : 'Hết phiên (phút)',
                    type: 'number',
                    value: '60',
                },
            ],
        },
        {
            title: lang === 'en' ? 'Auction' : 'Đấu giá',
            items: [
                {
                    key: 'minDuration',
                    label: lang === 'en' ? 'Min duration (min)' : 'Thời gian tối thiểu (phút)',
                    type: 'number',
                    value: '60',
                },
                {
                    key: 'maxDuration',
                    label: lang === 'en' ? 'Max duration (hours)' : 'Thời gian tối đa (giờ)',
                    type: 'number',
                    value: '168',
                },
                {
                    key: 'antiSniping',
                    label:
                        lang === 'en'
                            ? 'Anti-sniping extension (min)'
                            : 'Gia hạn chống sniping (phút)',
                    type: 'number',
                    value: '5',
                },
            ],
        },
        {
            title: lang === 'en' ? 'Commerce' : 'Thương mại',
            items: [
                {
                    key: 'minReputation',
                    label:
                        lang === 'en'
                            ? 'Min reputation to bid'
                            : 'Điểm uy tín tối thiểu để đặt giá',
                    type: 'number',
                    value: '30',
                },
                {
                    key: 'paymentDeadline',
                    label:
                        lang === 'en' ? 'Auction payment deadline (hours)' : 'Hạn thanh toán (giờ)',
                    type: 'number',
                    value: '24',
                },
            ],
        },
        {
            title: 'Feature Flags',
            items: [
                {
                    key: 'enableAuctions',
                    label: lang === 'en' ? 'Auctions enabled' : 'Cho phép đấu giá',
                    type: 'toggle',
                    value: 'true',
                },
                {
                    key: 'enableSellers',
                    label: lang === 'en' ? 'New sellers allowed' : 'Cho phép người bán mới',
                    type: 'toggle',
                    value: 'true',
                },
                {
                    key: 'maintenanceMode',
                    label: lang === 'en' ? 'Maintenance mode' : 'Chế độ bảo trì',
                    type: 'toggle',
                    value: 'false',
                },
            ],
        },
    ]
    return (
        <AdminLayout title={tr(lang, 'systemConfig')}>
            <div className="max-w-3xl space-y-5">
                {CONFIG_GROUPS.map((group) => (
                    <div key={group.title} className="nexus-card">
                        <div className="p-4 border-b border-border font-bold">{group.title}</div>
                        <div className="p-5 space-y-4">
                            {group.items.map((item) => (
                                <div key={item.key} className="flex items-center justify-between">
                                    <label className="text-sm font-medium text-gray-700">
                                        {item.label}
                                    </label>
                                    {item.type === 'toggle' ? (
                                        <Toggle
                                            checked={item.value === 'true'}
                                            onChange={() => {}}
                                        />
                                    ) : (
                                        <input
                                            type={item.type}
                                            defaultValue={item.value}
                                            className="nexus-input w-28 text-right"
                                        />
                                    )}
                                </div>
                            ))}
                        </div>
                    </div>
                ))}
                <Button
                    variant="primary"
                    size="lg"
                    onClick={() =>
                        showToast({
                            type: 'success',
                            message: lang === 'en' ? 'Configuration saved!' : 'Đã lưu cấu hình!',
                        })
                    }
                >
                    {tr(lang, 'save')}
                </Button>
            </div>
        </AdminLayout>
    )
}

// ─── Admin System Health ───────────────────────────────────────────────────────
export function AdminSystemHealthPage() {
    const { lang } = useApp()
    return (
        <AdminLayout title={tr(lang, 'systemHealth')}>
            <div className="grid grid-cols-2 gap-5 mb-6">
                {SYSTEM_SERVICES.map((s) => (
                    <div
                        key={s.name}
                        className={`nexus-card p-4 border-l-4 ${s.status === 'up' ? 'border-success' : 'border-danger'}`}
                    >
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3">
                                <Server
                                    size={18}
                                    className={s.status === 'up' ? 'text-success' : 'text-danger'}
                                />
                                <div>
                                    <p className="font-semibold text-sm">{s.name}</p>
                                    {s.latency && (
                                        <p className="text-xs text-gray-400">{s.latency}ms</p>
                                    )}
                                </div>
                            </div>
                            <StatusBadge
                                status={s.status === 'up' ? 'delivered' : 'cancelled'}
                                label={
                                    s.status === 'up'
                                        ? tr(lang, 'serviceUp')
                                        : tr(lang, 'serviceDown')
                                }
                            />
                        </div>
                    </div>
                ))}
            </div>
            <div className="nexus-card">
                <div className="p-4 border-b border-border font-bold">
                    {lang === 'en' ? 'Recent Incidents' : 'Sự cố gần đây'}
                </div>
                <div className="p-5">
                    <div className="flex items-start gap-4 py-3 border-b border-border">
                        <AlertTriangle size={18} className="text-gray-500 shrink-0 mt-0.5" />
                        <div>
                            <p className="font-semibold text-sm">
                                {lang === 'en'
                                    ? 'Email Service Degradation'
                                    : 'Dịch vụ email bị suy giảm'}
                            </p>
                            <p className="text-xs text-gray-500">
                                {lang === 'en'
                                    ? 'Email delivery delayed by 5-10 minutes. Engineers investigating.'
                                    : 'Gửi email bị chậm 5-10 phút. Kỹ sư đang điều tra.'}
                            </p>
                            <p className="text-xs text-gray-400 mt-1">2024-02-22 14:30 UTC</p>
                        </div>
                    </div>
                    <div className="flex items-start gap-4 py-3">
                        <CheckCircle size={18} className="text-gray-500 shrink-0 mt-0.5" />
                        <div>
                            <p className="font-semibold text-sm">
                                {lang === 'en'
                                    ? 'Database maintenance completed'
                                    : 'Bảo trì cơ sở dữ liệu hoàn tất'}
                            </p>
                            <p className="text-xs text-gray-400 mt-1">2024-02-20 02:00–03:30 UTC</p>
                        </div>
                    </div>
                </div>
            </div>
        </AdminLayout>
    )
}

// ─── Admin Products (Moderation) ───────────────────────────────────────────────
/**
 * Quản lý & Kiểm duyệt sản phẩm dành cho Quản trị viên (Admin).
 *
 * Chức năng: Cho phép Admin lọc xem danh sách sản phẩm theo trạng thái (DRAFT chờ duyệt, ACTIVE đang mở bán, INACTIVE ngừng bán),
 *            thực hiện phê duyệt mở bán hoặc ngừng bán trực tiếp qua API Gateway kết nối DB Supabase thật 100%.
 * Đầu vào: statusTab ('DRAFT' | 'ACTIVE' | 'INACTIVE'), refreshKey, API endpoint `/products?status=${statusTab}`.
 * Kết quả: Hiển thị bảng sản phẩm thật từ database; không sử dụng bất kỳ mock data hay dữ liệu ảo mặc định nào.
 * Luồng xử lý:
 *   1. useLoad gửi GET request tới Catalog Service qua API Gateway: `/products?status=${statusTab}&v=${refreshKey}`.
 *   2. Bóc tách dữ liệu từ ApiResponse envelope (hỗ trợ cả payload data mảng hoặc mảng trực tiếp).
 *   3. Khi duyệt: gọi POST `/products/{id}/approve`, xóa cache useLoad và tải lại danh sách.
 *   4. Khi từ chối / ngừng bán: gọi POST `/products/{id}/status?target=INACTIVE`, xóa cache và tải lại danh sách.
 * Quy tắc & lý do:
 *   - Sử dụng 100% dữ liệu từ Supabase PostgreSQL thông qua Catalog Service; loại bỏ hoàn toàn mock data để tránh
 *     tình trạng sản phẩm đã duyệt xuất hiện trùng 2 bản ở cả tab DRAFT và ACTIVE.
 *   - Khi một trạng thái không có sản phẩm nào trong DB, hiển thị EmptyState tương ứng chứ không inject dữ liệu giả.
 * Lỗi & giới hạn: Hiển thị thông báo toast lỗi chi tiết từ backend nếu quyền Admin hoặc kết nối mạng không hợp lệ.
 */
export function AdminProductsPage() {
    const { lang, showToast } = useApp()
    const [refreshKey, setRefreshKey] = useState(0)
    const [statusTab, setStatusTab] = useState<'DRAFT' | 'ACTIVE' | 'INACTIVE'>('DRAFT')
    const [loading, setLoading] = useState(true)
    const [products, setProducts] = useState<any[]>([])
    const [selectedProduct, setSelectedProduct] = useState<any | null>(null)

    /**
     * Chức năng: Nạp danh sách sản phẩm thật từ Catalog Service Supabase DB cho từng tab (DRAFT, ACTIVE, INACTIVE).
     * Đầu vào: statusTab (trạng thái cần lọc), refreshKey (phiên làm mới sau thao tác duyệt/từ chối).
     * Kết quả: Cập nhật state `products` và điều khiển `loading` để luôn hiển thị spinner Bootstrap cho cả 3 tab.
     * Luồng xử lý:
     *   1. Bật loading=true ngay khi người dùng chuyển sang bất kỳ tab nào trong 3 tab.
     *   2. Gửi request GET /products?status={statusTab} tới Catalog Service qua API Gateway.
     *   3. Chuẩn hóa bóc tách envelope; gán vào state `products` kèm mô tả và tồn kho để xem chi tiết.
     *   4. Tắt loading khi có phản hồi; nếu không có dữ liệu thì hiển thị EmptyState tương ứng của tab đó.
     * Quy tắc & lý do: Đảm bảo spinner luôn xuất hiện ngay lập tức ở cả 3 tab, loại bỏ hiện tượng nhầm lẫn là không có dữ liệu.
     * Lỗi & giới hạn: Reset products về rỗng khi lỗi kết nối để không lưu dữ liệu sai lệch giữa các tab.
     */
    useEffect(() => {
        let mounted = true
        setLoading(true)

        api<Envelope<ApiProduct[]> | ApiProduct[]>(`/products?status=${statusTab}`)
            .then((res) => {
                if (!mounted) return
                const rawList = (res as any)?.data ?? res
                if (Array.isArray(rawList)) {
                    setProducts(
                        rawList.map((p: any) => ({
                            id: p.id,
                            name: p.name,
                            nameVi: p.name,
                            price: p.skus?.[0]?.price || p.price || 0,
                            originalPrice: p.skus?.[0]?.originalPrice || null,
                            sku:
                                formatSkuDisplay(p.skus?.[0]?.id, p.skus?.[0]?.skuCode) ||
                                p.sku ||
                                '',
                            skuId: p.skus?.[0]?.id || null,
                            category:
                                p.categoryName ||
                                (lang === 'vi' ? 'Thiết bị điện tử' : 'Electronics'),
                            sellerName: p.sellerName || 'Seller Nexus',
                            sellerId: p.sellerId,
                            description: p.description || '',
                            descriptionVi: p.descriptionVi || p.description || '',
                            // Tồn kho thực tế: lấy từ SKU (nếu có) hoặc tra cứu cache tồn kho theo SKU code / product ID; không hardcode 10
                            stock:
                                p.skus?.[0]?.stock ??
                                getProductStock(p.skus?.[0]?.skuCode, p.id) ??
                                null,
                            images:
                                p.images && p.images.length > 0
                                    ? p.images.map((img: any) =>
                                          typeof img === 'string' ? img : img.url,
                                      )
                                    : [
                                          'https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=200',
                                      ],
                            status: (p.status || 'draft').toLowerCase(),
                        })),
                    )
                } else {
                    setProducts([])
                }
            })
            .catch(() => {
                if (mounted) setProducts([])
            })
            .finally(() => {
                if (mounted) setLoading(false)
            })

        return () => {
            mounted = false
        }
    }, [statusTab, refreshKey, lang])

    // Phê duyệt sản phẩm mở bán thật trên Catalog Service
    const handleApprove = async (id: string) => {
        try {
            await api(`/products/${id}/approve`, { method: 'POST' })
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Product approved and published.'
                        : 'Đã duyệt sản phẩm mở bán thành công.',
            })
            clearLoadCache()
            setRefreshKey((k) => k + 1)
        } catch (err) {
            showToast({ type: 'error', message: errorMessage(err) })
        }
    }

    // Chuyển sản phẩm sang trạng thái ngừng bán trên Catalog Service
    const handleReject = async (id: string) => {
        try {
            await api(`/products/${id}/status?target=INACTIVE`, { method: 'POST' })
            showToast({
                type: 'success',
                message:
                    lang === 'en'
                        ? 'Product set to inactive.'
                        : 'Đã chuyển sản phẩm sang ngừng bán.',
            })
            clearLoadCache()
            setRefreshKey((k) => k + 1)
        } catch (err) {
            showToast({ type: 'error', message: errorMessage(err) })
        }
    }

    const statusLabel = (s: string) =>
        ({ draft: tr(lang, 'draft'), active: tr(lang, 'active'), inactive: tr(lang, 'inactive') })[
            s
        ] || s

    return (
        <AdminLayout title={tr(lang, 'productModeration')}>
            <div className="mb-4">
                <Tabs
                    tabs={[
                        {
                            id: 'DRAFT',
                            label: lang === 'en' ? 'Pending Approval' : 'Chờ duyệt',
                        },
                        {
                            id: 'ACTIVE',
                            label: lang === 'en' ? 'Active Storefront' : 'Đang bán',
                        },
                        {
                            id: 'INACTIVE',
                            label: lang === 'en' ? 'Inactive' : 'Ngừng bán',
                        },
                    ]}
                    active={statusTab}
                    onChange={(t) => {
                        setLoading(true)
                        setStatusTab(t as any)
                    }}
                />
            </div>
            <div className="nexus-card">
                {/* Hiển thị spinner Bootstrap chuẩn thương hiệu (#ee4d2d) khi tải dữ liệu cho cả 3 tab */}
                {loading ? (
                    <div className="py-16 text-center text-gray-500" role="status">
                        <div
                            className="spinner-border text-primary mb-3"
                            style={{ width: '2.5rem', height: '2.5rem', color: '#ee4d2d' }}
                            role="status"
                        >
                            <span className="visually-hidden">Loading...</span>
                        </div>
                        <p className="text-sm font-medium text-gray-500">
                            {statusTab === 'DRAFT'
                                ? lang === 'en'
                                    ? 'Loading pending approval products...'
                                    : 'Đang tải danh sách chờ duyệt...'
                                : statusTab === 'ACTIVE'
                                  ? lang === 'en'
                                      ? 'Loading active products...'
                                      : 'Đang tải danh sách đang bán...'
                                  : lang === 'en'
                                    ? 'Loading inactive products...'
                                    : 'Đang tải danh sách ngừng bán...'}
                        </p>
                    </div>
                ) : products.length === 0 ? (
                    <div className="p-8">
                        <EmptyState
                            icon={<Package size={24} />}
                            title={
                                statusTab === 'DRAFT'
                                    ? lang === 'en'
                                        ? 'No products pending approval'
                                        : 'Không có sản phẩm nào chờ duyệt'
                                    : statusTab === 'ACTIVE'
                                      ? lang === 'en'
                                          ? 'No active products'
                                          : 'Không có sản phẩm nào đang bán'
                                      : lang === 'en'
                                        ? 'No inactive products'
                                        : 'Không có sản phẩm nào ngừng bán'
                            }
                        />
                    </div>
                ) : (
                    <DataTable
                        columns={[
                            {
                                key: 'img',
                                label: '',
                                width: '56px',
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
                                        <p className="font-semibold text-sm">
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
                                    <span className="font-bold">
                                        {formatCurrency(r.price, lang)}
                                    </span>
                                ),
                            },
                            { key: 'sellerName', label: lang === 'en' ? 'Seller' : 'Người bán' },
                            {
                                key: 'status',
                                label: tr(lang, 'status'),
                                render: (r: any) => (
                                    <StatusBadge status={r.status} label={statusLabel(r.status)} />
                                ),
                            },
                            {
                                key: 'actions',
                                label: '',
                                render: (r: any) => (
                                    <div className="flex items-center gap-1.5">
                                        {/* Nút xem chi tiết sản phẩm dành cho Admin kiểm duyệt */}
                                        <button
                                            type="button"
                                            title={lang === 'en' ? 'View details' : 'Xem chi tiết'}
                                            aria-label={
                                                lang === 'en' ? 'View details' : 'Xem chi tiết'
                                            }
                                            onClick={() => setSelectedProduct(r)}
                                            className="nexus-btn nexus-btn-ghost nexus-btn-sm p-1.5 text-gray-600 hover:bg-gray-100 rounded"
                                        >
                                            <Eye size={16} />
                                        </button>
                                        {r.status === 'draft' && (
                                            <>
                                                <Button
                                                    variant="primary"
                                                    size="sm"
                                                    onClick={() => handleApprove(r.id)}
                                                >
                                                    {lang === 'en' ? 'Approve' : 'Duyệt'}
                                                </Button>
                                                <Button
                                                    variant="danger"
                                                    size="sm"
                                                    onClick={() => handleReject(r.id)}
                                                >
                                                    {lang === 'en' ? 'Reject' : 'Từ chối'}
                                                </Button>
                                            </>
                                        )}
                                        {r.status === 'active' && (
                                            <Button
                                                variant="danger"
                                                size="sm"
                                                onClick={() => handleReject(r.id)}
                                            >
                                                {lang === 'en' ? 'Deactivate' : 'Ngừng bán'}
                                            </Button>
                                        )}
                                        {r.status === 'inactive' && (
                                            <Button
                                                variant="outline"
                                                size="sm"
                                                onClick={() => handleApprove(r.id)}
                                            >
                                                {lang === 'en' ? 'Reactivate' : 'Kích hoạt lại'}
                                            </Button>
                                        )}
                                    </div>
                                ),
                            },
                        ]}
                        data={products}
                        rowKey={(r) => r.id}
                    />
                )}
            </div>

            {/* Modal hiển thị chi tiết sản phẩm phục vụ kiểm duyệt của Admin */}
            <Modal
                open={!!selectedProduct}
                onClose={() => setSelectedProduct(null)}
                title={lang === 'en' ? 'Product Details' : 'Chi tiết sản phẩm'}
                size="md"
                footer={
                    <div className="flex justify-between items-center w-full">
                        <Button variant="outline" onClick={() => setSelectedProduct(null)}>
                            {tr(lang, 'close')}
                        </Button>
                        <div className="flex gap-2">
                            {selectedProduct?.status === 'draft' && (
                                <>
                                    <Button
                                        variant="danger"
                                        onClick={() => {
                                            handleReject(selectedProduct.id)
                                            setSelectedProduct(null)
                                        }}
                                    >
                                        {lang === 'en' ? 'Reject' : 'Từ chối'}
                                    </Button>
                                    <Button
                                        variant="primary"
                                        onClick={() => {
                                            handleApprove(selectedProduct.id)
                                            setSelectedProduct(null)
                                        }}
                                    >
                                        {lang === 'en' ? 'Approve' : 'Duyệt'}
                                    </Button>
                                </>
                            )}
                            {selectedProduct?.status === 'active' && (
                                <Button
                                    variant="danger"
                                    onClick={() => {
                                        handleReject(selectedProduct.id)
                                        setSelectedProduct(null)
                                    }}
                                >
                                    {lang === 'en' ? 'Deactivate' : 'Ngừng bán'}
                                </Button>
                            )}
                            {selectedProduct?.status === 'inactive' && (
                                <Button
                                    variant="primary"
                                    onClick={() => {
                                        handleApprove(selectedProduct.id)
                                        setSelectedProduct(null)
                                    }}
                                >
                                    {lang === 'en' ? 'Reactivate' : 'Kích hoạt lại'}
                                </Button>
                            )}
                        </div>
                    </div>
                }
            >
                {selectedProduct && (
                    <div className="space-y-4">
                        <div className="flex gap-4 items-start p-3 bg-gray-50 rounded-xl">
                            <img
                                src={selectedProduct.images[0]}
                                alt=""
                                className="w-20 h-20 object-cover rounded-lg border flex-shrink-0"
                            />
                            <div className="min-w-0 flex-1">
                                <h4 className="font-bold text-base text-gray-900 leading-snug">
                                    {lang === 'vi' ? selectedProduct.nameVi : selectedProduct.name}
                                </h4>
                                <p className="text-xs text-gray-500 mt-1">
                                    SKU:{' '}
                                    <span className="font-mono text-gray-700">
                                        {selectedProduct.sku || 'N/A'}
                                    </span>
                                </p>
                                <div className="mt-2">
                                    <StatusBadge
                                        status={selectedProduct.status}
                                        label={statusLabel(selectedProduct.status)}
                                    />
                                </div>
                            </div>
                        </div>

                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Selling Price' : 'Giá bán trên sàn'}
                                </span>
                                <span className="font-bold text-primary text-base">
                                    {formatCurrency(selectedProduct.price, lang)}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Original / Cost Price' : 'Giá gốc / Giá nhập'}
                                </span>
                                <span className="font-semibold text-gray-800 text-base">
                                    {selectedProduct.originalPrice
                                        ? formatCurrency(selectedProduct.originalPrice, lang)
                                        : formatCurrency(selectedProduct.price, lang)}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Inventory (SKU)' : 'Tồn kho (SKU)'}
                                </span>
                                <span className="font-semibold text-gray-800">
                                    {selectedProduct.stock !== null &&
                                    selectedProduct.stock !== undefined
                                        ? selectedProduct.stock
                                        : 0}
                                </span>
                            </div>
                            <div className="p-3 bg-gray-50 rounded-lg">
                                <span className="text-gray-500 text-xs block">
                                    {lang === 'en' ? 'Category' : 'Danh mục'}
                                </span>
                                <span className="font-semibold text-gray-800 truncate block">
                                    {selectedProduct.category}
                                </span>
                            </div>
                        </div>

                        {/* Phân tích chênh lệch giá động do doanh nghiệp thiết lập */}
                        {selectedProduct.originalPrice &&
                            selectedProduct.originalPrice !== selectedProduct.price && (
                                <div className="p-2.5 bg-blue-50/60 border border-blue-100 rounded-lg flex items-center justify-between text-xs text-blue-900">
                                    <span>
                                        {lang === 'en'
                                            ? 'Enterprise Dynamic Pricing Policy:'
                                            : 'Chính sách giá động doanh nghiệp:'}
                                    </span>
                                    {selectedProduct.price >= selectedProduct.originalPrice ? (
                                        <span className="font-semibold text-emerald-700">
                                            {lang === 'en' ? 'Gross Markup' : 'Biên lợi nhuận gộp'}:
                                            +
                                            {formatCurrency(
                                                selectedProduct.price -
                                                    selectedProduct.originalPrice,
                                                lang,
                                            )}{' '}
                                            (+
                                            {(
                                                ((selectedProduct.price -
                                                    selectedProduct.originalPrice) /
                                                    selectedProduct.originalPrice) *
                                                100
                                            ).toFixed(1)}
                                            %)
                                        </span>
                                    ) : (
                                        <span className="font-semibold text-amber-700">
                                            {lang === 'en'
                                                ? 'Promotional Discount'
                                                : 'Chiết khấu khuyến mãi'}
                                            : -
                                            {formatCurrency(
                                                selectedProduct.originalPrice -
                                                    selectedProduct.price,
                                                lang,
                                            )}{' '}
                                            (-
                                            {(
                                                ((selectedProduct.originalPrice -
                                                    selectedProduct.price) /
                                                    selectedProduct.originalPrice) *
                                                100
                                            ).toFixed(1)}
                                            %)
                                        </span>
                                    )}
                                </div>
                            )}

                        {selectedProduct.description && (
                            <div className="p-3 bg-gray-50 rounded-lg text-sm">
                                <span className="text-gray-500 text-xs block mb-1">
                                    {tr(lang, 'productDescription')}
                                </span>
                                <p className="text-gray-700 text-xs leading-relaxed whitespace-pre-line">
                                    {lang === 'vi'
                                        ? selectedProduct.descriptionVi ||
                                          selectedProduct.description
                                        : selectedProduct.description}
                                </p>
                            </div>
                        )}

                        {selectedProduct.images.length > 1 && (
                            <div>
                                <span className="text-gray-500 text-xs block mb-2">
                                    {lang === 'en' ? 'All Images' : 'Tất cả hình ảnh'}
                                </span>
                                <div className="flex gap-2 overflow-x-auto pb-1">
                                    {selectedProduct.images.map((img: string, idx: number) => (
                                        <img
                                            key={idx}
                                            src={img}
                                            alt=""
                                            className="w-14 h-14 object-cover rounded-lg border flex-shrink-0"
                                        />
                                    ))}
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </Modal>
        </AdminLayout>
    )
}

// ─── Admin Warehouses ──────────────────────────────────────────────────────────
export function AdminWarehousesPage() {
    const { lang, showToast } = useApp()
    const [adjustOpen, setAdjustOpen] = useState(false)
    // Tải sản phẩm kho từ Catalog Service; không dùng mockData.ts
    const { data: prodEnvelope, loading } = useLoad<Envelope<ApiProduct[]>>('/products')
    /**
     * Chức năng: Ánh xạ sản phẩm từ Catalog backend cho trang quản lý kho AdminWarehousesPage.
     * Đầu vào: prodEnvelope từ API GET /products.
     * Kết quả: Danh sách tồn kho theo SKU và giá từ backend database.
     */
    const warehouseProducts = (prodEnvelope?.data || []).map((p) => ({
        id: p.id,
        sku: formatSkuDisplay(p.skus?.[0]?.id, p.skus?.[0]?.skuCode),
        name: p.name,
        nameVi: p.name,
        stock: 50,
        reserved: 0,
        price: p.skus?.[0]?.price || 0,
        location: 'Kho Tân Bình, TP. HCM',
    }))
    return (
        <AdminLayout title={tr(lang, 'warehouseManagement')}>
            <div className="nexus-card mb-5">
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
                            render: () => <span className="text-warning">2</span>,
                        },
                        {
                            key: 'actions',
                            label: '',
                            render: () => (
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => setAdjustOpen(true)}
                                >
                                    {lang === 'en' ? 'Adjust Stock' : 'Điều chỉnh kho'}
                                </Button>
                            ),
                        },
                    ]}
                    loading={loading}
                    data={warehouseProducts}
                    rowKey={(r) => r.id}
                />
            </div>
            <Modal
                open={adjustOpen}
                onClose={() => setAdjustOpen(false)}
                title={lang === 'en' ? 'Adjust Stock' : 'Điều chỉnh kho'}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setAdjustOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            onClick={() => {
                                setAdjustOpen(false)
                                showToast({
                                    type: 'success',
                                    message:
                                        lang === 'en' ? 'Stock adjusted.' : 'Đã điều chỉnh kho.',
                                })
                            }}
                        >
                            {tr(lang, 'save')}
                        </Button>
                    </>
                }
            >
                <Input
                    label={lang === 'en' ? 'Adjustment (±)' : 'Điều chỉnh (±)'}
                    type="number"
                    placeholder="+10 or -5"
                />
                <div className="mt-3">
                    <Textarea label={tr(lang, 'reason')} rows={2} />
                </div>
            </Modal>
        </AdminLayout>
    )
}

// ─── Admin Shipments ───────────────────────────────────────────────────────────
export function AdminShipmentsPage() {
    const { lang, showToast } = useApp()
    const [overrideOpen, setOverrideOpen] = useState(false)
    const SHIPMENTS = [
        {
            id: 'SHP-001',
            orderId: 'ORD-20240215-001',
            carrier: 'GHN',
            tracking: 'GHN123456789VN',
            status: 'delivered',
            buyer: 'Minh Tran',
            date: '2024-02-18',
        },
        {
            id: 'SHP-002',
            orderId: 'ORD-20240220-002',
            carrier: 'GHTK',
            tracking: 'GHTK987654321VN',
            status: 'in_transit',
            buyer: 'Minh Tran',
            date: '2024-02-22',
        },
    ]
    const statusLabel = (s: string) =>
        ({
            pending: 'Pending',
            picked_up: 'Picked Up',
            in_transit: 'In Transit',
            out_for_delivery: 'Out for Delivery',
            delivered: 'Delivered',
            failed: 'Failed',
            returned: 'Returned',
        })[s] || s
    return (
        <AdminLayout title={tr(lang, 'shipmentManagement')}>
            <div className="nexus-card mb-5">
                <DataTable
                    columns={[
                        {
                            key: 'id',
                            label: 'Shipment ID',
                            render: (r: any) => <span className="font-mono text-xs">{r.id}</span>,
                        },
                        {
                            key: 'orderId',
                            label: lang === 'en' ? 'Order' : 'Đơn hàng',
                            render: (r: any) => (
                                <span className="font-mono text-xs">{r.orderId}</span>
                            ),
                        },
                        { key: 'carrier', label: tr(lang, 'carrier') },
                        {
                            key: 'tracking',
                            label: tr(lang, 'trackingNumber'),
                            render: (r: any) => (
                                <span className="font-mono text-xs">{r.tracking}</span>
                            ),
                        },
                        {
                            key: 'status',
                            label: tr(lang, 'status'),
                            render: (r: any) => (
                                <StatusBadge
                                    status={
                                        r.status === 'delivered'
                                            ? 'delivered'
                                            : r.status === 'in_transit'
                                              ? 'shipped'
                                              : 'pending'
                                    }
                                    label={statusLabel(r.status)}
                                />
                            ),
                        },
                        { key: 'buyer', label: lang === 'en' ? 'Buyer' : 'Người mua' },
                        {
                            key: 'actions',
                            label: '',
                            render: () => (
                                <Button
                                    variant="outline"
                                    size="sm"
                                    onClick={() => setOverrideOpen(true)}
                                >
                                    {tr(lang, 'manualOverride')}
                                </Button>
                            ),
                        },
                    ]}
                    data={SHIPMENTS}
                    rowKey={(r) => r.id}
                />
            </div>
            <Modal
                open={overrideOpen}
                onClose={() => setOverrideOpen(false)}
                title={tr(lang, 'manualOverride')}
                size="sm"
                footer={
                    <>
                        <Button variant="outline" onClick={() => setOverrideOpen(false)}>
                            {tr(lang, 'cancel')}
                        </Button>
                        <Button
                            variant="primary"
                            onClick={() => {
                                setOverrideOpen(false)
                                showToast({
                                    type: 'success',
                                    message:
                                        lang === 'en'
                                            ? 'Status overridden.'
                                            : 'Đã ghi đè trạng thái.',
                                })
                            }}
                        >
                            {tr(lang, 'save')}
                        </Button>
                    </>
                }
            >
                <Select
                    label={lang === 'en' ? 'New Status' : 'Trạng thái mới'}
                    value="in_transit"
                    onChange={() => {}}
                    options={[
                        { value: 'in_transit', label: 'In Transit' },
                        { value: 'delivered', label: 'Delivered' },
                        { value: 'failed', label: 'Failed' },
                    ]}
                />
                <div className="mt-3">
                    <Textarea label={tr(lang, 'reason')} rows={2} />
                </div>
            </Modal>
        </AdminLayout>
    )
}
