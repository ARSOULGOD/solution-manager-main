/**
 * Route-derived breadcrumbs (FE-024, task 106, UI §A.2). Current page is
 * not a link; long names truncate with a title. `Targets`/`Admin` are not
 * in this trail until those routes ship.
 *
 * Below `md` (task 106, `FE-D29`) the trail collapses to *"… / current"*
 * — the current crumb stays, since it is the one crumb that is not a
 * link and not recoverable from elsewhere, while every ancestor moves
 * into a `Menu` behind the "…" trigger rather than disappearing.
 */
import {
  Breadcrumbs as MuiBreadcrumbs,
  Link as MuiLink,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { Link, useLocation, useNavigate } from 'react-router';

import { t } from '@shared/i18n';

import { IconButton } from '../Button';
import { Menu, type MenuEntry } from '../Menu';

import type { BreadcrumbCrumb, BreadcrumbsProps } from './Breadcrumbs.types';

function crumbsFromPath(
  pathname: string,
  labels: { solution?: string; instance?: string },
): BreadcrumbCrumb[] {
  const parts = pathname.split('/').filter(Boolean);
  const crumbs: BreadcrumbCrumb[] = [{ label: t('navHome'), to: '/' }];

  if (parts.length === 0) {
    return [{ label: t('navHome') }];
  }

  if (parts[0] === 'solutions') {
    crumbs.push({ label: t('navSolutions'), to: '/solutions' });
  }

  const solutionId = parts[1];
  if (parts[0] === 'solutions' && solutionId !== undefined && parts[2] === 'instances') {
    crumbs.push({ label: labels.solution ?? solutionId, to: `/solutions/${solutionId}/instances` });
    crumbs.push({ label: t('breadcrumbInstances'), to: `/solutions/${solutionId}/instances` });
  }

  const instanceId = parts[3];
  const category = parts[4];
  if (instanceId !== undefined) {
    const instanceLabel = labels.instance ?? instanceId;
    if (category !== undefined) {
      crumbs.push({
        label: instanceLabel,
        to: `/solutions/${solutionId ?? ''}/instances/${instanceId}/${category}`,
      });
      const categoryLabel =
        category === 'designer'
          ? t('categoryDesigner')
          : category === 'governance'
            ? t('categoryGovernance')
            : category === 'monitoring'
              ? t('categoryMonitoring')
              : category;
      crumbs.push({ label: categoryLabel });
    } else {
      crumbs.push({ label: instanceLabel });
    }
  }

  return finalize(crumbs);
}

function finalize(crumbs: BreadcrumbCrumb[]): BreadcrumbCrumb[] {
  if (crumbs.length === 0) {
    return crumbs;
  }
  const last = crumbs[crumbs.length - 1];
  if (last === undefined) {
    return crumbs;
  }
  return [...crumbs.slice(0, -1), { label: last.label }];
}

export function Breadcrumbs({ items, labels = {} }: BreadcrumbsProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useTheme();
  const collapsed = useMediaQuery(theme.breakpoints.down('md'));
  const crumbs = items ?? crumbsFromPath(location.pathname, labels);
  const current = crumbs[crumbs.length - 1];
  const ancestors = crumbs.slice(0, -1);

  if (collapsed && current !== undefined) {
    const ancestorEntries: MenuEntry[] = ancestors
      .filter((crumb): crumb is BreadcrumbCrumb & { to: string } => crumb.to !== undefined)
      .map((crumb) => ({
        label: crumb.label,
        onClick: () => void navigate(crumb.to),
      }));

    return (
      <nav aria-label={t('breadcrumb')}>
        <MuiBreadcrumbs>
          {ancestorEntries.length > 0 && (
            <Menu
              trigger={
                <IconButton aria-label={t('breadcrumbMore')} size="sm">
                  …
                </IconButton>
              }
              entries={ancestorEntries}
            />
          )}
          <Typography
            color="text.primary"
            title={current.label}
            sx={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
          >
            {current.label}
          </Typography>
        </MuiBreadcrumbs>
      </nav>
    );
  }

  return (
    <nav aria-label={t('breadcrumb')}>
      <MuiBreadcrumbs>
        {crumbs.map((crumb, index) => {
          const isLast = index === crumbs.length - 1;
          if (isLast || crumb.to === undefined) {
            return (
              <Typography
                key={`${crumb.label}-${String(index)}`}
                color="text.primary"
                title={crumb.label}
                sx={{
                  maxWidth: 180,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {crumb.label}
              </Typography>
            );
          }
          return (
            <MuiLink
              key={crumb.to}
              component={Link}
              to={crumb.to}
              underline="hover"
              title={crumb.label}
              sx={{
                maxWidth: 180,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {crumb.label}
            </MuiLink>
          );
        })}
      </MuiBreadcrumbs>
    </nav>
  );
}
