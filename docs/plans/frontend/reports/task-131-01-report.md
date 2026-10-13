# Task 131.01 — Connection type descriptors

## Built

- Added the descriptor schema and the Solace broker and remote-server descriptors.
- Added validation for schema conformance, duplicate attribute keys, and `when` predicates that
  reference a discriminator in the same descriptor.
- Tightened the secret invariant in follow-up commit `3af808f`: `type: secret` and `secret: true`
  must agree in both directions.

## Verification

- `python3 contracts/connection-types/validate-descriptors.py` — two descriptors valid.
- Delivered by `80a50b7`; invariant correction in `3af808f`.
