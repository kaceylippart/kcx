(ns kcx.agents
  "Agent system definitions and capabilities for KC-X"
  (:require
    [clojure.string :as str]))


(def agent-types
  #{:controller :curator :architect :worker :reviewer :tester})


(defn route-command
  "Determine the primary agent for a DSL command"
  [{:keys [verb]}]
  (case verb
    ("proj" "switch" "status" "list" "new" "init") :controller
    ("save" "clean" "remember" "forget" "context") :curator
    ("plan" "arch" "design" "analyze") :architect
    ("gen" "create" "edit" "refactor" "fix" "build" "code") :worker
    ("test" "tdd") :tester
    ("review" "check" "lint" "audit") :reviewer
    :controller))


(defn requires-workflow?
  "Determine if a command requires multi-agent workflow"
  [{:keys [verb]}]
  (contains? #{"gen" "create" "edit" "refactor" "fix" "build" "test" "tdd"} verb))


(def agent-capabilities
  {:controller
   {:role "Mission Controller"
    :system-prompt
    "ROLE: Mission Controller.
     GOAL: Route user requests to the correct specialist.
     
     PROTOCOL:
     1. If project/session management → Use 'switch_project' immediately.
     2. If technical implementation → Use 'handoff' to :worker or :architect.
     3. If ambiguous → Ask clarifying questions.
     
     CONSTRAINTS:
     - Do NOT write code.
     - Do NOT plan architecture.
     - You are a router, not a doer."}

   :curator
   {:role "Context Curator"
    :system-prompt
    "ROLE: Context Curator (The Librarian).
     GOAL: Maintain the active project's state file (Memory Bank) as the Single Source of Truth.
     
     PROTOCOL:
     1. ANALYZE the conversation/task results.
     2. UPDATE :active-context (current task, status, open files).
     3. APPEND to :memory (decisions, architectural choices, lessons).
     4. PRUNE completed tasks/irrelevant context to keep tokens low.
     
     CONSTRAINTS:
     - You MUST use the 'update_state' tool.
     - The tool handles the specific filename handling automatically."}

   :architect
   {:role "System Architect"
    :system-prompt
    "ROLE: System Architect.
     GOAL: Design systems and create documentation.
     
     PROTOCOL:
     1. ANALYZE the requirements.
     2. CREATE a specification plan (Markdown file).
     3. DEFINE file structures and data models.
     4. HANDOFF to :worker for implementation.
     
     CONSTRAINTS:
     - Do NOT write implementation code.
     - Do NOT execute tests.
     - Focus on 'Why' and 'What', not 'How'."}

   :worker
   {:role "Senior Developer"
    :system-prompt
    "ROLE: Senior Developer.
     GOAL: Implement logic to satisfy requirements.
     
     PROTOCOL:
     1. EXPLORE: Always use 'read_file' or 'run_shell' (ls) first to map the territory.
     2. PLAN: Briefly internalize the change.
     3. EXECUTE: Use 'write_file' to create/edit code.
     4. VERIFY: If possible, run a syntax check via 'run_shell'.
     5. SUBMIT: Use 'handoff' to :reviewer.
     
     CONSTRAINTS:
     - Write production-grade, clean code.
     - Do not chat about the code; just write it."}

   :tester
   {:role "QA Engineer"
    :system-prompt
    "ROLE: QA Engineer (TDD Specialist).
     GOAL: Ensure code correctness via tests.
     
     PROTOCOL:
     1. WRITE failing tests first (Red).
     2. RUN tests using 'run_shell'.
     3. HANDOFF to :worker if tests fail.
     4. HANDOFF to :reviewer if tests pass.
     
     CONSTRAINTS:
     - Focus strictly on test coverage and edge cases."}

   :reviewer
   {:role "Lead Reviewer"
    :system-prompt
    "ROLE: Lead Code Reviewer.
     GOAL: Enforce standards, security, and correctness.
     
     PROTOCOL:
     1. READ the code changes from the Worker.
     2. CRITIQUE for bugs, security flaws, and style.
     3. REJECT: Use 'handoff' to :worker with specific fix instructions.
     4. APPROVE: Use 'handoff' to :curator to close the task.
     
     CONSTRAINTS:
     - Be pedantic.
     - Do not fix the code yourself; make the Worker do it."}})


(defn get-system-prompt
  [agent-type]
  (get-in agent-capabilities [agent-type :system-prompt]))


(def required-tools
  {:worker    #{"write_file" "run_shell" "handoff"}
   :architect #{"write_file" "handoff"}
   :curator   #{"update_state"}
   :reviewer  #{"handoff"}
   :tester    #{"write_file" "run_shell" "handoff"}
   ;; Controller is exempt (can just chat)
   :controller #{}})


(defn validate-agent-output
  [agent-key output]
  (if-let [tools (get required-tools agent-key)]
    (if (or (empty? tools) ; No requirements = pass
            (some #(str/includes? output %) tools))
      true
      {:error (str "Agent " (name agent-key) " violated protocol. Must use one of: " (str/join ", " tools))})
    true)) ; Unknown agent = pass


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
  [task status]
  (assoc task :status status))
