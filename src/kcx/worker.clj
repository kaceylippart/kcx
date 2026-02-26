(ns kcx.worker
  "Spawns isolated Claude instances for autonomous work.

   Current approach: CLI-based spawning with clean environment (env -i).
   This works with Claude CLI authentication (Team, Pro, etc.).

   Future: Direct API support via kcx.claude-api when ANTHROPIC_API_KEY is available.
   See src/kcx/claude_api.clj for the API-based implementation."
  (:require
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [kcx.expand :as expand]
    [kcx.logging :as log]
    [kcx.state :as state]))


(def max-workflow-iterations
  "Maximum WORKER → REVIEWER iterations before giving up. Override with KCX_MAX_ITERATIONS."
  (or (some-> (System/getenv "KCX_MAX_ITERATIONS") parse-long) 3))

;; Sanitize command components to prevent injection
(defn sanitize-shell-arg
  "Safely sanitize a string for shell usage by removing dangerous characters"
  [s]
  (when s
    (let [s (str s)]  ; Ensure it's a string
      (-> s
          ;; Remove (not escape) dangerous shell metacharacters
          (str/replace #"[;|&><$`\\]" "")
          ;; Remove path traversal patterns
          (str/replace #"\.\." "")
          ;; Remove line breaks
          (str/replace #"[\r\n]" "")
          ;; Escape quotes safely after removing dangerous chars
          (str/replace "\"" "\\\"")
          (str/replace "'" "\\'")
          ;; Limit length to prevent DoS - safe bounds check
          (#(let [len (count %)]
              (if (> len 1000) (subs % 0 1000) %)))))))

;; Process cleanup registry
(def ^:private active-processes (atom #{}))

(defn ^:private register-process [proc]
  (swap! active-processes conj proc)
  proc)

(defn ^:private unregister-process [proc]
  (swap! active-processes disj proc))

(defn shutdown-all-processes
  "Emergency cleanup - kill all active processes"
  []
  (log/log! :warn "SHUTDOWN ALL PROCESSES" {:count (count @active-processes)})
  (doseq [proc @active-processes]
    (try
      (when (and proc (not (realized? (:exit proc))))
        (.destroy (:proc proc)))
      (catch Exception e
        (log/log-error! "PROCESS CLEANUP FAILED" e))))
  (reset! active-processes #{}))

;; Ensure cleanup on JVM shutdown
(defonce shutdown-hook
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. shutdown-all-processes)))

(defn build-worker-prompt
  "Build a comprehensive prompt for autonomous multi-file work.
   Uses expanded verb/modifiers when available, falls back to raw tokens.
   Optional reviewer-feedback is passed when retrying after rejection.
   Supports :instruction field for natural language context."
  [{:keys [verb target modifiers instruction expanded-verb expanded-modifiers expanded?] :as cmd}
   & {:keys [reviewer-feedback iteration]}]
  (let [;; Sanitize all inputs to prevent injection
        safe-verb (sanitize-shell-arg verb)
        safe-target (sanitize-shell-arg target)
        safe-modifiers (map sanitize-shell-arg modifiers)
        safe-instruction (sanitize-shell-arg instruction)
        safe-reviewer-feedback (sanitize-shell-arg reviewer-feedback)

        ;; Use expanded text if available, otherwise fall back to raw tokens
        task-description (if expanded?
                           expanded-verb
                           (let [action (str/upper-case safe-verb)
                                 target-str (if (and safe-target (not= safe-target "global_context"))
                                              (str "starting from " safe-target)
                                              "across the codebase")]
                             (str action " " target-str)))

        ;; Filter modifiers for worker role
        worker-modifiers (when (seq expanded-modifiers)
                           (expand/filter-modifiers-for :worker expanded-modifiers))

        ;; Legacy constraints (when no expansion)
        constraints (when-not expanded?
                      (when (seq safe-modifiers)
                        [(str "FOCUS ON: " (str/join ", " safe-modifiers))]))

        memory-context (state/build-memory-context cmd)
        retry-context (when safe-reviewer-feedback
                        (str "\n⚠️ PREVIOUS ATTEMPT REJECTED (iteration " iteration ").\n"
                             "Reviewer feedback: " safe-reviewer-feedback "\n"
                             "Address this feedback in your implementation.\n"))]
    (str
      "You are WORKER, an autonomous coding agent. Your task: " task-description "\n"
      (when safe-instruction
        (str "\n## INSTRUCTION\n" safe-instruction "\n"))
      (when (seq worker-modifiers)
        (str "\n## DIRECTIVES\n"
             (str/join "\n" (map :prompt worker-modifiers)) "\n"))
      (when memory-context
        (str "\n" memory-context "\n"))
      (when (seq constraints)
        (str "\nConstraints: " (str/join ". " constraints) ".\n"))
      retry-context
      (if (#{"review" "check" "lint"} safe-verb)
        ;; Review verbs: lightweight read-and-report protocol
        (str "\n## PROTOCOL\n"
             "1. FIND: Locate the target file(s). Use Glob if the path is a namespace.\n"
             "2. READ: Read the file(s) thoroughly.\n"
             "3. REVIEW: Assess correctness, code quality, edge cases, and potential issues.\n")
        ;; Implementation verbs: full explore-and-implement protocol
        (str "\n## PROTOCOL\n"
             "1. EXPLORE: Search the codebase to understand the full scope. Use Glob/Grep to find all related files.\n"
             "2. ANALYZE: Read files to understand dependencies, patterns, and architecture.\n"
             "3. PLAN: Identify ALL files that need changes (not just the target).\n"
             "4. IMPLEMENT: Make comprehensive changes across all necessary files.\n"
             "5. VERIFY: If Bash is available, run tests/build to confirm changes work.\n"
             "\n## AUTONOMY\n"
             "- You have FULL permission to modify ANY file needed to complete the task.\n"
             "- Change as many files as necessary - don't limit yourself to one file.\n"
             "- Follow existing patterns and conventions in the codebase.\n"
             "- If you find related issues while working, fix them too.\n"))
      "\n## OUTPUT (required at end)\n"
      "WORKER_RESULT|STATUS|FILES|SUMMARY\n"
      "- STATUS: 'success' or 'failed'\n"
      "- FILES: comma-separated list of ALL files you modified\n"
      "- SUMMARY: brief description of changes\n"
      "Example: WORKER_RESULT|success|src/core.clj,src/utils.clj,test/core_test.clj|Refactored error handling across 3 files\n"
      "\nBegin.")))


(defn build-natural-prompt
  "Build a prompt for natural language task execution.
   Takes the user's prompt directly and wraps it with protocol instructions."
  [{:keys [prompt target] :as cmd} & {:keys [reviewer-feedback iteration]}]
  (let [;; Sanitize inputs to prevent injection
        safe-prompt (sanitize-shell-arg prompt)
        safe-reviewer-feedback (sanitize-shell-arg reviewer-feedback)

        memory-context (state/build-memory-context cmd)
        retry-context (when safe-reviewer-feedback
                        (str "\n⚠️ PREVIOUS ATTEMPT REJECTED (iteration " iteration ").\n"
                             "Reviewer feedback: " safe-reviewer-feedback "\n"
                             "Address this feedback in your implementation.\n"))]
    (str
      "You are WORKER, an autonomous coding agent.\n\n"
      "## YOUR TASK\n"
      safe-prompt "\n\n"
      (when memory-context
        (str "## CONTEXT FROM PREVIOUS WORK\n" memory-context "\n\n"))
      retry-context
      "## PROTOCOL\n"
      "1. EXPLORE: Search the codebase to understand the full scope. Use Glob/Grep to find relevant files.\n"
      "2. ANALYZE: Read files to understand dependencies, patterns, and architecture.\n"
      "3. PLAN: Identify ALL files that need changes.\n"
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
      "Example: WORKER_RESULT|success|src/core.clj,src/utils.clj|Implemented requested feature\n"
      "\nBegin.")))


(defn build-reviewer-prompt
  "Build prompt for reviewer to check worker's changes.
   Includes memory context of past issues and patterns.
   Uses expanded modifiers targeted at :reviewer when available."
  [worker-result files-changed & {:keys [cmd]}]
  (let [memory-context (when cmd (state/build-memory-context cmd))
        reviewer-modifiers (when (seq (:expanded-modifiers cmd))
                             (expand/filter-modifiers-for :reviewer (:expanded-modifiers cmd)))]
    (str
      "You are REVIEWER. Check these changes:\n"
      "Files: " (str/join ", " files-changed) "\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      (when (seq reviewer-modifiers)
        (str "\n## DIRECTIVES\n"
             (str/join "\n" (map :prompt reviewer-modifiers)) "\n"))
      "Summary: " (:summary worker-result) "\n"
      "\nRead the files. Verify correctness.\n"
      "\nOutput EXACTLY:\n"
      "REVIEW_RESULT|VERDICT|FEEDBACK\n"
      "Example: REVIEW_RESULT|approve|Looks good, zero check added correctly")))


(defn parse-worker-result
  "Extract structured result from worker output. Format: WORKER_RESULT|status|files|summary"
  [output]
  (if (and output (string? output))
    (if-let [match (re-find #"WORKER_RESULT\|(\w+)\|([^|]*)\|(.+)" output)]
      {:status (nth match 1)
       :files-changed (when-let [f (nth match 2)]
                        (when (seq f) (str/split (str/trim f) #",\s*")))
       :summary (nth match 3)}
      {:status "unknown"
       :files-changed []
       :summary (str "Could not parse output: " (subs output 0 (min 200 (count output))))})
    {:status "unknown"
     :files-changed []
     :summary "No output provided"}))


(defn parse-review-result
  "Extract structured result from reviewer output. Format: REVIEW_RESULT|verdict|feedback"
  [output]
  (if-let [match (re-find #"REVIEW_RESULT\|(\w+)\|(.+)" output)]
    {:verdict (nth match 1)
     :feedback (nth match 2)}
    {:verdict "approve"  ; Default approve if can't parse
     :feedback (str "Could not parse: " (subs output 0 (min 100 (count output))))}))


(def home-dir (System/getProperty "user.home"))

;; Find claude binary - check common locations with proper escaping
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
;; Job Tracking with Memory Management
;; ============================================================================
;; Track running jobs for status queries with TTL

(def ^:dynamic *current-job* nil)
(defonce jobs-state (atom {}))

;; Clean old jobs periodically to prevent memory leaks
(def job-ttl-ms (* 24 60 60 1000)) ; 24 hours

(defn clean-old-jobs!
  "Remove jobs older than TTL to prevent memory leaks"
  []
  (let [now (System/currentTimeMillis)
        cutoff (- now job-ttl-ms)]
    (swap! jobs-state
           (fn [jobs]
             (into {} (filter (fn [[_ job]]
                                (or (= :running (:status job))
                                    (> (or (:end-time job) (:start-time job)) cutoff)))
                              jobs))))))

;; Clean old jobs on a schedule
(defonce job-cleaner
  (future
    (while true
      (Thread/sleep job-ttl-ms)
      (clean-old-jobs!))))

;; ============================================================================
;; Last Command Tracking (for !redo)
;; ============================================================================
;; Track the last executed workflow command for redo functionality

(defonce last-command-state (atom nil))

(defn set-last-command!
  "Store the last executed command for redo functionality."
  [cmd]
  (reset! last-command-state cmd))

(defn get-last-command
  "Get the last executed command."
  []
  @last-command-state)

(defn merge-redo-command
  "Merge redo modifiers with the last command.
   - New modifiers are added to existing modifiers
   - New directives are added to existing directives
   - New instruction replaces or appends to existing instruction
   - Target from redo overrides if specified (not global_context)"
  [last-cmd redo-cmd]
  (let [;; Merge modifiers (add new ones)
        merged-modifiers (vec (distinct (concat (:modifiers last-cmd [])
                                                 (:modifiers redo-cmd []))))
        ;; Merge directives (add new ones)
        merged-directives (vec (distinct (concat (:directives last-cmd [])
                                                  (:directives redo-cmd []))))
        ;; Handle instruction - append if both exist, otherwise use whichever exists
        merged-instruction (cond
                             (and (:instruction last-cmd) (:instruction redo-cmd))
                             (str (:instruction last-cmd) "\n\nADDITIONAL: " (:instruction redo-cmd))

                             (:instruction redo-cmd)
                             (:instruction redo-cmd)

                             :else
                             (:instruction last-cmd))
        ;; Target - use redo's target if specified, otherwise keep original
        merged-target (if (and (:target redo-cmd)
                               (not= "global_context" (:target redo-cmd)))
                        (:target redo-cmd)
                        (:target last-cmd))]
    (assoc last-cmd
           :modifiers merged-modifiers
           :directives merged-directives
           :instruction merged-instruction
           :target merged-target
           :is-redo true
           :original-cmd last-cmd)))

(defn generate-job-id []
  (str (java.util.UUID/randomUUID)))

(defn format-elapsed
  "Format elapsed time in human-readable form."
  [start-ms]
  (let [elapsed (- (System/currentTimeMillis) start-ms)
        secs (quot elapsed 1000)
        mins (quot secs 60)
        secs-rem (mod secs 60)]
    (cond
      (< secs 60) (str secs "s")
      :else (str mins "m " secs-rem "s"))))

(defn start-job!
  "Start tracking a new job. Returns job-id."
  [cmd]
  (let [job-id (generate-job-id)
        job {:id job-id
             :cmd cmd
             :start-time (System/currentTimeMillis)
             :phase :starting
             :phase-start (System/currentTimeMillis)
             :status :running}]
    (swap! jobs-state assoc job-id job)
    job-id))

(defn update-job-phase!
  "Update the current job's phase."
  ([job-id phase]
   (update-job-phase! job-id phase nil))
  ([job-id phase extra-info]
   (swap! jobs-state update job-id merge
          {:phase phase
           :phase-start (System/currentTimeMillis)}
          (when extra-info extra-info))))

(defn complete-job!
  "Mark a job as complete."
  [job-id success?]
  (swap! jobs-state update job-id assoc
         :status (if success? :complete :failed)
         :end-time (System/currentTimeMillis)))

(defn get-running-jobs
  "Get all currently running jobs."
  []
  (->> @jobs-state
       vals
       (filter #(= :running (:status %)))))

(defn get-job [job-id]
  (get @jobs-state job-id))

(defn format-job-status
  "Format a job's status for display."
  [{:keys [id cmd phase start-time phase-start status agent iteration] :as job}]
  (when job
    (let [total-elapsed (format-elapsed start-time)
          phase-elapsed (format-elapsed phase-start)]
      (str "Job: " (:verb cmd) (when (:target cmd) (str " @" (:target cmd)))
           "\n  ID: " (subs id 0 8) "..."
           "\n  Status: " (name status)
           "\n  Phase: " (name phase) " (" phase-elapsed ")"
           (when agent (str "\n  Agent: " (name agent)))
           (when iteration (str "\n  Iteration: " iteration))
           "\n  Total time: " total-elapsed))))

;; ============================================================================
;; Clean Output Helpers
;; ============================================================================
;; Status lines are accumulated in an atom and returned with the workflow result
;; so they can be included in the MCP response (since MCP captures stdout/stderr)

(def ^:dynamic *status-lines* nil)
(def ^:dynamic *workflow-start* nil)
(def ^:dynamic *progress-callback* nil)

(defn status!
  "Record a status line. Accumulated for MCP response.
   Also sends MCP progress notification if callback is bound."
  [& parts]
  (let [elapsed (when *workflow-start*
                  (str "[" (format-elapsed *workflow-start*) "]"))
        line (str (when elapsed (str elapsed " "))
                  (str/join " " (map str parts)))]
    (when *status-lines*
      (swap! *status-lines* conj line))
    ;; Send MCP progress notification in real-time
    (when *progress-callback*
      (*progress-callback* line))
    ;; Also try stderr in case it works in some contexts
    (binding [*out* *err*]
      (println line))))

(defn detail!
  "Record a detail line. Only in verbose mode."
  [& parts]
  (when (verbose?)
    (let [line (str "  " (str/join " " (map str parts)))]
      (when *status-lines*
        (swap! *status-lines* conj line))
      (binding [*out* *err*]
        (println line)))))

(defn with-status-capture
  "Execute f while capturing status lines. Returns [result status-lines]."
  [f]
  (binding [*status-lines* (atom [])]
    (let [result (f)]
      [result @*status-lines*])))

(defn format-status-lines
  "Format captured status lines for display."
  [lines]
  (when (seq lines)
    (str/join "\n" lines)))

(defn format-files-changed
  "Format file changes compactly: '3 files' or 'file.clj'"
  [files]
  (let [n (count files)]
    (cond
      (zero? n) "no files"
      (= 1 n) (first files)
      :else (str n " files"))))


;; Heartbeat interval for status updates during long operations (15 seconds)
(def heartbeat-interval-ms 15000)


(defn spawn-claude
  "Spawn a Claude instance with the given prompt, return output.

   Uses env -i for clean environment isolation - only passes essential vars.
   This ensures the spawned Claude isn't affected by parent session config
   (e.g., Bedrock vs direct API, nested Claude detection, etc.).

   Emits periodic heartbeat status updates to show the agent is still working.
   
   Enhanced with proper timeout handling and resource cleanup."
  [prompt & {:keys [timeout-ms working-dir tools permission-mode agent-name]
             :or {timeout-ms 300000
                  working-dir worker-working-dir
                  tools worker-tools
                  permission-mode worker-permission-mode
                  agent-name "AGENT"}}]
  (log/log! :info "SPAWN CLAUDE" {:prompt-length (count prompt)
                                  :working-dir working-dir
                                  :timeout-ms timeout-ms
                                  :claude-path claude-path
                                  :model worker-model
                                  :tools tools
                                  :permission-mode permission-mode})
  (let [proc (atom nil)
        cleanup-fn (fn []
                     (when-let [p @proc]
                       (unregister-process p)
                       (when (and (:exit p) (not (realized? (:exit p))))
                         (try
                           (.destroy (:proc p))
                           (catch Exception e
                             (log/log-error! "PROCESS CLEANUP FAILED" e))))))]
    (try
      ;; Use env -i for clean spawn - only pass essential vars with sanitization
      ;; This isolates the child from parent Claude session config
      (let [safe-model (sanitize-shell-arg worker-model)
            safe-tools (sanitize-shell-arg tools)
            safe-permission-mode (sanitize-shell-arg permission-mode)
            ;; Prompt preserves newlines and pipes — only neutralize shell expansion chars
            ;; Inside double quotes, only $, `, \, and " are special
            safe-prompt (-> prompt
                            (str/replace #"[$`\\]" "")
                            (str/replace "\"" "\\\"")
                            (str/replace "'" "\\'"))
            safe-claude-path (sanitize-shell-arg claude-path)
            env-vars (str "PATH=\"$PATH\" "
                          "HOME=\"$HOME\" "
                          "ANTHROPIC_API_KEY=\"${ANTHROPIC_API_KEY:-}\" "
                          "ANTHROPIC_MODEL=\"" safe-model "\" "
                          "KCX_WORKER=true ")
            cmd (str "env -i " env-vars
                     safe-claude-path
                     " --print"
                     " --permission-mode " safe-permission-mode
                     " --tools '" safe-tools "'"
                     " -p " (pr-str safe-prompt)
                     " < /dev/null")
            _ (log/log! :debug "SPAWN CMD" {:cmd cmd})
            spawn-start (System/currentTimeMillis)
            ;; Start process asynchronously with timeout
            p (p/process {:out :string
                          :err :string
                          :dir working-dir}
                         "sh" "-c" cmd)]
        
        (reset! proc p)
        (register-process p)

        ;; Poll for completion with heartbeat and timeout
        (loop [last-heartbeat spawn-start]
          (let [now (System/currentTimeMillis)
                elapsed (- now spawn-start)
                elapsed-since-heartbeat (- now last-heartbeat)]
            (cond
              ;; Timeout reached
              (>= elapsed timeout-ms)
              (do
                (log/log! :warn "CLAUDE TIMEOUT" {:timeout-ms timeout-ms
                                                  :elapsed-ms elapsed})
                (cleanup-fn)
                {:success false
                 :output ""
                 :error (str "Process timed out after " (format-elapsed spawn-start))})

              ;; Process completed
              (not (.isAlive (:proc p)))
              (let [result @p]
                (log/log! :info "CLAUDE COMPLETE" {:exit (:exit result)
                                                   :output-length (count (:out result))
                                                   :elapsed-ms elapsed})
                (detail! agent-name "completed in" (format-elapsed spawn-start))
                (cleanup-fn)
                {:success (zero? (:exit result))
                 :output (:out result)
                 :error (:err result)})

              ;; Time for heartbeat
              (>= elapsed-since-heartbeat heartbeat-interval-ms)
              (do
                (status! "  ⋯" agent-name "working...")
                (Thread/sleep 1000)
                (recur now))

              ;; Keep waiting
              :else
              (do
                (Thread/sleep 1000)
                (recur last-heartbeat))))))
      (catch Exception e
        (cleanup-fn)
        (log/log-error! "CLAUDE SPAWN FAILED" e)
        {:success false
         :output ""
         :error (str e)}))))


(defn run-worker
  "Execute worker agent for a command.
   Optional reviewer-feedback and iteration for retry loops."
  [cmd & {:keys [reviewer-feedback iteration] :or {iteration 1}}]
  (let [prompt (build-worker-prompt cmd :reviewer-feedback reviewer-feedback :iteration iteration)
        _ (log/log! :info "WORKER START" {:verb (:verb cmd)
                                          :target (:target cmd)
                                          :iteration iteration
                                          :has-feedback (some? reviewer-feedback)})
        result (spawn-claude prompt :agent-name "WORKER")]
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
        result (spawn-claude prompt :timeout-ms 120000 :agent-name "REVIEWER")] ; 2 min for review
    (if (:success result)
      (let [parsed (parse-review-result (:output result))]
        (log/log! :info "REVIEWER DONE" parsed)
        parsed)
      {:verdict "approve" ; Default approve if reviewer fails
       :feedback (str "Reviewer unavailable: " (:error result))})))


(defn run-curator
  "Update memory bank with task result"
  [cmd worker-result review-result]
  (try
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
                                      :memory-size (count (:memory updated-state))}))
    (catch Exception e
      (log/log-error! "CURATOR FAILED" e))))


;; ============================================================================
;; Tester Agent Functions
;; ============================================================================

(defn build-tester-prompt
  "Build a prompt for autonomous test creation.
   Uses expanded verb/modifiers when available.
   Supports :instruction field for natural language context."
  [{:keys [verb target modifiers instruction expanded-verb expanded-modifiers expanded?] :as cmd}]
  (let [target-str (if (and target (not= target "global_context"))
                     (str "starting from " target)
                     "across the codebase")
        tdd-mode? (= "tdd" verb)
        ;; Use expanded verb if available for task description
        task-desc (if expanded?
                    expanded-verb
                    (str "Write " (if tdd-mode? "TDD" "comprehensive") " tests " target-str))
        tester-modifiers (when (seq expanded-modifiers)
                           (expand/filter-modifiers-for :tester expanded-modifiers))
        constraints (when-not expanded?
                      (when (seq modifiers)
                        [(str "FOCUS ON: " (str/join ", " modifiers))]))
        memory-context (state/build-memory-context cmd)]
    (str
      "You are TESTER, an autonomous testing agent. " task-desc "\n"
      (when instruction
        (str "\n## INSTRUCTION\n" instruction "\n"))
      (when (seq tester-modifiers)
        (str "\n## DIRECTIVES\n"
             (str/join "\n" (map :prompt tester-modifiers)) "\n"))
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
        result (spawn-claude prompt :tools "Read,Write,Edit,Glob,Grep" :agent-name "TESTER")]
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
        (log/log-error! "TEST COMMAND FAILED" e)
        {:success false
         :output ""
         :error (str e)}))))


(defn build-worker-from-tests-prompt
  "Build a prompt for worker to implement code to pass tests."
  [{:keys [target modifiers]} test-files test-output]
  (let [target-str (when (and target (not= target "global_context"))
                     (str " in " target))]
    (str
      "You are WORKER. Implement code" target-str " to make the tests pass.\n"
      "\nTest files: " (str/join ", " test-files) "\n"
      "\nTest output (currently failing):\n```\n" (subs test-output 0 (min 1000 (count test-output))) "\n```\n"
      (when (seq modifiers)
        (str "FOCUS ON: " (str/join ", " modifiers) ".\n"))
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
   Uses expanded verb/modifiers when available.
   Supports :instruction field for natural language context."
  [{:keys [verb target modifiers instruction expanded-verb expanded-modifiers expanded?] :as cmd}]
  (let [task-desc (if expanded?
                    expanded-verb
                    (let [action (case verb
                                  "plan" "Create an implementation plan"
                                  "design" "Design the system architecture"
                                  "arch" "Define the technical architecture"
                                  "analyze" "Analyze the codebase and requirements"
                                  (str "Create documentation for " verb))
                          target-str (if (and target (not= target "global_context"))
                                       (str " for " target)
                                       " for the system")]
                      (str action target-str)))
        architect-modifiers (when (seq expanded-modifiers)
                              (expand/filter-modifiers-for :architect expanded-modifiers))
        constraints (when-not expanded?
                      (when (seq modifiers)
                        [(str "FOCUS ON: " (str/join ", " modifiers))]))
        memory-context (state/build-memory-context cmd)]
    (str
      "You are ARCHITECT, an autonomous design agent. " task-desc "\n"
      (when instruction
        (str "\n## INSTRUCTION\n" instruction "\n"))
      (when (seq architect-modifiers)
        (str "\n## DIRECTIVES\n"
             (str/join "\n" (map :prompt architect-modifiers)) "\n"))
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
        result (spawn-claude prompt :tools "Read,Write,Glob,Grep" :agent-name "ARCHITECT")]
    (if (:success result)
      (let [parsed (parse-architect-result (:output result))]
        (log/log! :info "ARCHITECT DONE" parsed)
        (assoc parsed :raw-output (:output result)))
      {:status "failed"
       :summary (str "Architect spawn failed: " (:error result))
       :files-changed []})))


(defn build-worker-from-spec-prompt
  "Build a prompt for worker to implement based on architect's spec."
  [{:keys [target modifiers]} spec-files spec-summary]
  (let [target-str (when (and target (not= target "global_context"))
                     (str " in " target))]
    (str
      "You are WORKER. Implement the code" target-str " according to the architect's specification.\n"
      "\nSpecification files: " (str/join ", " spec-files) "\n"
      "\nSpec summary: " spec-summary "\n"
      (when (seq modifiers)
        (str "FOCUS ON: " (str/join ", " modifiers) ".\n"))
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
  [worker-result {:keys [target modifiers] :as cmd}]
  (let [memory-context (state/build-memory-context cmd)]
    (str
      "You are TESTER. Validate the changes made by Worker.\n"
      (when memory-context
        (str "\n" memory-context "\n"))
      "\nFiles changed: " (str/join ", " (:files-changed worker-result)) "\n"
      "Worker summary: " (:summary worker-result) "\n"
      (when target (str "Target: " target "\n"))
      (when (seq modifiers) (str "Focus on: " (str/join ", " modifiers) "\n"))
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
        result (spawn-claude prompt :tools "Read,Write,Edit,Glob,Grep,Bash" :agent-name "TESTER")]
    (if (:success result)
      (let [parsed (parse-tester-validation (:output result))]
        (log/log! :info "TESTER VALIDATION DONE" parsed)
        parsed)
      {:verdict "pass"  ; Default pass if tester fails to spawn
       :feedback (str "Tester unavailable: " (:error result))})))


;; ============================================================================
;; Workflow Handlers
;; ============================================================================
;; Each handler conforms to: (fn [cmd artifacts] -> {:success bool ...})
;; The state machine in kcx.workflow controls sequencing.
;; Handlers control capability — they spawn fully-capable Claude instances.


(defn- extract-feedback
  "Extract feedback from prior artifacts for retry context.
   Looks at tester and reviewer results from previous iterations."
  [artifacts]
  (let [test-feedback   (when-let [t (or (:test artifacts) (:validate artifacts))]
                          (when-not (:success t) (:feedback t)))
        review-feedback (when-let [r (:review artifacts)]
                          (when-not (:success r) (:feedback r)))
        arch-context    (when-let [a (:architect artifacts)]
                          (str "ARCHITECT SPEC:\n" (:summary a)
                               "\nFiles: " (str/join ", " (:files-changed a))))]
    (let [parts (remove nil? [arch-context
                              (when test-feedback (str "TESTER: " test-feedback))
                              (when review-feedback (str "REVIEWER: " review-feedback))])]
      (when (seq parts)
        (str/join "\n" parts)))))

(defn handle-worker
  "Worker handler — implements code changes.
   Reads feedback from prior tester/reviewer artifacts for retries.
   Uses natural language prompt builder when cmd has :prompt key."
  [cmd artifacts]
  (let [feedback (extract-feedback artifacts)
        prompt (if (:prompt cmd)
                 (build-natural-prompt cmd :reviewer-feedback feedback)
                 (build-worker-prompt cmd :reviewer-feedback feedback))]
    (status! "→ Handing off to WORKER...")
    (when *current-job*
      (update-job-phase! *current-job* :worker {:agent :worker}))
    (let [start-ms (System/currentTimeMillis)
          spawn-result (spawn-claude prompt :agent-name "WORKER")
          elapsed (format-elapsed start-ms)]
      (if (:success spawn-result)
        (let [parsed (parse-worker-result (:output spawn-result))
              n-files (count (:files-changed parsed))]
          (log/log! :info "WORKER DONE" parsed)
          (if (= "failed" (:status parsed))
            (do
              (status! "  ✗ WORKER failed after" elapsed)
              (status! "    " (:summary parsed))
              {:success false :files-changed [] :summary (:summary parsed)})
            (do
              (if (zero? n-files)
                (status! "  ✓ WORKER completed in" elapsed "— no files changed")
                (status! "  ✓ WORKER completed in" elapsed "— edited" (format-files-changed (:files-changed parsed))))
              (status! "    " (:summary parsed))
              {:success true
               :files-changed (:files-changed parsed)
               :summary (:summary parsed)
               :raw-output (:output spawn-result)})))
        (do
          (status! "  ✗ WORKER failed to spawn after" elapsed)
          {:success false
           :summary (str "Worker spawn failed: " (:error spawn-result))
           :files-changed []})))))

(defn handle-tester
  "Tester handler — validates worker's changes or writes initial tests.
   In TDD write-tests phase (no prior :work/:implement), writes new tests.
   In validation phase (has prior work), validates changes."
  [cmd artifacts]
  (let [;; Determine if this is a write-tests or validation phase
        worker-result (or (:work artifacts) (:implement artifacts))]
    (status! "→ Handing off to TESTER" (if worker-result "(validating changes)..." "(writing tests)..."))
    (when *current-job*
      (update-job-phase! *current-job* :tester {:agent :tester}))
    (let [start-ms (System/currentTimeMillis)]
      (if worker-result
        ;; Validation mode: check worker's changes
        (let [result (run-tester-validation worker-result cmd)
              elapsed (format-elapsed start-ms)]
          (if (= "pass" (:verdict result))
            (do
              (status! "  ✓ TESTER passed in" elapsed)
              (status! "    " (:feedback result))
              {:success true :verdict "pass" :feedback (:feedback result)})
            (do
              (status! "  ✗ TESTER failed in" elapsed)
              (status! "    " (:feedback result))
              {:success false :verdict "fail" :feedback (:feedback result)})))
        ;; Write-tests mode: create tests from scratch
        (let [result (run-tester cmd)
              elapsed (format-elapsed start-ms)]
          (if (= "failed" (:status result))
            (do
              (status! "  ✗ TESTER failed after" elapsed)
              (status! "    " (:summary result))
              {:success false :summary (:summary result) :files-changed []})
            (do
              (status! "  ✓ TESTER completed in" elapsed "— wrote" (format-files-changed (:files-changed result)))
              (status! "    " (:summary result))
              {:success true
               :files-changed (:files-changed result)
               :summary (:summary result)
               :raw-output (:raw-output result)})))))))

(defn handle-reviewer
  "Reviewer handler — reviews worker's changes, approves or rejects.
   Reads worker artifacts for file list and summary."
  [cmd artifacts]
  (let [worker-result (or (:work artifacts) (:implement artifacts))
        ;; Include architect files if present
        arch-files (get-in artifacts [:architect :files-changed])
        all-files (distinct (concat (or arch-files [])
                                    (or (:files-changed worker-result) [])))]
    (status! "→ Handing off to REVIEWER..." (str "(" (count all-files) " file" (when (not= 1 (count all-files)) "s") " to review)"))
    (when *current-job*
      (update-job-phase! *current-job* :reviewer {:agent :reviewer}))
    (let [start-ms (System/currentTimeMillis)
          review-input (assoc worker-result :files-changed all-files)
          result (run-reviewer review-input)
          elapsed (format-elapsed start-ms)]
      (if (= "approve" (:verdict result))
        (do
          (status! "  ✓ REVIEWER approved in" elapsed)
          (status! "    " (:feedback result))
          {:success true :verdict "approve" :feedback (:feedback result)})
        (do
          (status! "  ✗ REVIEWER rejected in" elapsed)
          (status! "    " (:feedback result))
          {:success false :verdict (:verdict result) :feedback (:feedback result)})))))

(defn build-curator-prompt
  "Build a prompt for curator to intelligently update the memory bank."
  [cmd artifacts current-state]
  (let [worker-result (or (:work artifacts) (:implement artifacts))
        review-result (:review artifacts)
        memory-str (with-out-str (pprint/pprint current-state))]
    (str
      "You are CURATOR, the memory bank manager. Your job is intelligent memory compaction.\n\n"
      "## CURRENT MEMORY BANK\n```edn\n" memory-str "\n```\n\n"
      "## WHAT JUST HAPPENED\n"
      "Action: " (:verb cmd) (when (:target cmd) (str " @" (:target cmd))) "\n"
      (when (:summary worker-result) (str "Summary: " (:summary worker-result) "\n"))
      (when (:files-changed worker-result) (str "Files changed: " (str/join ", " (:files-changed worker-result)) "\n"))
      (when (:feedback review-result) (str "Reviewer feedback: " (:feedback review-result) "\n"))
      "\n## YOUR TASK\n"
      "Update the memory bank state. You must:\n"
      "1. Increment :command-count\n"
      "2. Add a new entry to :memory for this task\n"
      "3. Review existing entries - reprioritize, edit, or remove stale/incorrect ones\n"
      "4. Prune entries that are no longer relevant\n"
      "5. Correct any entries that this task's results invalidate\n\n"
      "Priority levels: :critical (architecture, never expires), :high (100 cmd TTL), :normal (30 cmd TTL), :low (10 cmd TTL)\n\n"
      "## OUTPUT\n"
      "Output ONLY the updated EDN state. No explanation, no markdown fences.\n"
      "The output must be valid EDN matching the structure above.\n"
      "Begin.")))

(defn handle-curator
  "Curator handler — spawns Claude to intelligently update the memory bank.
   Claude reviews the full memory state and makes judgment calls about
   what to add, edit, reprioritize, or prune."
  [cmd artifacts]
  (status! "→ Handing off to CURATOR (compacting memory)...")
  (when *current-job*
    (update-job-phase! *current-job* :curator {:agent :curator}))
  (let [start-ms (System/currentTimeMillis)]
    (try
      (let [current-state (state/load-state)
            prompt (build-curator-prompt cmd artifacts current-state)
            result (spawn-claude prompt
                                :timeout-ms 120000
                                :tools "Read"
                                :agent-name "CURATOR")
            elapsed (format-elapsed start-ms)]
        (if (:success result)
          (let [output (str/trim (:output result))
                new-state (try
                            (edn/read-string output)
                            (catch Exception _
                              nil))]
            (if (and new-state (state/validate-state new-state))
              (do
                (state/save-state! new-state)
                (log/log! :info "CURATOR DONE (intelligent)" {:command-count (:command-count new-state)
                                                               :memory-size (count (:memory new-state))})
                (status! "  ✓ CURATOR updated memory in" elapsed
                         (str "(" (count (:memory new-state)) " entries)"))
                {:success true :updated true :intelligent true})
              ;; Fallback to mechanical update
              (let [worker-result (or (:work artifacts) (:implement artifacts))
                    review-result (:review artifacts)
                    updated (-> current-state
                                (state/increment-command-count)
                                (state/add-memory-entry
                                  {:action (:verb cmd)
                                   :target (or (:target cmd) (str/join ", " (:files-changed worker-result)))
                                   :description (:summary worker-result)
                                   :priority (if (= "approve" (:verdict review-result)) :normal :high)}))]
                (state/save-state! updated)
                (log/log! :info "CURATOR DONE (fallback)" {:command-count (:command-count updated)
                                                            :memory-size (count (:memory updated))})
                (status! "  ✓ CURATOR updated memory in" elapsed "(fallback)")
                {:success true :updated true :intelligent false})))
          ;; Spawn failed - use mechanical fallback
          (let [worker-result (or (:work artifacts) (:implement artifacts))
                review-result (:review artifacts)
                updated (-> (state/load-state)
                            (state/increment-command-count)
                            (state/add-memory-entry
                              {:action (:verb cmd)
                               :target (or (:target cmd) (str/join ", " (:files-changed worker-result)))
                               :description (:summary worker-result)
                               :priority (if (= "approve" (:verdict review-result)) :normal :high)}))]
            (state/save-state! updated)
            (log/log! :info "CURATOR DONE (spawn-failed fallback)" {})
            (status! "  ✓ CURATOR updated memory in" elapsed "(fallback)")
            {:success true :updated true :intelligent false})))
      (catch Exception e
        (log/log-error! "CURATOR FAILED" e)
        (status! "  ✗ CURATOR failed after" (format-elapsed start-ms))
        {:success true :updated false :error (str e)}))))

(defn handle-architect
  "Architect handler — creates specifications and plans.
   Returns specs that the worker will implement."
  [cmd artifacts]
  (status! "→ Handing off to ARCHITECT (creating specifications)...")
  (when *current-job*
    (update-job-phase! *current-job* :architect {:agent :architect}))
  (let [start-ms (System/currentTimeMillis)
        result (run-architect cmd)
        elapsed (format-elapsed start-ms)]
    (if (= "failed" (:status result))
      (do
        (status! "  ✗ ARCHITECT failed after" elapsed)
        (status! "    " (:summary result))
        {:success false :summary (:summary result) :files-changed []})
      (do
        (status! "  ✓ ARCHITECT completed in" elapsed "— wrote" (format-files-changed (:files-changed result)))
        (status! "    " (:summary result))
        {:success true
         :files-changed (:files-changed result)
         :summary (:summary result)
         :raw-output (:raw-output result)}))))


;; ============================================================================
;; Explainer Agent
;; ============================================================================
;; Read-only agent that explores code and explains it.
;; Returns explanation as text — no files written, no structured output needed.

(defn build-explainer-prompt
  "Build a prompt for the explainer agent.
   Uses expanded verb when available, otherwise constructs from raw tokens."
  [{:keys [target instruction expanded-verb expanded? expanded-modifiers] :as cmd}]
  (let [task-desc (if expanded?
                    expanded-verb
                    (str "Explain how " (or target "the codebase") " works."))
        modifiers (when (seq expanded-modifiers)
                    (expand/filter-modifiers-for :explainer expanded-modifiers))
        memory-context (state/build-memory-context cmd)]
    (str
      "You are EXPLAINER. Your task: " task-desc "\n"
      (when instruction
        (str "\n" instruction "\n"))
      (when (seq modifiers)
        (str "\n## DIRECTIVES\n"
             (str/join "\n" (map :prompt modifiers)) "\n"))
      (when memory-context
        (str "\n" memory-context "\n"))
      "\n## PROTOCOL\n"
      "1. Read the target file(s) and any closely related code.\n"
      "2. Explain clearly: what it does, how it works, and why it's structured that way.\n"
      "3. Note key dependencies, patterns, and non-obvious design decisions.\n"
      "\n## CONSTRAINTS\n"
      "- Do NOT create or modify any files.\n"
      "- Do NOT write code, tests, or documentation files.\n"
      "- Your entire output IS the explanation — write it clearly and concisely.\n"
      "\nBegin.")))

(defn handle-explainer
  "Explainer handler — reads code and returns explanation as text.
   No files written, no structured result parsing."
  [cmd artifacts]
  (status! "→ Handing off to EXPLAINER...")
  (when *current-job*
    (update-job-phase! *current-job* :explainer {:agent :explainer}))
  (let [start-ms (System/currentTimeMillis)
        prompt (build-explainer-prompt cmd)
        result (spawn-claude prompt
                             :tools "Read,Glob,Grep"
                             :timeout-ms 300000
                             :agent-name "EXPLAINER")
        elapsed (format-elapsed start-ms)]
    (if (:success result)
      (do
        (status! "  ✓ EXPLAINER completed in" elapsed)
        {:success true
         :explanation (:output result)
         :summary (let [out (:output result)
                        len (count out)]
                    (if (> len 200)
                      (str (subs out 0 200) "...")
                      out))})
      (do
        (status! "  ✗ EXPLAINER failed after" elapsed)
        {:success false
         :summary (str "Explainer failed: " (:error result))}))))


(defn build-handlers
  "Build the handlers map for the workflow state machine.
   Each handler: (fn [cmd artifacts] -> {:success bool ...})"
  []
  {:worker    handle-worker
   :tester    handle-tester
   :reviewer  handle-reviewer
   :curator   handle-curator
   :architect handle-architect
   :explainer handle-explainer})
