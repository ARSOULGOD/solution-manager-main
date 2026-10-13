/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { TextField } from './TextField';

function TextFieldGalleryDemo() {
  const [value, setValue] = useState('acme-01');
  const [password, setPassword] = useState('s3cr3t');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 320 }}>
      <TextField
        label="Instance name"
        value={value}
        onChange={(event) => setValue(event.target.value)}
        helperText="Lowercase, hyphen-separated."
      />
      <TextField label="Required field" value="" onChange={() => {}} required />
      <TextField
        label="Instance name"
        value="acme"
        onChange={() => {}}
        error="Name is already in use."
      />
      <TextField
        label="Password"
        value={password}
        onChange={(event) => setPassword(event.target.value)}
        reveal
      />
      <TextField label="Disabled" value="Not editable" onChange={() => {}} disabled />
    </div>
  );
}

export const textFieldGallerySection = {
  name: 'TextField',
  element: <TextFieldGalleryDemo />,
};
