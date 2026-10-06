import type { Order, PaymentStatus } from './types'

export function getEffectivePaymentStatus(order: Order, now = Date.now()): PaymentStatus {
  const protectionExpired = order.protectionEndsAt && new Date(order.protectionEndsAt).getTime() <= now
  const hasOpenDispute = order.disputeStatus === 'open' || order.disputeStatus === 'reviewing'

  if (order.paymentStatus === 'protection_period' && protectionExpired && !hasOpenDispute) {
    return 'released'
  }

  return order.paymentStatus
}
