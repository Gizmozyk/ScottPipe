# Upstream

Original NewPipe: https://github.com/TeamNewPipe/NewPipe

Remotes in this repo:
- `origin` → https://github.com/Gizmozyk/ScottPipe.git (this fork)
- `upstream` → https://github.com/TeamNewPipe/NewPipe.git

To see the current diff against upstream:

```
git fetch upstream dev
git diff upstream/dev..ScottDev
```

## Differences from upstream

Keep this list current as ScottPipe actually diverges — a one-line entry per
change with the reason, not a restatement of the diff itself.

- `.claude/hooks/session-start.sh`, `.claude/settings.json` — Claude Code
  session-start hook that provisions the Android SDK (compileSdk 37.0 /
  build-tools 37.0.0, per `buildSrc/ProjectConfig.kt`) and a vendored Gradle
  9.6.1 dist cache, for Claude Code's remote/web sandbox, where repo-scoped
  network access can reach the Android SDK downloads directly but not
  `github.com/gradle/gradle-distributions` (where Gradle's own distribution
  download redirects to). Tooling only — no app behavior changes.

No app-code differences yet — ScottDev is otherwise even with upstream `dev`.
