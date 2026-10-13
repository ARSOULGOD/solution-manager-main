/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { DataTable } from './DataTable';

function DataTableGalleryDemo() {
  const [selection, setSelection] = useState<string[]>([]);
  return (
    <DataTable
      rows={[
        { id: 'a', name: 'cache-prod' },
        { id: 'b', name: 'cache-dev' },
      ]}
      columns={[
        { field: 'name', headerName: 'Instance', info: 'driver and locus are data on the row' },
      ]}
      getRowId={(row) => row.id}
      selection={selection}
      onSelectionChange={setSelection}
      detailPanel={(row) => <div>Replicas for {row.name}</div>}
    />
  );
}

export const dataTableGallerySection = {
  name: 'DataTable',
  element: <DataTableGalleryDemo />,
};
