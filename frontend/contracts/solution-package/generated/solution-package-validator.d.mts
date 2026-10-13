import type { ErrorObject } from 'ajv';

import type { CanonicalSolutionPackage } from '../types';

interface SolutionPackageSchemaValidator {
  (data: unknown): data is CanonicalSolutionPackage;
  errors: ErrorObject[] | null;
}

declare const validate: SolutionPackageSchemaValidator;

export default validate;
