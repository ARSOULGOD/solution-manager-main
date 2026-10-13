/**
 * Local sign-in (task 037, `D13` 🔒 deferred, `FE-D24`, design §14/§15,
 * UI §A.3). Chromeless — `AppShell` (task 036) is for the authenticated
 * app; a signed-out operator has no drawer, no breadcrumbs, no organization
 * chip to show.
 *
 * Visual spec ported verbatim from the checked-in reference
 * (`docs/design/frontend/reference/theme.css` `.login-wrap`/`.login-mesh`/
 * `.login-card`/`.login-sub`, `reference/app/login.jsx`'s mesh generator) —
 * navy full-bleed background, a floating card at a fixed 404px/38·36·30
 * padding/14px radius, not the generic `shared/ui` `Paper` surface (none of
 * its three variants match this screen's exact numbers).
 *
 * **Dark-mode card (task 111, `FE-D5`).** The reference never styles
 * `.login-card` under `[data-theme="dark"]` — it stays hardcoded `#fff` —
 * so there is no fixed spec to port for dark mode; the card must be
 * genuinely mode-reactive instead. Background, border and shadow come from
 * `useTheme()` (`theme.palette.background.paper`, `theme.palette.divider`,
 * `theme.shadows[24]` — the same depth MUI's own default `Dialog` elevation
 * resolves to, the closest existing analogue to a floating card over a
 * dimmed backdrop), never from the flat `tokens` object, which `tokens.ts`
 * resolves once from `:root` at module load and never re-reads the
 * `[data-theme="dark"]` overrides (see the note at `tokens.ts:518-519`).
 * The outer navy backdrop is left as `tokens.console.navy`: `--navy` has no
 * dark-mode override in the reference either, so it is already the same
 * value in both modes — not the frozen-token bug, nothing to fix.
 *
 * `idleTimeoutMinutes` comes from `RuntimeConfig`, which a feature may not
 * import directly (boundaries: feature -> app is not a granted policy,
 * same constraint `RequireAuth.tsx`, task 031, documents for itself) — the
 * app-layer route (`LoginRoute.tsx`) supplies it as a prop.
 */
import { Box, Paper, Typography, useTheme } from '@mui/material';
import { useMemo } from 'react';
import { useSearchParams } from 'react-router';

import type { ArrivalReason } from '@shared/auth/AuthProvider';
import { t } from '@shared/i18n';
import { tokens } from '@shared/theme';

import { LoginArrivalAlert } from './LoginArrivalAlert';
import { LoginForm } from './LoginForm';

// Ported verbatim from `reference/app/login.jsx`'s mesh generator — same
// node coordinates, same >230 link-distance cutoff, same radii/opacities.
const MESH_NODES: ReadonlyArray<readonly [number, number]> = [
  [140, 150],
  [360, 90],
  [560, 180],
  [760, 110],
  [980, 200],
  [1080, 380],
  [860, 360],
  [640, 380],
  [420, 340],
  [220, 320],
  [160, 540],
  [380, 560],
  [600, 560],
  [820, 560],
  [1010, 600],
  [300, 720],
  [560, 730],
  [820, 730],
];
const MESH_LINK_DISTANCE = 230;

function isArrivalReason(value: string | null): value is Exclude<ArrivalReason, null> {
  return value === 'session' || value === 'idle';
}

function LoginMesh() {
  const links: Array<[readonly [number, number], readonly [number, number]]> = [];
  for (let i = 0; i < MESH_NODES.length; i += 1) {
    for (let j = i + 1; j < MESH_NODES.length; j += 1) {
      const a = MESH_NODES[i];
      const b = MESH_NODES[j];
      if (a === undefined || b === undefined) {
        continue;
      }
      if (Math.hypot(a[0] - b[0], a[1] - b[1]) <= MESH_LINK_DISTANCE) {
        links.push([a, b]);
      }
    }
  }

  return (
    <Box
      component="svg"
      viewBox="0 0 1200 800"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      sx={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        opacity: 0.5,
        zIndex: 0,
      }}
    >
      {links.map(([a, b], index) => (
        <line
          key={`link-${String(index)}`}
          x1={a[0]}
          y1={a[1]}
          x2={b[0]}
          y2={b[1]}
          stroke={tokens.console.accent}
          strokeOpacity={0.13}
          strokeWidth={1}
        />
      ))}
      {MESH_NODES.map((node, index) => (
        <circle
          key={`node-${String(index)}`}
          cx={node[0]}
          cy={node[1]}
          r={index % 3 === 0 ? 6 : 3.5}
          fill={tokens.console.accent}
          fillOpacity={index % 3 === 0 ? 0.85 : 0.45}
        />
      ))}
    </Box>
  );
}

export interface LoginPageProps {
  idleTimeoutMinutes: number;
}

export function LoginPage({ idleTimeoutMinutes }: LoginPageProps) {
  const theme = useTheme();
  const [searchParams] = useSearchParams();
  const arrivalParam = searchParams.get('arrival');
  const arrival: ArrivalReason = isArrivalReason(arrivalParam) ? arrivalParam : null;

  const idleFooter = useMemo(
    () => t('loginFooterIdle', { count: idleTimeoutMinutes }),
    [idleTimeoutMinutes],
  );

  return (
    <Box
      component="main"
      // Matches every other screen's landmark id, which `index.html`'s
      // static skip link targets (task 110) — this page had no id at
      // all, so the skip link's target never existed here.
      id="main-content"
      sx={{
        position: 'relative',
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: '24px',
        backgroundColor: tokens.console.navy,
        overflow: 'hidden',
      }}
    >
      <LoginMesh />
      <Paper
        elevation={0}
        sx={{
          position: 'relative',
          zIndex: 2,
          width: '100%',
          maxWidth: '404px',
          backgroundColor: theme.palette.background.paper,
          border: `1px solid ${theme.palette.divider}`,
          borderRadius: '14px',
          boxShadow: theme.shadows[24],
          padding: '38px 36px 30px',
        }}
      >
        <Box>
          <Typography variant="overline" color="text.secondary">
            {t('loginBrandTagline')}
          </Typography>
          <Typography variant="h5" component="h1" sx={{ mt: '14px' }}>
            {t('loginHeading')}
          </Typography>
          <Typography
            variant="body2"
            color="text.secondary"
            sx={{ fontSize: '13.5px', margin: '2px 0 24px' }}
          >
            {t('loginSubtitle')}
          </Typography>
        </Box>

        {arrival !== null && (
          <Box sx={{ mb: '18px' }}>
            <LoginArrivalAlert arrival={arrival} idleTimeoutMinutes={idleTimeoutMinutes} />
          </Box>
        )}

        <LoginForm />

        <Typography
          variant="caption"
          color="text.secondary"
          sx={{ display: 'block', mt: '22px', lineHeight: 1.6 }}
        >
          {t('loginFooterAccounts')} {idleFooter}
        </Typography>
      </Paper>
    </Box>
  );
}
