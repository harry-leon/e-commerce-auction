import tseslint from 'typescript-eslint'

// ESLint kiểm tra cú pháp/logic; tsconfig.json bật noUnusedLocals/noUnusedParameters để chặn code thừa khi typecheck/build.
export default tseslint.config({
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { parser: tseslint.parser, parserOptions: { ecmaFeatures: { jsx: true } } },
    plugins: { '@typescript-eslint': tseslint.plugin },
    rules: {
        'constructor-super': 'error',
        'no-constant-condition': 'error',
        'no-dupe-args': 'error',
        'no-dupe-else-if': 'error',
        'no-dupe-keys': 'error',
        'no-duplicate-case': 'error',
        'no-unreachable': 'error',
        'valid-typeof': 'error',
    },
})
