// VIOLATION — no VITE_*_URL env var may name a plane host (FE-D19, task 027).
export const dpUrl: string | undefined = import.meta.env.VITE_DP_URL;
