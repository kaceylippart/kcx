(ns kcx.agents
  "Agent system definitions and capabilities for KC-X"
  (:require
    [clojure.string :as str]))


;; --- AGENT TYPES ---
(def agent-types
  #{:controller ; High-level coordination, routing
    :curator    ; EDN state updates, memory management (The Librarian)
    :architect  ; System design, planning
    :worker     ; Code implementation (The Builder)
    :reviewer   ; Quality assurance, validation
    :tester})   ; TDD and verification

;; --- ROUTING ---
(defn route-command
  "Determine the primary agent for a DSL command"
  [{:keys [verb]}]
  (case verb
    ;; Project management
    ("proj" "switch" "status" "list" "new") :controller

    ;; Memory management
    ("remember" "forget" "context" "priority" "save" "clean") :curator

    ;; Architecture
    ("design" "architect" "plan" "arch") :architect

    ;; Implementation
    ("gen" "create" "edit" "refactor" "fix" "build" "run" "code") :worker

    ;; Testing
    ("test" "tdd") :tester

    ;; Review
    ("review" "check" "validate" "approve" "lint") :reviewer

    ;; Default
    :controller))


(defn requires-workflow?
  "Determine if a command requires multi-agent workflow"
  [{:keys [verb]}]
  (contains? #{"gen" "create" "edit" "refactor" "fix" "build" "test" "tdd"} verb))


;; --- CAPABILITIES & PROMPTS ---
(def agent-capabilities
  {:controller
   {:role "Mission Controller"
    :description "High-level project coordination and task routing"
    :system-prompt
    "ROLE: Mission Controller.
     GOAL: Analyze ambiguous requests and route them to specialists.
     PROTOCOL:
     1. STARTUP: If user mentions project names, use 'switch_project'.
     2. ROUTING: Route technical tasks to :architect, :worker, or :tester.
     3. STATUS: If asked for status/list, check registry or state.
     TOOL USE: 'switch_project', 'handoff'."}

   :curator
   {:role "Context Curator"
    :description "EDN state management and project memory"
    :system-prompt
    "ROLE: Context Curator (Librarian).
     GOAL: Maintain 'kcx_state.edn' as the Single Source of Truth.
     PROTOCOL:
     1. ANALYZE the conversation/task results.
     2. UPDATE :active-context (task status, files).
     3. APPEND to :memory (decisions, lessons).
     4. PRUNE completed tasks.
     TOOL USE: You MUST use 'update_state'."}

   :architect
   {:role "System Architect"
    :description "System design and planning"
    :system-prompt
    "ROLE: System Architect.
     GOAL: High-level design and planning. Do NOT write implementation code.
     PROTOCOL:
     1. ANALYZE requirements.
     2. CREATE a plan/spec (Markdown).
     3. IDENTIFY necessary files.
     4. HANDOFF to :worker to execute.
     TOOL USE: 'write_file' (docs), 'handoff'."}

   :worker
   {:role "Senior Developer"
    :description "Code implementation and file operations"
    :system-prompt
    "ROLE: Senior Developer.
     GOAL: Implement logic to satisfy requirements.
     PROTOCOL:
     1. READ: Understand existing code via 'read_file' or 'list_files'.
     2. IMPLEMENT: Write efficient, clean code.
     3. VERIFY: Ensure code compiles/runs if possible.
     4. SUBMIT: Handoff to :reviewer when done.
     TOOL USE: 'read_file', 'write_file', 'run_shell', 'handoff'."}

   :tester
   {:role "QA Engineer"
    :description "Test Driven Development"
    :system-prompt
    "ROLE: QA Engineer (TDD).
     GOAL: Ensure quality via tests.
     PROTOCOL:
     1. PRE-CODE: Write failing tests.
     2. VERIFY: Run tests using 'run_shell'.
     3. LOOP: If fail, handoff to :worker.
     4. SUCCESS: If pass, handoff to :reviewer.
     TOOL USE: 'write_file' (tests), 'run_shell', 'handoff'."}

   :reviewer
   {:role "Lead Reviewer"
    :description "Quality assurance and approval"
    :system-prompt
    "ROLE: Lead Code Reviewer.
     GOAL: Enforce standards and correctness.
     PROTOCOL:
     1. AUDIT: Read code changes from Worker.
     2. CRITIQUE: Check for bugs/security.
     3. REJECT: Handoff back to :worker with fixes.
     4. APPROVE: Handoff to :curator to close task.
     TOOL USE: 'read_file', 'handoff'."}})


(defn get-system-prompt
  [agent-type]
  (get-in agent-capabilities [agent-type :system-prompt]))


;; --- VALIDATION (Moved from templates.clj) ---
(defn validate-agent-output
  "Validate that agent followed their constraints"
  [agent-key output]
  (case agent-key
    :worker
    (or (str/includes? output "write_file")
        (str/includes? output "handoff")
        {:error "Worker must write code or handoff"})

    :architect
    (or (str/includes? output "write_file")
        (str/includes? output "handoff")
        {:error "Architect must document plan or handoff"})

    :curator
    (or (str/includes? output "update_state")
        {:error "Curator must use update_state"})

    :reviewer
    (or (str/includes? output "handoff")
        {:error "Reviewer must use handoff to proceed"})

    ;; Default pass
    true))


;; --- TASK HELPERS ---
(defn create-agent-task
  [command agent-type]
  (let [now (str (java.time.Instant/now))]
    {:id (str (java.util.UUID/randomUUID))
     :assigned-agent agent-type
     :status :pending
     :original-command command
     :created-at now
     :updated-at now}))


(defn update-task-status
  [task new-status & {:keys [result]}]
  (-> task
      (assoc :status new-status :updated-at (str (java.time.Instant/now)))
      (cond-> result (assoc :result result))))
