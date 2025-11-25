(ns kcx.agents
  "Agent system for KC-X multi-tiered architecture")

;; Agent types in the KC-X multi-tiered system
(def agent-types
  #{:controller      ; High-level coordination, routing, project management
    :memory-manager  ; EDN state updates, memory management, context shifts
    :coder-builder   ; Code implementation, file operations, change reporting
    :reviewer})      ; Quality assurance, requirement validation, approval gate

;; Task status types
(def task-statuses
  #{:pending       ; Task created, not yet started
    :in-progress   ; Agent is working on it
    :needs-review  ; Waiting for reviewer approval
    :approved      ; Reviewer approved, ready to execute
    :completed     ; Task fully finished
    :failed        ; Task failed with error
    :rejected})    ; Reviewer rejected the changes

;; Message types for inter-agent communication
(def message-types
  #{:route-command     ; Controller -> Other agents: route this command
    :implement-task    ; Controller -> CoderBuilder: implement this task
    :review-changes    ; CoderBuilder -> Reviewer: please review these changes
    :update-memory     ; Any -> MemoryManager: update state with this info
    :approval-request  ; Any -> Reviewer: approve this action
    :approval-response ; Reviewer -> Any: approval granted/denied
    :state-update      ; MemoryManager -> All: state has been updated
    :task-complete     ; Any -> Controller: task finished
    :error-report})    ; Any -> Controller: something went wrong

;; Agent router determines which agent should handle a command
(defn route-command
  "Determine the primary agent for a DSL command"
  [{:keys [verb]}]
  (case verb
    ;; Project and high-level coordination -> Controller
    ("proj" "switch" "plan" "status") :controller

    ;; Memory and state management -> MemoryManager
    ("remember" "forget" "context" "priority") :memory-manager

    ;; Code generation and modification -> CoderBuilder
    ("gen" "create" "edit" "refactor" "fix" "build" "test" "run") :coder-builder

    ;; Quality assurance and validation -> Reviewer
    ("review" "check" "validate" "approve" "lint") :reviewer

    ;; Default to CoderBuilder for unknown commands
    :coder-builder))

(defn requires-workflow?
  "Determine if a command requires multi-agent workflow"
  [{:keys [verb]}]
  ;; Commands that typically require collaboration between agents
  (contains? #{"gen" "create" "edit" "refactor" "fix" "build"} verb))

;; Agent capabilities and system prompts
(def agent-capabilities
  {:controller
   {:description "High-level project coordination and task routing"
    :system-prompt
    "You are the Controller Agent in the KC-X multi-agent system.
Your responsibilities:
1. Analyze DSL commands and create execution plans
2. Route tasks to appropriate specialized agents
3. Coordinate multi-agent workflows
4. Provide project status and high-level guidance
5. Handle project management and context switching

Focus on strategic planning and coordination rather than implementation details."}

   :memory-manager
   {:description "EDN state management and project memory"
    :system-prompt
    "You are the Memory Manager Agent in the KC-X multi-agent system.
Your responsibilities:
1. Update and maintain EDN state files
2. Track project decisions and context
3. Manage task priorities and memory
4. Handle context switching between projects
5. Preserve project history and learning

Focus on maintaining accurate state and providing context to other agents."}

   :coder-builder
   {:description "Code implementation and file operations"
    :system-prompt
    "You are the CoderBuilder Agent in the KC-X multi-agent system.
Your responsibilities:
1. Implement code changes based on Controller plans
2. Generate, modify, and refactor code files
3. Handle file operations and project structure
4. Execute builds, tests, and development tasks
5. Report implementation progress and issues

Focus on practical implementation while following the execution plan."}

   :reviewer
   {:description "Quality assurance and requirement validation"
    :system-prompt
    "You are the Reviewer Agent in the KC-X multi-agent system.
Your responsibilities:
1. Review all code changes before finalization
2. Validate requirements and constraints
3. Ensure code quality and standards compliance
4. Approve or reject proposed changes
5. Provide constructive feedback and improvements

Focus on quality, correctness, and adherence to project requirements."}})

(defn get-system-prompt
  "Get the system prompt for a specific agent type"
  [agent-type]
  (get-in agent-capabilities [agent-type :system-prompt]))

(defn create-agent-task
  "Create a new agent task"
  [command agent-type]
  (let [now (str (java.time.Instant/now))]
    {:id (str (java.util.UUID/randomUUID))
     :assigned-agent agent-type
     :status :pending
     :original-command command
     :created-at now
     :updated-at now
     :result nil
     :requires-review (= agent-type :coder-builder)}))

(defn create-agent-message
  "Create an inter-agent message"
  [from-agent to-agent message-type payload & {:keys [context requires-response]}]
  {:from-agent from-agent
   :to-agent to-agent
   :message-type message-type
   :payload payload
   :context context
   :requires-response (boolean requires-response)})

(defn update-task-status
  "Update the status of an agent task"
  [task new-status & {:keys [result]}]
  (-> task
      (assoc :status new-status
             :updated-at (str (java.time.Instant/now)))
      (cond-> result (assoc :result result))))