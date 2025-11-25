use crate::agents::{AgentRouter, AgentType, AgentCapabilities, AgentTask, TaskStatus};
use crate::dsl::DslCommand;
use std::collections::HashMap;
use uuid::Uuid;
use chrono;

/// Multi-agent workflow orchestrator for KC-X
pub struct AgentOrchestrator {
    active_tasks: HashMap<String, AgentTask>,
    agent_contexts: HashMap<AgentType, String>,
}

impl AgentOrchestrator {
    pub fn new() -> Self {
        Self {
            active_tasks: HashMap::new(),
            agent_contexts: HashMap::new(),
        }
    }

    /// Execute a DSL command using the multi-agent system
    pub fn execute_command(&mut self, cmd: &DslCommand, project_state: &str) -> String {
        // Step 1: Route command to appropriate agent
        let primary_agent = AgentRouter::route_command(cmd);
        let requires_workflow = AgentRouter::requires_workflow(cmd);

        if !requires_workflow {
            // Simple command - handle directly by single agent
            return self.execute_single_agent(cmd, primary_agent, project_state);
        }

        // Step 2: Create task and initiate multi-agent workflow
        let task_id = Uuid::new_v4().to_string();
        let task = AgentTask {
            id: task_id.clone(),
            assigned_agent: primary_agent.clone(),
            status: TaskStatus::Pending,
            original_command: (*cmd).clone(),
            created_at: chrono::Utc::now().to_rfc3339(),
            updated_at: chrono::Utc::now().to_rfc3339(),
            result: None,
            requires_review: true,
        };

        self.active_tasks.insert(task_id.clone(), task);

        // Step 3: Execute multi-agent workflow
        self.execute_workflow(&task_id, cmd, project_state)
    }

    /// Execute a single-agent command (no workflow required)
    fn execute_single_agent(&self, cmd: &DslCommand, agent_type: AgentType, project_state: &str) -> String {
        match agent_type {
            AgentType::Controller => self.handle_controller_command(cmd, project_state),
            AgentType::MemoryManager => self.handle_memory_command(cmd, project_state),
            AgentType::Reviewer => self.handle_review_command(cmd, project_state),
            AgentType::CoderBuilder => {
                // Even simple coder commands should go through review
                format!("AGENT_WORKFLOW_REQUIRED: Command '{}' requires CoderBuilder -> Reviewer workflow", cmd.verb)
            }
        }
    }

    /// Execute multi-agent workflow: Controller -> CoderBuilder -> Reviewer -> MemoryManager
    fn execute_workflow(&mut self, task_id: &str, cmd: &DslCommand, project_state: &str) -> String {
        // Phase 1: Controller plans the work
        let controller_plan = self.create_controller_plan(cmd, project_state);

        // Phase 2: CoderBuilder implements
        let implementation_request = self.create_implementation_request(cmd, &controller_plan, project_state);

        // Phase 3: Set up for review workflow
        if let Some(task) = self.active_tasks.get_mut(task_id) {
            task.status = TaskStatus::NeedsReview;
            task.updated_at = chrono::Utc::now().to_rfc3339();
        }

        // Return the structured workflow request
        format!(
            r#"MULTI_AGENT_WORKFLOW_INITIATED:

TASK_ID: {}
PRIMARY_AGENT: CoderBuilder
STATUS: NeedsReview

=== CONTROLLER PLAN ===
{}

=== IMPLEMENTATION REQUEST ===
{}

=== NEXT STEPS ===
1. CoderBuilder will implement the changes
2. Reviewer will validate the implementation
3. MemoryManager will update project state
4. User will be prompted for final approval

Please execute this multi-agent workflow using your available tools."#,
            task_id, controller_plan, implementation_request
        )
    }

    /// Create controller planning phase
    fn create_controller_plan(&self, cmd: &DslCommand, project_state: &str) -> String {
        let system_prompt = AgentCapabilities::get_system_prompt(&AgentType::Controller);

        format!(
            r#"{}

CURRENT PROJECT STATE:
{}

DSL COMMAND TO EXECUTE:
- Verb: {}
- Target: {}
- Includes: {:?}
- Excludes: {:?}
- Redirect: {:?}
- Agent: {:?}

Please create a detailed execution plan for this command, considering:
1. What files need to be created/modified
2. What constraints must be followed (+includes, -excludes)
3. What the expected outcome should be
4. Any dependencies or prerequisites"#,
            system_prompt,
            project_state,
            cmd.verb,
            cmd.target,
            cmd.includes,
            cmd.excludes,
            cmd.redirect,
            cmd.agent
        )
    }

    /// Create implementation request for CoderBuilder
    fn create_implementation_request(&self, cmd: &DslCommand, controller_plan: &str, project_state: &str) -> String {
        let system_prompt = AgentCapabilities::get_system_prompt(&AgentType::CoderBuilder);

        format!(
            r#"{}

CONTROLLER PLAN:
{}

CURRENT PROJECT STATE:
{}

IMPLEMENTATION REQUIREMENTS:
- Target: {}
- Must include: {:?}
- Must exclude: {:?}
- Output to: {:?}

Please implement the requested changes following the controller's plan.
Use the XML file output format and provide a detailed change report."#,
            system_prompt,
            controller_plan,
            project_state,
            cmd.target,
            cmd.includes,
            cmd.excludes,
            cmd.redirect
        )
    }

    /// Handle Controller-specific commands
    fn handle_controller_command(&self, cmd: &DslCommand, project_state: &str) -> String {
        match cmd.verb.as_str() {
            "proj" => {
                // Project management is already handled in main.rs
                format!("REDIRECT_TO_PROJECT_HANDLER: {}", cmd.target)
            }
            "status" => {
                format!(
                    r#"KC-X PROJECT STATUS:

ACTIVE TASKS: {}
PROJECT STATE: {}

Current project context from kcx_state.kdl:
{}"#,
                    self.active_tasks.len(),
                    if project_state.is_empty() { "Not initialized" } else { "Active" },
                    project_state
                )
            }
            "plan" => {
                format!(
                    r#"KC-X PLANNING MODE:

Target: {}
Includes: {:?}
Excludes: {:?}

Use the multi-agent workflow to break this down into actionable steps."#,
                    cmd.target, cmd.includes, cmd.excludes
                )
            }
            _ => format!("CONTROLLER_COMMAND_NOT_RECOGNIZED: {}", cmd.verb),
        }
    }

    /// Handle Memory Manager commands
    fn handle_memory_command(&self, cmd: &DslCommand, project_state: &str) -> String {
        let system_prompt = AgentCapabilities::get_system_prompt(&AgentType::MemoryManager);

        format!(
            r#"{}

CURRENT STATE:
{}

MEMORY COMMAND:
- Action: {}
- Target: {}
- Context: {:?}

Please update the project memory accordingly and output valid KDL."#,
            system_prompt,
            project_state,
            cmd.verb,
            cmd.target,
            cmd.includes
        )
    }

    /// Handle Reviewer commands
    fn handle_review_command(&self, cmd: &DslCommand, _project_state: &str) -> String {
        let system_prompt = AgentCapabilities::get_system_prompt(&AgentType::Reviewer);

        format!(
            r#"{}

REVIEW REQUEST:
- Target: {}
- Criteria: {:?}
- Avoid: {:?}

Please review the specified target and provide approval/rejection with detailed feedback."#,
            system_prompt,
            cmd.target,
            cmd.includes,
            cmd.excludes
        )
    }

    /// Get active task status
    pub fn get_task_status(&self, task_id: &str) -> Option<&AgentTask> {
        self.active_tasks.get(task_id)
    }

    /// Update task status
    pub fn update_task_status(&mut self, task_id: &str, status: TaskStatus, result: Option<String>) {
        if let Some(task) = self.active_tasks.get_mut(task_id) {
            task.status = status;
            task.updated_at = chrono::Utc::now().to_rfc3339();
            if let Some(res) = result {
                task.result = Some(res);
            }
        }
    }

    /// Get summary of all active tasks
    pub fn get_active_tasks_summary(&self) -> String {
        if self.active_tasks.is_empty() {
            return "No active tasks".to_string();
        }

        let mut summary = String::from("ACTIVE TASKS:\n");
        for (id, task) in &self.active_tasks {
            summary.push_str(&format!(
                "- {} [{}]: {} ({})\n",
                &id[..8], // Short ID
                task.status,
                task.original_command.verb,
                task.assigned_agent
            ));
        }
        summary
    }
}

impl Default for AgentOrchestrator {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsl::DslCommand;

    #[test]
    fn test_orchestrator_creation() {
        let orchestrator = AgentOrchestrator::new();
        assert_eq!(orchestrator.active_tasks.len(), 0);
    }

    #[test]
    fn test_single_agent_command() {
        let orchestrator = AgentOrchestrator::new();
        let cmd = DslCommand::parse(":status").unwrap();
        let result = orchestrator.execute_single_agent(&cmd, AgentType::Controller, "test_state");
        assert!(result.contains("KC-X PROJECT STATUS"));
    }

    #[test]
    fn test_workflow_initiation() {
        let mut orchestrator = AgentOrchestrator::new();
        let cmd = DslCommand::parse(":gen @test.rs +rust").unwrap();
        let result = orchestrator.execute_command(&cmd, "test_state");
        assert!(result.contains("MULTI_AGENT_WORKFLOW_INITIATED"));
        assert_eq!(orchestrator.active_tasks.len(), 1);
    }
}