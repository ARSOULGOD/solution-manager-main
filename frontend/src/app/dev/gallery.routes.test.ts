import { describe, expect, it } from 'vitest';

import { buildGalleryRoutes } from './gallery.routes';

describe('buildGalleryRoutes', () => {
  it('registers /dev/gallery under import.meta.env.DEV, which Vitest runs with true', () => {
    const routes = buildGalleryRoutes();

    expect(routes).toHaveLength(1);
    expect(routes[0]?.path).toBe('/dev/gallery');
  });

  // The false branch (production) is proven by build inspection, not by a
  // unit test: a runtime `isDev` parameter would let this function be
  // called with `false`, but that indirection is exactly what defeats the
  // dead-code elimination this route depends on for FE-D24 (see the
  // comment in gallery.routes.ts). `npm run build` producing no
  // Gallery-*.js chunk and no "/dev/gallery" string anywhere under dist/
  // is the real acceptance evidence — recorded in this task's report.
});
