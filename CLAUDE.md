# KCX - Knowledge Context eXchange

Multi-agent MCP server for AI workflow orchestration. Dense DSL input, autonomous agent pipelines, persistent memory bank.

## Using KCX

Use the `/kcx` slash command to run KCX commands:

```
/kcx !fix @calculator.clj
/kcx !review @calc.clj +thorough
/kcx !build a new auth feature
/kcx !explain @workflow.clj
/kcx !help
```

See `/kcx !help` for the full DSL reference and available verbs.

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
├── test/kcx/            # Unit tests (bb test)
├── playground/           # Test environment for agent runs
└── logs/                # Session logs
```

## Development

- **Runtime**: Babashka (`bb`), not Leiningen
- **Tests**: `bb test` — runs all test namespaces listed in `bb.edn`
- **MCP server caches code at startup** — restart after code changes (expansion dictionaries reload each command)
