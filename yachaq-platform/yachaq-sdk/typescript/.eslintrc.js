module.exports = {
  root: true,
  parser: '@typescript-eslint/parser',
  plugins: [
    '@typescript-eslint',
    'jsdoc',
  ],
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:jsdoc/recommended',
  ],
  rules: {
    // TSDoc checks
    'jsdoc/require-param-description': 0,
    'jsdoc/require-returns-description': 0,
    'jsdoc/check-tag-names': [
      'error', {
        'definedTags': ['remarks', 'defaultValue']
      }
    ],
    'jsdoc/no-types': 'error' // Enforce TSDoc style, no @type in comments
  },
  env: {
    node: true
  }
};
