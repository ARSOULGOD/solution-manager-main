/** Dev-only gallery demo (task 010's registry). */
import { Alert } from './Alert';
import type { AlertSeverity } from './Alert.types';

const severities: AlertSeverity[] = ['info', 'warn', 'err', 'ok', 'neutral'];

function AlertGalleryDemo() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 480 }}>
      {severities.map((severity) => (
        <Alert key={severity} severity={severity} title={severity}>
          Example {severity} alert body text.
        </Alert>
      ))}
      <Alert severity="err" title="Could not load" action={<button type="button">Retry</button>}>
        The server timed out.
      </Alert>
      <Alert severity="info" fullWidth>
        Banner placement — 3 of 12 instances unavailable.
      </Alert>
    </div>
  );
}

export const alertGallerySection = {
  name: 'Alert',
  element: <AlertGalleryDemo />,
};
