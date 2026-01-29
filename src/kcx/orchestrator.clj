(ns kcx.orchestrator
  "Workflow orchestration with two modes:

   1. AUTONOMOUS MODE (default):
      Commands like !fix, !test, !plan trigger worker/execute-*-workflow
      which spawns sub-agents and runs the entire chain autonomously.

   2. MANUAL MODE (for debugging/step-through):
      Use build-*-instruction functions to get XML-tagged instructions.
      Claude Code follows <do> steps, then passes <handoff> tags back
      to drive the workflow step-by-step. Useful for debugging.

   The autonomous mode is used by default. Manual mode is available
   via the instruction-building functions for special cases."
  (:require
    [clojure.string :as str]
    [kcx.agents :as agents]
    [kcx.state :as state]
    [kcx.worker :as worker]))


(defonce workflow-state (atom {:active-tasks {}}))


(defn add-task
  [t]
  (swap! workflow-state assoc-in [:active-tasks (:id t)] t))


(defn get-task
  [id]
  (get-in @workflow-state [:active-tasks id]))


(defn update-task
  [id f]
  (swap! workflow-state update-in [:active-tasks id] f))


;; ============================================================================
;; Controller Commands (non-workflow)
;; ============================================================================

(defn handle-controller
  [cmd]
  (case (:verb cmd)
    "proj" (state/switch-project (:target cmd))
    "list" (state/list-projects)
    "status" (str "→ status\n" (state/list-projects))
    ;; Jobs status command
    "jobs" (let [running (worker/get-running-jobs)]
             (if (empty? running)
               "No running jobs."
               (str "Running jobs:\n"
                    (str/join "\n\n"
                              (map worker/format-job-status running)))))
    ;; Default
    (str "→ " (:verb cmd) " (no handler)")))


;; ============================================================================
;; Manual Mode: XML Instruction Builders
;; ============================================================================
;; These functions build step-by-step instructions with XML handoff tags.
;; Used when Claude Code drives the workflow manually (debugging/step-through).

(defn build-worker-instruction
  "Instruction for worker agent (gen, edit, fix, debug, etc.)"
  [{:keys [verb target includes excludes]} task-id]
  (let [target-file (when (and target (not= target "global_context")) target)
        constraints (cond-> []
                      (seq includes) (into (map #(str "+" %) includes))
                      (seq excludes) (into (map #(str "-" %) excludes)))]
    (str
      "→ WORKER " (str/upper-case verb)
      (when target-file (str " @" target-file))
      (when (seq constraints) (str " " (str/join " " constraints))) "\n"
      "<do>Read target file → " (str/upper-case verb) " → mcp__kcx__write_file</do>\n"
      "<next><handoff task=\"" task-id "\" to=\"reviewer\"/></next>")))


(defn build-reviewer-instruction
  "Instruction for reviewer agent"
  [task-id task-info]
  (str
    "→ REVIEWER\n"
    "<do>Read changed files → Verify correctness</do>\n"
    "<approve><handoff task=\"" task-id "\" to=\"curator\"/></approve>\n"
    "<reject><handoff task=\"" task-id "\" to=\"worker\" feedback=\"DESCRIPTION\"/></reject>"))


(defn verb->priority
  "Map verb to priority level"
  [verb]
  (case verb
    ("fix" "debug") :high
    ("gen" "create") :normal
    ("edit" "refactor") :normal
    ("build") :normal
    :low))


(defn build-curator-instruction
  "Instruction for curator agent - update memory bank"
  [task-id task-info]
  (let [verb (get-in task-info [:original-command :verb])
        target (get-in task-info [:original-command :target])
        state-file (state/get-current-state-file)
        priority (verb->priority verb)]
    (str
      "→ CURATOR\n"
      "<do>\n"
      "1. mcp__kcx__read_state\n"
      "2. Increment :command-count\n"
      "3. Add to :memory with priority and TTL\n"
      "4. mcp__kcx__write_file\n"
      "</do>\n"
      "<entry action=\"" verb "\" target=\"" target "\" priority=\"" (name priority) "\"/>\n"
      "<file>" state-file "</file>\n"
      "<done task=\"" task-id "\"/>")))


;; ============================================================================
;; Manual Mode: XML Parsing & Handoff Routing
;; ============================================================================

(defn parse-xml-handoff
  "Parse XML handoff tag: <handoff task=\"uuid\" to=\"agent\" [feedback=\"...\"]/>
   Returns {:task \"uuid\" :to \"agent\" :feedback \"...\"} or nil"
  [input]
  (when-let [match (re-find #"<handoff\s+task=\"([^\"]+)\"\s+to=\"([^\"]+)\"(?:\s+feedback=\"([^\"]+)\")?\s*/>" input)]
    {:task (nth match 1)
     :to (nth match 2)
     :feedback (nth match 3)}))


(defn parse-xml-done
  "Parse XML done tag: <done task=\"uuid\"/>
   Returns task ID or nil"
  [input]
  (when-let [match (re-find #"<done\s+task=\"([^\"]+)\"\s*/>" input)]
    (second match)))


(defn handle-handoff
  "Handle handoff between agents"
  [{:keys [task to feedback]}]
  (let [to-agent (keyword to)
        task-info (get-task task)]
    (if (nil? task-info)
      (str "ERROR: Task " task " not found")
      (do
        (update-task task #(assoc % :current-agent to-agent))
        (case to-agent
          :reviewer (build-reviewer-instruction task task-info)
          :curator (build-curator-instruction task task-info)
          :worker (build-worker-instruction (:original-command task-info) task)
          (str "ERROR: Unknown agent " to))))))


(defn handle-done
  "Handle task completion"
  [task-id]
  (if-let [task-info (get-task task-id)]
    (do
      (update-task task-id #(assoc % :status :completed))
      "✓ DONE")
    (str "ERROR: Task " task-id " not found")))


(defn execute-workflow
  [task-id cmd]
  (update-task task-id #(agents/update-task-status % :in-progress))
  (build-worker-instruction cmd task-id))


(defn execute-xml-command
  "Execute XML-style system commands (handoff, done)"
  [input]
  (cond
    ;; Try handoff
    (str/includes? input "<handoff")
    (if-let [parsed (parse-xml-handoff input)]
      (handle-handoff parsed)
      "ERROR: Invalid handoff syntax")

    ;; Try done
    (str/includes? input "<done")
    (if-let [task-id (parse-xml-done input)]
      (handle-done task-id)
      "ERROR: Invalid done syntax")

    :else nil))


;; ============================================================================
;; Autonomous Mode: Result Formatting
;; ============================================================================

(defn format-workflow-result
  "Format a comprehensive result summary for the calling Claude.
   This ensures the caller understands the task is COMPLETE."
  [result cmd]
  (let [success? (:success result)
        ;; Collect files from all agents that may have changed files
        architect-files (get-in result [:architect :files-changed])
        worker-files (get-in result [:worker :files-changed])
        tester-files (get-in result [:tester :files-changed])
        all-files (distinct (concat (or architect-files [])
                                    (or worker-files [])
                                    (or tester-files [])))
        ;; Get summaries
        architect-summary (get-in result [:architect :summary])
        worker-summary (get-in result [:worker :summary])
        review-feedback (get-in result [:reviewer :feedback])]
    (str
      (if success?
        "═══ TASK COMPLETED SUCCESSFULLY ═══\n"
        "═══ TASK FAILED ═══\n")
      "\n"
      (when (seq all-files)
        (str "Files modified:\n"
             (str/join "\n" (map #(str "  • " %) all-files))
             "\n\n"))
      (when architect-summary
        (str "Architecture/Planning:\n  " architect-summary "\n\n"))
      (when worker-summary
        (str "Implementation:\n  " worker-summary "\n\n"))
      (when (and success? review-feedback)
        (str "Reviewer assessment:\n  " review-feedback "\n\n"))
      (if success?
        "⚠️ NO FURTHER ACTION NEEDED - The requested changes have been implemented, tested, and reviewed by sub-agents."
        (str "Failed at phase: " (name (or (:phase result) :unknown)))))))


;; ============================================================================
;; Autonomous Mode: Main Command Execution
;; ============================================================================
;; This is the default mode. Commands trigger complete autonomous workflows
;; that spawn sub-agents and run to completion without manual intervention.

(defn execute-command
  "Execute a parsed DSL command (from user input).
   Uses autonomous mode by default - spawns sub-agents for complete workflows."
  [cmd]
  (if (nil? cmd)
    "ERROR: Invalid command. Use: kcx !verb @target +include -exclude"
    (let [verb (:verb cmd)]
      (case verb
        ;; Controller commands
        ("proj" "list" "status" "jobs") (handle-controller cmd)
        ;; TDD/Test workflow - use tester agent
        ("test" "tdd")
        (let [[result lines] (worker/with-status-capture
                               #(worker/execute-tester-workflow cmd))]
          (str (worker/format-status-lines lines)
               "\n\n"
               (format-workflow-result result cmd)))
        ;; Architect workflow - specs then implementation
        ("plan" "arch" "design" "analyze")
        (let [[result lines] (worker/with-status-capture
                               #(worker/execute-architect-workflow cmd))]
          (str (worker/format-status-lines lines)
               "\n\n"
               (format-workflow-result result cmd)))
        ;; Other workflow commands - spawn autonomous agents
        (if (agents/requires-workflow? cmd)
          (let [[result lines] (worker/with-status-capture
                                 #(worker/execute-workflow cmd))]
            (str (worker/format-status-lines lines)
                 "\n\n"
                 (format-workflow-result result cmd)))
          (handle-controller cmd))))))
