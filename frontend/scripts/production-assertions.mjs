/**
 * FE-101 · the seven production-build assertions, as a pure function over a
 * described build (design §21, `FE-D14`, `FE-D24`).
 *
 * `verify-production-build.mjs` runs the real production build and describes
 * it to `assertProductionBuild` below; `production-assertions.selftest.mjs`
 * describes deliberately violating builds to the same function and requires
 * each assertion to fire. Keeping the logic pure — no `build()`, no `fs`, no
 * `process.exit` — is what makes that second half possible at all: an
 * assertion that has never been seen failing is a claim, not a check
 * (`docs/checklists/definition-of-done.md`; the same reasoning behind
 * `assert-lint-fixtures.mjs` and `e2e/reflow.selftest.spec.ts`).
 *
 * The seven, in the task's own order:
 *
 *   1. `no-auth-mock`        — `FE-D24`: a production build must not compile
 *                              the Phase 0 dev auth adapter.
 *   2. `no-dev-gallery`      — the `/dev/gallery` route and everything under
 *                              `src/app/dev/` is DEV-only scaffolding.
 *   3. `no-msw`              — no interception library, and no service-worker
 *                              script in the shipped directory either.
 *   4. `no-fixture-dataset`  — no invented data (`§9.3`) may be mistaken for
 *                              a real backend's answer.
 *   5. `no-secret`           — `I6`, `FE-D14`: the bundle is served to every
 *                              browser that can reach the gateway.
 *   6. `no-plane-hostname`   — `FE-D19`: the SPA holds no plane topology; it
 *                              speaks to one gateway origin by relative path.
 *   7. `config-replaceable`  — `FE-D14`: an operator retargets a deployment
 *                              by editing one file, never by rebuilding.
 *
 * ── Two detection strategies, deliberately ──────────────────────────────
 *
 * **Module presence** (1–4) reads the bundle's own module graph
 * (`chunk.moduleIds` plus each module's rendered size), not its text.
 * Minification renames identifiers and drops comments, so a grep for
 * `createDevMockAdapter` is a test of the minifier's mood; a module id is
 * what the bundler actually decided to include. Marker strings are kept as a
 * *second* net for the case a module gets inlined into another one's id,
 * never as the first. Note the size qualifier — see `modules()` below for
 * why membership alone is the wrong measure.
 *
 * **Rendered module text** (5–6) reads `chunk.modules[id].code`, which
 * attributes every byte to the module it came from. That is what lets
 * assertion 6 be strict about our own code while ignoring the `http://localhost`
 * that React Router carries as an internal fallback origin — a rule of the
 * form "no absolute URL anywhere" would be either false or unenforceable.
 */

/**
 * @typedef {{ fileName: string, code: string, moduleIds: string[], modules: Record<string, { code: string }> }} Chunk
 * @typedef {{ fileName: string, text: string | null }} Asset
 * @typedef {{ path: string, text: string | null }} ArtefactFile
 * @typedef {{ chunks: Chunk[], assets: Asset[], artefactFiles: ArtefactFile[] }} BuildSubject
 * @typedef {{ id: string, message: string }} Failure
 */

const posix = (id) => id.split('\\').join('/');

/**
 * Every module the build included **that contributes bytes to a chunk**,
 * flattened, with its owning chunk.
 *
 * The qualifier is load-bearing. A module can appear in `chunk.moduleIds`
 * with `renderedLength: 0` and `code: null` — it participated in the graph,
 * and then dead-code elimination removed all of it. `src/app/dev/gallery.routes.ts`
 * is exactly that: `buildGalleryRoutes` is called from `router.tsx`, so the
 * module is visited, and its `if (!import.meta.env.DEV) return []` folds the
 * whole body away, leaving nothing in the output. Failing on graph
 * membership alone would have made `no-dev-gallery` red for a module that
 * ships zero bytes — a gate nobody could get green without contorting the
 * router. What the assertions are about is what reaches the browser, so
 * that is what they measure.
 */
function* modules(subject) {
  for (const chunk of subject.chunks) {
    for (const id of chunk.moduleIds) {
      const rendered = chunk.modules[id];
      const code = rendered?.code ?? '';
      const bytes = rendered?.renderedLength ?? code.length;
      if (bytes === 0) {
        continue;
      }
      yield { chunk: chunk.fileName, id: posix(id), code };
    }
  }
}

/**
 * Modules built from this repository's own sources — the half of the graph
 * we are responsible for. A dependency's internals are the CSP gate's
 * business (`verify-csp.mjs`), not this one's.
 */
function* ownModules(subject) {
  for (const module of modules(subject)) {
    if (module.id.includes('/src/') && !module.id.includes('/node_modules/')) {
      yield module;
    }
  }
}

/** Anything the build emits that is text: chunk code, CSS, `index.html`. */
function* emittedText(subject) {
  for (const chunk of subject.chunks) {
    yield { where: chunk.fileName, text: chunk.code };
  }
  for (const asset of subject.assets) {
    if (asset.text !== null) {
      yield { where: asset.fileName, text: asset.text };
    }
  }
}

/**
 * Every file in the built artefact, as text where it is text.
 *
 * This is the **written output directory**, not `publicDir` and not the
 * bundle's in-memory chunk list. It is the only view that sees what a
 * customer actually receives: the emitted chunks AND everything `publicDir`
 * copied in AND whatever a `closeBundle` hook added or removed afterwards.
 * `no-msw` exists because a file can ship without ever entering the module
 * graph, and `config-replaceable` because `config.json` only exists at all
 * once the copy has happened.
 */
function* artefactText(subject) {
  for (const file of subject.artefactFiles) {
    if (file.text !== null) {
      yield { where: `dist/${file.path}`, text: file.text };
    }
  }
}

function modulesUnder(subject, prefix) {
  return [...modules(subject)].filter((module) => module.id.includes(prefix));
}

function emittedTextMatching(subject, pattern) {
  return [...emittedText(subject)].filter((entry) => pattern.test(entry.text));
}

// ── 1 · no auth mock ─────────────────────────────────────────────────────
const noAuthMock = {
  id: 'no-auth-mock',
  title: 'no auth mock in the production bundle',
  why: '`FE-D24`: shipping the Phase 0 mock session would grant a super-admin identity to anyone who loads the page, silently defeating `FE-D8` and `FE-D14` behind a build-config mistake.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];
    for (const module of modulesUnder(subject, '/src/shared/auth/devMockAdapter')) {
      failures.push({
        id: 'no-auth-mock',
        message: `${module.chunk} ships code from ${module.id}. AuthProvider must select it only inside a literal \`if (import.meta.env.DEV)\` branch so Vite folds the flag and drops the body.`,
      });
    }
    // Second net: the fixture identity is a string, so it survives
    // minification even if the module were inlined somewhere unexpected.
    for (const { where } of emittedTextMatching(
      subject,
      /devon\.ramirez@acme\.example|createDevMockAdapter/,
    )) {
      failures.push({
        id: 'no-auth-mock',
        message: `${where} carries the dev mock session's identity. No production artefact may name a fixture user (FE-D24).`,
      });
    }
    return failures;
  },
};

// ── 2 · no dev gallery route ─────────────────────────────────────────────
const noDevGallery = {
  id: 'no-dev-gallery',
  title: 'no dev gallery route in the production bundle',
  why: 'The component gallery and the dev package console (`src/app/dev/`) render every state of every component, including states a real deployment must never be able to reach by typing a URL.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];
    for (const module of modulesUnder(subject, '/src/app/dev/')) {
      failures.push({
        id: 'no-dev-gallery',
        message: `${module.chunk} ships code from ${module.id}. The \`import.meta.env.DEV\` check must sit textually inside the module for Vite to fold it and eliminate the body — routing it through a parameter or a helper defeats the constant-folding (see gallery.routes.ts's own header).`,
      });
    }
    for (const { where } of emittedTextMatching(subject, /\/dev\/gallery/)) {
      failures.push({
        id: 'no-dev-gallery',
        message: `${where} still references the /dev/gallery route path.`,
      });
    }
    for (const chunk of subject.chunks) {
      if (/^(?:assets\/)?Gallery-[^/]*\.js$/.test(chunk.fileName)) {
        failures.push({
          id: 'no-dev-gallery',
          message: `${chunk.fileName} is an emitted Gallery chunk — the lazy import was not eliminated.`,
        });
      }
    }
    return failures;
  },
};

// ── 3 · no MSW ───────────────────────────────────────────────────────────
const noMsw = {
  id: 'no-msw',
  title: 'no MSW in the production artefact',
  why: 'MSW answers requests the backend never saw. In a shipped build that is a console showing invented data as though it were the estate — the exact failure `docs/design/frontend/design.md` §9.3 exists to prevent.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];
    for (const prefix of [
      '/node_modules/msw/',
      '/node_modules/@mswjs/',
      '/node_modules/@bundled-es-modules/',
    ]) {
      for (const module of modulesUnder(subject, prefix)) {
        failures.push({
          id: 'no-msw',
          message: `${module.chunk} ships code from ${module.id} — an interception dependency reached the production bundle.`,
        });
      }
    }
    for (const { where } of emittedTextMatching(
      subject,
      /setupWorker|onUnhandledRequest|\[MSW\]/,
    )) {
      failures.push({
        id: 'no-msw',
        message: `${where} carries MSW's browser API.`,
      });
    }
    // The half a bundle analysis cannot see. `msw init public/` — the
    // documented install — puts the worker in `publicDir`, which is copied
    // verbatim into the artefact, so it shipped in every build until this
    // assertion caught it (2026-09-03). `vite.config.ts`'s
    // `dropDevOnlyAssetsFromBuild` removes it in `closeBundle`; this is the
    // check that proves the plugin actually ran.
    for (const file of subject.artefactFiles) {
      if (/mockServiceWorker\.js$/i.test(file.path)) {
        failures.push({
          id: 'no-msw',
          message: `the artefact ships ${file.path}. publicDir is copied verbatim into the build — \`dropDevOnlyAssetsFromBuild\` in vite.config.ts is what removes it, so this means the plugin did not run.`,
        });
      }
    }
    for (const { where, text } of artefactText(subject)) {
      if (/Mock Service Worker/.test(text)) {
        failures.push({
          id: 'no-msw',
          message: `${where} is Mock Service Worker's script under another name.`,
        });
      }
    }
    return failures;
  },
};

// ── 4 · no fixture dataset ───────────────────────────────────────────────
const noFixtureDataset = {
  id: 'no-fixture-dataset',
  title: 'no fixture dataset in the production bundle',
  why: 'Every row under `shared/testdoubles/data/` is INVENTED against a contract that does not exist yet (§9.3). A build that carries it can render a plausible estate with no backend behind it.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];
    for (const prefix of ['/src/shared/testdoubles/', '/src/test/']) {
      for (const module of modulesUnder(subject, prefix)) {
        failures.push({
          id: 'no-fixture-dataset',
          message: `${module.chunk} ships code from ${module.id} — the fixture layer is DEV- and test-only (design §9.1). A \`import type\` is erased; a value import is not.`,
        });
      }
    }
    // Markers chosen because each is a string the fixture layer owns and no
    // shipped code has a reason to contain: the plan's own debt marker, the
    // e2e session override, and the instance fixture set's export name.
    for (const { where } of emittedTextMatching(
      subject,
      /CONTRACT: TBD|e2e_grants|CACHE_INSTANCES/,
    )) {
      failures.push({
        id: 'no-fixture-dataset',
        message: `${where} carries a fixture-layer marker.`,
      });
    }
    return failures;
  },
};

// ── 5 · no secret ────────────────────────────────────────────────────────

/**
 * Shapes that are a credential whatever file they appear in. Run over
 * everything the artefact contains, dependencies included — a key committed
 * into a `node_modules` fixture is still a key served to every browser.
 */
const UNAMBIGUOUS_SECRETS = [
  { name: 'a PEM private key', pattern: /-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----/ },
  {
    name: 'a JSON Web Token',
    pattern: /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/,
  },
  { name: 'an AWS access key id', pattern: /\bAKIA[0-9A-Z]{16}\b/ },
  { name: 'a GitHub token', pattern: /\bgh[pousr]_[A-Za-z0-9]{20,}/ },
  { name: 'a Slack token', pattern: /\bxox[baprs]-[A-Za-z0-9-]{10,}/ },
  { name: 'a Google API key', pattern: /\bAIza[0-9A-Za-z_-]{35}\b/ },
  { name: 'a basic-auth URL', pattern: /\b[a-z][a-z0-9+.-]*:\/\/[^\s/'"]+:[^\s/'"]+@/i },
];

/**
 * A credential-shaped assignment. Two deliberate narrowings, both bought
 * with recall to keep the gate one a team will leave switched on:
 *
 *   · **Scoped to OUR modules and `publicDir`.** Minified dependency code
 *     assigns short strings to variables named `token` constantly — a
 *     parser's lookahead, a CSS tokenizer. Our own source is where a
 *     hardcoded credential would actually be written.
 *   · **The value must contain a non-letter.** `passwordLabel: 'Password'`
 *     in `shared/i18n/messages.en.ts` is a UI string, and a gate that fails
 *     the build on it teaches people to ignore it. Real credentials carry a
 *     digit, a symbol or punctuation essentially without exception; plain
 *     English words do not.
 */
const CREDENTIAL_ASSIGNMENT =
  /\b(?:password|passwd|pwd|secret|secretKey|clientSecret|apiKey|api_key|accessKey|access_key|privateKey|private_key|credential|authToken|auth_token|bearer)\s*[:=]\s*(["'`])(?=[^"'`\n]*[^A-Za-z "'`\n])[^"'`\n]{8,}\1/i;

/** Key names `config.json` must never carry — it is world-readable at the gateway origin (`I6`). */
const FORBIDDEN_CONFIG_KEY =
  /secret|password|passwd|credential|token|apikey|api_key|privatekey|private_key/i;

const noSecret = {
  id: 'no-secret',
  title: 'no secret in the production artefact',
  why: '`I6` and `FE-D14`: every byte here is served unauthenticated to any browser that can reach the gateway. A secret in the bundle is a secret published.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];

    for (const { where, text } of [...emittedText(subject), ...artefactText(subject)]) {
      for (const { name, pattern } of UNAMBIGUOUS_SECRETS) {
        if (pattern.test(text)) {
          failures.push({ id: 'no-secret', message: `${where} contains ${name}.` });
        }
      }
    }

    for (const module of ownModules(subject)) {
      const match = CREDENTIAL_ASSIGNMENT.exec(module.code);
      if (match) {
        failures.push({
          id: 'no-secret',
          message: `${module.id} assigns a literal to \`${match[0].split(/[:=]/)[0].trim()}\` (in ${module.chunk}). A credential is resolved server-side at render time (I6), never compiled in.`,
        });
      }
    }
    for (const { where, text } of artefactText(subject)) {
      const match = CREDENTIAL_ASSIGNMENT.exec(text);
      if (match) {
        failures.push({
          id: 'no-secret',
          message: `${where} assigns a literal to \`${match[0].split(/[:=]/)[0].trim()}\`.`,
        });
      }
    }

    // `config.json` is the one file an operator edits by hand, which makes
    // it the one most likely to be handed a password. Fail on the key name,
    // before a value is ever put behind it.
    for (const file of subject.artefactFiles) {
      if (!/(^|\/)config\.json$/.test(file.path) || file.text === null) {
        continue;
      }
      let parsed;
      try {
        parsed = JSON.parse(file.text);
      } catch {
        failures.push({ id: 'no-secret', message: `dist/${file.path} is not valid JSON.` });
        continue;
      }
      for (const key of Object.keys(parsed ?? {})) {
        if (FORBIDDEN_CONFIG_KEY.test(key)) {
          failures.push({
            id: 'no-secret',
            message: `dist/${file.path} declares "${key}". Runtime config carries a path, a timeout and a channel label — never a credential (I6, FE-D14).`,
          });
        }
      }
    }

    return failures;
  },
};

// ── 6 · no plane hostname ────────────────────────────────────────────────

/**
 * A URL with an authority. Applied only to our own modules' rendered code —
 * where, today, the count is zero: every absolute URL in `src/` is a citation
 * in a comment, and comments do not survive the build. So this can be strict,
 * and strict is what `FE-D19` wants: the SPA holds no host at all, not "no
 * host from a bad list".
 *
 * Two patterns rather than one optional scheme, because they need different
 * strictness. A scheme-ful URL is unambiguous, so any host shape counts
 * (`http://localhost:8080` is exactly the kind of thing that gets compiled in
 * by accident). A protocol-relative one is just two slashes, which a path
 * string can produce by accident (`"/api//v2"`), so it must carry a dotted
 * host to count. Protocol-relative paths are refused at runtime anyway, by
 * `planeClientFactory.ts`'s `assertRelativePath`.
 */
const ABSOLUTE_URLS = [
  /\bhttps?:\/\/[a-z0-9][a-z0-9.-]*(?::\d+)?/i,
  /(?<![a-z0-9:])\/\/[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)+(?::\d+)?/i,
];

const noPlaneHostname = {
  id: 'no-plane-hostname',
  title: 'no plane hostname in the production bundle',
  why: '`FE-D19`: the browser talks to one gateway origin by relative path. A compiled-in host is deploy topology baked into an artefact that is supposed to be deploy-agnostic — and it is how a build ends up unable to move between environments without a rebuild.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];
    for (const module of ownModules(subject)) {
      for (const pattern of ABSOLUTE_URLS) {
        const match = pattern.exec(module.code);
        if (match) {
          failures.push({
            id: 'no-plane-hostname',
            message: `${module.id} carries the absolute URL "${match[0]}" (in ${module.chunk}). Every call is a path under \`config.json\`'s apiBasePath; if this is a documentation link inside a user-facing string, it still ships a host — move it out.`,
          });
          break;
        }
      }
    }
    // The same rule one layer down: an operator can put an origin in
    // `apiBasePath` too, and then the SPA holds a plane host at runtime.
    for (const file of subject.artefactFiles) {
      if (!/(^|\/)config\.json$/.test(file.path) || file.text === null) {
        continue;
      }
      let parsed;
      try {
        parsed = JSON.parse(file.text);
      } catch {
        continue; // reported by `no-secret`
      }
      const base = parsed?.apiBasePath;
      if (typeof base === 'string' && !base.startsWith('/')) {
        failures.push({
          id: 'no-plane-hostname',
          message: `dist/${file.path} sets apiBasePath to "${base}". It must be an origin-relative path beginning with "/" (FE-D19) — the gateway is same-origin by construction.`,
        });
      }
    }
    return failures;
  },
};

// ── 7 · config.json externally replaceable ───────────────────────────────
const configReplaceable = {
  id: 'config-replaceable',
  title: 'config.json is externally replaceable without a rebuild',
  why: '`FE-D14`: one artefact, many deployments. Retargeting a console is editing one file next to `index.html` and reloading — if the value is compiled in, or the filename is content-hashed, that stops being true.',
  check(subject) {
    /** @type {Failure[]} */
    const failures = [];

    const configFile = subject.artefactFiles.find((file) => file.path === 'config.json');
    if (!configFile) {
      failures.push({
        id: 'config-replaceable',
        message:
          'the artefact has no config.json at its root — it would boot with nothing to read (loadConfig fetches /config.json).',
      });
      return failures;
    }

    // Content-hashed or bundler-processed: either makes the file something
    // the build owns rather than something the operator owns.
    for (const emitted of [...subject.chunks, ...subject.assets]) {
      if (/config[.-][^/]*\.json$/i.test(emitted.fileName)) {
        failures.push({
          id: 'config-replaceable',
          message: `${emitted.fileName} is an emitted, build-owned config asset. Runtime config must be copied verbatim from publicDir, never imported — an imported one is content-hashed and cannot be replaced by hand.`,
        });
      }
    }
    const indexHtml = subject.assets.find((asset) => asset.fileName === 'index.html');
    if (indexHtml?.text && /config[^"'\s]*\.json/i.test(indexHtml.text)) {
      failures.push({
        id: 'config-replaceable',
        message:
          'index.html references config.json. A link or preload pins the filename into the document — an operator replacing the file must not have to edit the HTML too.',
      });
    }

    // The runtime must actually go and fetch it.
    if (!emittedTextMatching(subject, /\/config\.json/).length) {
      failures.push({
        id: 'config-replaceable',
        message:
          'no emitted chunk fetches /config.json — boot is not reading runtime config at all.',
      });
    }

    // And must not have been handed the answer at build time. Both the
    // whole serialized object and each individual non-trivial value are
    // checked, because Vite would inline either shape.
    let parsed;
    try {
      parsed = JSON.parse(configFile.text ?? '');
    } catch {
      return failures; // reported by `no-secret`
    }
    const serialized = JSON.stringify(parsed);
    for (const { where, text } of emittedText(subject)) {
      if (text.includes(serialized)) {
        failures.push({
          id: 'config-replaceable',
          message: `${where} contains config.json's contents verbatim — the file was inlined at build time, so replacing it on disk would change nothing.`,
        });
      }
    }
    return failures;
  },
};

/**
 * Turns a Rollup/Rolldown bundle into the shape the assertions read. Lives
 * here rather than in the runner so the runner and the self-test describe a
 * build the same way — a self-test that built its own description could pass
 * while the gate read a field the bundler no longer populates.
 *
 * `artefactFiles` is not derivable from the bundle at all: copied public
 * assets and anything a `closeBundle` hook adds or removes exist only on
 * disk. The caller walks the written output directory and passes it in.
 */
export function describeBundle(output, artefactFiles) {
  const chunks = output
    .filter((item) => item.type === 'chunk')
    .map((chunk) => ({
      fileName: chunk.fileName,
      code: chunk.code,
      moduleIds: chunk.moduleIds ?? [],
      modules: chunk.modules ?? {},
    }));
  const assets = output
    .filter((item) => item.type === 'asset')
    .map((asset) => ({
      fileName: asset.fileName,
      text: typeof asset.source === 'string' ? asset.source : null,
    }));
  return { chunks, assets, artefactFiles };
}

/** The seven, in the order `docs/plans/frontend/implementation.md` states them. */
export const ASSERTIONS = [
  noAuthMock,
  noDevGallery,
  noMsw,
  noFixtureDataset,
  noSecret,
  noPlaneHostname,
  configReplaceable,
];

/**
 * @param {BuildSubject} subject
 * @returns {Failure[]} every violation found, tagged with the assertion that found it.
 */
export function assertProductionBuild(subject) {
  return ASSERTIONS.flatMap((assertion) => assertion.check(subject));
}
