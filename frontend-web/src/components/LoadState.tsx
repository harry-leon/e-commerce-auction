import { AlertBanner } from './UI'

/** Dùng chung trạng thái tải/lỗi để không hiển thị lỗi mạng như một danh sách rỗng thành công. */
export function LoadState({ error, loading }: { error: string; loading: boolean }) {
    if (error) return <AlertBanner type="error" message={error} />
    if (loading)
        return (
            <p role="status" className="py-6">
                Đang tải…
            </p>
        )
    return null
}
