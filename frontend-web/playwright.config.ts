import { defineConfig } from '@playwright/test'

// Tự dựng Vite cho test trình duyệt; dùng Edge local hoặc PLAYWRIGHT_CHANNEL được chỉ định.
// Các request backend trong e2e được chặn bằng fixture; kiểm chứng DB/SMTP thật nằm ở script smoke.
export default defineConfig({
  testDir: './e2e',
  use: { baseURL: 'http://localhost:18443', channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge', headless: true },
  webServer: {
    command: 'npm run dev -- --port 18443',
    url: 'http://localhost:18443',
    reuseExistingServer: !process.env.CI,
  },
})
