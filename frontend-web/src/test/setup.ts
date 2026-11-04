import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// jsdom không có scrolling thật; giữ API này để component điều hướng hoạt động như trong browser.
window.scrollTo = vi.fn()
// Dọn dẹp React tree sau mỗi test để tránh rò rỉ state giữa các case.
// Hoạt động đúng trong pool vmThreads; với vmForks (Vitest 5 default) thì gây lỗi "cannot find suite".
afterEach(() => cleanup())
