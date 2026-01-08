# KCX Test Plan

## Overview

Systematic testing plan for the KCX (Knowledge Context eXchange) MCP server.

## Test Environment

- **Playground**: `/Users/kacey.lippart/kcx/playground/` - Simple Clojure calculator with a bug
- **Logs**: `/Users/kacey.lippart/kcx/logs/` - Session logs from each server run
- **Memory Bank**: `/Users/kacey.lippart/kcx/memory-bank/` - Project state EDN files
- **Reset**: `./playground/reset.sh` - Restores playground to initial state

## Test Categories

### 1. MCP Protocol Compliance

| Test | Command | Expected | Status |
|------|---------|----------|--------|
| 1.1 Initialize | `{"method":"initialize"}` | Returns protocolVersion, capabilities, serverInfo | ⬜ |
| 1.2 List Tools | `{"method":"tools/list"}` | Returns kcx_command, read_state, write_file tools | ⬜ |
| 1.3 Invalid Method | `{"method":"invalid"}` | Returns null/error gracefully | ⬜ |
| 1.4 Malformed JSON | `{not valid json}` | Logs error, doesn't crash | ⬜ |

### 2. Tool: read_state

| Test | Input | Expected | Status |
|------|-------|----------|--------|
| 2.1 Default State | No prior state file | Returns template state | ⬜ |
| 2.2 Existing State | After save_state | Returns saved state | ⬜ |
| 2.3 Project Switch | After proj command | Returns project-specific state | ⬜ |

### 3. Tool: write_file

| Test | Input | Expected | Status |
|------|-------|----------|--------|
| 3.1 Create File | path + content | File created, success message | ⬜ |
| 3.2 Create Nested | path/to/deep/file.txt | Parent dirs created | ⬜ |
| 3.3 Overwrite File | Existing path | File overwritten | ⬜ |

### 4. Tool: kcx_command

#### 4.1 DSL Parsing (Traditional Syntax)

| Test | Input | Expected Parse | Status |
|------|-------|----------------|--------|
| 4.1.1 Simple verb | `:gen` | `{:verb "gen"}` | ⬜ |
| 4.1.2 With target | `:gen @file.clj` | `{:verb "gen" :target "file.clj"}` | ⬜ |
| 4.1.3 With includes | `:gen +async +logging` | `{:includes ["async" "logging"]}` | ⬜ |
| 4.1.4 With excludes | `:gen -println -debug` | `{:excludes ["println" "debug"]}` | ⬜ |
| 4.1.5 Full command | `:gen @main.clj +async -debug > out.clj &reviewer` | All fields populated | ⬜ |

#### 4.2 DSL Parsing (Claude-Safe Syntax)

| Test | Input | Expected Parse | Status |
|------|-------|----------------|--------|
| 4.2.1 Basic | `kcx:gen` | `{:verb "gen"}` | ⬜ |
| 4.2.2 With file | `kcx:gen file:main.clj` | `{:target "main.clj"}` | ⬜ |
| 4.2.3 With constraints | `kcx:gen with:async not:debug` | includes/excludes populated | ⬜ |
| 4.2.4 kx prefix | `kx:edit file:core.clj` | Same as kcx: prefix | ⬜ |

#### 4.3 Agent Routing

| Test | Verb | Expected Agent | Status |
|------|------|----------------|--------|
| 4.3.1 Project commands | `proj`, `status`, `list` | :controller | ⬜ |
| 4.3.2 Memory commands | `save`, `remember`, `context` | :curator | ⬜ |
| 4.3.3 Design commands | `plan`, `arch`, `design` | :architect | ⬜ |
| 4.3.4 Code commands | `gen`, `edit`, `fix`, `build` | :worker | ⬜ |
| 4.3.5 Test commands | `test`, `tdd` | :tester | ⬜ |
| 4.3.6 Review commands | `review`, `check`, `lint` | :reviewer | ⬜ |

#### 4.4 Workflow Execution

| Test | Command | Expected | Status |
|------|---------|----------|--------|
| 4.4.1 Simple (no workflow) | `proj:myproject` | Direct execution, no task ID | ⬜ |
| 4.4.2 Workflow trigger | `kcx:gen file:test.clj` | Task created, workflow plan returned | ⬜ |

### 5. State Management

| Test | Action | Expected | Status |
|------|--------|----------|--------|
| 5.1 Create project | `proj:newproject` | State file created in ~/.kcx/projects/ | ⬜ |
| 5.2 Switch project | `proj:existing` | .kcx_current_project updated | ⬜ |
| 5.3 List projects | `list` | Shows all projects, marks current | ⬜ |
| 5.4 State persistence | Update + restart | State survives restart | ⬜ |

### 6. End-to-End Scenarios (Playground)

| Test | Scenario | Expected | Status |
|------|----------|----------|--------|
| 6.1 Read file | Ask agent to read calculator.clj | File contents returned | ⬜ |
| 6.2 Find bug | Ask to identify divide-by-zero bug | Bug identified | ⬜ |
| 6.3 Fix bug | Ask to fix the divide function | Code updated correctly | ⬜ |
| 6.4 Run tests | Ask to run tests | Tests execute | ⬜ |
| 6.5 Memory update | Complete task, check state | Memory updated with decision | ⬜ |

### 7. Error Handling

| Test | Scenario | Expected | Status |
|------|----------|----------|--------|
| 7.1 Invalid DSL | `kcx:` (no verb) | Graceful error, logged | ⬜ |
| 7.2 Missing file | write to /nonexistent/path | Error message, no crash | ⬜ |
| 7.3 Corrupt state file | Invalid EDN in state | Falls back to template | ⬜ |

## Running Tests

### Manual Testing via CLI

```bash
# Start fresh
cd /Users/kacey.lippart/kcx
./playground/reset.sh

# Test single request
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' | bb kcx.clj

# Test sequence
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"kcx_command","arguments":{"command":"kcx:status"}}}' | bb kcx.clj
```

### Testing via Claude Code

1. Restart Claude Code to connect to kcx MCP
2. Use `/mcp` to verify connection
3. Ask Claude to use kcx tools
4. Check logs in `/Users/kacey.lippart/kcx/logs/`

## Log Analysis

After each test, check:
1. Session log file in `logs/`
2. Request/response pairs
3. Tool call details
4. Any ERROR entries

```bash
# View latest log
cat $(ls -t /Users/kacey.lippart/kcx/logs/*.log | head -1)
```

## Test Status Legend

- ⬜ Not tested
- ✅ Passing
- ❌ Failing
- 🟡 Partial/needs investigation