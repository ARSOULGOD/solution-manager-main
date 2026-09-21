// VIOLATION — shared/ui is presentational; it must not call the API layer (§4.4).
import { cpClient } from '@shared/api/cpClient';

export const leaked = cpClient;
