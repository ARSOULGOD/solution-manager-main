/**
 * Decorative, connection-type-keyed tab icons. Inlined `SvgIcon`s, not
 * `@mui/icons-material` (not a project dependency — same ruling
 * `composerIcons.tsx` already follows). Purely decorative: `aria-hidden`,
 * so the tab's accessible name stays the plain "{label}s ({count})" text
 * `ConnectionsPage.test.tsx` asserts against.
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import type { ReactElement } from 'react';

import type { ConnectionTypeKey } from '@shared/testdoubles/data/connections';

function BrokerIcon(props: SvgIconProps): ReactElement {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 3a3 3 0 0 1 1.6 5.54l2.47 4.28A3 3 0 0 1 20 15.5a3 3 0 1 1-5.96.5H9.96a3 3 0 1 1-1.07-3.24l2.47-4.26A3 3 0 0 1 12 3zm0 2a1 1 0 1 0 0 2 1 1 0 0 0 0-2zM7 15.5a1 1 0 1 0 2 0 1 1 0 0 0-2 0zm8.5 0a1 1 0 1 0 2 0 1 1 0 0 0-2 0zm-4.34-6.4-2.13 3.7a3 3 0 0 1 .77.9h4.4a3 3 0 0 1 .77-.9l-2.13-3.7a3 3 0 0 1-1.68 0z" />
    </SvgIcon>
  );
}

function RemoteServerIcon(props: SvgIconProps): ReactElement {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M4 4h16a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zm2.5 3a1 1 0 1 0 0 2 1 1 0 0 0 0-2zM4 12h16a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1zm2.5 3a1 1 0 1 0 0 2 1 1 0 0 0 0-2z" />
    </SvgIcon>
  );
}

export function ConnectionTypeIcon({
  connectionType,
  ...props
}: SvgIconProps & { connectionType: ConnectionTypeKey }): ReactElement {
  return connectionType === 'solace-broker' ? (
    <BrokerIcon {...props} />
  ) : (
    <RemoteServerIcon {...props} />
  );
}
