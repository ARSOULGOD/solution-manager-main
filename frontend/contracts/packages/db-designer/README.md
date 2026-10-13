# `db-designer` — example Solution Package

`package.json` here is a complete, canonical Solution Package for a hypothetical DB
micro-integration. It is the **authoring reference**: one JSON document carrying a non-empty
`packageVersion` array plus component/field membership, validated directly against
`contracts/solution-package.schema.json`.

Nothing imports it at startup. Exercise it the way an operator does: run the console in DEV, choose
this file in the Solutions catalog's load dialog, review the validation summary, and confirm. The
DEV repository then renders its catalog card, inventory and Designer screen without per-solution
console code.

Edit this file and choose it again to see either exact validation errors or the updated Designer.

The prose — how to author one, what is specified versus proposed, and the outer package descriptor
(`descriptor.yaml`) that this file is only half of — is at
`docs/design/common/contracts/packages/db-designer/README.md`.
