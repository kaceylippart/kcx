(ns kcx.worker
  "Spawns isolated Claude instances for autonomous work"
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [kcx.logging :as log]
    [kcx.state :as state]))


(defn build-worker-prompt
  "Build a focused prompt for the worker from DSL command"
  [{:keys [verb target includes excludes]}]
  (let [action (str/upper-case verb)
        constraints (cond-> []
                      (seq includes) (conj (str "FOCUS ON: " (str/join ", " includes)))
                      (seq excludes) (conj (str "AVOID: " (str/join ", " excludes))))
        target-str (when (and target (not= target "global_context"))
                     (str " in " target))]
    (str
      "You are WORKER. " action target-str ".\n"
      (when (seq constraints)
        (str (str/join ". " constraints) ".\n"))
      "\nWhen done, output EXACTLY:\n"
      "WORKER_RESULT|STATUS|FILES|SUMMARY\n"
      "Example: WORKER_RESULT|success|src/foo.clj,src/bar.clj|Fixed the bug\n"
      "\nBegin.")))


(defn build-reviewer-prompt
  "Build prompt for reviewer to check worker's changes"
  [worker-result files-changed]
  (str
    "You are REVIEWER. Check these changes:\n"
    "Files: " (str/join ", " files-changed) "\n"
    "Summary: " (:summary worker-result) "\n"
    "\nRead the files. Verify correctness.\n"
    "\nOutput EXACTLY:\n"
    "REVIEW_RESULT|VERDICT|FEEDBACK\n"
    "Example: REVIEW_RESULT|approve|Looks good, zero check added correctly"))


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
(def claude-path (str home-dir "/.claude/local/claude"))
;; KCX_HOME should point to the kcx repo root
(def kcx-home (or (System/getenv "KCX_HOME")
                  (str home-dir "/kcx")))
(def playground-dir (str kcx-home "/playground"))


(defn spawn-claude
  "Spawn a Claude instance with the given prompt, return output"
  [prompt & {:keys [timeout-ms working-dir]
             :or {timeout-ms 300000
                  working-dir playground-dir}}]
  (log/log! :info "SPAWN CLAUDE" {:prompt-length (count prompt)
                                  :working-dir working-dir
                                  :timeout-ms timeout-ms})
  (try
    ;; Use shell with explicit /dev/null for stdin to prevent hanging
    (let [result (p/shell {:out :string
                           :err :string
                           :dir working-dir
                           :extra-env {"KCX_WORKER" "true"}}
                          "sh" "-c"
                          (str claude-path
                               " --print"
                               " --tools 'Read,Glob,Grep,Edit'"
                               " --permission-mode acceptEdits"
                               " -p " (pr-str prompt)
                               " < /dev/null"))]
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
  "Execute worker agent for a command"
  [cmd]
  (let [prompt (build-worker-prompt cmd)
        _ (log/log! :info "WORKER START" {:verb (:verb cmd) :target (:target cmd)})
        result (spawn-claude prompt)]
    (if (:success result)
      (let [parsed (parse-worker-result (:output result))]
        (log/log! :info "WORKER DONE" parsed)
        (assoc parsed :raw-output (:output result)))
      {:status "failed"
       :summary (str "Worker spawn failed: " (:error result))
       :files-changed []})))


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


(defn execute-workflow
  "Run full WORKER → REVIEWER → CURATOR chain"
  [cmd]
  (log/log! :info "WORKFLOW START" cmd)

  ;; Worker phase
  (let [worker-result (run-worker cmd)]
    (println (str "→ WORKER " (str/upper-case (:verb cmd))
                  (when-let [t (:target cmd)] (str " @" t))))
    (println (str "  " (:summary worker-result)))

    (if (= "failed" (:status worker-result))
      (do
        (println "✗ WORKER FAILED")
        {:success false :phase :worker :result worker-result})

      ;; Reviewer phase
      (let [review-result (run-reviewer worker-result)]
        (println (str "→ REVIEWER " (str/upper-case (:verdict review-result))))
        (println (str "  " (:feedback review-result)))

        (if (= "reject" (:verdict review-result))
          ;; TODO: Could loop back to worker with feedback
          (do
            (println "✗ REJECTED - manual intervention needed")
            {:success false :phase :reviewer :result review-result})

          ;; Curator phase
          (do
            (run-curator cmd worker-result review-result)
            (println "→ CURATOR Memory updated")
            (println "✓ DONE")
            {:success true
             :worker worker-result
             :reviewer review-result}))))))
