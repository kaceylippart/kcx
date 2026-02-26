(ns kcx.orchestrator
  "Workflow orchestration for KCX.

   Dispatches parsed DSL commands through the data-driven workflow engine.
   The workflow state machine (kcx.workflow) controls sequencing.
   Handlers (kcx.worker) control capability."
  (:require
    [clojure.string :as str]
    [kcx.agents :as agents]
    [kcx.expand :as expand]
    [kcx.logging :as log]
    [kcx.state :as state]
    [kcx.worker :as worker]
    [kcx.workflow :as workflow]))


;; ============================================================================
;; Expansion Integration
;; ============================================================================

(defonce base-expansions (expand/load-base-expansions))

(defn- cmd->expandable
  "Adapt a parsed DSL command to the shape expand/expand expects."
  [cmd]
  (if (= "prompt" (:verb cmd))
    ;; Natural language — passthrough (no expansion)
    {:verb nil :prompt (:prompt cmd) :modifiers [] :user-text nil}
    ;; DSL command — adapt to expandable shape
    {:verb {:name (:verb cmd)
            :args (if (and (:target cmd) (not= "global_context" (:target cmd)))
                    [(:target cmd)]
                    [])}
     :modifiers (mapv (fn [inc] {:name inc :args []}) (or (:includes cmd) []))
     :user-text (:instruction cmd)}))

(defn- expand-cmd
  "Run expansion on a parsed command. Returns the cmd merged with expansion results."
  [cmd]
  (let [expandable (cmd->expandable cmd)
        expanded (expand/expand expandable base-expansions)]
    ;; Merge expansion results back onto the original cmd
    (merge cmd (select-keys expanded [:expanded-verb :expanded-modifiers
                                       :workflow :warnings :expanded?]))))


;; ============================================================================
;; Controller Commands (non-workflow)
;; ============================================================================

(defn handle-controller
  [cmd]
  (try
    (case (:verb cmd)
      "proj" (state/switch-project (:target cmd))
      "list" (state/list-projects)
      "status" (str "→ status\n" (state/list-projects))
      "jobs" (let [running (worker/get-running-jobs)]
               (if (empty? running)
                 "No running jobs."
                 (str "Running jobs:\n"
                      (str/join "\n\n"
                                (map worker/format-job-status running)))))
      (str "→ " (:verb cmd) " (no handler)"))
    (catch Exception e
      (str "Controller error: " (.getMessage e)))))


;; ============================================================================
;; Result Formatting
;; ============================================================================

(defn format-workflow-result
  "Format workflow result for MCP response.
   Reads from the artifacts map produced by the state machine."
  [result cmd]
  (try
    (let [success?   (:success result)
          artifacts  (:artifacts result)
          ;; Collect files from all agents
          all-files  (->> (vals artifacts)
                          (mapcat #(or (:files-changed %) []))
                          distinct)
          ;; Get summaries from key agents
          arch-summary   (get-in artifacts [:architect :summary])
          worker-summary (or (get-in artifacts [:work :summary])
                             (get-in artifacts [:implement :summary]))
          review-feedback (get-in artifacts [:review :feedback])]
      (str
        (if success?
          "═══ TASK COMPLETED SUCCESSFULLY ═══\n"
          "═══ TASK FAILED ═══\n")
        "\n"
        (when (seq all-files)
          (str "Files modified:\n"
               (str/join "\n" (map #(str "  • " %) all-files))
               "\n\n"))
        (when arch-summary
          (str "Architecture/Planning:\n  " arch-summary "\n\n"))
        (when worker-summary
          (str "Implementation:\n  " worker-summary "\n\n"))
        (when (and success? review-feedback)
          (str "Reviewer assessment:\n  " review-feedback "\n\n"))
        (if success?
          "KCX workflow complete. Present the above summary to the user. Do NOT take further action — do not review, fix, or modify any files mentioned above."
          (str "KCX workflow FAILED. Present the above summary to the user.\n"
               "Retries: " (pr-str (:retries result))))))
    (catch Exception e
      (str "ERROR: Failed to format workflow result - " (.getMessage e)))))


;; ============================================================================
;; Workflow Execution
;; ============================================================================

(defn run-workflow-command
  "Execute a command through the workflow state machine.
   Expands tokens, handles job tracking, status capture, and result formatting."
  [cmd]
  (log/log! :info "WORKFLOW START" {:verb (:verb cmd) :target (:target cmd)})
  ;; Track for redo
  (when-not (:is-redo cmd)
    (worker/set-last-command! cmd))
  ;; Expand tokens against dictionary
  (let [cmd      (expand-cmd cmd)
        _        (when (seq (:warnings cmd))
                   (doseq [w (:warnings cmd)]
                     (log/log! :warn "EXPANSION WARNING" {:warning w})))
        ;; Use workflow from expansion if available, fall back to verb->workflow
        wf       (if-let [wf-type (:workflow cmd)]
                   (workflow/get-workflow wf-type)
                   (workflow/verb->workflow (:verb cmd)))
        handlers (worker/build-handlers)
        job-id   (worker/start-job! cmd)
        [result lines]
        (worker/with-status-capture
          (fn []
            (binding [worker/*workflow-start* (System/currentTimeMillis)
                      worker/*current-job* job-id]
              (worker/status! "━━━" (str/upper-case (or (:verb cmd) "PROMPT"))
                              (when-let [t (:target cmd)]
                                (when (not= t "global_context") (str "@" t)))
                              "━━━")
              ;; Show expansion warnings to user
              (when (seq (:warnings cmd))
                (doseq [w (:warnings cmd)]
                  (worker/status! "⚠" w)))
              (let [result (workflow/run wf cmd handlers
                                        {:on-state (fn [state _def]
                                                     (log/log! :debug "STATE" {:state state}))})]
                (if (:success result)
                  (worker/status! "✓ DONE")
                  (worker/status! "✗ FAILED"))
                (worker/complete-job! job-id (:success result))
                result))))]
    (str (worker/format-status-lines lines)
         "\n\n"
         (format-workflow-result result cmd))))


;; ============================================================================
;; Redo
;; ============================================================================

(defn execute-redo
  "Execute a redo command by merging modifiers with the last command."
  [redo-cmd]
  (try
    (if-let [last-cmd (worker/get-last-command)]
      (let [merged-cmd (worker/merge-redo-command last-cmd redo-cmd)]
        (worker/status! "━━━ REDO ━━━")
        (worker/status! "Original:" (:verb last-cmd)
                        (when-let [t (:target last-cmd)]
                          (when (not= t "global_context") (str "@" t))))
        (when (seq (:includes redo-cmd))
          (worker/status! "Adding:" (str/join " " (map #(str "+" %) (:includes redo-cmd)))))
        (when (seq (:excludes redo-cmd))
          (worker/status! "Excluding:" (str/join " " (map #(str "-" %) (:excludes redo-cmd)))))
        (when (:instruction redo-cmd)
          (worker/status! "Instruction:" (:instruction redo-cmd)))
        (worker/status! "")
        (run-workflow-command merged-cmd))
      "ERROR: No previous command to redo. Run a command first.")
    (catch Exception e
      (str "ERROR: Redo execution failed - " (.getMessage e)))))


;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn execute-command
  "Execute a parsed DSL command.
   Routes controller commands directly, workflow commands through the state machine."
  [cmd]
  (try
    (if (nil? cmd)
      "ERROR: Invalid command. Use: kcx \"your prompt\" or kcx !verb @target +include -exclude"
      (let [verb (:verb cmd)]
        (case verb
          ;; Controller commands — no workflow needed
          ("proj" "list" "status" "jobs") (handle-controller cmd)

          ;; Redo — merge with last command and re-run
          "redo" (execute-redo cmd)

          ;; Everything else goes through the workflow engine
          (if (or (= verb "prompt") (agents/requires-workflow? cmd))
            (run-workflow-command cmd)
            (handle-controller cmd)))))
    (catch Exception e
      (str "ERROR: Command execution failed - " (.getMessage e)))))
