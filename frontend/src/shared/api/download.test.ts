import { afterEach, describe, expect, it, vi } from 'vitest';

import { startStreamingDownload } from './download';

afterEach(() => {
  document.querySelectorAll('iframe').forEach((el) => {
    el.remove();
  });
  vi.restoreAllMocks();
});

describe('startStreamingDownload', () => {
  it('navigates a hidden iframe to the URL — never calls fetch, never buffers a body', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');

    startStreamingDownload('/api/console/v1/export/range?metric=x');

    const iframe = document.querySelector('iframe');
    expect(iframe).not.toBeNull();
    expect(iframe?.src).toContain('/api/console/v1/export/range?metric=x');
    expect(iframe?.style.display).toBe('none');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('rejects an absolute URL — the SPA never navigates to a plane host directly (FE-D19)', () => {
    expect(() => startStreamingDownload('https://evil.example.test/export')).toThrow(/absolute URL/);
  });

  it('rejects a protocol-relative URL the same way', () => {
    expect(() => startStreamingDownload('//evil.example.test/export')).toThrow(/absolute URL/);
  });

  it('removes the iframe once it loads', () => {
    startStreamingDownload('/api/console/v1/export/range?metric=x');
    const iframe = document.querySelector('iframe');
    expect(iframe).not.toBeNull();

    iframe?.dispatchEvent(new Event('load'));

    expect(document.querySelector('iframe')).toBeNull();
  });

  it('does not throw when called twice in a row (two independent downloads)', () => {
    expect(() => {
      startStreamingDownload('/api/console/v1/export/range?metric=a');
      startStreamingDownload('/api/console/v1/export/range?metric=b');
    }).not.toThrow();
    expect(document.querySelectorAll('iframe')).toHaveLength(2);
  });
});
