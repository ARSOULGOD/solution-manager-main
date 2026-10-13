import { describe, expect, it } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';

import indexHtml from '../../index.html?raw';

import { assertReflow } from './reflow';

describe('assertReflow', () => {
  it('fails on a deliberately overflowing fixture at the reflow floor', () => {
    const root = document.createElement('div');
    const wide = document.createElement('div');
    wide.style.minWidth = '800px';
    wide.textContent = 'too wide';
    root.append(wide);
    document.body.append(root);

    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).toThrow(/min-width/i);

    root.remove();
  });

  it('passes a wrapping cluster that fits the floor', () => {
    const root = document.createElement('div');
    root.style.display = 'flex';
    root.style.flexWrap = 'wrap';
    const item = document.createElement('button');
    item.type = 'button';
    item.textContent = 'Ok';
    root.append(item);
    document.body.append(root);

    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();

    root.remove();
  });
});

describe('viewport meta (SC 1.4.4)', () => {
  it('does not suppress zoom', () => {
    const html = indexHtml;
    const viewport = /<meta\s+name="viewport"\s+content="([^"]*)"/i.exec(html);
    expect(viewport?.[1]).toBe('width=device-width, initial-scale=1');
    expect(html).not.toMatch(/user-scalable\s*=\s*no/i);
    expect(html).not.toMatch(/maximum-scale/i);
  });
});
