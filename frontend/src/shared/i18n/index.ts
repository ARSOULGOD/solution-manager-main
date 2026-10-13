/**
 * Message catalogue — single point of import for user-visible strings.
 * Do NOT read messages.en.ts directly; go through `t()`.
 */

export { t } from './t';
export {
  messagesEn,
  type MessageKey,
  type MessageValue,
  type Messages,
  type PluralMessage,
} from './messages.en';
