/**
 * YACHAQ Platform SDK for TypeScript/JavaScript
 * 
 * Provides a type-safe client for interacting with the YACHAQ Requester API.
 * 
 * @example
 * ```typescript
 * import { YachaqClient } from '@yachaq/sdk';
 * 
 * const client = new YachaqClient({
 *   baseUrl: 'https://api.yachaq.io',
 *   apiKey: 'your-api-key'
 * });
 * 
 * // Create a request
 * const result = await client.createRequest({
 *   requiredLabels: ['health:steps', 'health:sleep'],
 *   compensation: 5.00,
 *   outputMode: 'AGGREGATE_ONLY'
 * });
 * ```
 * 
 * Validates: Requirements 352.1, 352.2, 352.3
 */

export * from './client';
export * from './types';
export * from './errors';
