# KCX Clojure Conversion Design

## Project Overview
Converting KC-X from Rust (.kdl) to Clojure (.edn) while maintaining all functionality and architecture.

## Architecture Mapping

### Core Components
1. **DSL Parser** (`dsl.rs` → `kcx/dsl.clj`)
2. **Multi-Agent Orchestrator** (`orchestrator.rs` → `kcx/orchestrator.clj`)
3. **Agent System** (`agents.rs` → `kcx/agents.clj`)
4. **State Management** (`kdl_schema.rs` → `kcx/state.clj`)
5. **Main MCP Server** (`main.rs` → `kcx/core.clj` + `kcx.clj`)

### Data Format Conversion

#### KDL → EDN State Structure
```clojure
;; KDL format:
;; meta { version "1.0" author "KC-X" }
;; stack { language "Rust" framework "Axum" }
;; active_context { task "..." status "..." }
;; memory { decision "..." date="..." }

;; EDN equivalent:
{:meta {:version "1.0" :author "KC-X"}
 :stack {:language "Rust" :framework "Axum"}
 :active-context {:task "..." :status "..."}
 :memory [{:decision "..." :date "..."}
          {:decision "..." :date "..."}]}
```

### Clojure Project Structure
```
kcx/
├── deps.edn                    ; Dependencies
├── kcx.clj                     ; Main babashka entry point
└── src/
    └── kcx/
        ├── core.clj            ; Main MCP server logic
        ├── dsl.clj             ; DSL command parsing
        ├── agents.clj          ; Agent types and routing
        ├── orchestrator.clj    ; Multi-agent workflow
        ├── state.clj           ; State management (KDL→EDN)
        └── utils.clj           ; Shared utilities
```

### Key Dependencies
- `cheshire` - JSON handling for MCP protocol
- `clojure.edn` - EDN state file management
- `clojure.string` - String processing
- `clojure.java.io` - File I/O operations

### Agent System Design
```clojure
;; Agent types (enum → keyword)
#{:controller :memory-manager :coder-builder :reviewer}

;; DSL Command structure
{:verb "gen"
 :target "main.rs"
 :includes ["async" "serde"]
 :excludes ["unwrap"]
 :redirect "output.txt"
 :agent "coder"}

;; Agent Task structure
{:id "uuid"
 :assigned-agent :coder-builder
 :status :pending  ; :pending :in-progress :needs-review :completed :failed
 :original-command {...}
 :created-at "2025-11-25T..."
 :updated-at "2025-11-25T..."
 :result nil
 :requires-review true}
```

### MCP Tools Conversion
1. `kcx_command` - Execute DSL commands
2. `read_state` - Read EDN state file
3. `update_state` - Update EDN state file
4. `kcx_help` - Show help information
5. `write_file` - Write files

### Implementation Plan
1. ✅ Core data structure conversion
2. ✅ State management (KDL→EDN)
3. ✅ DSL parser implementation
4. ✅ Agent system and routing
5. ✅ Multi-agent orchestrator
6. ✅ MCP server implementation
7. ✅ Babashka entry point
8. ✅ Testing and validation

## Key Differences from Rust Version
- Uses EDN instead of KDL for state files
- Leverages Clojure's immutable data structures
- Simpler regex-based parsing (no lazy_static needed)
- Function-based rather than struct-based architecture
- Babashka compatibility for zero-install execution