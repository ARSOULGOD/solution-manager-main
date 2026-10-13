// VIOLATION — contracts/ imports neither React nor anything under src/.
import { createElement } from 'react';
import { HomeRoute } from '@app/routes/HomeRoute';

export const leaked = { createElement, HomeRoute };
