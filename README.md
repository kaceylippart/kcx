# KCX: Knowledge Context eXchange

**Stack:** Clojure | Babashka | MCP Protocol | EDN State

> Dense input. Structured workflows. Persistent memory.

## Overview

KCX is an MCP server that orchestrates AI workflows through a dense DSL. Tokens expand into rich, precise prompts through a layered dictionary system, then generate structured workflow plans that parent Claude executes directly — with full visibility, full context, and the ability to stop or redirect at any point. Only the curator (memory bank compactor) runs as an isolated sub-Claude.

### Core Principles

- **Brevity** — Tokens (`!verb`, `+modifier`, `@arg`) compress verbose prompts into keystrokes
- **Precision** — Tokens expand deterministically; no ambiguity, no interpretation
- **Context efficiency** — Persistent EDN memory bank, not ever-expanding chat history
- **Deterministic workflows** — Sequence defined as data; plans generated from state machine graphs
- **Full visibility** — Parent Claude executes the plan; you can see, stop, or redirect at any step

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

# Install the /kcx slash command
cp commands/kcx.md ~/.claude/commands/kcx.md
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

Five symbols, plus inline natural language. Each token is a key into an expansion dictionary — `!review` doesn't mean "review", it expands into a full, curated prompt. Invoke via the `/kcx` slash command in Claude Code:

```
/kcx !verb @file %value +modifier >directive and any natural language here
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
/kcx !fix @calc.clj and make sure the edge cases are covered
/kcx add error handling to the calculator     # pure natural language (quotes optional)
```

### Examples

```bash
/kcx !fix @calculator.clj                          # "Fix the following issue: calculator.clj."
/kcx !edit @calc.clj %"add error handling"          # Fills both template params
/kcx !review @calc.clj +thorough                    # Review with modifier
/kcx !design @auth-system                           # Architect → Curator (plan only)
/kcx !build a new REST endpoint for users           # Natural language after verb
/kcx !explain @workflow.clj                         # Read-only explanation
/kcx !fix @calc.clj >skip-tests just fix the typo  # Skip tester, inline instruction
/kcx !fix @calc.clj >fast                           # Worker + curator only
/kcx !fix @calc.clj >yolo just fix the typo        # Execute prompt directly, no workflow
/kcx !review @calc.clj +thorough >preview           # Show expanded prompt without running
/kcx !help @review                                  # Show params and template for !review
/kcx !tdd @utils.clj                                # TDD workflow
```

## Prompt Expansion

Tokens are keys into a layered expansion dictionary. `!review @calc.clj` expands to `"Review calc.clj, focusing on {scope}."` — the full prompt you would have typed manually, but in 2 tokens.

### How It Works

1. **Parser** extracts tokens from input (`!verb`, `@args`, `+modifiers`)
2. **Expander** resolves each token against the dictionary
3. **Template engine** fills `{param}` slots with `@` arguments (positional)
4. **Step renderers** receive the expanded text, not raw tokens

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

### Expansion Dictionary

Expansions merge in priority order: **project > base**.

| Tier | Location | Purpose |
|------|----------|---------|
| **Base** | `resources/base-expansions.edn` | Ships with KCX — sensible defaults |
| **Project** | `.kcx/expansions.edn` | Project-specific vocabulary and overrides |

If your project dictionary defines `!review`, it fully replaces the base definition — no partial merging.

### Unknown Tokens

Unresolved tokens warn with "did you mean?" suggestions based on edit distance:

```
⚠ !reveiw not found in expansions. Did you mean: !review?
```

### Modifier Targeting

Modifiers specify which role they apply to:

| `applies-to` | Roles |
|---------------|-------|
| `:all` | Every step in the pipeline |
| `:worker` | Worker only |
| `:reviewer` | Reviewer only |

```clojure
{"+thorough"   {:prompt "Be thorough." :applies-to :all}
 "+no-hedge"   {:prompt "Be direct."   :applies-to :reviewer}
 "+minimal"    {:prompt "Smallest change possible." :applies-to :worker}}
```

## Workflow Engine

Workflows are defined as data — a map of states and transitions. The MCP tool expands your DSL command, linearizes the workflow graph, and returns a structured plan. Parent Claude executes each step using its own tools, then calls back to the curator (the only spawned sub-Claude) to update the memory bank.

### Workflow Types

| Type | Trigger | Pipeline |
|------|---------|----------|
| **Standard** | `!fix`, `!edit`, `!debug`, `!build` | work → test → review → curate |
| **TDD** | `!test`, `!tdd` | write-tests → implement → validate → review → curate |
| **Architect** | `!plan`, `!design` | architect → curate |
| **Review** | `!review` | review → curate |
| **Explain** | `!explain` | explainer → curate |

### Retry Loops

The workflow plan includes retry instructions:
- **Tester fails** → return to worker step with feedback (up to 3 retries)
- **Reviewer rejects** → return to worker step with feedback (up to 3 retries)
- **Trivial changes** → tester and reviewer may skip with justification

### Pipeline Directives

Directives (`>`) modify the workflow graph at runtime by removing stages:

| Directive | Effect | Resulting pipeline |
|-----------|--------|--------------------|
| `>skip-tests` | Remove tester | work → review → curate |
| `>skip-review` | Remove reviewer | work → test → curate |
| `>fast` | Remove tester + reviewer | work → curate |
| `>yolo` | Skip workflow entirely | prompt only |
| `>preview` | Show expanded prompt | no execution |

Directives are composable: `>skip-tests >skip-review` = `>fast`.

### Roles

Each workflow step assigns a role to parent Claude with specific instructions:

| Role | Purpose | Executed by |
|------|---------|-------------|
| **Worker** | Code implementation, file operations | Parent Claude |
| **Tester** | Test writing, validation, test execution | Parent Claude |
| **Reviewer** | Code review, approval/rejection with feedback | Parent Claude |
| **Architect** | System design, planning, specifications | Parent Claude |
| **Explainer** | Read-only exploration and explanation | Parent Claude |
| **Curator** | Memory bank compaction | Spawned sub-Claude (isolated) |

The curator is isolated so it can assess the project state without bias from the conversation context — it acts as both compactor and referee.

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

This briefing is included at the top of every workflow plan and is the sole context the curator sub-Claude receives. The Curator's job is to keep it comprehensive — a new session reading only this document should understand the project well enough to work on it.

## Prompt Journal & Suggestor

KCX maintains a global prompt journal at `~/.kcx/journal.edn` that captures every command you run. A dedicated suggestor sub-Claude periodically analyzes your usage patterns and proposes new expansion dictionary entries.

### How It Works

1. Every workflow command is logged to the journal (capped at 200 entries)
2. After every 10 commands, the suggestor automatically analyzes patterns
3. If repeated phrases are found, it suggests new verbs or modifiers
4. Suggestions are presented — never auto-applied

### Manual Trigger

```bash
/kcx !suggest        # Analyze journal and suggest expansions now
```

### Auto-Trigger

Every 10 workflow commands, the suggestor runs silently in the background. If patterns are found, suggestions appear after the workflow plan. If nothing is found, it stays silent.

### Example Output

```
═══ EXPANSION SUGGESTIONS ═══
Analyzed 47 prompts. Found 2 patterns:

1. [verb] "add error handling" (8 occurrences, high confidence)
   !add-errors @target → "Add error handling to {target}." [standard]

2. [modifier] "step by step" (5 occurrences, medium confidence)
   +verbose → "Explain your reasoning step-by-step." (all)

To add, copy the EDN to .kcx/expansions.edn
═══════════════════════════════
```

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `KCX_WORKER_MODEL` | `claude-sonnet-4-20250514` | Model for curator sub-Claude |
| `KCX_WORKER_TOOLS` | `Read,Write,Edit,Glob,Grep,Bash` | Tools available to curator |
| `KCX_PERMISSION_MODE` | `bypassPermissions` | Permission mode for curator |
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
│   ├── orchestrator.clj # Command dispatch & plan generation
│   ├── worker.clj       # Curator spawning & redo tracking
│   ├── journal.clj      # Global prompt journal
│   ├── suggestor.clj    # Pattern analysis & expansion suggestions
│   ├── state.clj        # Memory bank (v2 briefing format)
│   ├── logging.clj      # Session logging
│   └── utils.clj        # IO aliases
├── test/kcx/
│   ├── workflow_test.clj     # State machine tests
│   ├── orchestrator_test.clj # Orchestrator tests
│   ├── expand_test.clj       # Expansion engine tests
│   ├── journal_test.clj      # Journal & counter tests
│   └── state_test.clj        # Memory bank tests
├── commands/
│   └── kcx.md           # /kcx slash command (copy to ~/.claude/commands/)
├── playground/          # Test environment
└── logs/                # Session logs
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `kcx_command` | Execute DSL commands or natural language prompts |

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the full version history.

**Latest: v0.8.0** — Parent-driven workflows, prompt journal & suggestor agent, architect plan-only, two-tier expansions.
