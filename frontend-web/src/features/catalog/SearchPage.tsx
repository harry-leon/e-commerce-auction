import { useState } from 'react'
import type { ApiProduct, ApiCategory, Envelope } from '../../api/client'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { Button, Input } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'
import { flatten, price } from './catalog'

/**
 * Luồng catalog: lấy bộ lọc từ form/URL → GET /products → hiển thị data và meta.total.
 * Mỗi lần đổi bộ lọc quay về trang 0; giới hạn/sort/giá được backend kiểm tra lại.
 */
export function SearchPage() {
    const { navigate, pageParams } = useApp()
    const [q, setQ] = useState(String(pageParams.q || ''))
    const [category, setCategory] = useState(String(pageParams.category || ''))
    const [min, setMin] = useState('')
    const [max, setMax] = useState('')
    const [kind, setKind] = useState('')
    const [sort, setSort] = useState('createdAt,desc')
    const [page, setPage] = useState(0)
    // Backend dùng trang bắt đầu từ 0; UI hiển thị số trang bắt đầu từ 1 và 12 sản phẩm mỗi trang.
    const query = new URLSearchParams({ q, page: String(page), size: '12', sort })
    if (category) query.set('categoryId', category)
    if (min) query.set('minPrice', min)
    if (max) query.set('maxPrice', max)
    if (kind) query.set('auctionEnabled', kind)
    const { data, error, loading } = useLoad<Envelope<ApiProduct[]>>(`/products?${query}`)
    const categories = useLoad<Envelope<ApiCategory[]>>('/categories')
    // Khi chưa tải được data hoặc thiếu ảnh, chỉ render collection hợp lệ để tránh lỗi map/index.
    const products = Array.isArray(data?.data) ? data.data : []
    const total = data?.meta?.total || 0
    return (
        <BuyerLayout>
            <h1 className="text-2xl font-bold mb-5">Sản phẩm</h1>
            <form
                className="nexus-card p-4 grid sm:grid-cols-3 gap-3 mb-6"
                onSubmit={(event) => event.preventDefault()}
            >
                <Input
                    label="Tìm kiếm"
                    value={q}
                    onChange={(event) => {
                        setQ(event.target.value)
                        setPage(0)
                    }}
                />
                <label>
                    Danh mục
                    <select
                        className="nexus-input w-full"
                        value={category}
                        onChange={(event) => {
                            setCategory(event.target.value)
                            setPage(0)
                        }}
                    >
                        <option value="">Tất cả</option>
                        {flatten(categories.data?.data || []).map((item) => (
                            <option key={item.id} value={item.id}>
                                {'—'.repeat(item.level - 1)} {item.nameVi || item.name}
                            </option>
                        ))}
                    </select>
                </label>
                <label>
                    Sắp xếp
                    <select
                        className="nexus-input w-full"
                        value={sort}
                        onChange={(event) => {
                            setSort(event.target.value)
                            setPage(0)
                        }}
                    >
                        <option value="createdAt,desc">Mới nhất</option>
                        <option value="name,asc">Tên A–Z</option>
                    </select>
                </label>
                <Input
                    label="Giá từ"
                    type="number"
                    min="0"
                    value={min}
                    onChange={(event) => {
                        setMin(event.target.value)
                        setPage(0)
                    }}
                />
                <Input
                    label="Giá đến"
                    type="number"
                    min="0"
                    value={max}
                    onChange={(event) => {
                        setMax(event.target.value)
                        setPage(0)
                    }}
                />
                <label>
                    Loại sản phẩm
                    <select
                        className="nexus-input w-full"
                        value={kind}
                        onChange={(event) => {
                            setKind(event.target.value)
                            setPage(0)
                        }}
                    >
                        <option value="">Tất cả</option>
                        <option value="false">Mua ngay</option>
                        <option value="true">Có đấu giá</option>
                    </select>
                </label>
            </form>
            <LoadState error={error || categories.error} loading={loading} />
            {!loading && !error && <p className="mb-4">{total} sản phẩm</p>}
            <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-5">
                {products.map((product) => (
                    <button
                        key={product.id}
                        className="nexus-card overflow-hidden text-left"
                        onClick={() => navigate('product-detail', { id: product.id })}
                    >
                        {product.images?.[0] && (
                            <img
                                className="h-48 w-full object-cover"
                                src={product.images?.[0].url}
                                alt={product.name}
                            />
                        )}
                        <div className="p-4">
                            <h2 className="font-semibold">{product.name}</h2>
                            <p>{price(product)}</p>
                        </div>
                    </button>
                ))}
            </div>
            {!loading && !error && total === 0 && <p>Không có sản phẩm phù hợp.</p>}
            <div className="flex items-center justify-center gap-4 mt-6">
                <Button disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>
                    Trước
                </Button>
                <span>Trang {page + 1}</span>
                <Button
                    disabled={(page + 1) * 12 >= total || loading}
                    onClick={() => setPage(page + 1)}
                >
                    Sau
                </Button>
            </div>
        </BuyerLayout>
    )
}
