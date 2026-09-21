export interface DurationControlProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  helperText?: string;
  error?: string;
  disabled?: boolean;
  name?: string;
}
