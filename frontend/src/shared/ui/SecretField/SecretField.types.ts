import type { TextFieldProps } from '../TextField';

export type SecretFieldProps = Omit<TextFieldProps, 'reveal'>;
