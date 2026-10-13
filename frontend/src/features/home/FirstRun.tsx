/**
 * First-run state (task 038, `UI-D19`, UI §A.4). Shown when the summary
 * reports no Solution Package loaded yet (`solutionsManaged === 0`).
 * Pure/presentational — `HomePage` reads `identity.superAdmin` and passes
 * it down, so this component needs no auth context of its own.
 *
 * The reference's second "Learn about packages" button has no real
 * destination anywhere in this plan, so it is not ported — an inert
 * button that goes nowhere is worse than one fewer button.
 */
import { Box, Typography, useTheme } from '@mui/material';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';

import { t } from '@shared/i18n';
import { Alert, Button, PageHeader, Paper } from '@shared/ui';
import { Grid, PageWidth, Stack } from '@shared/ui/layout';

import { GridIcon, LockIcon, MapPinIcon, PackageIcon, ServerIcon, UploadIcon } from './icons';

const SOLUTIONS_HREF = '/solutions';

function FirstRunTile({ icon, label }: { icon: ReactNode; label: string }) {
  const theme = useTheme();
  return (
    <Box sx={{ p: 2, border: `1px solid ${theme.palette.divider}`, borderRadius: 1.5 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Box aria-hidden sx={{ color: 'text.secondary', display: 'flex' }}>
          {icon}
        </Box>
        <Typography sx={{ fontSize: 22, fontWeight: 600, color: 'text.disabled' }}>—</Typography>
      </Box>
      <Typography variant="body2" sx={{ fontWeight: 600, mt: 1 }}>
        {label}
      </Typography>
    </Box>
  );
}

export interface FirstRunProps {
  superAdmin: boolean;
}

export function FirstRun({ superAdmin }: FirstRunProps) {
  const navigate = useNavigate();

  return (
    <PageWidth>
      <Stack gap={3}>
        <PageHeader title={t('homeTitle')} />
        <Paper surface="padded" data-first-run>
          <Box sx={{ maxWidth: 520, mx: 'auto', textAlign: 'center' }}>
            <Box
              aria-hidden
              sx={{
                width: 60,
                height: 60,
                mx: 'auto',
                mb: 2.25,
                borderRadius: 2,
                display: 'grid',
                placeItems: 'center',
                backgroundColor: 'primary.main',
                color: 'primary.contrastText',
              }}
            >
              <PackageIcon sx={{ fontSize: 28 }} />
            </Box>
            <Typography variant="h5" component="h2">
              {t('homeFirstRunTitle')}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.25 }}>
              {t('homeFirstRunBody')}
            </Typography>
            <Box sx={{ my: 3 }}>
              <Grid columns={{ xs: 1, sm: 3 }}>
                <FirstRunTile icon={<GridIcon sx={{ fontSize: 18 }} />} label={t('homeFirstRunTileSolutions')} />
                <FirstRunTile icon={<ServerIcon sx={{ fontSize: 18 }} />} label={t('homeFirstRunTileInstances')} />
                <FirstRunTile icon={<MapPinIcon sx={{ fontSize: 18 }} />} label={t('homeFirstRunTileTargets')} />
              </Grid>
            </Box>
            {superAdmin ? (
              <Button icon={<UploadIcon />} onClick={() => void navigate(SOLUTIONS_HREF)}>
                {t('homeFirstRunAction')}
              </Button>
            ) : (
              <Alert severity="neutral" icon={<LockIcon />}>
                {t('homeFirstRunAdminOnly')}
              </Alert>
            )}
          </Box>
        </Paper>
      </Stack>
    </PageWidth>
  );
}
