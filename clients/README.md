# Clients

Each client is a self-contained folder: `clients/<name>/` (today: `android/`).

## Rules

- **No cross-client imports.** A client must never reference another client's
  folder (`clients/<other>/`, `../<other>/`). `tools/check_isolation.py` enforces
  this in CI (`newui.yml`, source job).
- **Shared logic is not client code.** Put it in `core/` (Go) or `ui/` (Rust +
  Slint), and expose it to clients through the method table in `protocol/`
  (`python3 tools/protocol.py gen|check`).
- **One build workflow per client:** `.github/workflows/build-<name>.yml`
  (Android keeps its historical name, `build-apk.yml`). Its `push`/`pull_request`
  `paths` filter is the shared set plus the client's own folder and the
  workflow file itself:

  ```yaml
  paths:
    - 'core/**'
    - 'protocol/**'
    - 'ui/**'
    - 'tools/**'
    - 'version.properties'
    - 'flclash-manifest.json'
    - 'licenses/**'
    - '.github/actions/**'
    - 'clients/<name>/**'
    - '.github/workflows/build-<name>.yml'
    - '!**/*.md'
  ```

  So a change inside `clients/a/` never starts `build-b.yml`, a change to a shared
  path starts every client build, and docs-only (`*.md`) changes build nothing.
  Keep `workflow_dispatch` on every workflow.
- `newui.yml` (core + source validation) runs on the shared set plus all of
  `clients/**`, because the protocol check spans every implementation.
- Release (`build-release.yml`) and smoke/debug workflows are dispatch-only.
