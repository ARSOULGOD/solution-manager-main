export interface MapEntry {
  key: string;
  value: string;
}

export interface MapControlProps {
  label: string;
  entries: readonly MapEntry[];
  onChange: (entries: readonly MapEntry[]) => void;
  disabled?: boolean;
  helperText?: string;
  error?: string;
}
