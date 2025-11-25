use serde::{Deserialize, Serialize};
use serde_json::json;
use std::fs;
use std::io::{self, BufRead};

mod dsl;
mod kdl_schema;
mod agents;
mod orchestrator;
mod symbol_resolver;
use dsl::DslCommand;
use kdl_schema::MemoryFileValidator;
use agents::AgentRouter;
use orchestrator::AgentOrchestrator;
use symbol_resolver::{SymbolResolver, ConflictLevel};
use std::sync::{Arc, Mutex};
use lazy_static::lazy_static;

lazy_static! {
    static ref ORCHESTRATOR: Arc<Mutex<AgentOrchestrator>> = Arc::new(Mutex::new(AgentOrchestrator::new()));
}

#[derive(Serialize, Deserialize, Debug)]
struct JsonRpcRequest {
    jsonrpc: String,
    method: String,
    params: Option<serde_json::Value>,
    id: Option<serde_json::Value>,
}

fn main() {
    eprintln!("🔌 KC-X MCP Server Starting...");

    let stdin = io::stdin();
    for line in stdin.lock().lines() {
        let input = line.unwrap();
        if input.is_empty() {
            continue;
        }

        if let Ok(req) = serde_json::from_str::<JsonRpcRequest>(&input) {
            handle_request(req);
        } else {
            eprintln!("❌ Failed to parse JSON");
        }
    }
}

fn handle_request(req: JsonRpcRequest) {
    match req.method.as_str() {
        "initialize" => {
            send_response(
                req.id,
                json!({
                    "protocolVersion": "2024-11-05",
                    "capabilities": { "tools": {} },
                    "serverInfo": { "name": "kcx", "version": "1.0" }
                }),
            );
        }
        "notifications/initialized" => {}
        "tools/list" => {
            send_response(
                req.id,
                json!({
                    "tools": [
                        {
                               "name": "kcx_command",
                                 "description": "Execute a KC-X DSL command. Supports both traditional and Claude-safe syntax:

TRADITIONAL: ':gen @file.rs +async -unwrap'
CLAUDE-SAFE: 'kcx:gen file:main.rs with:async not:unwrap'
RAW MODE: 'raw: :gen @file.rs +async -unwrap'

Use this for any command that looks like KC-X DSL syntax.",
                                 "inputSchema": {
                                "type": "object",
                                "properties": {
                                    "command": { "type": "string" }
                                },
                                "required": ["command"]
                            }
                        },
                        {
                            "name": "read_state",
                            "description": "Read the KDL state file to understand project context.",
                            "inputSchema": { "type": "object", "properties": {} }
                        },
                        {
                            "name": "update_state",
                            "description": "Overwrite kcx_state.kdl with new content. Use this to update tasks/memory.",
                            "inputSchema": {
                                "type": "object",
                                "properties": {
                                    "kdl": { "type": "string", "description": "The full valid KDL content" }
                                },
                                "required": ["kdl"]
                            }
                        },
                        {
                            "name": "kcx_help",
                            "description": "Get KC-X syntax help and Claude-safe alternatives for symbol conflicts.",
                            "inputSchema": {
                                "type": "object",
                                "properties": {
                                    "topic": {
                                        "type": "string",
                                        "enum": ["syntax", "symbols", "agents", "examples", "all"],
                                        "description": "Help topic to display"
                                    }
                                },
                                "required": []
                            }
                        },
                        {
                            "name": "write_file",
                            "description": "Write content to a file.",
                            "inputSchema": {
                                "type": "object",
                                "properties": {
                                    "path": { "type": "string" },
                                    "content": { "type": "string" }
                                },
                                "required": ["path", "content"]
                            }
                        }
                    ]
                }),
            );
        }
        "tools/call" => {
            let params = req.params.unwrap();
            let name = params["name"].as_str().unwrap();
            let args = &params["arguments"];

            eprintln!("🚀 Tool Call: {}", name);

            let result = match name {
                "kcx_command" => {
                    let cmd = args["command"].as_str().unwrap_or("");
                    execute_dsl(cmd)
                }
                "kcx_help" => {
                    let topic = args.get("topic")
                        .and_then(|t| t.as_str())
                        .unwrap_or("all");
                    get_kcx_help(topic)
                }
                "read_state" => {
                    let state_file = get_current_state_file();
                    match fs::read_to_string(&state_file) {
                        Ok(content) => {
                            // Validate the existing content
                            match MemoryFileValidator::validate(&content) {
                                Ok(_) => content,
                                Err(_) => {
                                    // Try to migrate legacy format
                                    match MemoryFileValidator::migrate_legacy_format(&content) {
                                        Ok(migrated) => {
                                            eprintln!("🔄 Migrated legacy KDL format");
                                            // Save the migrated format
                                            let _ = fs::write(&state_file, &migrated);
                                            migrated
                                        }
                                        Err(e) => {
                                            eprintln!("⚠️  KDL validation failed: {}", e);
                                            MemoryFileValidator::create_template()
                                        }
                                    }
                                }
                            }
                        }
                        Err(_) => {
                            eprintln!("📝 Creating new KDL state file from template");
                            let template = MemoryFileValidator::create_template();
                            let _ = fs::write(&state_file, &template);
                            template
                        }
                    }
                }
                "update_state" => {
                    let kdl = args["kdl"].as_str().unwrap_or("");
                    let state_file = get_current_state_file();

                    // Validate the KDL before writing
                    match MemoryFileValidator::validate(kdl) {
                        Ok(_) => {
                            fs::write(&state_file, kdl).expect("Write failed");
                            "✅ State updated successfully with valid KDL schema.".to_string()
                        }
                        Err(e) => {
                            eprintln!("⚠️  KDL validation failed during update: {}", e);
                            // Try to migrate/fix the format
                            match MemoryFileValidator::migrate_legacy_format(kdl) {
                                Ok(migrated) => {
                                    fs::write(&state_file, &migrated).expect("Write failed");
                                    format!(
                                        "🔄 State updated with automatic migration: {}",
                                        e.message
                                    )
                                }
                                Err(migration_error) => {
                                    format!(
                                        "❌ KDL validation failed: {}. Migration also failed: {}",
                                        e.message, migration_error.message
                                    )
                                }
                            }
                        }
                    }
                }
                "write_file" => {
                    let path = args["path"].as_str().unwrap();
                    let content = args["content"].as_str().unwrap();
                    if let Some(parent) = std::path::Path::new(path).parent() {
                        fs::create_dir_all(parent).ok();
                    }
                    fs::write(path, content).expect("Write failed");
                    format!("Wrote to {}", path)
                }
                _ => "Unknown tool".to_string(),
            };

            send_response(
                req.id,
                json!({
                    "content": [{ "type": "text", "text": result }],
                    "isError": false
                }),
            );
        }
        _ => {}
    }
}

fn send_response(id: Option<serde_json::Value>, result: serde_json::Value) {
    let response = json!({ "jsonrpc": "2.0", "id": id, "result": result });
    println!("{}", response);
}

/// Get KC-X help information based on topic
fn get_kcx_help(topic: &str) -> String {
    match topic {
        "syntax" | "symbols" => SymbolResolver::get_syntax_help(),
        "agents" => r#"KC-X MULTI-AGENT SYSTEM:

=== AGENT TYPES ===
🎯 Controller Agent
   - High-level coordination and project management
   - Routes commands to appropriate agents
   - Handles: proj, status, plan commands

🧠 Memory Manager Agent
   - Updates KDL state and project memory
   - Manages priorities and context shifts
   - Handles: remember, forget, context commands

⚡ Coder/Builder Agent
   - Code implementation and file operations
   - Generates, modifies, and refactors code
   - Handles: gen, create, edit, refactor, fix commands

🔍 Reviewer Agent
   - Quality assurance and requirement validation
   - Reviews all code changes before finalization
   - Handles: review, check, validate, approve commands

=== WORKFLOW ===
Complex tasks flow through: Controller → CoderBuilder → Reviewer → MemoryManager
Simple tasks go directly to the appropriate specialized agent.
"#.to_string(),
        "examples" => r#"KC-X COMMAND EXAMPLES:

=== TRADITIONAL SYNTAX ===
:gen @auth.rs +async +serde -unwrap > @auth_tests.rs &reviewer
:edit @main.rs +logging -println
:refactor @utils.rs +clean > @utils_v2.rs
:proj @myproject +init
:status

=== CLAUDE-SAFE SYNTAX ===
kcx:gen file:auth.rs with:async with:serde not:unwrap to:auth_tests.rs as:reviewer
kx:edit %main.rs with:logging without:println
kcx:refactor file:utils.rs with:clean to:utils_v2.rs
proj:myproject with:init

=== RAW MODE (Bypass Claude interpretation) ===
raw: :gen @auth.rs +async -unwrap > @tests.rs
raw: !edit @main.rs +logging -println

=== MIXED SYNTAX ===
kx:gen file:auth.rs +async without:unwrap out:tests.rs agent:reviewer
kcx:edit %main.rs with:logging -println to:main_v2.rs
"#.to_string(),
        _ => format!(r#"KC-X HELP - Multi-Agent Development Assistant

KC-X is a Context Operating System that uses specialized AI agents
to handle complex software engineering workflows.

{}

{}

{}

=== QUICK START ===
1. Try: 'kcx:gen file:hello.rs with:main'
2. Check status: 'status'
3. Get project help: 'proj'
4. View syntax help: Use kcx_help tool with topic 'syntax'

=== CONFLICT-FREE USAGE ===
When Claude interprets symbols like @ or ! in unexpected ways:
- Use Claude-safe syntax: 'kcx:gen file:main.rs with:async'
- Use raw mode: 'raw: :gen @main.rs +async'
- Get syntax help: kcx_help tool

Built with ❤️ in Rust | Version 1.0 | Multi-Agent Architecture
"#,
            get_kcx_help("agents"),
            get_kcx_help("examples"),
            SymbolResolver::get_syntax_help()
        )
    }
}

/// Execute DSL command using the multi-agent orchestration system
fn execute_dsl(input: &str) -> String {
    // Step 1: Detect and resolve symbol conflicts
    let conflict_level = ConflictLevel::detect(input);
    let normalized_input = SymbolResolver::normalize_for_parsing(input);

    // Step 2: Handle help requests
    if input.trim() == "help" || input.trim() == ":help" || input.trim() == "syntax" {
        return SymbolResolver::get_syntax_help();
    }

    // Step 3: Parse the normalized command
    if let Some(cmd) = DslCommand::parse(&normalized_input) {
        // Handle project management commands directly (they don't need multi-agent workflow)
        if cmd.verb == "proj" {
            return handle_proj_command(&cmd);
        }

        // Get current project state for context
        let state_file = get_current_state_file();
        let project_state = match fs::read_to_string(&state_file) {
            Ok(content) => {
                match MemoryFileValidator::validate(&content) {
                    Ok(_) => content,
                    Err(_) => {
                        // Use template if invalid
                        MemoryFileValidator::create_template()
                    }
                }
            }
            Err(_) => MemoryFileValidator::create_template(),
        };

        // Use multi-agent orchestrator to execute the command
        match ORCHESTRATOR.lock() {
            Ok(mut orchestrator) => {
                let result = orchestrator.execute_command(&cmd, &project_state);

                // Add routing information for debugging
                let primary_agent = AgentRouter::route_command(&cmd);
                let requires_workflow = AgentRouter::requires_workflow(&cmd);

                let conflict_info = match conflict_level {
                    ConflictLevel::None => "✅ No symbol conflicts detected".to_string(),
                    ConflictLevel::Low => format!("⚠️ Minor conflicts resolved. Safe alternative: {}",
                        SymbolResolver::recommend_syntax(input, ConflictLevel::Low)),
                    ConflictLevel::High => format!("🔧 Major conflicts resolved. Safe alternative: {}",
                        SymbolResolver::recommend_syntax(input, ConflictLevel::High)),
                };

                format!(
                    r#"KC-X MULTI-AGENT EXECUTION:

SYMBOL CONFLICT ANALYSIS:
{}

PARSED COMMAND:
- Original: {}
- Normalized: {}
- Verb: {}
- Target: {}
- Includes: {:?}
- Excludes: {:?}
- Redirect: {:?}
- Agent Preference: {:?}

ROUTING DECISION:
- Primary Agent: {}
- Requires Multi-Agent Workflow: {}

EXECUTION RESULT:
{}

=== ACTIVE TASKS ===
{}"#,
                    conflict_info,
                    input,
                    normalized_input,
                    cmd.verb,
                    cmd.target,
                    cmd.includes,
                    cmd.excludes,
                    cmd.redirect,
                    cmd.agent,
                    primary_agent,
                    requires_workflow,
                    result,
                    orchestrator.get_active_tasks_summary()
                )
            }
            Err(e) => {
                format!("❌ Orchestrator lock error: {}", e)
            }
        }
    } else {
        let conflict_level = ConflictLevel::detect(input);
        let help_text = match conflict_level {
            ConflictLevel::High => format!(
                "❌ Invalid DSL syntax with symbol conflicts detected.\n\nTry Claude-safe syntax:\n{}\n\nFor help: 'syntax' or 'help'",
                SymbolResolver::recommend_syntax(":gen file:example.rs with:constraint not:avoid to:output as:agent", ConflictLevel::High)
            ),
            _ => "❌ Invalid DSL syntax. Examples:\n  Traditional: ':gen @file.rs +constraint -avoid'\n  Claude-safe: 'kcx:gen file:example.rs with:constraint not:avoid'\n  Raw mode: 'raw: :gen @file.rs +constraint'\n\nFor help: 'syntax' or 'help'".to_string()
        };
        help_text
    }
}

// --- PROJECT MANAGEMENT FUNCTIONS ---

fn get_current_state_file() -> String {
    // Check if there's a current project set
    if let Ok(current_project) = fs::read_to_string(".kcx_current_project") {
        let project_name = current_project.trim();
        if !project_name.is_empty() && project_name != "global" {
            let project_file = format!("kcx_state_{}.kdl", project_name);
            if fs::metadata(&project_file).is_ok() {
                return project_file;
            }
        }
    }

    // Default to global state file
    "kcx_state.kdl".to_string()
}

fn set_current_project(project_name: &str) -> Result<(), std::io::Error> {
    if project_name == "global" {
        // Remove current project file to default to global
        let _ = fs::remove_file(".kcx_current_project");
        Ok(())
    } else {
        fs::write(".kcx_current_project", project_name)
    }
}

fn handle_proj_command(cmd: &DslCommand) -> String {
    match cmd.target.as_str() {
        "global_context" => {
            // No target specified - list available projects
            list_projects()
        }
        "global" => {
            // Switch to global project
            if let Err(e) = set_current_project("global") {
                format!("❌ Failed to switch to global project: {}", e)
            } else {
                "✅ Switched to global project (kcx_state.kdl)".to_string()
            }
        }
        project_name => {
            // Target specified - create/switch to project
            let should_init = cmd.includes.contains(&"init".to_string());
            create_or_switch_project(project_name, should_init)
        }
    }
}

fn list_projects() -> String {
    // Get current project
    let current_project = fs::read_to_string(".kcx_current_project")
        .unwrap_or_else(|_| "global".to_string())
        .trim()
        .to_string();

    match fs::read_dir(".") {
        Ok(entries) => {
            let mut projects = Vec::new();

            // Add global project
            if fs::metadata("kcx_state.kdl").is_ok() {
                let marker = if current_project == "global" {
                    " 👈 current"
                } else {
                    ""
                };
                projects.push(format!("global (kcx_state.kdl){}", marker));
            }

            // Find project-specific state files
            for entry in entries.flatten() {
                if let Some(name) = entry.file_name().to_str() {
                    if name.starts_with("kcx_state_") && name.ends_with(".kdl") {
                        let project_name = name
                            .strip_prefix("kcx_state_")
                            .unwrap()
                            .strip_suffix(".kdl")
                            .unwrap();
                        let marker = if project_name == current_project {
                            " 👈 current"
                        } else {
                            ""
                        };
                        projects.push(format!("{} ({}){}", project_name, name, marker));
                    }
                }
            }

            if projects.is_empty() {
                "📋 No kcx projects found. Use ':proj @project_name' to create one.".to_string()
            } else {
                format!("📋 Available kcx projects:\n{}", projects.join("\n"))
            }
        }
        Err(_) => "❌ Failed to list projects".to_string(),
    }
}

fn create_or_switch_project(project_name: &str, force_init: bool) -> String {
    // Validate project name
    if !is_valid_project_name(project_name) {
        return "❌ Invalid project name. Use alphanumeric characters, underscores, and hyphens only.".to_string();
    }

    let filename = format!("kcx_state_{}.kdl", project_name);

    // Check if project exists
    let exists = fs::metadata(&filename).is_ok();

    if exists && !force_init {
        // Switch to existing project
        if let Err(e) = set_current_project(project_name) {
            eprintln!("⚠️  Failed to set current project: {}", e);
        }
        format!(
            "✅ Switched to existing project '{}' at {}. Use '+init' to reinitialize.",
            project_name, filename
        )
    } else {
        // Create new project state file
        let template = MemoryFileValidator::create_template();

        // Customize template for the project
        let project_template = template.replace(
            r#"active_context {
    task "New Task"
    status "Ready to begin"
}"#,
            &format!(
                r#"active_context {{
    task "Project: {}"
    status "Project initialized and ready to begin"
}}"#,
                project_name
            ),
        );

        match fs::write(&filename, &project_template) {
            Ok(_) => {
                // Set this as the current project
                if let Err(e) = set_current_project(project_name) {
                    eprintln!("⚠️  Failed to set current project: {}", e);
                }

                if exists {
                    format!(
                        "🔄 Reinitialized and switched to project '{}' at {}",
                        project_name, filename
                    )
                } else {
                    format!(
                        "✨ Created and switched to project '{}' at {}",
                        project_name, filename
                    )
                }
            }
            Err(e) => format!("❌ Failed to create project file: {}", e),
        }
    }
}

fn is_valid_project_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 50
        && name
            .chars()
            .all(|c| c.is_alphanumeric() || c == '_' || c == '-')
        && !name.starts_with('-')
        && !name.ends_with('-')
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs;
    use std::io::Write;
    use tempfile::NamedTempFile;

    // ============================================================================
    // DSL PARSING TESTS
    // ============================================================================

    #[test]
    fn test_dsl_basic_verb_parsing() {
        let cmd = DslCommand::parse("gen").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "global_context");
        assert!(cmd.includes.is_empty());
        assert!(cmd.excludes.is_empty());
        assert!(cmd.redirect.is_none());
        assert!(cmd.agent.is_none());
    }

    #[test]
    fn test_dsl_with_colon_prefix() {
        let cmd = DslCommand::parse(":gen @test.rs").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
    }

    #[test]
    fn test_dsl_with_exclamation_prefix() {
        let cmd = DslCommand::parse("!run @main.rs").unwrap();
        assert_eq!(cmd.verb, "run");
        assert_eq!(cmd.target, "main.rs");
    }

    #[test]
    fn test_dsl_with_slash_prefix() {
        let cmd = DslCommand::parse("/help @docs").unwrap();
        assert_eq!(cmd.verb, "help");
        assert_eq!(cmd.target, "docs");
    }

    #[test]
    fn test_dsl_with_target() {
        let cmd = DslCommand::parse("gen @test.rs").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
    }

    #[test]
    fn test_dsl_with_complex_target() {
        let cmd = DslCommand::parse("gen @src/lib/module.rs").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "src/lib/module.rs");
    }

    #[test]
    fn test_dsl_with_includes() {
        let cmd = DslCommand::parse("gen @test.rs +rust +testing").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
        assert_eq!(cmd.includes, vec!["rust", "testing"]);
    }

    #[test]
    fn test_dsl_with_excludes() {
        let cmd = DslCommand::parse("gen @code.rs -deprecated -old").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "code.rs");
        assert_eq!(cmd.excludes, vec!["deprecated", "old"]);
    }

    #[test]
    fn test_dsl_with_redirect() {
        let cmd = DslCommand::parse("gen @test.rs > output.txt").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
        assert_eq!(cmd.redirect, Some("output.txt".to_string()));
    }

    #[test]
    fn test_dsl_with_redirect_at_symbol() {
        let cmd = DslCommand::parse("gen @test.rs > @results.md").unwrap();
        assert_eq!(cmd.redirect, Some("results.md".to_string()));
    }

    #[test]
    fn test_dsl_with_agent() {
        let cmd = DslCommand::parse("gen @test.rs &coder").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
        assert_eq!(cmd.agent, Some("coder".to_string()));
    }

    #[test]
    fn test_dsl_complex_command() {
        let cmd = DslCommand::parse(":gen @test.rs +rust +async -deprecated > @output.md &tester")
            .unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
        assert_eq!(cmd.includes, vec!["rust", "async"]);
        assert_eq!(cmd.excludes, vec!["deprecated"]);
        assert_eq!(cmd.redirect, Some("output.md".to_string()));
        assert_eq!(cmd.agent, Some("tester".to_string()));
    }

    #[test]
    fn test_dsl_no_target_defaults_to_global_context() {
        let cmd = DslCommand::parse("help +docs").unwrap();
        assert_eq!(cmd.verb, "help");
        assert_eq!(cmd.target, "global_context");
        assert_eq!(cmd.includes, vec!["docs"]);
    }

    #[test]
    fn test_dsl_whitespace_handling() {
        let cmd = DslCommand::parse("   :  gen   @test.rs   +rust   ").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test.rs");
        assert_eq!(cmd.includes, vec!["rust"]);
    }

    #[test]
    fn test_dsl_invalid_command() {
        let result = DslCommand::parse("@@@invalid");
        assert!(result.is_none());
    }

    #[test]
    fn test_dsl_empty_command() {
        let result = DslCommand::parse("");
        assert!(result.is_none());
    }

    // ============================================================================
    // JSON-RPC REQUEST TESTS
    // ============================================================================

    #[test]
    fn test_jsonrpc_request_deserialization() {
        let json_str = r#"
        {
            "jsonrpc": "2.0",
            "method": "initialize",
            "params": {},
            "id": 1
        }
        "#;

        let req: JsonRpcRequest = serde_json::from_str(json_str).unwrap();
        assert_eq!(req.jsonrpc, "2.0");
        assert_eq!(req.method, "initialize");
        assert_eq!(req.id, Some(json!(1)));
    }

    #[test]
    fn test_jsonrpc_request_without_params() {
        let json_str = r#"
        {
            "jsonrpc": "2.0",
            "method": "tools/list",
            "id": 2
        }
        "#;

        let req: JsonRpcRequest = serde_json::from_str(json_str).unwrap();
        assert_eq!(req.method, "tools/list");
        assert!(req.params.is_none());
    }

    #[test]
    fn test_jsonrpc_request_without_id() {
        let json_str = r#"
        {
            "jsonrpc": "2.0",
            "method": "notifications/initialized"
        }
        "#;

        let req: JsonRpcRequest = serde_json::from_str(json_str).unwrap();
        assert_eq!(req.method, "notifications/initialized");
        assert!(req.id.is_none());
    }

    // ============================================================================
    // DSL EXECUTION TESTS
    // ============================================================================

    #[test]
    fn test_execute_dsl_valid_command() {
        let result = execute_dsl(":gen @test.rs +rust");
        assert!(result.contains("KC-X MULTI-AGENT EXECUTION:"));
        assert!(result.contains("Verb: gen"));
        assert!(result.contains("Target: test.rs"));
        assert!(result.contains("Primary Agent: CoderBuilder"));
    }

    #[test]
    fn test_execute_dsl_invalid_command() {
        let result = execute_dsl("@@@ !!! ### invalid");
        assert!(result.contains("Invalid DSL syntax"));
        assert!(result.contains("symbol conflicts detected"));
        assert!(result.contains("Claude-safe syntax"));
        assert!(result.contains("For help"));
    }

    #[test]
    fn test_execute_dsl_complex_command() {
        let result = execute_dsl(":gen @main.rs +rust +async -old > @output.txt &coder");
        assert!(result.contains("KC-X MULTI-AGENT EXECUTION:"));
        assert!(result.contains("Verb: gen"));
        assert!(result.contains("Target: main.rs"));
        assert!(result.contains("Includes: [\"rust\", \"async\"]"));
        assert!(result.contains("Excludes: [\"old\"]"));
        assert!(result.contains("Primary Agent: CoderBuilder"));
    }

    // ============================================================================
    // FILE OPERATIONS TESTS (for write_file functionality)
    // ============================================================================

    #[test]
    fn test_write_file_creates_directory() {
        let temp_dir = tempfile::tempdir().unwrap();
        let file_path = temp_dir.path().join("subdir").join("test.txt");
        let content = "Hello, World!";

        // Simulate the write_file tool logic
        if let Some(parent) = file_path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(&file_path, content).unwrap();

        assert!(file_path.exists());
        let read_content = fs::read_to_string(&file_path).unwrap();
        assert_eq!(read_content, content);
    }

    #[test]
    fn test_write_file_overwrites_existing() {
        let mut temp_file = NamedTempFile::new().unwrap();
        write!(temp_file, "Original content").unwrap();

        let file_path = temp_file.path();
        fs::write(file_path, "New content").unwrap();

        let content = fs::read_to_string(file_path).unwrap();
        assert_eq!(content, "New content");
    }

    // ============================================================================
    // STATE FILE TESTS
    // ============================================================================

    #[test]
    fn test_read_state_file_exists() {
        let temp_file = NamedTempFile::new().unwrap();
        let kdl_content = r#"
project "TestProject" {
    task "implement_feature" {
        status "in_progress"
        description "Implementing new feature"
    }
}
        "#;

        fs::write(temp_file.path(), kdl_content).unwrap();

        // Simulate reading from the actual file path
        let content = fs::read_to_string(temp_file.path()).unwrap();
        assert!(content.contains("TestProject"));
        assert!(content.contains("implement_feature"));
    }

    #[test]
    fn test_read_state_file_not_exists() {
        let non_existent_path = "/tmp/non_existent_kcx_state.kdl";
        let default_content = "meta project=\"New\"";

        // Simulate the logic from read_state tool
        let content =
            fs::read_to_string(non_existent_path).unwrap_or_else(|_| default_content.to_string());

        assert_eq!(content, default_content);
    }

    // ============================================================================
    // INTEGRATION TESTS
    // ============================================================================

    #[test]
    fn test_dsl_command_roundtrip() {
        let original_command = ":gen @test.rs +rust +async -deprecated > @output.md &tester";
        let parsed = DslCommand::parse(original_command).unwrap();
        let executed = execute_dsl(original_command);

        // Verify parsing worked correctly
        assert_eq!(parsed.verb, "gen");
        assert_eq!(parsed.target, "test.rs");
        assert_eq!(parsed.includes, vec!["rust", "async"]);
        assert_eq!(parsed.excludes, vec!["deprecated"]);
        assert_eq!(parsed.redirect, Some("output.md".to_string()));
        assert_eq!(parsed.agent, Some("tester".to_string()));

        // Verify execution produces multi-agent workflow output
        assert!(executed.contains("KC-X MULTI-AGENT EXECUTION:"));
        assert!(executed.contains("Primary Agent: CoderBuilder"));
    }

    #[test]
    fn test_error_handling_malformed_json() {
        let malformed_json = r#"{ "jsonrpc": "2.0", "method": "test" "#;
        let result = serde_json::from_str::<JsonRpcRequest>(malformed_json);
        assert!(result.is_err());
    }

    #[test]
    fn test_dsl_edge_cases() {
        // Test with multiple spaces and mixed prefixes
        let cases = vec![
            ("gen", "gen", "global_context"),
            (":gen", "gen", "global_context"),
            ("!gen", "gen", "global_context"),
            ("/gen", "gen", "global_context"),
            ("   gen   ", "gen", "global_context"),
        ];

        for (input, expected_verb, expected_target) in cases {
            let cmd = DslCommand::parse(input).unwrap();
            assert_eq!(cmd.verb, expected_verb);
            assert_eq!(cmd.target, expected_target);
        }
    }

    // ============================================================================
    // PERFORMANCE/STRESS TESTS
    // ============================================================================

    #[test]
    fn test_large_dsl_command() {
        let large_includes: Vec<String> = (0..100).map(|i| format!("+tag{}", i)).collect();
        let large_excludes: Vec<String> = (0..100).map(|i| format!("-old{}", i)).collect();

        let mut command = String::from("gen @large_file.rs ");
        command.push_str(&large_includes.join(" "));
        command.push(' ');
        command.push_str(&large_excludes.join(" "));

        let cmd = DslCommand::parse(&command).unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "large_file.rs");
        assert_eq!(cmd.includes.len(), 100);
        assert_eq!(cmd.excludes.len(), 100);
    }

    #[test]
    fn test_multiple_dsl_parsing() {
        let commands = vec![
            ":gen @test1.rs +rust",
            "!run @main.rs -debug",
            "/help @docs.md +markdown > @output.html",
            "analyze @code.rs +performance &optimizer",
        ];

        for cmd_str in commands {
            let cmd = DslCommand::parse(cmd_str);
            assert!(cmd.is_some(), "Failed to parse: {}", cmd_str);
        }
    }

    #[test]
    fn test_hyphen_in_target_name() {
        let cmd = DslCommand::parse(":gen @test-project +rust").unwrap();
        assert_eq!(cmd.verb, "gen");
        assert_eq!(cmd.target, "test-project");
        assert_eq!(cmd.includes, vec!["rust"]);
        assert!(
            cmd.excludes.is_empty(),
            "Excludes should be empty but was: {:?}",
            cmd.excludes
        );
    }
}
