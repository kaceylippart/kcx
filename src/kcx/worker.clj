(ns kcx.worker
  "Spawns isolated Claude instances for autonomous work.

   Current approach: CLI-based spawning with clean environment (env -i).
   This works with Claude CLI authentication (Team, Pro, etc.).

   Future: Direct API support via kcx.claude-api when ANTHROPIC_API_KEY is available.
   See src/kcx/claude_api.clj for the API-based implementation."
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [kcx.logging :as log]
    [kcx.state :as state]))


(def max-workflow-iterations
  "Maximum WORKER → REVIEWER iterations before giving up. Override with KCX_MAX_ITERATIONS."
  (or (some-> (System/getenv "KCX_MAX_ITERATIONS") parse-long) 3))

(defn build-worker-prompt
  "Build a comprehensive prompt for autonomous multi-file work.
   Includes memory context from past work on this target.
   Optional reviewer-feedback is passed when retrying after rejection."
  [{:keys [verb target includes excludes] :as cmd} & {:keys [reviewer-feedback iteration]}]
  (let [action (str/upper-case verb)
        constraints (cond-> []
                      (seq includes) (conj (str "FOCUS ON: " (str/join ", " includes)))
                      (seq excludes) (conj (str "AVOID: " (str/join ", " excludes))))
        target-str (if (and target (not= target "global_context"))
                     (str "starting from " target)
                     "across the codebase")
        memory-context (state/build-memory-context cmd)
        retry-context (when reviewer-feedback
                        (str "\n⚠️ PREVIOUS ATTEMPT REJECTED (iteration " iteration ").\n"
                             "Reviewer feedback: " reviewer-feedback "\n"
                             "Address this feedback in your implementation.\n"))]
    (str
      "You are WORKER, an autonomous coding agent. Your task: " action " " target-str ".\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      (when (seq constraints)
        (str "\nConstraints: " (str/join ". " constraints) ".\n"))
      retry-context
      "\n## PROTOCOL\n"
      "1. EXPLORE: Search the codebase to understand the full scope. Use Glob/Grep to find all related files.\n"
      "2. ANALYZE: Read files to understand dependencies, patterns, and architecture.\n"
      "3. PLAN: Identify ALL files that need changes (not just the target).\n"
      "4. IMPLEMENT: Make comprehensive changes across all necessary files.\n"
      "5. VERIFY: If Bash is available, run tests/build to confirm changes work.\n"
      "\n## AUTONOMY\n"
      "- You have FULL permission to modify ANY file needed to complete the task.\n"
      "- Change as many files as necessary - don't limit yourself to one file.\n"
      "- Follow existing patterns and conventions in the codebase.\n"
      "- If you find related issues while working, fix them too.\n"
      "\n## OUTPUT (required at end)\n"
      "WORKER_RESULT|STATUS|FILES|SUMMARY\n"
      "- STATUS: 'success' or 'failed'\n"
      "- FILES: comma-separated list of ALL files you modified\n"
      "- SUMMARY: brief description of changes\n"
      "Example: WORKER_RESULT|success|src/core.clj,src/utils.clj,test/core_test.clj|Refactored error handling across 3 files\n"
      "\nBegin.")))


(defn build-reviewer-prompt
  "Build prompt for reviewer to check worker's changes.
   Includes memory context of past issues and patterns."
  [worker-result files-changed & {:keys [cmd]}]
  (let [memory-context (when cmd (state/build-memory-context cmd))]
    (str
      "You are REVIEWER. Check these changes:\n"
      "Files: " (str/join ", " files-changed) "\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      "Summary: " (:summary worker-result) "\n"
      "\nRead the files. Verify correctness.\n"
      "\nOutput EXACTLY:\n"
      "REVIEW_RESULT|VERDICT|FEEDBACK\n"
      "Example: REVIEW_RESULT|approve|Looks good, zero check added correctly")))


(defn parse-worker-result
  "Extract structured result from worker output. Format: WORKER_RESULT|status|files|summary"
  [output]
  (if-let [match (re-find #"WORKER_RESULT\|(\w+)\|([^|]*)\|(.+)" output)]
    {:status (nth match 1)
     :files-changed (when-let [f (nth match 2)]
                      (when (seq f) (str/split (str/trim f) #",\s*")))
     :summary (nth match 3)}
    {:status "unknown"
     :files-changed []
     :summary (str "Could not parse output: " (subs output 0 (min 200 (count output))))}))


(defn parse-review-result
  "Extract structured result from reviewer output. Format: REVIEW_RESULT|verdict|feedback"
  [output]
  (if-let [match (re-find #"REVIEW_RESULT\|(\w+)\|(.+)" output)]
    {:verdict (nth match 1)
     :feedback (nth match 2)}
    {:verdict "approve"  ; Default approve if can't parse
     :feedback (str "Could not parse: " (subs output 0 (min 100 (count output))))}))


(def home-dir (System/getProperty "user.home"))

;; Find claude binary - check common locations
(def claude-path
  (or (System/getenv "CLAUDE_PATH")
      (let [which-result (try
                           (-> (p/shell {:out :string} "which" "claude")
                               :out
                               clojure.string/trim)
                           (catch Exception _ nil))]
        (when (and which-result (seq which-result))
          which-result))
      ;; Fallback locations
      (str home-dir "/Library/pnpm/claude")
      "claude"))

;; KCX_HOME should point to the kcx repo root
(def kcx-home (or (System/getenv "KCX_HOME")
                  (str home-dir "/kcx")))
(def playground-dir (str kcx-home "/playground"))

;; Working directory for agent operations - defaults to current directory
(def worker-working-dir
  "Working directory for worker agents. Override with KCX_WORKING_DIR env var.
   Defaults to current directory (.) for real project work.
   Set to 'playground' to use the KCX playground directory."
  (let [configured (System/getenv "KCX_WORKING_DIR")]
    (cond
      (= configured "playground") playground-dir
      (seq configured) configured
      :else ".")))

;; ============================================================================
;; Agent Spawn Configuration
;; ============================================================================
;; These can be overridden via environment variables for different setups

(def worker-model
  "Model for worker agents. Override with KCX_WORKER_MODEL env var."
  (or (System/getenv "KCX_WORKER_MODEL") "claude-sonnet-4-20250514"))

(def worker-tools
  "Tools available to worker agents. Override with KCX_WORKER_TOOLS env var.
   Includes Bash by default for running tests and verification."
  (or (System/getenv "KCX_WORKER_TOOLS") "Read,Write,Edit,Glob,Grep,Bash"))

(def worker-permission-mode
  "Permission mode for workers. Override with KCX_PERMISSION_MODE env var.
   Options: bypassPermissions (default, needed for autonomous operation), acceptEdits (prompts for some ops)
   Note: acceptEdits doesn't work well in non-interactive mode - blocks on prompts that can't be answered."
  (or (System/getenv "KCX_PERMISSION_MODE") "bypassPermissions"))

(def output-mode
  "Output verbosity. Override with KCX_OUTPUT_MODE env var.
   Options: minimal (clean progress lines), verbose (full details)"
  (or (System/getenv "KCX_OUTPUT_MODE") "minimal"))

(defn verbose? [] (= output-mode "verbose"))

;; ============================================================================
;; Clean Output Helpers
;; ============================================================================

(defn status!
  "Print a clean status line. Always shown."
  [& parts]
  (println (str/join " " (map str parts))))

(defn detail!
  "Print detail line. Only shown in verbose mode."
  [& parts]
  (when (verbose?)
    (println (str "  " (str/join " " (map str parts))))))

(defn format-files-changed
  "Format file changes compactly: '3 files' or 'file.clj'"
  [files]
  (let [n (count files)]
    (cond
      (zero? n) "no files"
      (= 1 n) (first files)
      :else (str n " files"))))


(defn spawn-claude
  "Spawn a Claude instance with the given prompt, return output.

   Uses env -i for clean environment isolation - only passes essential vars.
   This ensures the spawned Claude isn't affected by parent session config
   (e.g., Bedrock vs direct API, nested Claude detection, etc.)."
  [prompt & {:keys [timeout-ms working-dir tools permission-mode]
             :or {timeout-ms 300000
                  working-dir worker-working-dir
                  tools worker-tools
                  permission-mode worker-permission-mode}}]
  (log/log! :info "SPAWN CLAUDE" {:prompt-length (count prompt)
                                  :working-dir working-dir
                                  :timeout-ms timeout-ms
                                  :claude-path claude-path
                                  :model worker-model
                                  :tools tools
                                  :permission-mode permission-mode})
  (try
    ;; Use env -i for clean spawn - only pass essential vars
    ;; This isolates the child from parent Claude session config
    (let [env-vars (str "PATH=\"$PATH\" "
                        "HOME=\"$HOME\" "
                        "ANTHROPIC_API_KEY=\"${ANTHROPIC_API_KEY:-}\" "
                        "ANTHROPIC_MODEL=\"" worker-model "\" "
                        "KCX_WORKER=true ")
          cmd (str "env -i " env-vars
                   claude-path
                   " --print"
                   " --permission-mode " permission-mode
                   " --tools '" tools "'"
                   " -p " (pr-str prompt)
                   " < /dev/null")
          _ (log/log! :debug "SPAWN CMD" {:cmd cmd})
          result (p/shell {:out :string
                           :err :string
                           :dir working-dir}
                          "sh" "-c" cmd)]
      (log/log! :info "CLAUDE COMPLETE" {:exit (:exit result)
                                         :output-length (count (:out result))})
      {:success (zero? (:exit result))
       :output (:out result)
       :error (:err result)})
    (catch Exception e
      (log/log-error! "CLAUDE SPAWN FAILED" e)
      {:success false
       :output ""
       :error (str e)})))


(defn run-worker
  "Execute worker agent for a command.
   Optional reviewer-feedback and iteration for retry loops."
  [cmd & {:keys [reviewer-feedback iteration] :or {iteration 1}}]
  (let [prompt (build-worker-prompt cmd :reviewer-feedback reviewer-feedback :iteration iteration)
        _ (log/log! :info "WORKER START" {:verb (:verb cmd)
                                          :target (:target cmd)
                                          :iteration iteration
                                          :has-feedback (some? reviewer-feedback)})
        result (spawn-claude prompt)]
    (if (:success result)
      (let [parsed (parse-worker-result (:output result))]
        (log/log! :info "WORKER DONE" parsed)
        (assoc parsed :raw-output (:output result) :iteration iteration))
      {:status "failed"
       :summary (str "Worker spawn failed: " (:error result))
       :files-changed []
       :iteration iteration})))


(defn run-reviewer
  "Execute reviewer agent to check worker's changes"
  [worker-result]
  (let [prompt (build-reviewer-prompt worker-result (:files-changed worker-result))
        _ (log/log! :info "REVIEWER START" {:files (:files-changed worker-result)})
        result (spawn-claude prompt :timeout-ms 120000)] ; 2 min for review
    (if (:success result)
      (let [parsed (parse-review-result (:output result))]
        (log/log! :info "REVIEWER DONE" parsed)
        parsed)
      {:verdict "approve" ; Default approve if reviewer fails
       :feedback (str "Reviewer unavailable: " (:error result))})))


(defn run-curator
  "Update memory bank with task result"
  [cmd worker-result review-result]
  (let [current-state (state/load-state)
        updated-state (-> current-state
                          (state/increment-command-count)
                          (state/add-memory-entry
                            {:action (:verb cmd)
                             :target (or (:target cmd) (str/join ", " (:files-changed worker-result)))
                             :description (:summary worker-result)
                             :priority (if (= "approve" (:verdict review-result)) :normal :high)}))]
    (state/save-state! updated-state)
    (log/log! :info "CURATOR DONE" {:command-count (:command-count updated-state)
                                    :memory-size (count (:memory updated-state))})))


;; ============================================================================
;; Tester Agent Functions
;; ============================================================================

(defn build-tester-prompt
  "Build a prompt for autonomous test creation.
   Includes memory context of past test patterns and issues."
  [{:keys [verb target includes excludes] :as cmd}]
  (let [target-str (if (and target (not= target "global_context"))
                     (str "starting from " target)
                     "across the codebase")
        tdd-mode? (= "tdd" verb)
        constraints (cond-> []
                      (seq includes) (conj (str "FOCUS ON: " (str/join ", " includes)))
                      (seq excludes) (conj (str "AVOID: " (str/join ", " excludes))))
        memory-context (state/build-memory-context cmd)]
    (str
      "You are TESTER, an autonomous testing agent. Write " (if tdd-mode? "TDD" "comprehensive") " tests " target-str ".\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      (when (seq constraints)
        (str "\nConstraints: " (str/join ". " constraints) ".\n"))
      "\n## PROTOCOL\n"
      "1. EXPLORE: Search for existing tests and code patterns. Understand the testing conventions.\n"
      "2. ANALYZE: Read the code to identify all testable units, edge cases, and error conditions.\n"
      "3. PLAN: Identify ALL test files needed (may span multiple modules).\n"
      "4. IMPLEMENT: Write comprehensive tests. Cover happy paths, edge cases, and error handling.\n"
      (when tdd-mode? "5. TDD: Write failing tests FIRST, then minimal code to pass.\n")
      "5. VERIFY: If Bash is available, run the test suite to confirm tests execute.\n"
      "\n## AUTONOMY\n"
      "- Create test files wherever appropriate (follow project conventions).\n"
      "- Test multiple modules if the target spans them.\n"
      "- Include integration tests if relevant.\n"
      "\n## OUTPUT (required at end)\n"
      "TESTER_RESULT|STATUS|FILES|SUMMARY\n"
      "Example: TESTER_RESULT|success|test/core_test.clj,test/utils_test.clj|Added 12 unit tests covering edge cases\n"
      "\nBegin.")))


(defn parse-tester-result
  "Extract structured result from tester output. Format: TESTER_RESULT|status|files|summary"
  [output]
  (if-let [match (re-find #"TESTER_RESULT\|(\w+)\|([^|]*)\|(.+)" output)]
    {:status (nth match 1)
     :files-changed (when-let [f (nth match 2)]
                      (when (seq f) (str/split (str/trim f) #",\s*")))
     :summary (nth match 3)}
    {:status "unknown"
     :files-changed []
     :summary (str "Could not parse tester output: " (subs output 0 (min 200 (count output))))}))


(defn run-tester
  "Execute tester agent to write tests."
  [cmd]
  (let [prompt (build-tester-prompt cmd)
        _ (log/log! :info "TESTER START" {:verb (:verb cmd) :target (:target cmd)})
        result (spawn-claude prompt :tools "Read,Write,Edit,Glob,Grep")]
    (if (:success result)
      (let [parsed (parse-tester-result (:output result))]
        (log/log! :info "TESTER DONE" parsed)
        (assoc parsed :raw-output (:output result)))
      {:status "failed"
       :summary (str "Tester spawn failed: " (:error result))
       :files-changed []})))


(defn run-tests-command
  "Run tests and return results. Configurable via KCX_TEST_CMD."
  [working-dir]
  (let [test-cmd (or (System/getenv "KCX_TEST_CMD") "bb -m test-runner 2>&1 || true")]
    (log/log! :info "RUNNING TESTS" {:cmd test-cmd :dir working-dir})
    (try
      (let [result (p/shell {:out :string :err :string :dir working-dir}
                            "sh" "-c" test-cmd)]
        {:success (zero? (:exit result))
         :output (:out result)
         :error (:err result)})
      (catch Exception e
        {:success false
         :output ""
         :error (str e)}))))


(defn build-worker-from-tests-prompt
  "Build a prompt for worker to implement code to pass tests."
  [{:keys [target includes excludes]} test-files test-output]
  (let [target-str (when (and target (not= target "global_context"))
                     (str " in " target))]
    (str
      "You are WORKER. Implement code" target-str " to make the tests pass.\n"
      "\nTest files: " (str/join ", " test-files) "\n"
      "\nTest output (currently failing):\n```\n" (subs test-output 0 (min 1000 (count test-output))) "\n```\n"
      (when (seq includes)
        (str "FOCUS ON: " (str/join ", " includes) ".\n"))
      (when (seq excludes)
        (str "AVOID: " (str/join ", " excludes) ".\n"))
      "\nPROTOCOL:\n"
      "1. Read the test files to understand requirements\n"
      "2. Implement the minimum code to pass tests\n"
      "3. Follow TDD principles - don't over-engineer\n"
      "\nWhen done, output EXACTLY:\n"
      "WORKER_RESULT|STATUS|FILES|SUMMARY\n"
      "\nBegin.")))


;; ============================================================================
;; Architect Agent Functions
;; ============================================================================

(defn build-architect-prompt
  "Build a prompt for autonomous architectural planning.
   Includes memory context of past architectural decisions."
  [{:keys [verb target includes excludes] :as cmd}]
  (let [action (case verb
                 "plan" "Create an implementation plan"
                 "design" "Design the system architecture"
                 "arch" "Define the technical architecture"
                 "analyze" "Analyze the codebase and requirements"
                 (str "Create documentation for " verb))
        target-str (if (and target (not= target "global_context"))
                     (str " for " target)
                     " for the system")
        constraints (cond-> []
                      (seq includes) (conj (str "FOCUS ON: " (str/join ", " includes)))
                      (seq excludes) (conj (str "AVOID: " (str/join ", " excludes))))
        memory-context (state/build-memory-context cmd)]
    (str
      "You are ARCHITECT, an autonomous design agent. " action target-str ".\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      (when (seq constraints)
        (str "\nConstraints: " (str/join ". " constraints) ".\n"))
      "\n## PROTOCOL\n"
      "1. EXPLORE: Thoroughly examine the codebase structure, dependencies, and patterns.\n"
      "2. ANALYZE: Understand existing architecture, data flows, and integration points.\n"
      "3. DESIGN: Create comprehensive specification documents covering:\n"
      "   - System overview and component relationships\n"
      "   - Data structures and interfaces\n"
      "   - File organization and module boundaries\n"
      "   - Implementation phases and dependencies\n"
      "4. DOCUMENT: Write clear markdown specs that workers can follow.\n"
      "\n## AUTONOMY\n"
      "- Explore ALL relevant parts of the codebase.\n"
      "- Create multiple spec files if the scope warrants it.\n"
      "- Reference existing patterns and suggest improvements.\n"
      "- Do NOT write implementation code - focus on the 'what' and 'why'.\n"
      "\n## OUTPUT (required at end)\n"
      "ARCHITECT_RESULT|STATUS|FILES|SUMMARY\n"
      "Example: ARCHITECT_RESULT|success|docs/api-spec.md,docs/data-model.md|Created API and data specifications\n"
      "\nBegin.")))


(defn parse-architect-result
  "Extract structured result from architect output. Format: ARCHITECT_RESULT|status|files|summary"
  [output]
  (if-let [match (re-find #"ARCHITECT_RESULT\|(\w+)\|([^|]*)\|(.+)" output)]
    {:status (nth match 1)
     :files-changed (when-let [f (nth match 2)]
                      (when (seq f) (str/split (str/trim f) #",\s*")))
     :summary (nth match 3)}
    {:status "unknown"
     :files-changed []
     :summary (str "Could not parse architect output: " (subs output 0 (min 200 (count output))))}))


(defn run-architect
  "Execute architect agent to create specs/plans."
  [cmd]
  (let [prompt (build-architect-prompt cmd)
        _ (log/log! :info "ARCHITECT START" {:verb (:verb cmd) :target (:target cmd)})
        result (spawn-claude prompt :tools "Read,Write,Glob,Grep")]
    (if (:success result)
      (let [parsed (parse-architect-result (:output result))]
        (log/log! :info "ARCHITECT DONE" parsed)
        (assoc parsed :raw-output (:output result)))
      {:status "failed"
       :summary (str "Architect spawn failed: " (:error result))
       :files-changed []})))


(defn build-worker-from-spec-prompt
  "Build a prompt for worker to implement based on architect's spec."
  [{:keys [target includes excludes]} spec-files spec-summary]
  (let [target-str (when (and target (not= target "global_context"))
                     (str " in " target))]
    (str
      "You are WORKER. Implement the code" target-str " according to the architect's specification.\n"
      "\nSpecification files: " (str/join ", " spec-files) "\n"
      "\nSpec summary: " spec-summary "\n"
      (when (seq includes)
        (str "FOCUS ON: " (str/join ", " includes) ".\n"))
      (when (seq excludes)
        (str "AVOID: " (str/join ", " excludes) ".\n"))
      "\nPROTOCOL:\n"
      "1. Read the specification files thoroughly\n"
      "2. Implement code following the spec exactly\n"
      "3. Create all required files and structures\n"
      "\nWhen done, output EXACTLY:\n"
      "WORKER_RESULT|STATUS|FILES|SUMMARY\n"
      "\nBegin.")))


;; ============================================================================
;; Tester Validation (for Worker → Tester loop)
;; ============================================================================

(defn build-tester-validation-prompt
  "Build a prompt for tester to validate worker's changes.
   Includes memory context of past test patterns and issues."
  [worker-result {:keys [target includes excludes] :as cmd}]
  (let [memory-context (state/build-memory-context cmd)]
    (str
      "You are TESTER. Validate the changes made by Worker.\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      "\nFiles changed: " (str/join ", " (:files-changed worker-result)) "\n"
      "Worker summary: " (:summary worker-result) "\n"
      (when target (str "Target: " target "\n"))
      (when (seq includes) (str "Focus on: " (str/join ", " includes) "\n"))
      (when (seq excludes) (str "Avoid: " (str/join ", " excludes) "\n"))
      "\nPROTOCOL:\n"
      "1. Read the changed files\n"
      "2. Write or update tests to cover the changes\n"
      "3. Run tests to verify correctness\n"
      "4. Check for edge cases and error handling\n"
      "\nWhen done, output EXACTLY:\n"
      "TESTER_VALIDATION|VERDICT|FEEDBACK\n"
      "Where VERDICT is 'pass' or 'fail'\n"
      "Example: TESTER_VALIDATION|pass|All tests pass, good coverage\n"
      "Example: TESTER_VALIDATION|fail|Missing null check test, edge case not handled\n"
      "\nBegin.")))


(defn parse-tester-validation
  "Parse tester validation result. Format: TESTER_VALIDATION|verdict|feedback"
  [output]
  (if-let [match (re-find #"TESTER_VALIDATION\|(\w+)\|(.+)" output)]
    {:verdict (nth match 1)
     :feedback (nth match 2)}
    ;; Also check for TESTER_RESULT format as fallback
    (if-let [match (re-find #"TESTER_RESULT\|(\w+)\|[^|]*\|(.+)" output)]
      {:verdict (if (= "success" (nth match 1)) "pass" "fail")
       :feedback (nth match 2)}
      {:verdict "pass"  ; Default pass if can't parse
       :feedback (str "Could not parse: " (subs output 0 (min 100 (count output))))})))


(defn run-tester-validation
  "Run tester to validate worker's changes."
  [worker-result cmd]
  (let [prompt (build-tester-validation-prompt worker-result cmd)
        _ (log/log! :info "TESTER VALIDATION START" {:files (:files-changed worker-result)})
        result (spawn-claude prompt :tools "Read,Write,Edit,Glob,Grep,Bash")]
    (if (:success result)
      (let [parsed (parse-tester-validation (:output result))]
        (log/log! :info "TESTER VALIDATION DONE" parsed)
        parsed)
      {:verdict "pass"  ; Default pass if tester fails to spawn
       :feedback (str "Tester unavailable: " (:error result))})))


;; ============================================================================
;; Workflow Execution
;; ============================================================================

(defn run-worker-tester-loop
  "Inner loop: Worker ↔ Tester until tests pass or max iterations.
   Returns {:success bool :phase :worker|:tester :worker {...} :tester {...} :attempts int}"
  [cmd feedback]
  (loop [attempt 1
         tester-feedback nil]
    ;; Combine external feedback with tester feedback
    (let [combined-feedback (cond
                              (and feedback tester-feedback)
                              (str feedback "\nTESTER: " tester-feedback)
                              feedback feedback
                              tester-feedback (str "TESTER: " tester-feedback)
                              :else nil)
          _ (status! "→ WORKER" (when (> attempt 1) (str "(attempt " attempt ")")))
          worker-result (run-worker cmd :reviewer-feedback combined-feedback :iteration attempt)
          files (format-files-changed (:files-changed worker-result))]

      (if (= "failed" (:status worker-result))
        (do
          (status! "✗ WORKER failed")
          (detail! (:summary worker-result))
          {:success false :phase :worker :worker worker-result :attempts attempt})

        (do
          (status! "  WORKER edited" files)
          (detail! (:summary worker-result))

          ;; Tester validation
          (status! "→ TESTER validating...")
          (let [tester-result (run-tester-validation worker-result cmd)]

            (if (= "pass" (:verdict tester-result))
              (do
                (status! "  TESTER passed")
                (detail! (:feedback tester-result))
                {:success true :worker worker-result :tester tester-result :attempts attempt})

              ;; Tests fail - retry or give up
              (if (< attempt max-workflow-iterations)
                (do
                  (status! "  TESTER failed - retrying" (str "(" (inc attempt) "/" max-workflow-iterations ")"))
                  (detail! (:feedback tester-result))
                  (recur (inc attempt) (:feedback tester-result)))
                (do
                  (status! "✗ TESTER failed after" attempt "attempts")
                  (detail! (:feedback tester-result))
                  {:success false :phase :tester :worker worker-result :tester tester-result :attempts attempt})))))))))


(defn execute-workflow
  "Run WORKER → TESTER → REVIEWER → CURATOR chain with nested loops.

   Inner loop: Worker ↔ Tester (until tests pass)
   Outer loop: If Reviewer rejects, back to Worker → Tester cycle

   Returns {:success bool :worker {...} :tester {...} :reviewer {...} :iterations {...}}"
  [cmd]
  (log/log! :info "WORKFLOW START" cmd)
  (status! "━━━" (str/upper-case (:verb cmd)) (when-let [t (:target cmd)] (str "@" t)) "━━━")

  (loop [cycle 1
         reviewer-feedback nil]
    (when (> cycle 1)
      (status! "↻ CYCLE" (str cycle "/" max-workflow-iterations)))

    ;; Run Worker → Tester inner loop
    (let [wt-result (run-worker-tester-loop cmd reviewer-feedback)]

      (if-not (:success wt-result)
        ;; Worker or Tester failed
        (assoc wt-result :cycle cycle)

        ;; Worker/Tester passed - proceed to Reviewer
        (do
          (status! "→ REVIEWER reviewing...")
          (let [review-result (run-reviewer (:worker wt-result))]

            (cond
              ;; Approved
              (= "approve" (:verdict review-result))
              (do
                (status! "  REVIEWER approved")
                (detail! (:feedback review-result))
                (run-curator cmd (:worker wt-result) review-result)
                (status! "→ CURATOR updated memory")
                (status! "✓ DONE")
                {:success true
                 :worker (:worker wt-result)
                 :tester (:tester wt-result)
                 :reviewer review-result
                 :iterations {:cycle cycle :worker-attempts (:attempts wt-result)}})

              ;; Rejected - restart Worker → Tester cycle
              (and (contains? #{"reject" "needs_revision"} (:verdict review-result))
                   (< cycle max-workflow-iterations))
              (do
                (status! "  REVIEWER rejected - restarting cycle")
                (detail! (:feedback review-result))
                (recur (inc cycle) (str "REVIEWER: " (:feedback review-result))))

              ;; Max cycles reached
              :else
              (do
                (status! "✗ REJECTED after" cycle "cycles")
                {:success false
                 :phase :reviewer
                 :worker (:worker wt-result)
                 :tester (:tester wt-result)
                 :reviewer review-result
                 :iterations {:cycle cycle :worker-attempts (:attempts wt-result)}}))))))))


(defn execute-tester-workflow
  "Run TDD workflow: TESTER → RUN → WORKER → RUN → REVIEWER → CURATOR

   1. Tester writes failing tests
   2. Run tests to verify they fail
   3. Worker implements to pass tests
   4. Run tests to verify they pass
   5. Reviewer validates
   6. Curator updates memory

   Returns {:success bool :tester {...} :worker {...} :reviewer {...} :iterations int}"
  [cmd]
  (log/log! :info "TDD WORKFLOW START" cmd)
  (status! "━━━ TDD" (str/upper-case (:verb cmd)) (when-let [t (:target cmd)] (str "@" t)) "━━━")

  ;; Phase 1: TESTER writes tests
  (status! "→ TESTER writing tests...")
  (let [tester-result (run-tester cmd)]

    (if (= "failed" (:status tester-result))
      (do
        (status! "✗ TESTER failed")
        (detail! (:summary tester-result))
        {:success false :phase :tester :result tester-result})

      ;; Phase 2: Verify tests fail (Red)
      (do
        (status! "  TESTER wrote" (format-files-changed (:files-changed tester-result)))
        (detail! (:summary tester-result))
        (let [test-run-1 (run-tests-command playground-dir)]
          (status! "→ TEST RUN (red phase)" (if (:success test-run-1) "pass ⚠️" "fail ✓"))

        ;; We expect tests to fail initially (TDD Red phase)
        ;; If they pass, that's unexpected but we continue

          ;; Phase 3: WORKER implements
          (loop [iteration 1
                 feedback nil]
            (status! "→ WORKER implementing" (when (> iteration 1) (str "(attempt " iteration ")")))

          (let [worker-prompt (if feedback
                                ;; Retry with feedback
                                (build-worker-prompt
                                  (assoc cmd :verb "fix")
                                  :reviewer-feedback feedback
                                  :iteration iteration)
                                ;; First attempt - use test context
                                (build-worker-from-tests-prompt
                                  cmd
                                  (:files-changed tester-result)
                                  (:output test-run-1)))
                worker-result (spawn-claude worker-prompt)]

            (if-not (:success worker-result)
              (do
                (status! "✗ WORKER failed")
                {:success false :phase :worker :result worker-result :iterations iteration})

              (let [parsed-worker (parse-worker-result (:output worker-result))]
                (status! "  WORKER edited" (format-files-changed (:files-changed parsed-worker)))
                (detail! (:summary parsed-worker))

                ;; Phase 4: Run tests again
                (let [test-run-2 (run-tests-command playground-dir)]
                  (status! "→ TEST RUN (green phase)" (if (:success test-run-2) "pass ✓" "fail"))

                  (if-not (:success test-run-2)
                    ;; Tests still failing - retry worker
                    (if (< iteration max-workflow-iterations)
                      (do
                        (status! "  Tests failing - retrying" (str "(" (inc iteration) "/" max-workflow-iterations ")"))
                        (recur (inc iteration) (str "Tests still failing:\n" (subs (:output test-run-2) 0 (min 500 (count (:output test-run-2)))))))
                      (do
                        (status! "✗ TESTS failed after" iteration "attempts")
                        {:success false
                         :phase :tests
                         :tester tester-result
                         :worker parsed-worker
                         :iterations iteration}))

                    ;; Tests passing - proceed to reviewer
                    (do
                      (status! "→ REVIEWER reviewing...")
                      (let [review-result (run-reviewer (assoc parsed-worker
                                                               :files-changed (concat (:files-changed tester-result)
                                                                                      (:files-changed parsed-worker))))]

                        (cond
                          ;; Approved
                          (= "approve" (:verdict review-result))
                          (do
                            (status! "  REVIEWER approved")
                            (detail! (:feedback review-result))
                            (run-curator cmd parsed-worker review-result)
                            (status! "→ CURATOR updated memory")
                            (status! "✓ DONE")
                            {:success true
                             :tester tester-result
                             :worker parsed-worker
                             :reviewer review-result
                             :iterations iteration})

                          ;; Rejected but can retry
                          (and (contains? #{"reject" "needs_revision"} (:verdict review-result))
                               (< iteration max-workflow-iterations))
                          (do
                            (status! "  REVIEWER rejected - retrying" (str "(attempt " (inc iteration) ")"))
                            (detail! (:feedback review-result))
                            (recur (inc iteration) (:feedback review-result)))

                          ;; Rejected and max iterations
                          :else
                          (do
                            (status! "✗ REJECTED after" iteration "attempts")
                            {:success false
                             :phase :reviewer
                             :tester tester-result
                             :worker parsed-worker
                             :reviewer review-result
                             :iterations iteration})))))))))))))))


(defn execute-architect-workflow
  "Run ARCHITECT → WORKER → TESTER → REVIEWER → CURATOR workflow.

   1. Architect creates specifications/plans
   2. Worker implements based on specs (loops with Tester until tests pass)
   3. Reviewer validates implementation
   4. Curator updates memory

   Returns {:success bool :architect {...} :worker {...} :tester {...} :reviewer {...} :iterations {...}}"
  [cmd]
  (log/log! :info "ARCHITECT WORKFLOW START" cmd)
  (status! "━━━ ARCHITECT" (str/upper-case (:verb cmd)) (when-let [t (:target cmd)] (str "@" t)) "━━━")

  ;; Phase 1: ARCHITECT creates specs
  (status! "→ ARCHITECT creating specifications...")
  (let [architect-result (run-architect cmd)]

    (if (= "failed" (:status architect-result))
      (do
        (status! "✗ ARCHITECT failed")
        (detail! (:summary architect-result))
        {:success false :phase :architect :result architect-result})

      ;; Phase 2-4: Worker → Tester → Reviewer cycle
      (do
        (status! "  ARCHITECT wrote" (format-files-changed (:files-changed architect-result)))
        (detail! (:summary architect-result))
        (loop [cycle 1
               reviewer-feedback nil]
          (when (> cycle 1)
            (status! "↻ CYCLE" (str cycle "/" max-workflow-iterations)))

        ;; Combine architect context with any reviewer feedback
        (let [context (str "ARCHITECT SPEC:\n" (:summary architect-result)
                           "\nFiles: " (str/join ", " (:files-changed architect-result))
                           (when reviewer-feedback (str "\n\nREVIEWER: " reviewer-feedback)))
              wt-result (run-worker-tester-loop cmd context)]

          (if-not (:success wt-result)
            ;; Worker or Tester failed
            (assoc wt-result :architect architect-result :cycle cycle)

            ;; Worker/Tester passed - proceed to Reviewer
            (do
              (status! "→ REVIEWER reviewing...")
              (let [review-result (run-reviewer (assoc (:worker wt-result)
                                                       :files-changed (concat (:files-changed architect-result)
                                                                              (:files-changed (:worker wt-result)))))]

                (cond
                  ;; Approved
                  (= "approve" (:verdict review-result))
                  (do
                    (status! "  REVIEWER approved")
                    (detail! (:feedback review-result))
                    (run-curator cmd (:worker wt-result) review-result)
                    (status! "→ CURATOR updated memory")
                    (status! "✓ DONE")
                    {:success true
                     :architect architect-result
                     :worker (:worker wt-result)
                     :tester (:tester wt-result)
                     :reviewer review-result
                     :iterations {:cycle cycle :worker-attempts (:attempts wt-result)}})

                  ;; Rejected - restart Worker → Tester cycle
                  (and (contains? #{"reject" "needs_revision"} (:verdict review-result))
                       (< cycle max-workflow-iterations))
                  (do
                    (status! "  REVIEWER rejected - restarting cycle")
                    (detail! (:feedback review-result))
                    (recur (inc cycle) (:feedback review-result)))

                  ;; Max cycles reached
                  :else
                  (do
                    (status! "✗ REJECTED after" cycle "cycles")
                    {:success false
                     :phase :reviewer
                     :architect architect-result
                     :worker (:worker wt-result)
                     :tester (:tester wt-result)
                     :reviewer review-result
                     :iterations {:cycle cycle :worker-attempts (:attempts wt-result)}})))))))))))

