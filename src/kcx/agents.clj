(ns kcx.agents
  "Agent definitions and capabilities"
  (:require
    [clojure.string :as str]))


(def agent-capabilities
  {:controller
   {:role "Mission Controller"
    :prompt "ROLE: Mission Controller.
             GOAL: Route requests.
             PROTOCOL:
             1. STARTUP: Use 'switch_project' if project named.
             2. ROUTING: Route to :worker, :architect, :tester.
             3. STATUS: Use ':list' or ':status'.
             TOOL USE: 'switch_project', 'handoff'."}

   :curator
   {:role "Context Curator"
    :prompt "ROLE: Context Curator.
             GOAL: Maintain kcx_state.edn.
             PROTOCOL: Update :active-context and append to :memory.
             TOOL USE: 'update_state'."}

   :architect
   {:role "System Architect"
    :prompt "ROLE: System Architect.
             GOAL: Design and Plan. No code.
             PROTOCOL: Analyze requirements, write specs (Markdown).
             TOOL USE: 'write_file', 'handoff'."}

   :worker
   {:role "Senior Developer"
    :prompt "ROLE: Senior Developer.
             GOAL: Implement logic.
             PROTOCOL: Read files, write code, verify.
             TOOL USE: 'read_file', 'write_file', 'run_shell', 'handoff'."}

   :tester
   {:role "QA Engineer"
    :prompt "ROLE: QA Engineer (TDD).
             GOAL: Ensure quality.
             PROTOCOL: Write failing tests, verify, loop.
             TOOL USE: 'write_file', 'run_shell', 'handoff'."}

   :reviewer
   {:role "Lead Reviewer"
    :prompt "ROLE: Lead Reviewer.
             GOAL: Standards and Security.
             PROTOCOL: Audit code, critique, approve/reject.
             TOOL USE: 'read_file', 'handoff'."}})


(defn get-system-prompt
  [type]
  (get-in agent-capabilities [type :prompt]))


(defn route-command
  [{:keys [verb]}]
  (case verb
    ("proj" "switch" "status" "list") :controller
    ("save" "clean" "remember") :curator
    ("plan" "arch" "design") :architect
    ("gen" "fix" "refactor" "build") :worker
    ("test" "tdd") :tester
    ("review" "check") :reviewer
    :controller))


(defn requires-workflow?
  [{:keys [verb]}]
  (contains? #{"gen" "fix" "refactor" "test"} verb))


(defn create-agent-task
  [cmd type]
  {:id (str (java.util.UUID/randomUUID))
   :assigned-agent type
   :status :pending
   :original-command cmd})


(defn update-task-status
  [task status]
  (assoc task :status status))
