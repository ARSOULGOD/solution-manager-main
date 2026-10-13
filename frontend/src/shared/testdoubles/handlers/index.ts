/**
 * Every fixture handler in one array (task 033). `server.ts` registers
 * this as the default handler set so `onUnhandledRequest: "error"` (task
 * 008) only fires for a call this task genuinely didn't anticipate.
 */
import { consoleHandlers } from './console';
import { cpHandlers } from './cp';
import { dpHandlers } from './dp';

export const handlers = [...cpHandlers, ...dpHandlers, ...consoleHandlers];
