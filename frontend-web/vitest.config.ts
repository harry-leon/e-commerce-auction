import { defineConfig } from 'vitest/config'

// Đo toàn bộ TS/TSX ứng dụng, kể cả module chưa được import bởi test; không bỏ các trang demo khỏi báo cáo.
// Ngưỡng LINE 70% giống quy ước JaCoCo backend; test/fixture hỗ trợ kiểm thử không phải code ứng dụng.
export default defineConfig({
    test: {
        include: ['src/**/*.test.{ts,tsx}'],
        environment: 'jsdom',
        setupFiles: ['src/test/setup.ts'],
        coverage: {
            provider: 'v8',
            reporter: ['text', 'text-summary', 'cobertura', 'html'],
            include: ['src/**/*.{ts,tsx}'],
            exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**', 'src/**/*.d.ts'],
            thresholds: { lines: 70 },
        },
    },
})
