use kdl::KdlDocument;
use crate::dsl::DslCommand;

pub fn compile_system_prompt(state: &KdlDocument, cmd: &DslCommand) -> String {
    // 1. Extract Stack Info
    let mut stack_info = String::new();
    if let Some(stack) = state.get("stack") {
        if let Some(children) = stack.children() {
            for node in children.nodes() {
                // FIX: Use .entries().get(0) to access the first positional argument safely
                let val = node.entries()
                    .get(0)
                    .and_then(|e| e.value().as_string())
                    .unwrap_or("?");
                    
                stack_info.push_str(&format!("- {}: {}\n", node.name(), val));
            }
        }
    }

    // 2. Extract Context Info
    let mut context_info = String::new();
    if let Some(ctx) = state.get("active_context") {
        if let Some(children) = ctx.children() {
            for node in children.nodes() {
                // FIX: Same here
                let val = node.entries()
                    .get(0)
                    .and_then(|e| e.value().as_string())
                    .unwrap_or("");
                    
                context_info.push_str(&format!("- {}: {}\n", node.name(), val));
            }
        }
    }

    // 3. Handle Optional Fields
    let redirect_instr = match &cmd.redirect {
        Some(path) => format!("**OVERRIDE:** Do NOT edit the original target. Write output to: '{}'", path),
        None => String::new(),
    };

    let agent_instr = match &cmd.agent {
        Some(agent) => format!("**ROLE:** You are acting as the '{}' agent. Adopt this persona strictly.", agent),
        None => String::new(),
    };

    // 4. Construct the Prompt
    format!(r#"
# SYSTEM ROLE: KC-X ENGINE
You are a State-Driven Execution Engine. You do not chat. You EXECUTE.
{agent}

# CURRENT STATE (Read-Only)
[Stack]
{stack}

[Active Context]
{context}

# USER COMMAND
Action: {verb}
Target: {target}
{redirect}
Constraints:
  Include: {includes:?}
  Exclude: {excludes:?}

# PROTOCOL
1. Execute the command strictly adhering to constraints.
2. **CRITICAL:** To create or edit a file, you MUST use this EXACT XML format. 
   Do NOT use markdown code blocks (```).
   
   <file path="path/to/file.ext">
   ... content ...
   </file>

3. Output a code block labelled [KC-X UPDATE] with updated KDL state.
"#, 
    stack = stack_info,
    context = context_info,
    verb = cmd.verb,
    target = cmd.target,
    includes = cmd.includes,
    excludes = cmd.excludes,
    redirect = redirect_instr,
    agent = agent_instr
    )
}