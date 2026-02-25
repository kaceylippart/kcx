# KCX: Knowledge Context eXchange

**Stack:** Clojure | Babashka | MCP Protocol | EDN State

> Dense input. Autonomous agents. Persistent memory.

## Overview

KCX is a multi-agent MCP server that orchestrates AI workflows through a data-driven state machine. It provides a dense DSL for expressing intent and routes commands through specialized agents that run to completion without manual intervention.

### Core Principles

- **Brevity** — Symbols (`!`, `@`, `+`, `-`) maximize intent per keystroke
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

```
kcx !verb @target +include -exclude >output &agent "instruction"
```

| Symbol | Purpose | Example |
|--------|---------|---------|
| `!` | Verb (action) | `!fix`, `!gen`, `!debug` |
| `@` | Target (file) | `@calculator.clj` |
| `+` | Include constraint | `+error-handling` |
| `-` | Exclude constraint | `-println` |
| `>` | Output redirect | `>output.clj` |
| `&` | Agent preference | `&reviewer` |
| `"..."` | Natural language | `"add retry logic"` |

Natural language works standalone too:

```bash
kcx "add error handling to the calculator"
```

### Verbs

| Category | Verbs |
|----------|-------|
| Project | `status`, `proj`, `list` |
| Code | `gen`, `create`, `edit`, `fix`, `build`, `debug` |
| Testing | `test`, `tdd` |
| Review | `review`, `check`, `lint` |
| Repeat | `redo` (re-run last command with modifications) |

### Examples

```bash
kcx !status                                              # Project status
kcx !fix @calculator.clj +error-handling                 # Fix with constraint
kcx !gen +web-api "build a campaign list endpoint"       # Generate with instruction
kcx !tdd @utils.clj                                      # TDD workflow
kcx !redo -docstrings "don't modify foo.clj"             # Re-run with changes
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
{:meta {:version "0.5.0" :created "2025-01-13T..."}
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
├── src/kcx/
│   ├── core.clj         # MCP server & request handling
│   ├── dsl.clj          # DSL parser
│   ├── workflow.clj     # State machine definitions & executor
│   ├── orchestrator.clj # Command dispatch & result formatting
│   ├── worker.clj       # Agent spawning, prompts, handlers
│   ├── agents.clj       # Agent type definitions
│   ├── state.clj        # Memory bank (EDN)
│   ├── logging.clj      # Session logging
│   └── utils.clj        # Utilities
├── test/kcx/
│   ├── workflow_test.clj     # State machine tests
│   └── orchestrator_test.clj # Orchestrator tests
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
