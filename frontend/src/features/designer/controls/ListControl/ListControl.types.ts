export interface ListControlProps {
  label: string;
  values: readonly string[];
  onChange: (values: readonly string[]) => void;
  disabled?: boolean;
  helperText?: string;
  error?: string;
}
