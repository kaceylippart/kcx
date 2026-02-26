# KCX: Knowledge Context eXchange

**Stack:** Clojure | Babashka | MCP Protocol | EDN State

> Dense input. Autonomous agents. Persistent memory.

## Overview

KCX is a multi-agent MCP server that orchestrates AI workflows through a data-driven state machine. A dense DSL compresses intent into minimal keystrokes — tokens expand into rich, precise prompts through a layered dictionary system, then route through specialized agents that run to completion without manual intervention.

### Core Principles

- **Brevity** — Tokens (`!verb`, `+modifier`, `@arg`) compress verbose prompts into keystrokes
- **Precision** — Tokens expand deterministically; no ambiguity, no interpretation
- **Context efficiency** — Persistent EDN memory bank, not ever-expanding chat history
- **Deterministic workflows** — Sequence controlled by a state machine in code, not prompts
- **Constrain sequence, not capability** — Agents get full tool access; the engine controls ordering

## Quick Start

### Prerequisites

- [Babashka](https://github.com/babashka/babashka) (bb)
- Claude CLI installed and in PATH (or set `CLAUDE_PATH`)
- Valid `ANTHROPIC_API_KEY` environment variable

### Installation

```bash
git clone <repo-url>
cd kcx
chmod +x kcx.clj
```

### Claude Code Configuration

Add to your Claude Code MCP settings:

```json
{
  "mcpServers": {
    "kcx": {
      "command": "/path/to/kcx/kcx.clj"
    }
  }
}
```

### Running

```bash
# Start MCP server (for Claude Code integration)
./kcx.clj

# Run tests
bb test
```

## DSL

Five symbols, plus inline natural language. Each token is a key into an expansion dictionary — `!review` doesn't mean "review", it expands into a full, curated prompt.

```
kcx !verb @file %value +modifier >directive and any natural language here
```

| Symbol | Purpose | Example |
|--------|---------|---------|
| `!` | Verb (action) — expands to base prompt | `!fix`, `!review`, `!explain` |
| `@` | Positional param (file path, with autocomplete) | `@calculator.clj` |
| `%` | Positional param (general value, no autocomplete) | `%workflows`, `%"multi word"` |
| `+` | Modifier — appends behavioral directives to prompts | `+thorough`, `+no-hedge` |
| `>` | Pipeline directive — changes workflow shape | `>skip-tests`, `>fast` |

`@` and `%` are interchangeable — both fill positional template params in order. Use `@` for file paths (leverages Claude's autocomplete), `%` for everything else. Quote multi-word values: `%"add error handling"`.

Everything that isn't a symbol token is natural language, passed as-is:

```bash
kcx !fix @calc.clj and make sure the edge cases are covered
kcx "add error handling to the calculator"   # pure natural language
```

### Examples

```bash
kcx !fix @calculator.clj                          # "Fix the following issue: calculator.clj."
kcx !edit @calc.clj %"add error handling"          # Fills both template params
kcx !review @calc.clj +thorough                    # Review with modifier
kcx !design @auth-system                           # Architect → Worker → Tester → Reviewer
kcx !build a new REST endpoint for users            # Natural language after verb
kcx !explain @workflow.clj                         # Read-only explanation
kcx !fix @calc.clj >skip-tests just fix the typo  # Skip tester, inline instruction
kcx !fix @calc.clj >fast                           # Worker + curator only
kcx !review @calc.clj +thorough >preview           # Show expanded prompt without running
kcx !help @review                                  # Show params and template for !review
kcx !tdd @utils.clj                                # TDD workflow
```

## Prompt Expansion

Tokens are keys into a layered expansion dictionary. `!review @calc.clj` expands to `"Review calc.clj, focusing on {scope}."` — the full prompt you would have typed manually, but in 3 tokens.

### How It Works

1. **Parser** extracts tokens from input (`!verb`, `@args`, `+modifiers`)
2. **Expander** resolves each token against the dictionary
3. **Template engine** fills `{param}` slots with `@` arguments (positional)
4. **Prompt builders** receive the expanded text, not raw tokens

### Template System

Verb and modifier definitions use `{param}` templates with positional arguments:

```clojure
;; Definition
{"review" {:prompt "Review {target}, focusing on {scope}."
           :params [{:name "target" :default "the codebase"}
                    {:name "scope"  :default "correctness and code quality"}]
           :workflow :standard}}

;; Expansion
;; !review @calc.clj @divide-fn  → "Review calc.clj, focusing on divide-fn."
;; !review @calc.clj             → "Review calc.clj, focusing on correctness and code quality."
;; !review                       → "Review the codebase, focusing on correctness and code quality."
```

Params with `:default` fall back when not provided. Params without `:default` are required — the expansion engine returns an error if they're missing:

```clojure
{"debug" {:prompt "Debug the following error: {target}."
          :params [{:name "target"}]  ;; no :default = required
          :workflow :standard}}
```

### Three-Tier Dictionary

Expansions merge in priority order: **personal > project > base**.

| Tier | Location | Purpose |
|------|----------|---------|
| **Base** | `resources/base-expansions.edn` | Ships with KCX — sensible defaults |
| **Project** | `.kcx/expansions.edn` | Team-shared vocabulary |
| **Personal** | `~/.kcx/expansions.edn` | Individual overrides |

If your personal dictionary defines `!review`, it fully replaces the base definition — no partial merging.

### Unknown Tokens

Unresolved tokens warn with "did you mean?" suggestions based on edit distance:

```
⚠ !reveiw not found in expansions. Did you mean: !review?
```

### Modifier Targeting

Modifiers specify which agent they apply to:

| `applies-to` | Agents |
|---------------|--------|
| `:all` | Every agent in the pipeline |
| `:worker` | Worker only |
| `:reviewer` | Reviewer only |

```clojure
{"+thorough"   {:prompt "Be thorough." :applies-to :all}
 "+no-hedge"   {:prompt "Be direct."   :applies-to :reviewer}
 "+minimal"    {:prompt "Smallest change possible." :applies-to :worker}}
```

## Workflow Engine

Workflows are defined as data — a map of states and transitions — executed by a single state machine. Each state dispatches to a handler that spawns an autonomous Claude instance.

### Workflow Types

| Type | Trigger | Pipeline |
|------|---------|----------|
| **Standard** | `!fix`, `!edit`, `!debug`, `!build` | work → test → review → curate |
| **TDD** | `!test`, `!tdd` | write-tests → implement → validate → review → curate |
| **Architect** | `!plan`, `!design` | architect → work → test → review → curate |
| **Review** | `!review` | review → done |
| **Explain** | `!explain` | explainer → done |

### Retry Loops

The state machine handles retries automatically:
- **Tester fails** → routes back to worker with feedback (up to 3 retries)
- **Reviewer rejects** → routes back to worker with feedback (up to 3 retries)
- **Retry exhaustion** → workflow fails with accumulated context

### Pipeline Directives

Directives (`>`) modify the workflow graph at runtime by removing stages:

| Directive | Effect | Resulting pipeline |
|-----------|--------|--------------------|
| `>skip-tests` | Remove tester | work → review → curate |
| `>skip-review` | Remove reviewer | work → test → curate |
| `>fast` | Remove tester + reviewer | work → curate |
| `>yolo` | Worker only | work → done |
| `>preview` | Show expanded prompt | no execution |

Directives are composable: `>skip-tests >skip-review` = `>fast`.

### Agents

| Agent | Role | Capability |
|-------|------|------------|
| **Worker** | Developer | Code generation, file operations, full tool access |
| **Tester** | QA Engineer | Test writing, validation, test execution |
| **Reviewer** | QA | Code review, approval/rejection with feedback |
| **Curator** | Librarian | Memory bank compaction (Claude-powered, intelligent) |
| **Architect** | Designer | System design, planning, specifications |
| **Explainer** | Reader | Read-only exploration and explanation (no file writes) |

## State Management

KCX uses a persistent **briefing document** as its memory bank. Each project gets a structured EDN file with five sections that the Curator agent maintains intelligently after each workflow run.

```clojure
{:meta {:version "2.0" :project "my-app" :command-count 15 :updated "2026-02-26"}
 :briefing
 {:project-map    "What files exist, what they do, how they connect..."
  :conventions    "Naming patterns, test structure, coding style..."
  :architecture   "Key design decisions and their rationale..."
  :active-context "Last task, recent changes, current focus..."
  :known-issues   "Bugs, tech debt, gotchas..."}}
```

This briefing is the **sole context** each sub-agent receives about the project. The Curator's job is to keep it comprehensive — a new agent reading only this document should understand the project well enough to work on it.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `KCX_WORKER_MODEL` | `claude-sonnet-4-20250514` | Model for spawned agents |
| `KCX_WORKER_TOOLS` | `Read,Write,Edit,Glob,Grep,Bash` | Tools available to agents |
| `KCX_WORKING_DIR` | `.` | Working directory for agents |
| `KCX_PERMISSION_MODE` | `bypassPermissions` | Permission mode for agents |
| `KCX_MAX_ITERATIONS` | `3` | Max retries on rejection |
| `CLAUDE_PATH` | Auto-detected | Path to Claude CLI binary |

## Project Structure

```
kcx/
├── kcx.clj              # Entry point
├── VERSION              # Version tracking
├── bb.edn               # Babashka config & tasks
├── resources/
│   └── base-expansions.edn  # Base expansion dictionary
├── src/kcx/
│   ├── core.clj         # MCP server & request handling
│   ├── dsl.clj          # DSL parser & syntax help
│   ├── expand.clj       # Prompt expansion engine
│   ├── workflow.clj     # State machine definitions & executor
│   ├── orchestrator.clj # Command dispatch & result formatting
│   ├── worker.clj       # Agent spawning, prompts, handlers
│   ├── state.clj        # Memory bank (v2 briefing format)
│   ├── logging.clj      # Session logging
│   └── utils.clj        # IO aliases
├── test/kcx/
│   ├── workflow_test.clj     # State machine tests
│   ├── orchestrator_test.clj # Orchestrator tests
│   ├── expand_test.clj       # Expansion engine tests
│   └── state_test.clj        # Memory bank tests
├── playground/          # Test environment
└── logs/                # Session logs
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `kcx_command` | Execute DSL commands or natural language prompts |

## Changelog

### v0.7.0 — Memory Bank v2, Verb Routing, Help & Preview
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

### v0.6.0 — Pipeline Directives, Explainer Agent, DSL Refinement
- Pipeline directives (`>skip-tests`, `>fast`, `>yolo`) — rewrite workflow graph at runtime
- Explainer agent — read-only `!explain` verb, no file writes
- `%` param alias — `@` for files (with autocomplete), `%` for general values
- Quoted multi-word params: `%"add error handling"`
- Multiple positional params fill template slots in order
- Required params (no `:default` = error if missing)
- Review workflow (`!review` → reviewer only, no worker overhead)
- Simplified DSL to 5 symbols, inline natural language (no quotes needed)
- All agents get full tool access — constrain sequence, not capability
- Three-tier expansion loading with hot-reload (no restart needed)
- Single MCP tool: `kcx_command`
- 82 tests, 214 assertions

### v0.5.1 — Prompt Expansion Engine
- Token expansion system: `!verb` and `+modifier` resolve against layered dictionaries
- Template rendering with `{param}` slots, positional args, and `:omit` support
- Three-tier dictionary merging (base < project < personal)
- "Did you mean?" suggestions for typos (Levenshtein + prefix matching)
- Modifier filtering by agent role (`:worker`, `:reviewer`, `:all`)
- Base vocabulary: 7 verbs, 6 modifiers
- 29 tests, 111 assertions

### v0.5.0 — State Machine Refactor
- Replace 4 duplicated workflow functions with data-driven state machine
- Single executor walks declarative workflow graphs
- Handler contract: `(fn [cmd artifacts] -> {:success bool ...})`
- Artifacts accumulate through pipeline — each handler gets all prior output
- Net ~500 lines removed, 20 unit tests added

### v0.4.0 — Autonomous Agents & Visibility
- Memory bank as source of truth for agent decisions
- Autonomous multi-file operations for workers
- Workflow progress output with elapsed time and job tracking
- Comprehensive result summaries in MCP responses

### v0.3.0 — Agent Spawning & Isolation
- Independent agent spawning with `env -i` isolation
- Clean environment separation from parent Claude session
- Test harness and evaluation system
- Permission mode configuration

### v0.2.0 — Clojure Rewrite
- Full rewrite from Rust to Clojure/Babashka
- MCP protocol implementation
- DSL parser with security validation
- Agent routing and orchestration

### v0.1.0 — Initial Implementation
- Rust-based MCP server
- Basic DSL concept
