import type { ReactNode } from 'react';

export interface SearchFieldProps {
  label?: string;
  value: string;
  onChange: (value: string) => void;
  /** Debounce for free text only. Enum filters must not use this. */
  debounceMs?: number;
}

export interface FilterBarProps {
  children?: ReactNode;
  search?: SearchFieldProps;
  resultCount: number;
}
