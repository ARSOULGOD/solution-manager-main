/**
 * `controlKind -> component` lookup (task 047; `UI-D14`, `UI-D22`,
 * `UI-D23`, `FE-D23`). The same ten entries render every solution's
 * Designer form — adding a *control* here is an SM release; adding a
 * *field* to a package is data, never a code change (`UI-D14`).
 *
 * Five of the ten kinds already have exactly the control they need from
 * earlier tasks: `text`/`number`/`toggle`/`select` are the general-purpose
 * form controls from task 012, and `secret` is `SecretField` itself
 * (task 023, `FE-D23`) — so this registry reuses them directly rather
 * than wrapping each a second time for no behavioural difference. The
 * other five (`duration`/`list`/`map`/`table`/`yaml`) have no earlier
 * equivalent and are built under this directory.
 *
 * Exhaustive over `ControlKind` at the **type** level: `ComponentType`
 * <never> (never `<any>` — forbidden everywhere by `G2`) is the correct
 * entry type for a lookup table that's indexed dynamically by
 * `controlKind` at runtime and never called with one fixed prop shape at
 * the call site — `never` is a valid parameter type for every component's
 * props by contravariance, so assigning the concrete object literal below
 * to `ControlRegistry` is what makes an omitted or misspelled
 * `ControlKind` member fail `tsc`, not merely lint (task 047 acceptance:
 * "missing kind fails typecheck").
 */
import type { ComponentType } from 'react';

import type { ControlKind } from '@contracts/view-model/types';
import { NumberField, SecretField, Select, Switch, TextField } from '@shared/ui';

import { DurationControl } from './DurationControl';
import { ListControl } from './ListControl';
import { MapControl } from './MapControl';
import { TableControl } from './TableControl';
import { YamlControl } from './YamlControl';

export type ControlRegistry = { [K in ControlKind]: ComponentType<never> };

export const controlRegistry: ControlRegistry = {
  text: TextField,
  number: NumberField,
  toggle: Switch,
  select: Select,
  secret: SecretField,
  duration: DurationControl,
  list: ListControl,
  map: MapControl,
  table: TableControl,
  yaml: YamlControl,
};
