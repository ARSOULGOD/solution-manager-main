export type Env = 'DEV' | 'TEST' | 'PROD' | (string & {});

export interface EnvChipProps {
  env: Env;
}
