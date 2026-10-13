// VIOLATION — features/x may import features/y's public barrel only (FE-D3, §4.1).
import { designerInternal } from '../designer/internal.ts';

export const leaked = designerInternal;
