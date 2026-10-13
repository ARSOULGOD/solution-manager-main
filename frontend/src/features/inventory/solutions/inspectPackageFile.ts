import {
  parseAndValidateSolutionPackage,
  type PackageValidationOptions,
  type PackageValidationResult,
} from '@contracts/solution-package';

function failure(code: string, message: string): PackageValidationResult {
  return { valid: false, issues: [{ gate: 1, code, path: '/', message }] };
}

function readText(file: File): Promise<string> {
  if (typeof file.text === 'function') return file.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('The file could not be read.'));
    reader.readAsText(file);
  });
}

/** Reads and validates a browser-selected JSON file without mutating the catalog. */
export async function inspectPackageFile(
  file: File,
  options: PackageValidationOptions,
): Promise<PackageValidationResult> {
  if (!file.name.toLowerCase().endsWith('.json')) {
    return failure(
      'UNSUPPORTED_FILE_TYPE',
      'Select the Solution Package JSON document. Archive upload is not available yet.',
    );
  }
  try {
    return parseAndValidateSolutionPackage(await readText(file), options);
  } catch (error) {
    return failure(
      'FILE_READ_FAILED',
      error instanceof Error ? error.message : 'The selected file could not be read.',
    );
  }
}
