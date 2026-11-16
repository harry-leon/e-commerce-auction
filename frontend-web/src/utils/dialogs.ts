import Swal, { type SweetAlertOptions } from 'sweetalert2'
import confetti from 'canvas-confetti'

const defaultCustomClass = {
    popup: 'nexus-swal-popup rounded-2xl shadow-2xl border border-gray-100 p-6',
    title: 'text-xl font-bold text-gray-900',
    htmlContainer: 'text-sm text-gray-600 mt-2',
    confirmButton:
        'px-5 py-2.5 rounded-xl font-semibold text-white bg-primary hover:bg-primary-dark transition-all duration-200 shadow-md hover:shadow-lg focus:outline-none focus:ring-2 focus:ring-primary/40 mr-2',
    cancelButton:
        'px-5 py-2.5 rounded-xl font-semibold text-gray-700 bg-gray-100 hover:bg-gray-200 transition-all duration-200 border border-gray-200 focus:outline-none focus:ring-2 focus:ring-gray-300 ml-2',
    actions: 'flex items-center justify-center gap-3 mt-6',
}

const nexusSwal = Swal.mixin({
    customClass: defaultCustomClass,
    buttonsStyling: false,
})

export interface ConfirmDialogOptions {
    title: string
    text?: string
    confirmText?: string
    cancelText?: string
    icon?: 'warning' | 'info' | 'question' | 'error' | 'success'
    isDanger?: boolean
}

/**
 * Hộp thoại xác nhận hành động quan trọng (SweetAlert2) được thiết kế đồng bộ theo phong cách Nexus.
 */
export async function confirmDialog({
    title,
    text,
    confirmText = 'Xác nhận',
    cancelText = 'Hủy bỏ',
    icon = 'question',
    isDanger = false,
}: ConfirmDialogOptions): Promise<boolean> {
    const customConfig: SweetAlertOptions = {
        title,
        text,
        icon,
        showCancelButton: true,
        confirmButtonText: confirmText,
        cancelButtonText: cancelText,
        reverseButtons: true,
        backdrop: 'rgba(15, 23, 42, 0.45)',
        customClass: isDanger
            ? {
                  ...defaultCustomClass,
                  confirmButton:
                      'px-5 py-2.5 rounded-xl font-semibold text-white bg-red-600 hover:bg-red-700 transition-all duration-200 shadow-md hover:shadow-lg focus:outline-none focus:ring-2 focus:ring-red-500/40 mr-2',
              }
            : defaultCustomClass,
    }

    const result = await nexusSwal.fire(customConfig)
    return result.isConfirmed
}

/**
 * Thông báo thành công dạng popup Modal.
 */
export async function successAlert(title: string, text?: string): Promise<void> {
    await nexusSwal.fire({
        title,
        text,
        icon: 'success',
        confirmButtonText: 'Đóng',
        backdrop: 'rgba(15, 23, 42, 0.45)',
    })
}

/**
 * Thông báo lỗi dạng popup Modal.
 */
export async function errorAlert(title: string, text?: string): Promise<void> {
    await nexusSwal.fire({
        title,
        text,
        icon: 'error',
        confirmButtonText: 'Đã hiểu',
        backdrop: 'rgba(15, 23, 42, 0.45)',
    })
}

/**
 * Kích hoạt hiệu ứng pháo hoa Confetti khi hoàn tất giao dịch hoặc thắng đấu giá.
 */
export function fireConfetti(options?: confetti.Options): void {
    try {
        if (typeof window === 'undefined' || typeof document === 'undefined') return
        const testCanvas = document.createElement('canvas')
        // JSDOM hoặc môi trường test không có canvas 2D context thật -> bỏ qua để tránh unhandled error
        const ctx = testCanvas.getContext && testCanvas.getContext('2d')
        if (!ctx) return

        confetti({
            particleCount: 80,
            spread: 70,
            origin: { y: 0.6 },
            colors: ['#ee4d2d', '#ff5722', '#16a34a', '#f59e0b', '#3b82f6'],
            ...options,
        })
    } catch {
        // Fallback im lặng nếu môi trường không hỗ trợ canvas
    }
}
