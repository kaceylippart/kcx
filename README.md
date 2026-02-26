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

KCX uses three symbols. Each token is a key into an expansion dictionary — `!review` doesn't mean "review", it expands into a full, curated prompt.

```
kcx !verb @arg1 @arg2 +modifier "additional context"
```

| Symbol | Purpose | Example |
|--------|---------|---------|
| `!` | Verb (action) — expands to base prompt | `!fix`, `!review`, `!tdd` |
| `@` | Argument — fills template params | `@calculator.clj`, `@divide-fn` |
| `+` | Modifier — appends behavioral directives | `+thorough`, `+skip-tests` |
| `"..."` | Natural language — additional context | `"the divide function has a bug"` |

`@` arguments bind to the immediately preceding `!` or `+`. Standalone `@` is an error.

Natural language works standalone too — it bypasses expansion entirely:

```bash
kcx "add error handling to the calculator"
```

### Examples

```bash
kcx !fix @calculator.clj                          # "Fix the issue in calculator.clj."
kcx !review @calc.clj @divide-fn                   # "Review calc.clj, focusing on divide-fn."
kcx !review                                        # "Review the codebase." (defaults)
kcx !fix @calc.clj +thorough +skip-tests           # Fix with modifiers
kcx !tdd @utils.clj                                # TDD workflow
kcx !fix @calc.clj "division by zero crashes"      # DSL + natural language
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

Every param has a string default — no magic clause dropping, just straightforward substitution.

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
| **Standard** | `!fix`, `!gen`, `!edit`, etc. | work → test → review → curate |
| **TDD** | `!test`, `!tdd` | write-tests → implement → validate → review → curate |
| **Architect** | `!plan`, `!design`, `!arch` | architect → work → test → review → curate |

### Retry Loops

The state machine handles retries automatically:
- **Tester fails** → routes back to worker with feedback (up to 3 retries)
- **Reviewer rejects** → routes back to worker with feedback (up to 3 retries)
- **Retry exhaustion** → workflow fails with accumulated context

### Agents

| Agent | Role | Capability |
|-------|------|------------|
| **Worker** | Developer | Code generation, file operations, full tool access |
| **Tester** | QA Engineer | Test writing, validation, test execution |
| **Reviewer** | QA | Code review, approval/rejection with feedback |
| **Curator** | Librarian | Memory bank compaction (Claude-powered, intelligent) |
| **Architect** | Designer | System design, planning, specifications |

## State Management

KCX uses EDN files as a persistent memory bank. The Curator agent compresses workflow results into structured memory after each run.

```clojure
{:meta {:version "0.5.1" :created "2025-01-13T..."}
 :stack {:language "Clojure" :framework "Babashka"}
 :active-context {:task "Current task" :status "in-progress"}
 :memory [{:action "fix" :target "calculator.clj" :priority :high}]}
```

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
│   ├── dsl.clj          # DSL parser
│   ├── expand.clj       # Prompt expansion engine
│   ├── workflow.clj     # State machine definitions & executor
│   ├── orchestrator.clj # Command dispatch & result formatting
│   ├── worker.clj       # Agent spawning, prompts, handlers
│   ├── agents.clj       # Agent type definitions
│   ├── state.clj        # Memory bank (EDN)
│   ├── logging.clj      # Session logging
│   └── utils.clj        # Utilities
├── test/kcx/
│   ├── workflow_test.clj     # State machine tests
│   ├── orchestrator_test.clj # Orchestrator tests
│   └── expand_test.clj       # Expansion engine tests
├── playground/          # Test environment
├── logs/                # Session logs
└── memory-bank/         # Project state files
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `kcx_command` | Execute DSL commands or natural language prompts |
| `read_state` | Read project memory bank |
| `write_file` | Write content to files |

## Changelog

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
