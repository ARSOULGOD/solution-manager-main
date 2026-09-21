/**
 * Decorative, role-keyed icons for the palette and canvas nodes — inlined
 * `SvgIcon`s, not `@mui/icons-material` (not a project dependency, same
 * ruling `SectionCard`/`ValidationSummary`/`RolloutCard` already follow).
 *
 * Keyed by `role` only (`source` / `sink` / anything else), never by a
 * component `type` string — a per-type icon table would be exactly the
 * "hard-coded FTP/SFTP/S3 list" the palette's own caption disclaims
 * (design §10, UI §A.8.18).
 */
import { SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';
import type { ReactElement } from 'react';

function SourceIcon(props: SvgIconProps): ReactElement {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M4 4h16v4H4V4zm0 6h10v4H4v-4zm0 6h16v4H4v-4z" />
    </SvgIcon>
  );
}

function SinkIcon(props: SvgIconProps): ReactElement {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 2l9 5v10l-9 5-9-5V7l9-5zm0 2.3L5 8v8l7 3.7 7-3.7V8l-7-3.7z" />
    </SvgIcon>
  );
}

function GenericComponentIcon(props: SvgIconProps): ReactElement {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M4 4h7v7H4V4zm9 0h7v7h-7V4zM4 13h7v7H4v-7zm9 0h7v7h-7v-7z" />
    </SvgIcon>
  );
}

export function ComponentRoleIcon({
  role,
  ...props
}: SvgIconProps & { role: string | undefined }): ReactElement {
  if (role === 'source') {
    return <SourceIcon {...props} />;
  }
  if (role === 'sink') {
    return <SinkIcon {...props} />;
  }
  return <GenericComponentIcon {...props} />;
}
