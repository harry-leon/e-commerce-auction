import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import App from '../App'

const route = vi.hoisted(() => ({ fail: false }))
vi.mock('../routes/AppRouter', () => ({
    AppRouter: () => {
        if (route.fail) throw new Error('Route crashed')
        return <main>Healthy routed content</main>
    },
}))

// Kiểm tra error boundary thật: lỗi render không làm trắng trang và người dùng có thể Retry sau khi nguyên nhân hết.
beforeEach(() => {
    route.fail = false
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
})
it('renders normal content without the legacy role switch toolbar', () => {
    render(<App />)
    expect(screen.getByText('Healthy routed content')).toBeVisible()
    expect(screen.queryByText('Demo:')).not.toBeInTheDocument()
})
it('shows an error and recovers through Retry', () => {
    route.fail = true
    render(<App />)
    expect(screen.getByText('Application Error / Lỗi ứng dụng')).toBeVisible()
    route.fail = false
    fireEvent.click(screen.getByRole('button', { name: /Retry/ }))
    expect(screen.getByText('Healthy routed content')).toBeVisible()
})
