# Font Files (Self-Hosted)

This directory holds self-hosted font files to support air-gapped deployments (REQ-003).

## Expected Files

The following font files should be added here. They are referenced by `src/shared/theme/tokens.css`.

### Rubik
- `Rubik-Light.woff2` / `Rubik-Light.woff` (weight: 300)
- `Rubik-Regular.woff2` / `Rubik-Regular.woff` (weight: 400)
- `Rubik-Medium.woff2` / `Rubik-Medium.woff` (weight: 500)
- `Rubik-SemiBold.woff2` / `Rubik-SemiBold.woff` (weight: 600)
- `Rubik-Bold.woff2` / `Rubik-Bold.woff` (weight: 700)

### Inter
- `Inter-Regular.woff2` / `Inter-Regular.woff` (weight: 400)
- `Inter-Medium.woff2` / `Inter-Medium.woff` (weight: 500)
- `Inter-SemiBold.woff2` / `Inter-SemiBold.woff` (weight: 600)
- `Inter-Bold.woff2` / `Inter-Bold.woff` (weight: 700)

### JetBrains Mono
- `JetBrainsMono-Regular.woff2` / `JetBrainsMono-Regular.woff` (weight: 400)
- `JetBrainsMono-Medium.woff2` / `JetBrainsMono-Medium.woff` (weight: 500)
- `JetBrainsMono-SemiBold.woff2` / `JetBrainsMono-SemiBold.woff` (weight: 600)

## License

Ensure the font files are licensed for use in the Solution Manager frontend. All three fonts are available from Google Fonts under the SIL Open Font License (OFL) or compatible licenses.

## Acquisition Task

Sourcing and adding the actual font binaries is a follow-up task, out of scope for FE-002. The CSS layer is ready; font files may be added at any time and the app will pick them up automatically.
