# Contributing

This repository is lecture material. Students read the commit history as well as the code, so every commit has to teach something on its own.

## Contextual commits

A contextual commit carries one idea and explains itself:

- **One idea per commit.** A new Service, one Resilience Policy, one Experiment. Unrelated changes go in separate commits, even when they are small.
- **Subject:** a gitmoji code, then an imperative summary under about 60 characters.
- **Body:** why the change exists and what a student should notice. Use the words from [`CONTEXT.md`](CONTEXT.md) (Service, Dependency, Fallback, Steady State, SPS, Experiment, Blast Radius). Point to the ADR in [`docs/adr/`](docs/adr/) or the Netflix source that motivated it.
- **Builds and tests pass** at every commit, so a student can check out any point in history and run it.

Example:

```
:sparkles: Add popular-titles Fallback for recommendations

recommendations is a Non-critical Dependency of api: when it fails, a
viewer can still start playback, so SPS should not move. The Fallback
returns the cached popular titles instead of personalised ones.

This is the pattern from Netflix's Hystrix post "Fault Tolerance in a
High Volume, Distributed System" (2012).
```

## Gitmoji in use

| Code | Use for |
|---|---|
| `:tada:` | The initial commit |
| `:building_construction:` | Project structure: modules, build layout |
| `:memo:` | Docs, ADRs, glossary, lecture guide |
| `:sparkles:` | A new Service, feature or Experiment |
| `:construction:` | Work in progress that still builds |
| `:bug:` | Fix a bug |
| `:white_check_mark:` | Add or update tests |
| `:boom:` | Introduce a deliberate failure for teaching (a naive path that cascades) |
| `:shield:` | Add or tune a Resilience Policy (timeout, retry, breaker, bulkhead, Fallback) |
| `:chart_with_upwards_trend:` | Telemetry and dashboard metrics |
| `:lipstick:` | Dashboard look and feel |
| `:whale:` | Docker and Compose |
| `:construction_worker:` | CI |
| `:wrench:` | Configuration |
| `:recycle:` | Refactor without behaviour change |
| `:arrow_up:` | Upgrade a dependency |
| `:heavy_plus_sign:` / `:heavy_minus_sign:` | Add or remove a dependency |
| `:see_no_evil:` | `.gitignore` |

Full list: <https://gitmoji.dev>.

## Architecture decisions

A decision that is hard to reverse, surprising without context, and the result of a real trade-off gets an ADR in [`docs/adr/`](docs/adr/), numbered in sequence. A change that contradicts an ADR either follows it or replaces it with a new ADR in the same commit.

## Documentation in two languages

Human documentation exists in English (`README.md`, `docs/en/`) and Brazilian Portuguese (`README.pt-BR.md`, `docs/pt-br/`), with the same pages in both. A change to one language updates the other in the same commit. Domain terms from `CONTEXT.md` stay in English in both; Portuguese text puts the term in bold with a gloss on first use, for example "**Blast Radius** (raio de impacto)".
