/** `UI-D29` client-side drift detection by connection revision. */
export interface DriftEntry {
  readonly connectionId: string;
  readonly name: string;
  readonly appliedRevision: number;
  readonly currentRevision: number;
  readonly affectedFields: readonly string[];
}

export function detectDrift(
  appliedRevisions: Readonly<Record<string, number>>,
  current: readonly { id: string; name: string; revision: number }[],
  fieldsByConnection: Readonly<Record<string, readonly string[]>>,
): DriftEntry[] {
  const entries: DriftEntry[] = [];
  for (const [connectionId, appliedRevision] of Object.entries(appliedRevisions)) {
    const affectedFields = fieldsByConnection[connectionId];
    if (affectedFields === undefined || affectedFields.length === 0) continue;

    const row = current.find((connection) => connection.id === connectionId);
    // A vanished connection is CONNECTION_UNRESOLVED, not drift. "Review and save"
    // cannot repair it, so do not offer that misleading remediation here.
    if (row === undefined || row.revision === appliedRevision) continue;

    entries.push({
      connectionId,
      name: row.name,
      appliedRevision,
      currentRevision: row.revision,
      affectedFields,
    });
  }
  return entries;
}
