/* eslint-env node */
module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true },
  },
  settings: {
    react: { version: 'detect' },
    'import/resolver': {
      typescript: { project: './tsconfig.json' },
      node: true,
    },
  },
  plugins: [
    '@typescript-eslint',
    'react',
    'react-hooks',
    'react-refresh',
    'jsx-a11y',
    'import',
  ],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended',
    'plugin:react/jsx-runtime',
    'plugin:react-hooks/recommended',
    'plugin:jsx-a11y/recommended',
    'plugin:import/recommended',
    'plugin:import/typescript',
    'prettier',
  ],
  ignorePatterns: [
    'dist',
    'node_modules',
    'coverage',
    'playwright-report',
    'test-results',
    'src/api/generated/**',
    '.eslintrc.cjs',
  ],
  rules: {
    'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    'no-console': ['error', { allow: ['warn', 'error'] }],
    '@typescript-eslint/consistent-type-imports': [
      'error',
      { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
    ],
    '@typescript-eslint/no-explicit-any': 'error',
    '@typescript-eslint/no-unused-vars': [
      'error',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
    ],
    'import/order': [
      'error',
      {
        groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
        'newlines-between': 'always',
        alphabetize: { order: 'asc', caseInsensitive: true },
      },
    ],
    'no-restricted-syntax': [
      'error',
      {
        selector:
          "CallExpression[callee.object.name='localStorage'][callee.property.name='setItem'] > Literal[value=/token/i]",
        message: 'Do not persist JWT tokens in localStorage. Use memory or sessionStorage.',
      },
      {
        selector:
          "MemberExpression[object.name='Number'][property.name='parseFloat']",
        message: 'Use Money helper / decimal.js-light for monetary values; do not use Number.parseFloat.',
      },
      {
        selector: "CallExpression[callee.name='parseFloat']",
        message: 'Use Money helper / decimal.js-light for monetary values; do not use parseFloat.',
      },
      {
        selector:
          "CallExpression[callee.object.name='Math'][callee.property.name='random']",
        message: 'Math.random() is forbidden. Randomness lives on the server.',
      },
    ],
    'import/no-restricted-paths': [
      'error',
      {
        zones: [
          {
            target: './src/api',
            from: './src/game',
            message: 'src/api/** must not import from src/game/**.',
          },
          {
            target: './src/api',
            from: './src/pages',
            message: 'src/api/** must not import from src/pages/**.',
          },
          {
            target: './src/common',
            from: './src/game',
            message: 'src/common/** must not import from src/game/**.',
          },
          {
            target: './src/game/feature/freespins',
            from: './src/game/feature/pickcollect',
            message: 'Cross-feature imports are forbidden between freespins and pickcollect.',
          },
          {
            target: './src/game/feature/pickcollect',
            from: './src/game/feature/freespins',
            message: 'Cross-feature imports are forbidden between freespins and pickcollect.',
          },
        ],
      },
    ],
  },
  overrides: [
    {
      files: ['**/*.test.ts', '**/*.test.tsx', '**/*.spec.ts', '**/*.spec.tsx', 'vitest.setup.ts', 'src/mocks/**'],
      rules: {
        'no-console': 'off',
        '@typescript-eslint/no-explicit-any': 'off',
      },
    },
    {
      files: ['src/observability/logger.ts'],
      rules: {
        'no-console': 'off',
      },
    },
  ],
};
