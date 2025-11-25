use kdl::KdlDocument;
use crate::dsl::DslCommand;

pub fn compile_system_prompt(state: &KdlDocument, cmd: &DslCommand) -> String {
    // 1. Extract Stack Info
    let mut stack_info = String::new();
    if let Some(stack) = state.get("stack") {
        if let Some(children) = stack.children() {
            for node in children.nodes() {
                let val = node.entries()
                    .get(0)
                    .and_then(|e| e.value().as_string()) // Fix 1
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
                let val = node.entries()
                    .get(0)
                    .and_then(|e| e.value().as_string()) // Fix 2
                    .unwrap_or("");
                context_info.push_str(&format!("- {}: {}\n", node.name(), val));
            }
        }
    }

    // 3. Extract Memory (The History)
    let mut memory_info = String::new();
    if let Some(mem) = state.get("memory") {
        if let Some(children) = mem.children() {
            for node in children.nodes() {
                // Content (Positional arg 0)
                let content = node.entries()
                    .get(0)
                    .and_then(|e| e.value().as_string()) // Fix 3
                    .unwrap_or("");
                
                // Date (Property "date")
                // THIS WAS THE ERROR: Added .value() before .as_string()
                let date = node.get("date")
                    .and_then(|e| e.value().as_string()) 
                    .unwrap_or("?");
                    
                memory_info.push_str(&format!("- {}: \"{}\" (Date: {})\n", node.name(), content, date));
            }
        }
    }

    // 4. Handle Optional Fields
    let redirect_instr = match &cmd.redirect {
        Some(path) => format!("**OVERRIDE:** Do NOT edit the original target. Write output to: '{}'", path),
        None => String::new(),
    };

    let agent_instr = match &cmd.agent {
        Some(agent) => format!("**ROLE:** You are acting as the '{}' agent. Adopt this persona strictly.", agent),
        None => String::new(),
    };

    // 5. Construct the Prompt
    format!(r#"
# SYSTEM ROLE: KC-X ENGINE
You are a State-Driven Execution Engine. You do not chat. You EXECUTE.
{agent}

# CURRENT STATE (Read-Only)
[Stack]
{stack}

[Active Context]
{context}

[Memory Bank]
{memory}

# USER COMMAND
Action: {verb}
Target: {target}
{redirect}
Constraints:
  Include: {includes:?}
  Exclude: {excludes:?}

# PROTOCOL
1. **EXECUTE:** Perform the action. Adhere to constraints strictly.

2. **FILE OPS:** To create/edit files, use this EXACT XML format:
   <file path="path/to/file.ext">
   ... content ...
   </file>

3. **STATE MANAGEMENT (CRITICAL):**
   You must output a [KC-X UPDATE] block containing the FULL KDL state.
   
   - `meta` & `stack`: PRESERVE existing values.
   - `active_context`: OVERWRITE. Update `task`, `status`.
   - `memory`: **APPEND-ONLY.** You MUST copy the [Memory Bank] items above into your new state, then ADD your new decisions. Do not drop old history.

   Example Update:
   [KC-X UPDATE]
   ```kdl
   meta ...
   stack {{ ... }}
   active_context {{ ... }}
   memory {{
       decision "Old Decision" date="..." // Copied from input
       decision "New Decision" date="..." // Added by you
   }}
       "#, 
       stack = stack_info, 
       context = context_info, 
       memory = memory_info, // <--- Injected here! 
       verb = cmd.verb, 
       target = cmd.target, 
       includes = cmd.includes, 
       excludes = cmd.excludes, 
       redirect = redirect_instr, 
       agent = agent_instr)}
