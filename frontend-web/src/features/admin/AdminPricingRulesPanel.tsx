import { useEffect, useState } from 'react'
import { useApp } from '../../context'
import { formatCurrency } from '../../i18n'
import { useLoad } from '../../hooks/useLoad'
import { errorMessage, type ApiCategory, type Envelope } from '../../api/client'
import {
    createPricingRule,
    deletePricingRule,
    listAllPricingRules,
    previewDynamicPrice,
    updatePricingRule,
    type PricePreviewResponse,
    type PricingRuleRequest,
    type PricingRuleResponse,
} from '../../api/pricing'
import {
    AlertBanner,
    Button,
    ConfirmModal,
    Input,
    Modal,
    Pencil,
    Plus,
    StatusBadge,
    Toggle,
    Trash2,
} from '../../components/UI'

/** Dẹt cây danh mục đa cấp để Admin chọn đúng danh mục đích hoặc để trống cho quy tắc toàn sàn. */
function flattenCategories(list: ApiCategory[]): ApiCategory[] {
    return list.flatMap((c) => [c, ...flattenCategories(c.children || [])])
}

/** Format phần trăm dễ đọc từ tỷ lệ thập phân của BE (0.15 -> "+15.0%"). */
function fmtPercent(rate: number): string {
    const pct = rate * 100
    const sign = pct > 0 ? '+' : ''
    return `${sign}${pct.toFixed(1)}%`
}

/**
 * Bảng quản trị Quy tắc Tính Giá Động (Dynamic Pricing Engine) đặt trong AdminSystemConfigPage.
 * Chức năng: Cho phép Admin cấu hình Margin, Season Factor, Market Adjustment theo danh mục/toàn sàn
 * và kiểm thử công thức `sellingPrice = originalPrice * (1 + max(0, margin + season + market))`.
 */
export function AdminPricingRulesPanel() {
    const { lang, showToast } = useApp()
    const { data: categoriesEnv } = useLoad<Envelope<ApiCategory[]>>('/categories')
    const categories = flattenCategories(categoriesEnv?.data || [])

    const [rules, setRules] = useState<PricingRuleResponse[]>([])
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    // State modal thêm/sửa quy tắc
    const [modalOpen, setModalOpen] = useState(false)
    const [editingId, setEditingId] = useState<string | null>(null)
    const [saving, setSaving] = useState(false)
    const [deleteId, setDeleteId] = useState<string | null>(null)

    const [name, setName] = useState('')
    const [categoryId, setCategoryId] = useState('')
    const [marginRate, setMarginRate] = useState('0.15')
    const [seasonFactor, setSeasonFactor] = useState('0')
    const [marketRate, setMarketRate] = useState('0')
    const [priority, setPriority] = useState('10')
    const [isActive, setIsActive] = useState(true)

    // State công cụ tính thử giá động theo Backend /api/v1/pricing-rules/preview
    const [simOriginalPrice, setSimOriginalPrice] = useState('1000000')
    const [simCategoryId, setSimCategoryId] = useState('')
    const [simResult, setSimResult] = useState<PricePreviewResponse | null>(null)
    const [simLoading, setSimLoading] = useState(false)

    const loadRules = async () => {
        setLoading(true)
        setError(null)
        try {
            const list = await listAllPricingRules()
            setRules(list)
        } catch (err) {
            setError(errorMessage(err))
        } finally {
            setLoading(false)
        }
    }

    useEffect(() => {
        void loadRules()
    }, [])

    const openCreateModal = () => {
        setEditingId(null)
        setName('')
        setCategoryId('')
        setMarginRate('0.15')
        setSeasonFactor('0')
        setMarketRate('0')
        setPriority('10')
        setIsActive(true)
        setModalOpen(true)
    }

    const openEditModal = (rule: PricingRuleResponse) => {
        setEditingId(rule.id)
        setName(rule.name)
        setCategoryId(rule.categoryId || '')
        setMarginRate(String(rule.marginRate))
        setSeasonFactor(String(rule.seasonFactor))
        setMarketRate(String(rule.marketAdjustmentRate))
        setPriority(String(rule.priority))
        setIsActive(rule.isActive)
        setModalOpen(true)
    }

    const handleSaveRule = async () => {
        if (!name.trim()) {
            showToast({
                type: 'warning',
                message: lang === 'en' ? 'Rule name is required' : 'Vui lòng nhập tên quy tắc giá',
            })
            return
        }
        const margin = Number(marginRate)
        if (!Number.isFinite(margin) || margin < 0) {
            showToast({
                type: 'warning',
                message:
                    lang === 'en'
                        ? 'Margin rate must be >= 0'
                        : 'Biên lợi nhuận (marginRate) phải >= 0',
            })
            return
        }
        const payload: PricingRuleRequest = {
            name: name.trim(),
            categoryId: categoryId || null,
            marginRate: margin,
            seasonFactor: Number(seasonFactor) || 0,
            marketAdjustmentRate: Number(marketRate) || 0,
            priority: Math.round(Number(priority) || 0),
            isActive,
        }
        setSaving(true)
        try {
            if (editingId) {
                await updatePricingRule(editingId, payload)
            } else {
                await createPricingRule(payload)
            }
            showToast({
                type: 'success',
                message:
                    lang === 'en' ? 'Dynamic pricing rule saved!' : 'Đã lưu quy tắc tính giá động!',
            })
            setModalOpen(false)
            await loadRules()
        } catch (err) {
            showToast({ type: 'error', message: errorMessage(err) })
        } finally {
            setSaving(false)
        }
    }

    const handleDeleteRule = async () => {
        if (!deleteId) return
        try {
            await deletePricingRule(deleteId)
            showToast({
                type: 'success',
                message: lang === 'en' ? 'Pricing rule deleted' : 'Đã xóa quy tắc tính giá',
            })
            setDeleteId(null)
            await loadRules()
        } catch (err) {
            showToast({ type: 'error', message: errorMessage(err) })
        }
    }

    const handlePreview = async () => {
        const minP = Number(simOriginalPrice)
        if (!Number.isFinite(minP) || minP < 0) return
        setSimLoading(true)
        try {
            const res = await previewDynamicPrice(minP, simCategoryId || null)
            setSimResult(res)
        } catch (err) {
            showToast({ type: 'error', message: errorMessage(err) })
        } finally {
            setSimLoading(false)
        }
    }

    return (
        <div className="space-y-4">
            <div className="nexus-card">
                <div className="p-4 border-b border-border flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <h3 className="font-bold text-gray-900">
                            {lang === 'en'
                                ? 'Dynamic Pricing Rules (Catalog Engine)'
                                : 'Quy tắc Tính Giá Động (Dynamic Pricing Engine)'}
                        </h3>
                        <p className="text-xs text-gray-500 mt-0.5">
                            {lang === 'en'
                                ? 'Selling price = sku.originalPrice × (1 + max(0, margin + season + market)). Guaranteed >= minPrice.'
                                : 'Giá bán = sku.originalPrice × (1 + max(0, margin + season + market)). Luôn >= giá nhập tối thiểu.'}
                        </p>
                    </div>
                    <Button variant="primary" size="sm" onClick={openCreateModal}>
                        <Plus size={14} />
                        {lang === 'en' ? 'Add Pricing Rule' : 'Thêm quy tắc giá'}
                    </Button>
                </div>

                {error && (
                    <div className="p-4">
                        <AlertBanner type="error" message={error} />
                    </div>
                )}

                <div className="p-4 overflow-x-auto">
                    {loading ? (
                        <p className="text-sm text-gray-400">
                            {lang === 'en' ? 'Loading pricing rules...' : 'Đang tải quy tắc giá...'}
                        </p>
                    ) : rules.length === 0 ? (
                        <p className="text-sm text-gray-400">
                            {lang === 'en'
                                ? 'No pricing rules configured.'
                                : 'Chưa có quy tắc giá nào.'}
                        </p>
                    ) : (
                        <table className="w-full text-sm text-left">
                            <thead>
                                <tr className="border-b border-border text-xs text-gray-500 uppercase">
                                    <th className="py-2 pr-3">
                                        {lang === 'en' ? 'Rule' : 'Tên quy tắc'}
                                    </th>
                                    <th className="py-2 pr-3">
                                        {lang === 'en' ? 'Scope' : 'Phạm vi'}
                                    </th>
                                    <th className="py-2 pr-3">Margin</th>
                                    <th className="py-2 pr-3">Season</th>
                                    <th className="py-2 pr-3">Market</th>
                                    <th className="py-2 pr-3">Priority</th>
                                    <th className="py-2 pr-3">
                                        {lang === 'en' ? 'Status' : 'Trạng thái'}
                                    </th>
                                    <th className="py-2 text-right">
                                        {lang === 'en' ? 'Actions' : 'Thao tác'}
                                    </th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border/60">
                                {rules.map((rule) => (
                                    <tr key={rule.id}>
                                        <td className="py-2.5 pr-3 font-medium text-gray-900">
                                            {rule.name}
                                        </td>
                                        <td className="py-2.5 pr-3 text-gray-600">
                                            {rule.categoryName ||
                                                (lang === 'en' ? 'Global (All)' : 'Toàn sàn')}
                                        </td>
                                        <td className="py-2.5 pr-3 font-mono text-emerald-700">
                                            {fmtPercent(rule.marginRate)}
                                        </td>
                                        <td className="py-2.5 pr-3 font-mono text-gray-700">
                                            {fmtPercent(rule.seasonFactor)}
                                        </td>
                                        <td className="py-2.5 pr-3 font-mono text-gray-700">
                                            {fmtPercent(rule.marketAdjustmentRate)}
                                        </td>
                                        <td className="py-2.5 pr-3 font-mono">{rule.priority}</td>
                                        <td className="py-2.5 pr-3">
                                            <StatusBadge
                                                status={rule.isActive ? 'active' : 'inactive'}
                                                label={
                                                    rule.isActive
                                                        ? lang === 'en'
                                                            ? 'Active'
                                                            : 'Đang áp dụng'
                                                        : lang === 'en'
                                                          ? 'Inactive'
                                                          : 'Tạm tắt'
                                                }
                                            />
                                        </td>
                                        <td className="py-2.5 text-right whitespace-nowrap">
                                            <Button
                                                variant="ghost"
                                                size="sm"
                                                onClick={() => openEditModal(rule)}
                                            >
                                                <Pencil size={13} />
                                            </Button>
                                            <Button
                                                variant="ghost"
                                                size="sm"
                                                className="text-danger"
                                                onClick={() => setDeleteId(rule.id)}
                                            >
                                                <Trash2 size={13} />
                                            </Button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </div>
            </div>

            {/* Công cụ kiểm thử nhanh công thức Backend */}
            <div className="nexus-card p-4 space-y-3 bg-gray-50/70">
                <p className="text-sm font-bold text-gray-800">
                    {lang === 'en'
                        ? 'Test Dynamic Price Simulator (/pricing-rules/preview)'
                        : 'Kiểm thử nhanh công thức tính giá (/pricing-rules/preview)'}
                </p>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 items-end">
                    <Input
                        label={
                            lang === 'en' ? 'SKU Min / Original Price' : 'Giá nhập tối thiểu SKU'
                        }
                        type="number"
                        value={simOriginalPrice}
                        onChange={(e) => setSimOriginalPrice(e.target.value)}
                    />
                    <div>
                        <label className="block text-xs font-semibold text-gray-700 mb-1">
                            {lang === 'en' ? 'Category (optional)' : 'Danh mục (tùy chọn)'}
                        </label>
                        <select
                            className="nexus-input w-full"
                            value={simCategoryId}
                            onChange={(e) => setSimCategoryId(e.target.value)}
                        >
                            <option value="">
                                {lang === 'en' ? 'Global fallback' : 'Mặc định toàn sàn'}
                            </option>
                            {categories.map((c) => (
                                <option key={c.id} value={c.id}>
                                    {lang === 'vi' ? c.nameVi || c.name : c.name}
                                </option>
                            ))}
                        </select>
                    </div>
                    <Button variant="secondary" loading={simLoading} onClick={handlePreview}>
                        {lang === 'en' ? 'Calculate Price' : 'Tính giá bán thử'}
                    </Button>
                </div>
                {simResult && (
                    <div className="p-3 bg-white rounded-lg border border-border text-xs flex flex-wrap items-center justify-between gap-2">
                        <div>
                            <span className="text-gray-500">
                                {lang === 'en' ? 'Applied rule:' : 'Quy tắc áp dụng:'}{' '}
                            </span>
                            <span className="font-semibold text-gray-900">
                                {simResult.appliedRuleName || 'System Fallback'}
                            </span>
                            <span className="ml-2 text-gray-500">
                                ({fmtPercent(simResult.effectiveRate)})
                            </span>
                        </div>
                        <div className="text-sm font-bold text-primary">
                            {lang === 'en' ? 'Computed Selling Price:' : 'Giá bán ra:'}{' '}
                            {formatCurrency(simResult.sellingPrice, lang)}
                        </div>
                    </div>
                )}
            </div>

            <Modal
                open={modalOpen}
                onClose={() => setModalOpen(false)}
                title={
                    editingId
                        ? lang === 'en'
                            ? 'Edit Pricing Rule'
                            : 'Cập nhật quy tắc giá'
                        : lang === 'en'
                          ? 'Create Pricing Rule'
                          : 'Tạo quy tắc tính giá mới'
                }
                footer={
                    <>
                        <Button variant="outline" onClick={() => setModalOpen(false)}>
                            {lang === 'en' ? 'Cancel' : 'Hủy'}
                        </Button>
                        <Button variant="primary" loading={saving} onClick={handleSaveRule}>
                            {lang === 'en' ? 'Save Rule' : 'Lưu quy tắc'}
                        </Button>
                    </>
                }
            >
                <div className="space-y-3">
                    <Input
                        label={lang === 'en' ? 'Rule Name *' : 'Tên quy tắc *'}
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="Tet Season Electronics +22%"
                    />
                    <div>
                        <label className="block text-xs font-semibold text-gray-700 mb-1">
                            {lang === 'en' ? 'Target Category' : 'Danh mục áp dụng'}
                        </label>
                        <select
                            className="nexus-input w-full"
                            value={categoryId}
                            onChange={(e) => setCategoryId(e.target.value)}
                        >
                            <option value="">
                                {lang === 'en' ? 'Global (All Categories)' : 'Toàn sàn (Mặc định)'}
                            </option>
                            {categories.map((c) => (
                                <option key={c.id} value={c.id}>
                                    {lang === 'vi' ? c.nameVi || c.name : c.name}
                                </option>
                            ))}
                        </select>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                        <Input
                            label="Margin Rate (e.g. 0.15 = 15%)"
                            type="number"
                            value={marginRate}
                            onChange={(e) => setMarginRate(e.target.value)}
                        />
                        <Input
                            label="Season Factor (e.g. 0.05)"
                            type="number"
                            value={seasonFactor}
                            onChange={(e) => setSeasonFactor(e.target.value)}
                        />
                        <Input
                            label="Market Adjustment (e.g. -0.02)"
                            type="number"
                            value={marketRate}
                            onChange={(e) => setMarketRate(e.target.value)}
                        />
                        <Input
                            label="Priority"
                            type="number"
                            value={priority}
                            onChange={(e) => setPriority(e.target.value)}
                        />
                    </div>
                    <div className="flex items-center justify-between pt-2">
                        <span className="text-sm font-medium text-gray-700">
                            {lang === 'en' ? 'Active' : 'Kích hoạt ngay'}
                        </span>
                        <Toggle checked={isActive} onChange={setIsActive} />
                    </div>
                </div>
            </Modal>

            <ConfirmModal
                open={Boolean(deleteId)}
                onClose={() => setDeleteId(null)}
                onConfirm={handleDeleteRule}
                title={lang === 'en' ? 'Delete pricing rule?' : 'Xóa quy tắc giá này?'}
                message={
                    lang === 'en'
                        ? 'Catalog pricing will fall back to parent category or global rules.'
                        : 'Giá sản phẩm thuộc danh mục này sẽ tự động chuyển về quy tắc cha hoặc mặc định toàn sàn.'
                }
                danger
            />
        </div>
    )
}
