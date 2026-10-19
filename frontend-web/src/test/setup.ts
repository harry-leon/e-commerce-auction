import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// jsdom không có scrolling thật; giữ API này để component điều hướng hoạt động như trong browser.
window.scrollTo = vi.fn()
afterEach(() => cleanup())
