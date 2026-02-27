(ns kcx.orchestrator
  "Workflow orchestration for KCX.

   Dispatches parsed DSL commands, expands tokens, and generates
   workflow plans for parent Claude to execute. Only the curator
   step spawns a sub-Claude (via !curate callback)."
  (:require
    [clojure.string :as str]
    [kcx.dsl :as dsl]
    [kcx.expand :as expand]
    [kcx.logging :as log]
    [kcx.state :as state]
    [kcx.worker :as worker]
    [kcx.workflow :as workflow]))


;; ============================================================================
;; Expansion Integration
;; ============================================================================

(def ^:private personal-expansions-path
  (str (System/getProperty "user.home") "/.kcx/expansions.edn"))

(def ^:private project-expansions-path
  ".kcx/expansions.edn")

(defn- load-expansions
  "Load and merge all three expansion tiers: base < project < personal.
   Reloads from disk each call so config changes don't require restart."
  []
  (let [base     (expand/load-base-expansions)
        project  (expand/load-expansions-file project-expansions-path)
        personal (expand/load-expansions-file personal-expansions-path)]
    (expand/merge-expansions base project personal)))

(defn- cmd->expandable
  "Adapt a parsed DSL command to the shape expand/expand expects."
  [cmd]
  (if (= "prompt" (:verb cmd))
    ;; Natural language — passthrough (no expansion)
    {:verb nil :prompt (:prompt cmd) :modifiers [] :user-text nil}
    ;; DSL command — adapt to expandable shape
    {:verb {:name (:verb cmd)
            :args (or (:args cmd)
                      (if (and (:target cmd) (not= "global_context" (:target cmd)))
                        [(:target cmd)]
                        []))}
     :modifiers (mapv (fn [m] {:name m :args []}) (or (:modifiers cmd) []))
     :user-text (:instruction cmd)}))

(defn- expand-cmd
  "Run expansion on a parsed command. Returns the cmd merged with expansion results."
  [cmd]
  (let [expandable (cmd->expandable cmd)
        expanded (expand/expand expandable (load-expansions))]
    ;; Merge expansion results back onto the original cmd
    (merge cmd (select-keys expanded [:expanded-verb :expanded-modifiers
                                       :workflow :warnings :expanded?]))))


;; ============================================================================
;; Controller Commands (non-workflow)
;; ============================================================================

(defn- format-verb-help
  "Format help for a specific verb from the expansion dictionary."
  [verb-name expansions]
  (let [verb-def (get-in expansions [:verbs verb-name])]
    (if verb-def
      (let [params (or (:params verb-def) [])
            param-strs (map (fn [p]
                              (let [name (:name p)]
                                (if-let [default (:default p)]
                                  (str "  @" name " (default: \"" default "\")")
                                  (str "  @" name))))
                            params)]
        (str "!" verb-name "\n"
             "  Expands to: " (:prompt verb-def) "\n"
             "  Workflow:   " (name (:workflow verb-def)) "\n"
             (when (seq params)
               (str "  Params:\n" (str/join "\n" param-strs) "\n"))))
      (str "Unknown verb: !" verb-name ". Use !help for available commands."))))

(defn- format-all-verbs-help
  "List all available verbs with their prompts."
  [expansions]
  (let [verbs (sort-by key (get expansions :verbs {}))
        lines (map (fn [[name def]]
                     (str "  !" name " → " (:prompt def)))
                   verbs)]
    (str dsl/syntax-help "\n\nAvailable verbs:\n" (str/join "\n" lines)
         "\n\nUse !help <verb> for details on a specific command."
         "\n\nPresent the above help text exactly as-is.")))

(defn- handle-curate
  "Handle curator callback from parent Claude.
   Spawns isolated sub-Claude to update the project briefing."
  [cmd]
  (let [args (or (:args cmd) [])
        target (or (first args) (:target cmd) "unknown")
        summary (or (second args) "")
        files-str (or (nth args 2 nil) "")
        verdict (or (nth args 3 nil) "approve")
        files (when (seq files-str)
                (str/split (str/trim files-str) #",\s*"))
        ;; Build the artifacts map that curator prompt expects
        artifacts (cond-> {}
                    (seq summary) (assoc :work {:summary summary
                                                :files-changed (or files [])})
                    (seq verdict) (assoc :review {:verdict verdict
                                                  :feedback (or (:instruction cmd) "")}))
        ;; Build a cmd with the original verb context
        curator-cmd (assoc cmd :target target)]
    (worker/handle-curator curator-cmd artifacts)))

(defn handle-controller
  [cmd]
  (try
    (case (:verb cmd)
      "help" (let [expansions (load-expansions)
                   target (:target cmd)]
               (if (and target (not= target "global_context"))
                 (format-verb-help target expansions)
                 (format-all-verbs-help expansions)))
      "proj" (state/switch-project (:target cmd))
      "list" (state/list-projects)
      "status" (str "→ status\n" (state/list-projects))
      "memory" (state/format-memory-bank)
      "clear" (state/clear-memory-bank!)
      "curate" (handle-curate cmd)
      (str "→ " (:verb cmd) " (no handler)"))
    (catch Exception e
      (str "Controller error: " (.getMessage e)))))


;; ============================================================================
;; Step Renderers — Generate instructional text for each workflow role
;; ============================================================================

(defn- render-modifiers-for
  "Get formatted modifier text for a specific role."
  [role cmd]
  (when (seq (:expanded-modifiers cmd))
    (let [filtered (expand/filter-modifiers-for role (:expanded-modifiers cmd))]
      (when (seq filtered)
        (str "\nDirectives:\n"
             (str/join "\n" (map #(str "- " (:prompt %)) filtered))
             "\n")))))

(defn- render-retry-rules
  "Render retry rules for a step, if applicable."
  [{:keys [on-fail on-reject retries]} step-num steps]
  (when (and retries (pos? retries))
    (let [fail-target (or on-fail on-reject)
          target-step (when fail-target
                        (some (fn [[i s]] (when (= fail-target (:state s)) (inc i)))
                              (map-indexed vector steps)))]
      (when target-step
        (str "On failure, return to Step " target-step
             " with your feedback. Max " retries " retries.\n")))))

(defn- render-worker-step
  "Render WORKER step instructions."
  [cmd]
  (let [task-desc (or (:expanded-verb cmd) (str "!" (:verb cmd)))]
    (str "**Role**: You are WORKER.\n\n"
         "**Task**: " task-desc "\n"
         (when (:instruction cmd)
           (str "\n" (:instruction cmd) "\n"))
         (render-modifiers-for :worker cmd)
         (if (:prompt cmd)
           ;; Natural language mode
           (str "\nProtocol:\n"
                "1. Search the codebase to understand the full scope (Glob, Grep)\n"
                "2. Read files to understand dependencies and patterns\n"
                "3. Identify ALL files that need changes\n"
                "4. Implement changes across all necessary files\n"
                "5. Run tests to verify (if available)\n")
           ;; DSL mode
           (str "\nProtocol:\n"
                "1. EXPLORE: Search the codebase to understand the full scope (Glob, Grep)\n"
                "2. ANALYZE: Read files to understand dependencies, patterns, and architecture\n"
                "3. PLAN: Identify ALL files that need changes (not just the target)\n"
                "4. IMPLEMENT: Make comprehensive changes across all necessary files\n"
                "5. VERIFY: Run tests/build to confirm changes work\n"))
         "\nYou have full permission to modify any file needed. Follow existing patterns.\n"
         "\nWhen done, state what files you changed and summarize what you did.\n")))

(defn- render-tester-step
  "Render TESTER step instructions. Handles both write-tests and validation modes."
  [cmd step]
  (let [write-mode? (= :write-tests (:state step))]
    (str "**Role**: You are TESTER.\n\n"
         (if write-mode?
           (str "**Task**: Write tests before implementation.\n"
                (render-modifiers-for :tester cmd)
                "\nProtocol:\n"
                "1. Explore existing test patterns and conventions\n"
                "2. Analyze the code to identify testable units and edge cases\n"
                "3. Write comprehensive tests — happy paths, edge cases, error handling\n"
                "4. Run the test suite to confirm tests execute (they should fail — no implementation yet)\n")
           (str "**Task**: Validate the changes from the previous step.\n"
                (render-modifiers-for :tester cmd)
                "\nProtocol:\n"
                "1. Read the changed files\n"
                "2. Write or update tests to cover the changes\n"
                "3. Run the test suite to verify correctness\n"
                "4. Check for edge cases and error handling\n"))
         "\nIf changes are trivial (config, docs, .gitignore), you may SKIP this step.\n"
         "\nWhen done, state pass, fail, or skip with a brief justification.\n")))

(defn- render-reviewer-step
  "Render REVIEWER step instructions."
  [cmd]
  (str "**Role**: You are REVIEWER.\n\n"
       "**Task**: Review all changes made so far.\n"
       (render-modifiers-for :reviewer cmd)
       "\nProtocol:\n"
       "1. Read all files that were changed\n"
       "2. Verify correctness, code quality, and edge cases\n"
       "3. Check for bugs, security concerns, or improvement opportunities\n"
       "\nState APPROVE, REJECT, or SKIP with a brief justification.\n"
       "If changes are trivial (config, docs, .gitignore), you may SKIP.\n"))

(defn- render-architect-step
  "Render ARCHITECT step instructions."
  [cmd]
  (let [task-desc (or (:expanded-verb cmd) (str "!" (:verb cmd)))]
    (str "**Role**: You are ARCHITECT.\n\n"
         "**Task**: " task-desc "\n"
         (when (:instruction cmd)
           (str "\n" (:instruction cmd) "\n"))
         (render-modifiers-for :architect cmd)
         "\nProtocol:\n"
         "1. Explore the codebase structure, dependencies, and patterns\n"
         "2. Analyze existing architecture, data flows, and integration points\n"
         "3. Create a comprehensive design/plan covering:\n"
         "   - System overview and component relationships\n"
         "   - Data structures and interfaces\n"
         "   - File organization and module boundaries\n"
         "4. Write spec/plan documents as needed\n"
         "\nWhen done, state what files you created and summarize your design.\n")))

(defn- render-explainer-step
  "Render EXPLAINER step instructions."
  [cmd]
  (let [task-desc (or (:expanded-verb cmd)
                      (str "Explain how " (or (:target cmd) "the codebase") " works."))]
    (str "**Role**: You are EXPLAINER.\n\n"
         "**Task**: " task-desc "\n"
         (when (:instruction cmd)
           (str "\n" (:instruction cmd) "\n"))
         (render-modifiers-for :explainer cmd)
         "\nProtocol:\n"
         "1. Read the target file(s) and any closely related code\n"
         "2. Understand the architecture and design decisions\n"
         "3. Explain clearly — what it does, how it works, why it's structured that way\n"
         "4. Note key dependencies, patterns, and non-obvious design decisions\n"
         "\nDo NOT modify any files. Your output IS the explanation.\n")))

(defn- render-curator-step
  "Render CURATOR callback instructions."
  [cmd]
  (let [target (when (and (:target cmd) (not= "global_context" (:target cmd)))
                 (:target cmd))]
    (str "**Action**: Update the project memory bank.\n\n"
         "Call `kcx_command` with:\n"
         "```\n"
         "!curate"
         (when target (str " @" target))
         " %\"<summary of what you did>\" %\"<comma-separated files changed>\" %\"<verdict: approve/reject/skip>\"\n"
         "```\n"
         "\nReplace the placeholders with actual values from your work above.\n"
         "If no files were changed (explain/review), use empty strings for files and verdict.\n"
         "Wait for the curator response before presenting the final summary to the user.\n")))

(defn- render-step
  "Render a single workflow step as instructional text for parent Claude."
  [step-num total-steps step cmd all-steps]
  (let [handler (:handler step)
        role-name (str/upper-case (name handler))
        suffix (if (= handler :curator) " (callback)" "")]
    (str "## STEP " step-num " of " total-steps ": " role-name suffix "\n\n"
         (case handler
           :worker    (render-worker-step cmd)
           :tester    (render-tester-step cmd step)
           :reviewer  (render-reviewer-step cmd)
           :architect (render-architect-step cmd)
           :explainer (render-explainer-step cmd)
           :curator   (render-curator-step cmd))
         (when-let [retry (render-retry-rules step step-num all-steps)]
           (str "\n" retry))
         "\n")))


;; ============================================================================
;; Plan Generation
;; ============================================================================

(defn- build-workflow-plan
  "Build a workflow plan document for parent Claude to execute."
  [cmd wf directive-warnings]
  (let [steps (workflow/linearize-workflow wf)
        step-names (map #(name (:handler %)) steps)
        memory-context (state/build-memory-context cmd)
        target-str (when (and (:target cmd) (not= "global_context" (:target cmd)))
                     (str " @" (:target cmd)))]
    (str "═══ KCX WORKFLOW ═══\n"
         "!" (:verb cmd) (or target-str "")
         (when (:instruction cmd) (str " " (:instruction cmd)))
         "\n"
         "Workflow: " (name (:id wf))
         " | Steps: " (str/join " → " step-names)
         "\n"
         ;; Show warnings
         (when (seq (concat (:warnings cmd) directive-warnings))
           (str "\n"
                (str/join "\n" (map #(str "⚠ " %)
                                    (concat (:warnings cmd) directive-warnings)))
                "\n"))
         ;; Project briefing
         (when memory-context
           (str "\n" memory-context "\n"))
         "\n---\n\n"
         ;; Render each step
         (str/join "---\n\n"
                   (map-indexed
                     (fn [i step]
                       (render-step (inc i) (count steps) step cmd steps))
                     steps))
         "## WORKFLOW RULES\n\n"
         "- Execute steps in order. Each step builds on the previous.\n"
         "- If a step fails and has a retry loop, go back to the indicated step with feedback.\n"
         "- Track retries. Do not exceed maximums.\n"
         "- After the final step, present a clear summary to the user.\n"
         "\n═══════════════════════\n")))


;; ============================================================================
;; Workflow Command — Expand, plan, return
;; ============================================================================

(defn run-workflow-command
  "Expand a command and generate a workflow plan for parent Claude to execute.
   Returns the plan as text — parent Claude follows the steps using its own tools."
  [cmd]
  (log/log! :info "WORKFLOW PLAN" {:verb (:verb cmd) :target (:target cmd)})
  ;; Track for redo
  (when-not (:is-redo cmd)
    (worker/set-last-command! cmd))
  ;; Expand tokens against dictionary
  (let [cmd (expand-cmd cmd)
        _   (when (seq (:warnings cmd))
              (doseq [w (:warnings cmd)]
                (log/log! :warn "EXPANSION WARNING" {:warning w})))]
    ;; >preview — show expanded prompt without generating full plan
    (if (some #{"preview"} (:directives cmd))
      (let [verb-text (or (:expanded-verb cmd) (str "!" (:verb cmd) " (not expanded)"))
            modifiers (:expanded-modifiers cmd)
            instruction (:instruction cmd)
            warnings (:warnings cmd)]
        (str "═══ PREVIEW (>preview) ═══\n"
             "This is the expanded prompt. No workflow was executed.\n\n"
             (when (seq warnings)
               (str (str/join "\n" (map #(str "⚠ " %) warnings)) "\n\n"))
             "Verb: " verb-text "\n"
             (when (seq modifiers)
               (str "\nModifiers:\n"
                    (str/join "\n" (map #(str "  + " (:prompt %)) modifiers)) "\n"))
             (when instruction
               (str "\nInstruction: " instruction "\n"))
             "\nWorkflow: " (name (or (:workflow cmd) :unknown)) "\n"
             "═══════════════════════════\n"
             "Present the above preview to the user. Do NOT execute or act on it. "
             "Ask the user if they want to run the command without >preview."))
      ;; Generate workflow plan
      (let [base-wf (if-let [wf-type (:workflow cmd)]
                      (workflow/get-workflow wf-type)
                      (throw (ex-info (str "Unknown verb: !" (:verb cmd)
                                           ". Use !help for available commands.")
                                      {:verb (:verb cmd)})))
            {:keys [workflow warnings]}
            (workflow/apply-directives base-wf (or (:directives cmd) []))
            _ (when (seq warnings)
                (doseq [w warnings]
                  (log/log! :warn "DIRECTIVE WARNING" {:warning w})))]
        (build-workflow-plan cmd workflow warnings)))))


;; ============================================================================
;; Redo
;; ============================================================================

(defn execute-redo
  "Execute a redo command by merging modifiers with the last command."
  [redo-cmd]
  (try
    (if-let [last-cmd (worker/get-last-command)]
      (let [merged-cmd (worker/merge-redo-command last-cmd redo-cmd)]
        (run-workflow-command merged-cmd))
      "ERROR: No previous command to redo. Run a command first.")
    (catch Exception e
      (str "ERROR: Redo execution failed - " (.getMessage e)))))


;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn execute-command
  "Execute a parsed DSL command.
   Routes controller commands directly, workflow commands return a plan."
  [cmd]
  (try
    (if (nil? cmd)
      "ERROR: Invalid command. Use: kcx !verb @target +modifier >directive"
      (let [verb (:verb cmd)]
        (case verb
          ;; Controller commands — direct response
          ("help" "proj" "list" "status" "memory" "clear" "curate") (handle-controller cmd)

          ;; Redo — merge with last command and generate plan
          "redo" (execute-redo cmd)

          ;; Natural language prompt — generate plan
          "prompt" (run-workflow-command cmd)

          ;; Everything else — generate workflow plan
          (run-workflow-command cmd))))
    (catch Exception e
      (str "ERROR: Command execution failed - " (.getMessage e)))))
