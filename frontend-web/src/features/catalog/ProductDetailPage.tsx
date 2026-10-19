import { useState } from 'react'
import type { ApiProduct, Envelope } from '../../api/client'
import { useApp } from '../../context'
import { BuyerLayout } from '../../components/Layout'
import { Button } from '../../components/UI'
import { LoadState } from '../../components/LoadState'
import { useLoad } from '../../hooks/useLoad'
import { price } from './catalog'

/** Lấy UUID từ URL, tải chi tiết thật và hiển thị ảnh/SKU; không thay sản phẩm thiếu bằng dữ liệu demo. */
export function ProductDetailPage() {
    const { pageParams, navigate } = useApp()
    const { data, error, loading } = useLoad<Envelope<ApiProduct>>(
        `/products/${encodeURIComponent(String(pageParams.id || ''))}`,
    )
    const [image, setImage] = useState(0)
    const product = data?.data
    return (
        <BuyerLayout>
            <Button onClick={() => navigate('search')}>Quay lại sản phẩm</Button>
            <LoadState error={error} loading={loading} />
            {product && (
                <article className="nexus-card p-6 mt-5 grid md:grid-cols-2 gap-8">
                    <div>
                        {product.images[image] && (
                            <img
                                src={product.images[image].url}
                                alt={product.name}
                                className="w-full rounded-lg"
                            />
                        )}
                        <div className="flex gap-2 mt-3">
                            {product.images.map((item, index) => (
                                <button
                                    key={item.id}
                                    onClick={() => setImage(index)}
                                    aria-label={`Ảnh ${index + 1}`}
                                >
                                    <img src={item.url} alt="" className="w-16 h-16 object-cover" />
                                </button>
                            ))}
                        </div>
                    </div>
                    <div>
                        <h1 className="text-2xl font-bold">{product.name}</h1>
                        <p className="text-xl my-4">{price(product)}</p>
                        <p className="whitespace-pre-wrap">{product.description}</p>
                        <h2 className="font-semibold mt-6">Phiên bản sản phẩm</h2>
                        <ul>
                            {product.skus.map((sku) => (
                                <li className="py-2 border-b" key={sku.id}>
                                    {sku.skuCode} · {Number(sku.price).toLocaleString('vi-VN')} ₫ ·{' '}
                                    {sku.status}
                                </li>
                            ))}
                        </ul>
                    </div>
                </article>
            )}
        </BuyerLayout>
    )
}
