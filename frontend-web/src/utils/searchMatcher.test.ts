import { describe, it, expect } from 'vitest'
import { removeVietnameseTones, matchesSearchKeyword, scoreProductSearch } from './searchMatcher'

describe('Vietnamese Smart Search Matcher', () => {
    it('removes Vietnamese tones accurately', () => {
        expect(removeVietnameseTones('Đèn bàn học LED')).toBe('den ban hoc led')
        expect(removeVietnameseTones('Áo thun cotton')).toBe('ao thun cotton')
        expect(removeVietnameseTones('Chuột Gaming')).toBe('chuot gaming')
    })

    it('matches "đèn led" with "Đèn bàn học LED" even when separated by other words', () => {
        const productName = 'Đèn bàn học LED'
        expect(matchesSearchKeyword(productName, 'đèn led')).toBe(true)
        expect(matchesSearchKeyword(productName, 'ĐÈN LED')).toBe(true)
        expect(matchesSearchKeyword(productName, 'den led')).toBe(true)
    })

    it('matches when words are in reverse order ("led đèn")', () => {
        const productName = 'Đèn bàn học LED'
        expect(matchesSearchKeyword(productName, 'led đèn')).toBe(true)
        expect(matchesSearchKeyword(productName, 'led den')).toBe(true)
    })

    it('matches partial keywords ("bàn học", "ban hoc", "đèn", "led")', () => {
        const productName = 'Đèn bàn học LED'
        expect(matchesSearchKeyword(productName, 'bàn học')).toBe(true)
        expect(matchesSearchKeyword(productName, 'ban hoc')).toBe(true)
        expect(matchesSearchKeyword(productName, 'đèn')).toBe(true)
        expect(matchesSearchKeyword(productName, 'den')).toBe(true)
        expect(matchesSearchKeyword(productName, 'led')).toBe(true)
    })

    it('does not match completely unrelated queries', () => {
        const productName = 'Đèn bàn học LED'
        expect(matchesSearchKeyword(productName, 'tai nghe')).toBe(false)
        expect(matchesSearchKeyword(productName, 'laptop msi')).toBe(false)
        expect(matchesSearchKeyword(productName, 'ao thun')).toBe(false)
    })

    it('scores product with query words in title higher than product with query words only in description', () => {
        const directProduct = {
            name: 'Đèn bàn học LED',
            description: 'Đèn bàn học bảo vệ thị lực',
        }
        const indirectProduct = {
            name: 'Mô hình Figure Monkey D. Luffy',
            description: 'Có tích hợp đèn led đổi màu chân đế',
        }

        const scoreDirect = scoreProductSearch(directProduct, 'đèn led')
        const scoreIndirect = scoreProductSearch(indirectProduct, 'đèn led')

        expect(scoreDirect).toBeGreaterThan(scoreIndirect)
    })
})
