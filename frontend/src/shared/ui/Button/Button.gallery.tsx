/**
 * Dev-only gallery demo (task 010's registry) — every variant, size and
 * the disabled state, reviewable in both themes from `/dev/gallery`.
 */
import { Button, IconButton } from './Button';

const variants = ['contained', 'outlined', 'text', 'danger', 'dangerContained'] as const;
const sizes = ['sm', 'md', 'lg'] as const;

function ButtonGalleryDemo() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {sizes.map((size) => (
        <div key={size} style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
          <span style={{ width: 32 }}>{size}</span>
          {variants.map((variant) => (
            <Button key={variant} variant={variant} size={size}>
              {variant}
            </Button>
          ))}
        </div>
      ))}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
        <span style={{ width: 32 }}>disabled</span>
        <Button disabled>contained</Button>
        <Button variant="danger" disabled>
          danger
        </Button>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
        <span style={{ width: 32 }}>icon</span>
        <IconButton aria-label="Close" size="md">
          <span aria-hidden>×</span>
        </IconButton>
        <IconButton aria-label="Close" size="sm">
          <span aria-hidden>×</span>
        </IconButton>
      </div>
    </div>
  );
}

export const buttonGallerySection = {
  name: 'Button',
  element: <ButtonGalleryDemo />,
};
