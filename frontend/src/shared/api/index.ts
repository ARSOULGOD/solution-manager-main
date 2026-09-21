/**
 * Public barrel for `shared/api` (task 027). Every later API task (028
 * error codec, 029 query defaults, 035 streaming download) appends its own
 * exports here rather than opening a second entry point.
 */
export { configureCsrf, onUnauthorized, request, requestWithCorrelation, RequestCoordinator } from './http';
export { CORRELATION_ID_HEADER, generateCorrelationId, readCorrelationId } from './correlation';
export {
  ApiError,
  shouldRetry,
  type ApiErrorInit,
  type CsrfConfig,
  type ErrorCode,
  type HttpMethod,
  type RequestOptions,
  type RequestResult,
  type SpecifiedErrorCode,
  type TbdErrorCode,
} from './types';

export { createCpClient } from './cpClient';
export { createDpClient } from './dpClient';
export { createConsoleClient } from './consoleClient';
export type { PlaneClient } from './planeClientFactory';

export {
  describeErrorCode,
  ERROR_CODE_REGISTRY,
  errorCodeStatus,
  type ErrorCodeEntry,
  type ErrorCodeStatus,
} from './errorCodes';
export { MUTATION_RETRY, retryDelay } from './retry';

export { createQueryClient } from './queryClient';
export { queryKeys } from './queryKeys';

export type { Paged } from './contracts/common';

export { startStreamingDownload } from './download';
