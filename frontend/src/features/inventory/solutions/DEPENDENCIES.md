# Project Dependencies

This document summarizes the directly declared third-party libraries used across the Solution Manager repository. It was prepared by checking the dependency manifests, lockfiles, and source imports.

## Summary

| Project area | Runtime dependencies | Development dependencies | Notes |
| --- | ---: | ---: | --- |
| Frontend | 12 | 25 | React and Vite application |
| View-model contract | 0 | 3 | Dependency-free at runtime |
| Python validators | 3 | 0 | Contract validation scripts |
| Go agent | 0 | 0 | Uses the Go standard library and internal packages only |

The repository has **40 distinct directly declared third-party libraries**. The npm lockfiles also include transitive dependencies: 606 lockfile entries for the frontend and 92 for the view-model contract package.

## Languages and package ecosystems

| Project area | Language used by this project | Package ecosystem |
| --- | --- | --- |
| Frontend application | TypeScript and JavaScript | npm |
| View-model contract | TypeScript and JavaScript | npm |
| Contract validators | Python | PyPI/pip |
| Go agent | Go | Go modules |
| Backend migrations and tests | SQL | No package manager currently declared |

The language shown for a library below describes the language and package ecosystem through which this project consumes it. It does not attempt to list every implementation language used inside the library's upstream repository.

## Frontend runtime libraries

These dependencies are declared in `frontend/package.json`.

| Library | Version | Purpose |
| --- | --- | --- |
| `react` | `~19.2.8` | UI framework |
| `react-dom` | `~19.2.8` | Browser rendering |
| `react-router` | `^7.18.2` | Application routing |
| `@mui/material` | `^9.3.1` | UI component library |
| `@mui/x-data-grid` | `^9.11.0` | Data-grid components |
| `@emotion/react` | `^11.14.0` | CSS-in-JS runtime |
| `@emotion/styled` | `^11.14.1` | Styled components used by Emotion and MUI |
| `@tanstack/react-query` | `^5.101.4` | Server-state fetching and caching |
| `react-hook-form` | `^7.86.0` | Form state and validation |
| `zustand` | `^5.0.15` | Client-side state management |
| `ajv` | `^8.20.0` | JSON Schema validation |
| `echarts` | `^6.1.0` | Charts and visualizations |

## Frontend development and testing libraries

These development dependencies are declared in `frontend/package.json`.

### Build and language

| Library | Version |
| --- | --- |
| `typescript` | `^6.0.3` |
| `vite` | `^8.2.1` |
| `@vitejs/plugin-react` | `^6.0.5` |
| `@types/node` | `^22.20.1` |
| `@types/react` | `^19.2.18` |
| `@types/react-dom` | `^19.2.4` |

### Unit and integration testing

| Library | Version |
| --- | --- |
| `vitest` | `^4.1.11` |
| `jsdom` | `^24.0.0` |
| `@testing-library/dom` | `^10.4.1` |
| `@testing-library/react` | `^16.3.2` |
| `@testing-library/jest-dom` | `^7.0.1` |
| `@testing-library/user-event` | `^14.6.5` |
| `msw` | `^2.15.0` |
| `jest-axe` | `^11.0.0` |

### End-to-end testing

| Library | Version |
| --- | --- |
| `@playwright/test` | `^1.62.1` |

### Linting and formatting

| Library | Version |
| --- | --- |
| `eslint` | `^9.39.5` |
| `@eslint/js` | `^9.39.5` |
| `typescript-eslint` | `^8.67.0` |
| `eslint-config-prettier` | `^10.1.8` |
| `eslint-import-resolver-typescript` | `^4.4.5` |
| `eslint-plugin-boundaries` | `^7.2.0` |
| `eslint-plugin-import` | `^2.32.0` |
| `eslint-plugin-react-hooks` | `^7.1.1` |
| `globals` | `^17.11.0` |
| `prettier` | `^3.9.6` |

## View-model contract package

The `contracts/view-models` package is dependency-free at runtime. It has the following development dependencies:

| Library | Version |
| --- | --- |
| `@types/node` | `^26.2.0` |
| `typescript` | `7.0.2` |
| `vitest` | `4.1.10` |

This package uses newer TypeScript and Node type versions than the frontend application.

## Python validation libraries

These packages are declared in `contracts/requirements.txt`.

| Library | Version | Purpose |
| --- | --- | --- |
| `jsonschema` | `>=4.25.1` | JSON Schema validation |
| `referencing` | `>=0.36.2` | Schema reference resolution |
| `PyYAML` | `>=6.0.3` | YAML parsing |

## Go agent

The Go agent declares Go `1.26.4` in `go-agent/go-agent/go.mod`. It has no external Go module dependencies and currently uses only the Go standard library and repository-internal packages.

## Apache 2.0 license compatibility audit

> **Important:** This is an engineering license inventory, not legal advice. The result covers the directly declared dependencies in this document. A release-compliance process should also scan transitive dependencies and bundled assets.

The license identifiers below were checked against the versions recorded in the npm lockfiles. Python licenses were checked from installed package metadata and the PyYAML project metadata. Compatibility is classified using the [Apache Software Foundation third-party license policy](https://www.apache.org/legal/resolved.html), under which Apache-2.0, MIT/X11, and ISC are Category A licenses that may be included in Apache products.

### Result

| License | Direct libraries | Apache classification | Result |
| --- | ---: | --- | --- |
| MIT | 36 | Category A | Compatible |
| Apache-2.0 | 3 | Category A | Compatible |
| ISC | 1 | Category A | Compatible |
| **Total** | **40** | **Category A only** | **No direct-dependency conflict found** |

### Frontend runtime dependencies

| Library | Resolved version | Language/ecosystem | License | Apache-compatible |
| --- | --- | --- | --- | --- |
| `@emotion/react` | `11.14.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@emotion/styled` | `11.14.1` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@mui/material` | `9.3.1` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@mui/x-data-grid` | `9.11.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@tanstack/react-query` | `5.101.4` | JavaScript/TypeScript (npm) | MIT | Yes |
| `ajv` | `8.20.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `echarts` | `6.1.0` | JavaScript/TypeScript (npm) | Apache-2.0 | Yes |
| `react` | `19.2.8` | JavaScript/TypeScript (npm) | MIT | Yes |
| `react-dom` | `19.2.8` | JavaScript/TypeScript (npm) | MIT | Yes |
| `react-hook-form` | `7.86.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `react-router` | `7.18.2` | JavaScript/TypeScript (npm) | MIT | Yes |
| `zustand` | `5.0.15` | JavaScript/TypeScript (npm) | MIT | Yes |

### Frontend development dependencies

| Library | Resolved version | Language/ecosystem | License | Apache-compatible |
| --- | --- | --- | --- | --- |
| `@eslint/js` | `9.39.5` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@playwright/test` | `1.62.1` | JavaScript/TypeScript (npm) | Apache-2.0 | Yes |
| `@testing-library/dom` | `10.4.1` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@testing-library/jest-dom` | `7.0.1` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@testing-library/react` | `16.3.2` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@testing-library/user-event` | `14.6.5` | JavaScript/TypeScript (npm) | MIT | Yes |
| `@types/node` | `22.20.1` | TypeScript declarations (npm) | MIT | Yes |
| `@types/react` | `19.2.18` | TypeScript declarations (npm) | MIT | Yes |
| `@types/react-dom` | `19.2.4` | TypeScript declarations (npm) | MIT | Yes |
| `@vitejs/plugin-react` | `6.0.5` | JavaScript/TypeScript (npm) | MIT | Yes |
| `eslint` | `9.39.5` | JavaScript/TypeScript (npm) | MIT | Yes |
| `eslint-config-prettier` | `10.1.8` | JavaScript/TypeScript (npm) | MIT | Yes |
| `eslint-import-resolver-typescript` | `4.4.5` | JavaScript/TypeScript (npm) | ISC | Yes |
| `eslint-plugin-boundaries` | `7.2.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `eslint-plugin-import` | `2.32.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `eslint-plugin-react-hooks` | `7.1.1` | JavaScript/TypeScript (npm) | MIT | Yes |
| `globals` | `17.11.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `jest-axe` | `11.0.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `jsdom` | `24.1.3` | JavaScript/TypeScript (npm) | MIT | Yes |
| `msw` | `2.15.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `prettier` | `3.9.6` | JavaScript/TypeScript (npm) | MIT | Yes |
| `typescript` | `6.0.3` | JavaScript/TypeScript (npm) | Apache-2.0 | Yes |
| `typescript-eslint` | `8.67.0` | JavaScript/TypeScript (npm) | MIT | Yes |
| `vite` | `8.2.1` | JavaScript/TypeScript (npm) | MIT | Yes |
| `vitest` | `4.1.11` | JavaScript/TypeScript (npm) | MIT | Yes |

### View-model package development dependencies

These names also occur in the frontend list, so they do not increase the distinct-library total. Their separately locked versions were still checked.

| Library | Resolved version | Language/ecosystem | License | Apache-compatible |
| --- | --- | --- | --- | --- |
| `@types/node` | `26.2.0` | TypeScript declarations (npm) | MIT | Yes |
| `typescript` | `7.0.2` | JavaScript/TypeScript (npm) | Apache-2.0 | Yes |
| `vitest` | `4.1.10` | JavaScript/TypeScript (npm) | MIT | Yes |

### Python validation dependencies

| Library | Audited version | Language/ecosystem | License | Apache-compatible |
| --- | --- | --- | --- | --- |
| `jsonschema` | `4.25.1` | Python (PyPI/pip) | MIT | Yes |
| `referencing` | `0.36.2` | Python (PyPI/pip) | MIT | Yes |
| `PyYAML` | `6.0.3` | Python (PyPI/pip) | MIT | Yes |

### Distribution obligations

- MIT, ISC, and Apache-2.0 dependencies generally require preservation of their copyright and license notices when their code is redistributed.
- Review each dependency's packaged `LICENSE` and any `NOTICE` file when producing a distributable release. Apache-2.0 dependencies can carry NOTICE text that must be preserved where applicable.
- Do not treat this direct-dependency result as a complete software bill of materials. The frontend and view-model lockfiles contain transitive packages that require their own automated license scan before release.
- Re-run the audit whenever a dependency or lockfile changes because a later version may use a different license.

## Findings

- Files under `frontend/contracts/packages/*/package.json` are solution-package data files, not npm package manifests. They do not declare JavaScript dependencies.
- The backend currently contains SQL migrations and tests but no Maven, Gradle, npm, or other backend dependency manifest.
- Production source imports match the declared runtime dependencies. No missing production dependency was found.
- `axe-core` is imported as a type in `frontend/src/test/jest-axe.d.ts`, but it is available transitively through `jest-axe`. Declaring `axe-core` directly as a development dependency would make this relationship more robust.
- References to `@mui/x-data-grid-pro`, `@mui/x-data-grid-premium`, and `@mui/x-charts-pro` occur only in intentional lint fixtures and forbidden-dependency checks. They are not application dependencies.
