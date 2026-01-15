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

When you run a command like `kcx !fix @calculator.clj +error-handling`:

1. **DSL Parser** extracts verb, target, and constraints
2. **Agent Router** determines which agent handles the command
3. **Orchestrator** returns structured instructions
4. **Worker** executes the task
5. **Reviewer** validates the changes
6. **Curator** updates the memory bank

Each handoff uses XML tags for deterministic execution:

```xml
<handoff task="uuid" to="reviewer"/>
<done task="uuid"/>
```
