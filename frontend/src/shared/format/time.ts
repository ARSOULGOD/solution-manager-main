/**
 * UTC is canonical on the wire (`B/D15`); this module is the only place
 * display localisation happens. There is no display-timezone toggle in v1
 * — the browser's resolved zone is the default — but the active zone must
 * always be labelled on a time axis: an unlabelled axis is how an operator
 * misreads an incident window (`dynamic-metrics` design §18).
 */

export const displayTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

export function formatDateTime(isoUtc: string, options: Intl.DateTimeFormatOptions = {}): string {
  const {
    timeZone = displayTimeZone,
    dateStyle = 'medium',
    timeStyle = 'medium',
    ...rest
  } = options;
  return new Intl.DateTimeFormat('en-US', { dateStyle, timeStyle, timeZone, ...rest }).format(
    new Date(isoUtc),
  );
}

export function formatTime(isoUtc: string, options: Intl.DateTimeFormatOptions = {}): string {
  const { timeZone = displayTimeZone, timeStyle = 'medium', ...rest } = options;
  return new Intl.DateTimeFormat('en-US', { timeStyle, timeZone, ...rest }).format(
    new Date(isoUtc),
  );
}

export function timeZoneLabel(
  timeZone: string = displayTimeZone,
  referenceDate: Date = new Date(),
): string {
  const parts = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'short' }).formatToParts(
    referenceDate,
  );
  const zonePart = parts.find((part) => part.type === 'timeZoneName');
  return zonePart?.value ?? timeZone;
}

/** The label a time axis must always carry alongside its ticks (`dynamic-metrics` §18). */
export function formatAxisTimestamp(isoUtc: string, timeZone: string = displayTimeZone): string {
  return `${formatTime(isoUtc, { timeZone })} ${timeZoneLabel(timeZone, new Date(isoUtc))}`;
}
