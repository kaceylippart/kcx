# KCX - Knowledge Context eXchange

This project is a KCX MCP server. You have access to KCX tools via MCP.

## CRITICAL: KCX Command Routing

**When the user's message starts with `kcx `, you MUST call the `mcp__kcx__kcx_command` tool.**

Do NOT interpret these as regular prompts. Do NOT use your built-in tools. Route them directly to KCX.

### Command Format

```
kcx !<verb> @<target> +<include> -<exclude> ><output> &<agent>
```

### Examples

| User Input | Action |
|------------|--------|
| `kcx !status` | Call `mcp__kcx__kcx_command` with `{"command": "kcx !status"}` |
| `kcx !fix @calculator.clj +error-handling` | Call `mcp__kcx__kcx_command` with `{"command": "kcx !fix @calculator.clj +error-handling"}` |
| `kcx !debug @api.clj +logging -println` | Call `mcp__kcx__kcx_command` with `{"command": "kcx !debug @api.clj +logging -println"}` |

### DSL Symbols

| Symbol | Meaning | Example |
|--------|---------|---------|
| `!` | Verb (action) | `!fix`, `!gen`, `!debug` |
| `@` | Target (file) | `@calculator.clj` |
| `+` | Include constraint | `+error-handling` |
| `-` | Exclude constraint | `-println` |
| `>` | Output redirect | `>output.clj` |
| `&` | Agent preference | `&reviewer` |

### Verbs

| Verb | Purpose |
|------|---------|
| `status`, `proj`, `list` | Project management |
| `gen`, `create`, `edit`, `fix`, `build`, `debug` | Code implementation |
| `test`, `tdd` | Testing |
| `review`, `check`, `lint` | Code review |

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

**MINIMAL OUTPUT.** Just status lines:
```
→ WORKER FIX @calculator.clj
→ REVIEWER APPROVED
→ CURATOR Memory updated
✓ DONE
```

## Project Structure

```
kcx/
├── src/kcx/          # MCP server source
├── playground/       # Test environment
├── logs/             # Session logs
└── memory-bank/      # Project state files
```
