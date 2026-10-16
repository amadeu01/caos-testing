# Agent guide

Chaos engineering lecture demo: a mini-Netflix platform on Quarkus where students inject Faults and watch Steady State hold or collapse.

- **Vocabulary:** read [`CONTEXT.md`](CONTEXT.md) before naming anything (classes, endpoints, dashboard labels, docs, commit text). Use its terms; its _Avoid_ lists name the words to replace.
- **Decisions:** read [`docs/adr/`](docs/adr/) before changing architecture, build, threading, AWS access or frameworks. A change that conflicts with an ADR ships with a new ADR that supersedes it.
- **Commits:** follow [`CONTRIBUTING.md`](CONTRIBUTING.md): gitmoji subject, one idea per commit, a body that teaches.
- **Design and plans:** specs live in `docs/superpowers/specs/`, implementation plans in `docs/superpowers/plans/`.
