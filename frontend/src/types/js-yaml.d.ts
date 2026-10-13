declare module 'js-yaml' {
  export interface LoadOptions {
    json?: boolean;
    schema?: unknown;
  }

  export interface DumpOptions {
    noRefs?: boolean;
    lineWidth?: number;
    noCompatMode?: boolean;
  }

  export function load(input: string, options?: LoadOptions): unknown;
  export function dump(input: unknown, options?: DumpOptions): string;
}
