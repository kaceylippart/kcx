# KCX - Knowledge Context eXchange

This project is a KCX MCP server. You have access to KCX tools via MCP.

## CRITICAL: KCX Command Routing

**When the user's message starts with `kcx `, you MUST call the `mcp__kcx__kcx_command` tool.**

Do NOT interpret these as regular prompts. Do NOT use your built-in tools. Route them directly to KCX.

### Command Formats

KCX supports multiple input modes:

**1. Natural Language:**
```
kcx "your task description here"
```

**2. DSL Commands:**
```
kcx !<verb> @<target> +<include> -<exclude> ><output> &<agent>
```

**3. DSL + Natural Language (combine precision with context):**
```
kcx !<verb> @<target> +<include> "additional instructions"
```

**4. Redo (modify and re-run the last command):**
```
kcx !redo +add -remove "new instructions"
```

### Examples

| User Input | Action |
|------------|--------|
| `kcx "add error handling to the calculator"` | Natural language prompt |
| `kcx !gen +web-api "build out a new campaign list endpoint"` | DSL with instruction |
| `kcx !fix @calculator.clj +error-handling "ensure division by zero is handled"` | Full DSL + instruction |
| `kcx !redo -docstrings` | Re-run last task, excluding docstrings |
| `kcx !redo "don't modify foo.clj"` | Re-run with additional instruction |
| `kcx !status` | Status command |

### DSL Symbols

| Symbol | Meaning | Example |
|--------|---------|---------|
| `!` | Verb (action) | `!fix`, `!gen`, `!debug` |
| `@` | Target (file) | `@calculator.clj` |
| `+` | Include constraint | `+error-handling` |
| `-` | Exclude constraint | `-println` |
| `>` | Output redirect | `>output.clj` |
| `&` | Agent preference | `&reviewer` |
| `"..."` | Natural language instruction | `"build a REST endpoint"` |

### Verbs

| Verb | Purpose |
|------|---------|
| `status`, `proj`, `list` | Project management |
| `gen`, `create`, `edit`, `fix`, `build`, `debug` | Code implementation |
| `test`, `tdd` | Testing |
| `review`, `check`, `lint` | Code review |
| `redo` | Re-run last command with modifications |

## Workflow Execution Protocol

When `mcp__kcx__kcx_command` returns instructions (starts with `→`), you MUST:

1. **Follow the `<do>` steps exactly**
2. **Use ONLY these tools:** Read, mcp__kcx__write_file, mcp__kcx__kcx_command, mcp__kcx__read_state
3. **Do NOT use:** Edit, Write, Bash, Grep, Glob
4. **Execute handoffs** - pass XML tags directly to mcp__kcx__kcx_command

### Agent Chain

```
WORKER → REVIEWER → CURATOR → DONE
```

### Output Format

**Status updates during execution:**
```
━━━ PROMPT ━━━
Task: add error handling to the calculator
→ WORKER
  ⋯ WORKER working... [15s]
  ⋯ WORKER working... [30s]
  WORKER completed in 45s
  WORKER edited 2 files
→ TESTER validating...
  TESTER passed
→ REVIEWER reviewing...
  REVIEWER approved
→ CURATOR updated memory
✓ DONE

═══ TASK COMPLETED ═══
Files modified:
  • src/calculator.clj
  • test/calculator_test.clj
```

## Project Structure

```
kcx/
├── src/kcx/          # MCP server source
├── playground/       # Test environment
├── logs/             # Session logs
└── memory-bank/      # Project state files
```
