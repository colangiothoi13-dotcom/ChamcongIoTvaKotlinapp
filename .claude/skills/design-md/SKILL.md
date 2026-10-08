---
name: design-md
description: Create, update, validate, or follow DESIGN.md design-system documents using the google-labs-code/design.md specification. Use for documenting an existing visual identity, extracting design tokens from Android/Kotlin UI, or implementing UI from an existing DESIGN.md.
license: Apache-2.0
---

# DESIGN.md

Use DESIGN.md to record exact design tokens alongside the reasoning for applying them. This is a project-local adapter for [google-labs-code/design.md](https://github.com/google-labs-code/design.md), not an upstream-provided skill.

## Read the relevant design context

- For authoring or validating a document, read [references/spec.md](references/spec.md), the bundled official format specification.
- For implementing UI, first read the project's existing DESIGN.md. Its tokens provide exact values; its prose explains usage. Preserve the user's requested scope.
- When documenting an existing app, inspect its theme, colors, typography, dimensions, shapes, and representative components. In this Android/Kotlin project, search Compose theme definitions and XML resources with `rg`. Derive values from source; distinguish observed values from proposed changes or estimates from screenshots.
- Preserve established design choices and existing document content unless the user requests a change. Upstream examples illustrate syntax and are not this app's visual identity. Create a project DESIGN.md only when the requested work calls for that document.

## Author the document

Use YAML frontmatter for machine-readable values when tokens are available, followed by Markdown rationale. Quote hexadecimal colors and token references. Keep token values and prose consistent, and resolve references such as `{colors.primary}` to defined values.

Use the canonical `##` headings in this order, including only relevant sections:

1. Overview
2. Colors
3. Typography
4. Layout
5. Elevation & Depth
6. Shapes
7. Components
8. Do's and Don'ts

Keep headings recognizable to the parser; the prose may use the user's language. Preserve unknown sections when editing an existing document and avoid duplicate headings. The spec documents allowed aliases and intentionally omitted token groups.

The supported token groups are `colors`, `typography`, `rounded`, `spacing`, and `components`. Read the bundled spec for their types and component properties. Define hover, active, or pressed styles as separate component entries when applicable. Explain elevation, interaction, and other behavior that the token schema does not express in prose.

For Android, retain native `dp` and `sp` in implementation. The DESIGN.md dimension grammar supports `px`, `em`, and `rem`, so document the chosen mapping to Android logical dimensions explicitly rather than inserting unsupported units or treating physical pixels as density-independent pixels. When adapting eight-digit colors, account for CSS `#RRGGBBAA` versus Android `#AARRGGBB`; use six-digit opaque colors when appropriate.

## Validate or compare

The verified CLI version is `@google/design.md@0.4.0` and requires Node.js 18 or later. On Windows/PowerShell, use its dot-free `designmd` alias to avoid the `.md` file-association collision:

```powershell
npx.cmd --yes --package "@google/design.md@0.4.0" designmd lint DESIGN.md
npx.cmd --yes --package "@google/design.md@0.4.0" designmd diff DESIGN-before.md DESIGN.md
```

Run `lint` after creating or editing a DESIGN.md. For an existing document, use `diff` when assessing changes to its tokens or rationale. Lint exits with code 1 for errors; diff exits with code 1 when errors or warnings increase. Read the JSON report, fix structural errors and broken references, and address or explain relevant warnings. If the CLI cannot run, report that validation was unavailable rather than claiming a passing result.

Use exports only when the user needs them:

```powershell
npx.cmd --yes --package "@google/design.md@0.4.0" designmd export --format dtcg DESIGN.md
```

The CLI also supports `json-tailwind` and `css-tailwind`; these exports do not generate Android theme code. Apply tokens to the existing Kotlin/XML styling mechanism when UI implementation is requested, then use the project's normal checks for the changed UI.

## Source and maintenance

The bundled specification is an unmodified snapshot of `docs/spec.md` from upstream commit `9bf8eae67128b6cc55ad9bf86665767deb4c11cd`, retrieved on 2026-10-05. The format identifies itself as `alpha`. Its [Apache-2.0 license](LICENSE) is included. The workflow and Android adaptation above are locally authored.

When updating this skill, refresh the specification and license from the same upstream revision and verify the CLI version and commands. OpenSkills can read this local skill, but it has no upstream SKILL.md installation path for automatic `openskills update`.
