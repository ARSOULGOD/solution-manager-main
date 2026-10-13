/**
 * Task 028's named entry point for the error type. The class itself is
 * declared in `types.ts` (task 026, §6.0.1) so `http.ts` can throw it
 * without importing forward from this later task — see that file's header.
 * This module is the stable `@shared/api/ApiError` import path callers
 * outside `shared/api` should use.
 */
export { ApiError, type ApiErrorInit } from './types';
