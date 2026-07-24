# wiki

A small second-brain for this repo: notes, decisions, gotchas, anything worth
remembering that doesn't belong in code comments or commit messages.

Tracked in git, not part of the build — nothing here is referenced by
`settings.gradle.kts` or any module's source set.

## Pages

- [upstream.md](upstream.md) — original NewPipe, and what ScottPipe changes
- [ideas.md](ideas.md) — features/patches from other forks worth pursuing
- [features/kid-mode.md](features/kid-mode.md) — parental controls, what's
  built vs. planned
- [adr/0001-kid-mode-architecture.md](adr/0001-kid-mode-architecture.md) —
  why kid mode is built the way it is
- [testing.md](testing.md) — local SDK/emulator setup, known emulator
  gotchas, and how to drive the UI for manual testing
