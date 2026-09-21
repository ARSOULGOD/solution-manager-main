// VIOLATION — shared may import nothing from features (design §7).
import { inventoryPublic } from '../feature/inventory/index.ts';

export const leaked = inventoryPublic;
