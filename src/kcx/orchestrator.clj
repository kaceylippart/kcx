(ns kcx.orchestrator
  "Workflow logic - spawns isolated Claude instances for autonomous work"
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


(defn handle-controller
  [cmd]
  (case (:verb cmd)
    "proj" (state/switch-project (:target cmd))
    "list" (state/list-projects)
    "status" (str "→ status\n" (state/list-projects))
    ;; Default
    (str "→ " (:verb cmd) " (no handler)")))


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


(defn execute-command
  "Execute a parsed DSL command (from user input)"
  [cmd]
  (if (nil? cmd)
    "ERROR: Invalid command. Use: kcx !verb @target +include -exclude"
    (let [verb (:verb cmd)]
      (case verb
        ;; Controller commands
        ("proj" "list" "status") (handle-controller cmd)
        ;; Workflow commands - spawn autonomous agents
        (if (agents/requires-workflow? cmd)
          (let [result (worker/execute-workflow cmd)]
            (if (:success result)
              "✓ WORKFLOW COMPLETE"
              (str "✗ WORKFLOW FAILED at " (name (:phase result)))))
          (handle-controller cmd))))))
