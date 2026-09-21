/**
 * Catalogue lookup. Selects an ICU-style plural form via `Intl.PluralRules`
 * and fills named `{token}` placeholders — the mechanism that keeps a
 * sentence one catalogue entry instead of one concatenated from fragments
 * (design §18: "no concatenated sentences"). A plural key's type requires a
 * numeric `count` param at the call site.
 */
import { messagesEn, type MessageKey, type Messages } from './messages.en';

type Params = Record<string, string | number>;

type ParamsFor<K extends MessageKey> = Messages[K] extends string
  ? [params?: Params]
  : [params: { count: number } & Params];

const pluralRules = new Intl.PluralRules('en-US');

export function t<K extends MessageKey>(key: K, ...args: ParamsFor<K>): string {
  const entry = messagesEn[key];
  const params: Params = args[0] ?? {};

  if (typeof entry === 'string') {
    return interpolate(entry, params);
  }

  const count = params['count'];
  if (typeof count !== 'number') {
    throw new Error(`t("${key}"): plural message requires a numeric "count" param`);
  }
  const category = pluralRules.select(count) === 'one' ? 'one' : 'other';
  return interpolate(entry[category], params);
}

function interpolate(template: string, params: Params): string {
  return template.replace(/\{(\w+)\}/g, (match, name: string) => {
    const value = params[name];
    return value === undefined ? match : String(value);
  });
}
