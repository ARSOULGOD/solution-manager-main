/** Dev-only gallery demo (task 010's registry). */
import { Paper } from './Paper';

function PaperGalleryDemo() {
  return (
    <div style={{ display: 'grid', gap: 12 }}>
      <Paper surface="flat">Flat — no extra padding.</Paper>
      <Paper surface="padded">Padded surface.</Paper>
      <Paper surface="hoverable">Hoverable — raises on hover.</Paper>
      <Paper dashed>Dashed border — a placeholder card (task 129).</Paper>
      {/* `fill` only shows against a container that HAS a height, which is
          the whole point of it: a dashboard grid cell. */}
      <div style={{ height: 120 }}>
        <Paper fill>Fill — stretches to its container and lays children out as a column.</Paper>
      </div>
    </div>
  );
}

export const paperGallerySection = {
  name: 'Paper',
  element: <PaperGalleryDemo />,
};
