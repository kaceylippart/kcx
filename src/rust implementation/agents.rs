use crate::dsl::DslCommand;
use serde::{Deserialize, Serialize};
use std::fmt;

/// Agent types in the KC-X multi-tiered system
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum AgentType {
    Controller,   // High-level coordination, routing, project management
    MemoryManager, // KDL state updates, memory management, context shifts
    CoderBuilder, // Code implementation, file operations, change reporting
    Reviewer,     // Quality assurance, requirement validation, approval gate
}

impl fmt::Display for AgentType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AgentType::Controller => write!(f, "Controller"),
            AgentType::MemoryManager => write!(f, "MemoryManager"),
            AgentType::CoderBuilder => write!(f, "CoderBuilder"),
            AgentType::Reviewer => write!(f, "Reviewer"),
        }
    }
}

/// Agent communication message structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentMessage {
    pub from_agent: AgentType,
    pub to_agent: AgentType,
    pub message_type: MessageType,
    pub payload: String,
    pub context: Option<String>,
    pub requires_response: bool,
}

/// Types of messages agents can send to each other
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum MessageType {
    RouteCommand,      // Controller -> Other agents: route this command
    ImplementTask,     // Controller -> CoderBuilder: implement this task
    ReviewChanges,     // CoderBuilder -> Reviewer: please review these changes
    UpdateMemory,      // Any -> MemoryManager: update state with this info
    ApprovalRequest,   // Any -> Reviewer: approve this action
    ApprovalResponse,  // Reviewer -> Any: approval granted/denied
    StateUpdate,       // MemoryManager -> All: state has been updated
    TaskComplete,      // Any -> Controller: task finished
    ErrorReport,       // Any -> Controller: something went wrong
}

/// Agent task status tracking
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentTask {
    pub id: String,
    pub assigned_agent: AgentType,
    pub status: TaskStatus,
    pub original_command: DslCommand,
    pub created_at: String,
    pub updated_at: String,
    pub result: Option<String>,
    pub requires_review: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TaskStatus {
    Pending,      // Task created, not yet started
    InProgress,   // Agent is working on it
    NeedsReview,  // Waiting for reviewer approval
    Approved,     // Reviewer approved, ready to execute
    Completed,    // Task fully finished
    Failed,       // Task failed with error
    Rejected,     // Reviewer rejected the changes
}

impl fmt::Display for TaskStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TaskStatus::Pending => write!(f, "Pending"),
            TaskStatus::InProgress => write!(f, "InProgress"),
            TaskStatus::NeedsReview => write!(f, "NeedsReview"),
            TaskStatus::Approved => write!(f, "Approved"),
            TaskStatus::Completed => write!(f, "Completed"),
            TaskStatus::Failed => write!(f, "Failed"),
            TaskStatus::Rejected => write!(f, "Rejected"),
        }
    }
}

/// Agent router determines which agent should handle a command
pub struct AgentRouter;

impl AgentRouter {
    /// Determine the primary agent for a DSL command
    pub fn route_command(cmd: &DslCommand) -> AgentType {
        match cmd.verb.as_str() {
            // Project and high-level coordination -> Controller
            "proj" | "switch" | "plan" | "status" => AgentType::Controller,

            // Memory and state management -> MemoryManager
            "remember" | "forget" | "context" | "priority" => AgentType::MemoryManager,

            // Code implementation -> CoderBuilder
            "gen" | "create" | "edit" | "refactor" | "fix" | "build" => AgentType::CoderBuilder,

            // Review and validation -> Reviewer
            "review" | "check" | "validate" | "approve" => AgentType::Reviewer,

            // Default: Controller handles routing
            _ => AgentType::Controller,
        }
    }

    /// Determine if a command requires multi-agent workflow
    pub fn requires_workflow(cmd: &DslCommand) -> bool {
        match cmd.verb.as_str() {
            // Simple commands that can be handled by single agent
            "proj" | "status" | "ls" | "read" => false,

            // Complex commands that need Controller -> CoderBuilder -> Reviewer -> MemoryManager
            "gen" | "edit" | "refactor" | "fix" | "create" => true,

            // Memory updates go directly to MemoryManager
            "remember" | "forget" | "context" => false,

            // Reviews go directly to Reviewer
            "review" | "validate" => false,

            _ => true, // Default to workflow for unknown commands
        }
    }
}

/// Agent capabilities and prompt templates
pub struct AgentCapabilities;

impl AgentCapabilities {
    /// Get the system prompt for a specific agent type
    pub fn get_system_prompt(agent_type: &AgentType) -> String {
        match agent_type {
            AgentType::Controller => {
                r#"You are the KC-X Controller Agent. Your role is high-level coordination and agent orchestration.

RESPONSIBILITIES:
- Parse and route DSL commands to appropriate specialized agents
- Manage project-level concerns and context switching
- Coordinate multi-step workflows between agents
- Handle project management commands (proj, switch, plan, status)
- Ensure tasks flow properly through the agent pipeline

OUTPUT FORMAT:
- For routing: Specify which agent should handle the command and why
- For coordination: Provide clear instructions for the workflow
- Always maintain awareness of project context and priorities"#.to_string()
            },

            AgentType::MemoryManager => {
                r#"You are the KC-X Memory Manager Agent. Your role is state management and memory updates.

RESPONSIBILITIES:
- Update kcx_state.kdl with new information, decisions, and context changes
- Manage memory relevance - remove outdated or irrelevant items
- Handle priority changes and context shifts
- Interpret feedback from other agents and update state accordingly
- Maintain the active_context, memory, and stack sections

OUTPUT FORMAT:
- Always output valid KDL for state updates
- Provide clear reasoning for memory changes
- Flag when context shifts require attention"#.to_string()
            },

            AgentType::CoderBuilder => {
                r#"You are the KC-X Coder/Builder Agent. Your role is code implementation and file operations.

RESPONSIBILITIES:
- Generate, modify, and refactor code files based on DSL commands
- Execute file system operations (create, read, write, delete)
- Report changes back in structured format for review
- Follow constraints from DSL (+includes, -excludes)
- Implement the Auto-Gardener Protocol with proper XML output

OUTPUT FORMAT:
<file path="src/example.rs">
// Generated code here
</file>

[CHANGE REPORT]
- Created: src/example.rs
- Modified: None
- Reasoning: Generated new Rust module as requested

Always include detailed change reports for the Reviewer."#.to_string()
            },

            AgentType::Reviewer => {
                r#"You are the KC-X Reviewer Agent. Your role is quality assurance and requirement validation.

RESPONSIBILITIES:
- Review all CoderBuilder output before finalization
- Ensure changes meet requirements and constraints from DSL commands
- Validate code quality, style, and best practices
- Check that +includes are present and -excludes are avoided
- Approve or reject changes with detailed feedback

OUTPUT FORMAT:
[REVIEW DECISION: APPROVED/REJECTED]

REASONING:
- Requirement compliance: ✓/✗
- Code quality: ✓/✗
- Constraint adherence: ✓/✗
- Overall assessment: [detailed feedback]

You are the final quality gate before file system modifications."#.to_string()
            }
        }
    }

    /// Get available tools for each agent type
    pub fn get_available_tools(agent_type: &AgentType) -> Vec<String> {
        match agent_type {
            AgentType::Controller => vec![
                "route_to_agent".to_string(),
                "read_state".to_string(),
                "create_task".to_string(),
            ],
            AgentType::MemoryManager => vec![
                "read_state".to_string(),
                "update_state".to_string(),
                "validate_kdl".to_string(),
            ],
            AgentType::CoderBuilder => vec![
                "write_file".to_string(),
                "read_file".to_string(),
                "list_files".to_string(),
                "execute_command".to_string(),
            ],
            AgentType::Reviewer => vec![
                "read_file".to_string(),
                "validate_code".to_string(),
                "check_requirements".to_string(),
            ],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsl::DslCommand;

    #[test]
    fn test_agent_routing() {
        // Test Controller routing
        let proj_cmd = DslCommand::parse(":proj @myproject").unwrap();
        assert_eq!(AgentRouter::route_command(&proj_cmd), AgentType::Controller);

        // Test CoderBuilder routing
        let gen_cmd = DslCommand::parse(":gen @test.rs +rust").unwrap();
        assert_eq!(AgentRouter::route_command(&gen_cmd), AgentType::CoderBuilder);

        // Test MemoryManager routing
        let remember_cmd = DslCommand::parse(":remember +context").unwrap();
        assert_eq!(AgentRouter::route_command(&remember_cmd), AgentType::MemoryManager);

        // Test Reviewer routing
        let review_cmd = DslCommand::parse(":review @changes").unwrap();
        assert_eq!(AgentRouter::route_command(&review_cmd), AgentType::Reviewer);
    }

    #[test]
    fn test_workflow_requirements() {
        let simple_cmd = DslCommand::parse(":proj").unwrap();
        assert!(!AgentRouter::requires_workflow(&simple_cmd));

        let complex_cmd = DslCommand::parse(":gen @test.rs +async").unwrap();
        assert!(AgentRouter::requires_workflow(&complex_cmd));
    }

    #[test]
    fn test_agent_capabilities() {
        let controller_prompt = AgentCapabilities::get_system_prompt(&AgentType::Controller);
        assert!(controller_prompt.contains("Controller Agent"));

        let coder_tools = AgentCapabilities::get_available_tools(&AgentType::CoderBuilder);
        assert!(coder_tools.contains(&"write_file".to_string()));
    }
}