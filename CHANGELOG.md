# Changelog

## v0.8.0 — Parent-Driven Workflows + Prompt Journal
- **Workflow commands now return plans** — parent Claude executes steps with full visibility and control
- Only the curator remains as a spawned sub-Claude (isolated for unbiased memory compaction)
- **Prompt journal** — global command log at `~/.kcx/journal.edn`, captures every workflow command
- **Suggestor agent** — dedicated sub-Claude that analyzes journal patterns and proposes new expansion entries
- Auto-suggestion every 10 commands (silent if no patterns found) + manual `!suggest` command
- `:workflow :skip` in expansion dictionary bakes in `>yolo` behavior for specific verbs
- Architect workflow stops after planning — no longer chains into building/testing
- Two-tier expansion dictionary (removed personal overrides tier)
- New `!curate` callback command — parent Claude calls this after completing workflow steps
- `/kcx` slash command for Claude Code integration
- `!memory` command — display current project briefing
- `!clear` command — reset memory bank to fresh template
- Skip verdict for tester and reviewer on trivial changes (config, docs, .gitignore)
- Curator added to review and explain workflows for cross-command context continuity
- `>yolo` directive skips workflow entirely — returns expanded prompt directly
- Removed `kcx` prefix requirement from DSL parser
- Dead code sweep: removed all sub-agent handlers, prompt builders, parsers, job tracking (~1000 lines)
- 98 tests, 266 assertions

## v0.7.0 — Memory Bank v2, Verb Routing, Help & Preview
- Memory bank redesigned as structured briefing document (5 sections of curator-maintained prose)
- Removed mechanical TTL/priority system — curator manages context intelligently
- Auto-migration from v1 (flat entries) to v2 (briefing) on load
- Single source of truth for verb routing — expansion dictionary only, no hardcoded fallback
- Unknown verbs return error with `!help` hint
- `!help` command — lists all verbs; `!help <verb>` shows template, params, defaults
- `>preview` directive — shows expanded prompt without running workflow
- `!plan` and `!design` verbs wired to architect workflow
- `!build` verb for new features (no params, pure natural language)
- Dead code sweep: removed agents.clj, claude_api.clj, stale test files (-1,700 lines)
- 89 tests, 242 assertions

## v0.6.0 — Pipeline Directives, Explainer Agent, DSL Refinement
- Pipeline directives (`>skip-tests`, `>fast`, `>yolo`) — rewrite workflow graph at runtime
- Explainer agent — read-only `!explain` verb, no file writes
- `%` param alias — `@` for files (with autocomplete), `%` for general values
- Quoted multi-word params: `%"add error handling"`
- Multiple positional params fill template slots in order
- Required params (no `:default` = error if missing)
- Review workflow (`!review` → reviewer only, no worker overhead)
- Simplified DSL to 5 symbols, inline natural language (no quotes needed)
- All agents get full tool access — constrain sequence, not capability
- Layered expansion loading with hot-reload (no restart needed)
- Single MCP tool: `kcx_command`
- 82 tests, 214 assertions

## v0.5.1 — Prompt Expansion Engine
- Token expansion system: `!verb` and `+modifier` resolve against layered dictionaries
- Template rendering with `{param}` slots, positional args, and `:omit` support
- Three-tier dictionary merging (base < project < personal)
- "Did you mean?" suggestions for typos (Levenshtein + prefix matching)
- Modifier filtering by agent role (`:worker`, `:reviewer`, `:all`)
- Base vocabulary: 7 verbs, 6 modifiers
- 29 tests, 111 assertions

## v0.5.0 — State Machine Refactor
- Replace 4 duplicated workflow functions with data-driven state machine
- Single executor walks declarative workflow graphs
- Handler contract: `(fn [cmd artifacts] -> {:success bool ...})`
- Artifacts accumulate through pipeline — each handler gets all prior output
- Net ~500 lines removed, 20 unit tests added

## v0.4.0 — Autonomous Agents & Visibility
- Memory bank as source of truth for agent decisions
- Autonomous multi-file operations for workers
- Workflow progress output with elapsed time and job tracking
- Comprehensive result summaries in MCP responses

## v0.3.0 — Agent Spawning & Isolation
- Independent agent spawning with `env -i` isolation
- Clean environment separation from parent Claude session
- Test harness and evaluation system
- Permission mode configuration

## v0.2.0 — Clojure Rewrite
- Full rewrite from Rust to Clojure/Babashka
- MCP protocol implementation
- DSL parser with security validation
- Agent routing and orchestration

## v0.1.0 — Initial Implementation
- Rust-based MCP server
- Basic DSL concept
