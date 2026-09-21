/**
 * The one error surface (design §16, `FE-D10`). Always visually and
 * semantically distinct from an empty state — "empty is never a silent
 * success" — so no other component may render this to mean "no data".
 */
import { Alert, AlertTitle, Box, Button, Typography } from '@mui/material';

export interface ErrorPanelProps {
  title: string;
  detail: string;
  code?: string;
  correlationId?: string;
  onRetry?: () => void;
}

export function ErrorPanel({ title, detail, code, correlationId, onRetry }: ErrorPanelProps) {
  return (
    <Alert severity="error" variant="outlined" role="alert">
      <AlertTitle>{title}</AlertTitle>
      <Typography variant="body2">{detail}</Typography>
      {code !== undefined && (
        <Typography variant="caption" component="p" sx={{ mt: 1 }}>
          Error code: {code}
        </Typography>
      )}
      {correlationId !== undefined && (
        <Typography variant="caption" component="p">
          Correlation id: {correlationId}
        </Typography>
      )}
      {onRetry !== undefined && (
        <Box sx={{ mt: 1 }}>
          <Button size="small" color="error" onClick={onRetry}>
            Retry
          </Button>
        </Box>
      )}
    </Alert>
  );
}
