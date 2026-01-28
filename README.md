\**Project state: WIP*\*


# KCX: Knowledge Context eXchange

**Stack:** Clojure | Babashka | MCP Protocol | EDN State

## Overview

KCX is a Multi-Agent MCP Server that transforms how you collaborate with AI on software engineering tasks. It provides a dense DSL for expressing intent and orchestrates specialized agents through structured workflows.

### Core Principles

- **Terminal-First:** No context switching - work stays in your IDE/terminal
- **Persistent State:** Context lives in EDN files (Memory Bank), not chat history
- **Dense Input:** Symbols (`!`, `@`, `+`, `-`) maximize intent per keystroke
- **Structured Output:** Agents follow deterministic workflows with handoffs

## Architecture

```
User Terminal
    │
    ▼
┌─────────────────────────────────────────┐
│           Babashka MCP Server           │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  │
│  │   DSL   │  │  State  │  │  Agent  │  │
│  │ Parser  │  │ Manager │  │ Router  │  │
│  └─────────┘  └─────────┘  └─────────┘  │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│          Orchestrated Workflow          │
│                                         │
│   WORKER ──► REVIEWER ──► CURATOR       │
│      │          │            │          │
│   (code)    (verify)    (memory)        │
└─────────────────────────────────────────┘
```

### Agents

| Agent | Role | Responsibilities |
|-------|------|------------------|
| **Controller** | Router | Task routing, project management |
| **Worker** | Developer | Code generation, file operations |
| **Reviewer** | QA | Code review, validation, approval |
| **Curator** | Librarian | Memory bank updates, context management |
| **Architect** | Designer | System design, specifications |
| **Tester** | QA Engineer | TDD, test coverage |

## DSL Syntax

```
kcx !verb @target +include -exclude >output &agent
```

| Symbol | Purpose | Example |
|--------|---------|---------|
| `!` | Verb (action) | `!fix`, `!gen`, `!debug` |
| `@` | Target (file) | `@calculator.clj` |
| `+` | Include constraint | `+error-handling` |
| `-` | Exclude constraint | `-println` |
| `>` | Output redirect | `>output.clj` |
| `&` | Agent preference | `&reviewer` |

### Verbs

| Category | Verbs |
|----------|-------|
| Project | `status`, `proj`, `list` |
| Code | `gen`, `create`, `edit`, `fix`, `build`, `debug` |
| Testing | `test`, `tdd` |
| Review | `review`, `check`, `lint` |

### Examples

```bash
kcx !status                           # Check project status
kcx !fix @calculator.clj +error-handling
kcx !gen @utils.clj +async -blocking
kcx !debug @api.clj +logging -println
kcx !review @core.clj &reviewer
```

## State Management

KCX uses EDN (Extensible Data Notation) for persistent state - the "Memory Bank".

```clojure
{:meta {:version "1.0"
        :created "2025-01-13T..."}

 :stack {:language "Clojure"
         :framework "Babashka"}

 :active-context {:task "Current task description"
                  :status "in-progress"}

 :memory [{:action "fix"
           :target "calculator.clj"
           :priority :high
           :timestamp "2025-01-13T..."}]}
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `kcx_command` | Execute DSL commands, triggers agent workflows |
| `read_state` | Read project state (memory bank) |
| `write_file` | Write content to files |

## Quick Start

### Prerequisites

- [Babashka](https://github.com/babashka/babashka) (bb)

### Installation

```bash
git clone <repo-url>
cd kcx
chmod +x kcx.clj
```

### Running

```bash
# Start MCP server (for Claude Code integration)
./kcx.clj
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

## Agent Spawning

KCX spawns independent Claude instances for worker and reviewer agents. These run in isolated environments to avoid conflicts with the parent Claude session.

### How It Works

When executing workflows, KCX:
1. Uses `env -i` to create a clean environment (no inherited vars)
2. Passes only essential variables: `PATH`, `HOME`, `ANTHROPIC_API_KEY`
3. Spawns Claude CLI with `--print` mode for non-interactive execution
4. Restricts tools and permissions for safety

### Configuration

Customize agent behavior via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `KCX_WORKER_MODEL` | `claude-sonnet-4-20250514` | Model for worker/reviewer agents |
| `KCX_WORKER_TOOLS` | `Read,Write,Edit,Glob,Grep` | Tools available to agents |
| `KCX_PERMISSION_MODE` | `bypassPermissions` | Permission mode (`bypassPermissions` for autonomous, `acceptEdits` for prompts) |
| `KCX_MAX_ITERATIONS` | `3` | Max worker retries on rejection |
| `KCX_TEST_CMD` | `bb -m test-runner` | Command to run tests (for TDD workflow) |
| `CLAUDE_PATH` | Auto-detected | Path to Claude CLI binary |
| `KCX_HOME` | `~/kcx` | KCX installation directory |

### Example Configurations

**Default (Autonomous):**
```bash
# Agents run autonomously - required for non-interactive operation
export KCX_PERMISSION_MODE="bypassPermissions"
export KCX_WORKER_TOOLS="Read,Write,Edit,Glob,Grep"
```

**With Bash Access:**
```bash
# Add Bash for agents that need shell commands
export KCX_PERMISSION_MODE="bypassPermissions"
export KCX_WORKER_TOOLS="Read,Write,Edit,Glob,Grep,Bash"
```

**Read-Only Reviewer:**
```bash
# For review-only workflows
export KCX_WORKER_TOOLS="Read,Glob,Grep"
```

### Requirements

- Claude CLI installed and in PATH (or set `CLAUDE_PATH`)
- Valid `ANTHROPIC_API_KEY` environment variable
- Babashka (bb) for running the MCP server

## Testing

```bash
./test_kcx.clj
```

## Project Structure

```
kcx/
├── kcx.clj              # Entry point (Babashka script)
├── src/kcx/
│   ├── core.clj         # MCP server & request handling
│   ├── dsl.clj          # DSL command parser
│   ├── agents.clj       # Agent definitions & routing
│   ├── orchestrator.clj # Workflow execution
│   ├── state.clj        # State management
│   ├── logging.clj      # Session logging
│   └── utils.clj        # Utilities
├── playground/          # Test environment
├── logs/                # Session logs
└── memory-bank/         # Project state files
```

## Workflow

KCX supports two execution modes:

### Autonomous Mode (Default)

When you run a command like `kcx !fix @calculator.clj +error-handling`:

1. **DSL Parser** extracts verb, target, and constraints
2. **Agent Router** determines which agent handles the command
3. **Orchestrator** spawns autonomous sub-agents
4. **Worker** executes the task (with rejection loops if needed)
5. **Reviewer** validates the changes
6. **Curator** updates the memory bank

The entire workflow runs to completion without manual intervention.

### Workflow Types

| Command Type | Workflow | Agents |
|--------------|----------|--------|
| `!fix`, `!gen`, `!edit`, etc. | Worker Flow | WORKER → TESTER → REVIEWER → CURATOR |
| `!test`, `!tdd` | TDD Flow | TESTER (write) → WORKER → TESTER (validate) → REVIEWER → CURATOR |
| `!plan`, `!design`, `!arch` | Architect Flow | ARCHITECT → WORKER → TESTER → REVIEWER → CURATOR |

### Worker → Tester Validation Loop

All workflows include a Worker → Tester validation loop:

1. **Worker** performs the task
2. **Tester** validates with tests
   - If tests fail → feedback to Worker, retry (up to `KCX_MAX_ITERATIONS`)
   - If tests pass → proceed to Reviewer
3. **Reviewer** validates the implementation
   - If rejected → back to step 1 (Worker → Tester cycle)
   - If approved → Curator

This ensures all code changes have test coverage before review.

### Manual Mode (Debugging)

For step-by-step execution, the orchestrator can return XML-tagged instructions:

```xml
<handoff task="uuid" to="reviewer"/>
<done task="uuid"/>
```

This mode is useful for debugging workflows.
