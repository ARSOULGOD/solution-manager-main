/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { TextArea } from './TextArea';

function TextAreaGalleryDemo() {
  const [value, setValue] = useState('First line of notes.');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 320 }}>
      <TextArea
        label="Notes"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        helperText="Optional context for the reviewer."
      />
      <TextArea label="Notes" value="" onChange={() => {}} error="Notes are required." />
      <TextArea label="Disabled" value="Not editable" onChange={() => {}} disabled />
    </div>
  );
}

export const textAreaGallerySection = {
  name: 'TextArea',
  element: <TextAreaGalleryDemo />,
};
