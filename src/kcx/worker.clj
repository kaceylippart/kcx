(ns kcx.worker
  "Curator agent spawning and command tracking for KCX.

   The curator is the only agent that runs as a spawned sub-Claude.
   All other workflow steps are executed by the parent Claude directly.
   The curator remains isolated for unbiased memory compaction."
  (:require
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [kcx.logging :as log]
    [kcx.state :as state]))


;; ============================================================================
;; Shell Sanitization
;; ============================================================================

(defn sanitize-shell-arg
  "Safely sanitize a string for shell usage by removing dangerous characters"
  [s]
  (when s
    (let [s (str s)]
      (-> s
          (str/replace #"[;|&><$`\\]" "")
          (str/replace #"\.\." "")
          (str/replace #"[\r\n]" "")
          (str/replace "\"" "\\\"")
          (str/replace "'" "\\'")
          (#(let [len (count %)]
              (if (> len 1000) (subs % 0 1000) %)))))))


;; ============================================================================
;; Claude Configuration
;; ============================================================================

(def home-dir (System/getProperty "user.home"))

(def claude-path
  (or (System/getenv "CLAUDE_PATH")
      (let [which-result (try
                           (-> (p/shell {:out :string} "which" "claude")
                               :out
                               str/trim)
                           (catch Exception _ nil))]
        (when (and which-result (seq which-result))
          which-result))
      (str home-dir "/Library/pnpm/claude")
      "claude"))

(def worker-model
  "Model for curator agent. Override with KCX_WORKER_MODEL env var."
  (or (System/getenv "KCX_WORKER_MODEL") "claude-sonnet-4-20250514"))

(def worker-tools
  "Tools available to curator. Override with KCX_WORKER_TOOLS env var."
  (or (System/getenv "KCX_WORKER_TOOLS") "Read,Write,Edit,Glob,Grep,Bash"))

(def worker-permission-mode
  "Permission mode for curator. Override with KCX_PERMISSION_MODE env var."
  (or (System/getenv "KCX_PERMISSION_MODE") "bypassPermissions"))


;; ============================================================================
;; MCP Progress (for status updates during curator spawn)
;; ============================================================================

(def ^:dynamic *progress-callback* nil)

(def heartbeat-interval-ms 15000)

(defn- status! [& parts]
  (let [line (str/join " " (remove nil? parts))]
    (when *progress-callback*
      (*progress-callback* line))))

(defn- format-elapsed
  "Format elapsed time in human-readable form."
  [start-ms]
  (let [elapsed (- (System/currentTimeMillis) start-ms)
        secs (quot elapsed 1000)
        mins (quot secs 60)
        secs-rem (mod secs 60)]
    (cond
      (< secs 1) "<1s"
      (< secs 60) (str secs "s")
      :else (str mins "m" secs-rem "s"))))


;; ============================================================================
;; Last Command Tracking (for !redo)
;; ============================================================================

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
  "Merge redo modifiers with the last command."
  [last-cmd redo-cmd]
  (let [merged-modifiers (vec (distinct (concat (:modifiers last-cmd [])
                                                 (:modifiers redo-cmd []))))
        merged-directives (vec (distinct (concat (:directives last-cmd [])
                                                  (:directives redo-cmd []))))
        merged-instruction (cond
                             (and (:instruction last-cmd) (:instruction redo-cmd))
                             (str (:instruction last-cmd) "\n\nADDITIONAL: " (:instruction redo-cmd))
                             (:instruction redo-cmd) (:instruction redo-cmd)
                             :else (:instruction last-cmd))
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


;; ============================================================================
;; Claude Spawning (curator only)
;; ============================================================================

(defn spawn-claude
  "Spawn a Claude instance with the given prompt, return output.
   Used only for the curator agent."
  [prompt & {:keys [timeout-ms agent-name]
             :or {timeout-ms 120000
                  agent-name "CURATOR"}}]
  (log/log! :info "SPAWN CLAUDE" {:prompt-length (count prompt)
                                  :timeout-ms timeout-ms
                                  :agent-name agent-name})
  (try
    (let [safe-model (sanitize-shell-arg worker-model)
          safe-tools (sanitize-shell-arg worker-tools)
          safe-permission-mode (sanitize-shell-arg worker-permission-mode)
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
          spawn-start (System/currentTimeMillis)
          p (p/process {:out :string :err :string :dir "."} "sh" "-c" cmd)]

      ;; Poll for completion with heartbeat and timeout
      (loop [last-heartbeat spawn-start]
        (let [now (System/currentTimeMillis)
              elapsed (- now spawn-start)
              elapsed-since-heartbeat (- now last-heartbeat)]
          (cond
            (>= elapsed timeout-ms)
            (do
              (log/log! :warn "CLAUDE TIMEOUT" {:timeout-ms timeout-ms})
              (try (.destroy (:proc p)) (catch Exception _ nil))
              {:success false :output "" :error (str "Timed out after " (format-elapsed spawn-start))})

            (not (.isAlive (:proc p)))
            (let [result @p]
              (log/log! :info "CLAUDE COMPLETE" {:exit (:exit result)
                                                 :output-length (count (:out result))})
              {:success (zero? (:exit result))
               :output (:out result)
               :error (:err result)})

            (>= elapsed-since-heartbeat heartbeat-interval-ms)
            (do
              (status! "  ⋯" agent-name "working...")
              (Thread/sleep 1000)
              (recur now))

            :else
            (do
              (Thread/sleep 1000)
              (recur last-heartbeat))))))
    (catch Exception e
      (log/log-error! "CLAUDE SPAWN FAILED" e)
      {:success false :output "" :error (str e)})))


;; ============================================================================
;; Curator — The only spawned agent
;; ============================================================================

(defn build-curator-prompt
  "Build a prompt for curator to update the project briefing document."
  [cmd artifacts current-state]
  (let [worker-result (or (:work artifacts) (:implement artifacts))
        review-result (:review artifacts)
        briefing (:briefing current-state)
        cmd-count (get-in current-state [:meta :command-count] 0)]
    (str
      "You are CURATOR. You maintain the project briefing document — the sole context "
      "that future agents receive about this project.\n\n"
      "## CURRENT BRIEFING\n\n"
      (when (:project-map briefing) (str "### Project Map\n" (:project-map briefing) "\n\n"))
      (when (:conventions briefing) (str "### Conventions\n" (:conventions briefing) "\n\n"))
      (when (:architecture briefing) (str "### Architecture\n" (:architecture briefing) "\n\n"))
      (when (:active-context briefing) (str "### Active Context\n" (:active-context briefing) "\n\n"))
      (when (:known-issues briefing) (str "### Known Issues\n" (:known-issues briefing) "\n\n"))
      "## WHAT JUST HAPPENED\n"
      "Action: " (:verb cmd) (when (:target cmd) (str " @" (:target cmd))) "\n"
      (when (:instruction cmd) (str "Instruction: " (:instruction cmd) "\n"))
      (when (:summary worker-result) (str "Summary: " (:summary worker-result) "\n"))
      (when (:files-changed worker-result) (str "Files changed: " (str/join ", " (:files-changed worker-result)) "\n"))
      (when (:feedback review-result) (str "Reviewer feedback: " (:feedback review-result) "\n"))
      "\n## YOUR TASK\n"
      "Update the project briefing. Output valid EDN with this exact structure:\n\n"
      "{:meta {:version \"2.0\" :project \"" (get-in current-state [:meta :project] "unknown") "\""
      " :command-count " (inc cmd-count) " :updated \"YYYY-MM-DD\"}\n"
      " :briefing\n"
      " {:project-map    \"...\"\n"
      "  :conventions    \"...\"\n"
      "  :architecture   \"...\"\n"
      "  :active-context \"...\"\n"
      "  :known-issues   \"...\"}}\n\n"
      "Guidelines:\n"
      "- **project-map**: What files exist, what they do, how they connect. Update if files were added/removed/renamed.\n"
      "- **conventions**: Naming patterns, test structure, coding style. Update if new patterns emerged.\n"
      "- **architecture**: Key design decisions and their rationale. Update if architectural changes were made.\n"
      "- **active-context**: What was just done, what's in progress, recent changes. ALWAYS update this section.\n"
      "- **known-issues**: Bugs, tech debt, gotchas. Add new issues, remove resolved ones.\n"
      "- Keep each section concise but comprehensive. A new agent reading only this briefing should understand the project.\n"
      "- If a section says \"Not yet populated\", populate it now based on what you can infer from the task and files.\n"
      "- Use the tools available to you (Read, Glob, Grep) to explore the project if sections are sparse.\n\n"
      "Output ONLY the EDN. No explanation, no markdown fences.\n"
      "Begin.")))

(defn handle-curator
  "Curator handler — spawns Claude to intelligently update the project briefing."
  [cmd artifacts]
  (status! "→ Spawning CURATOR (updating briefing)...")
  (let [start-ms (System/currentTimeMillis)]
    (try
      (let [current-state (state/load-state)
            prompt (build-curator-prompt cmd artifacts current-state)
            result (spawn-claude prompt :timeout-ms 120000 :agent-name "CURATOR")
            elapsed (format-elapsed start-ms)]
        (if (:success result)
          (let [output (str/trim (:output result))
                new-state (try (edn/read-string output) (catch Exception _ nil))]
            (if (and new-state (state/validate-state new-state))
              (do
                (state/save-state! new-state)
                (log/log! :info "CURATOR DONE" {:command-count (get-in new-state [:meta :command-count])})
                (str "CURATOR: Briefing updated (command #" (get-in new-state [:meta :command-count]) ") in " elapsed "."))
              ;; Fallback: just bump command-count
              (let [bumped (update-in current-state [:meta :command-count] (fnil inc 0))]
                (state/save-state! bumped)
                (log/log! :warn "CURATOR OUTPUT INVALID" {:output (subs output 0 (min 200 (count output)))})
                (str "CURATOR: Briefing updated in " elapsed " (fallback — invalid EDN from curator)."))))
          ;; Spawn failed
          (let [bumped (update-in current-state [:meta :command-count] (fnil inc 0))]
            (state/save-state! bumped)
            (str "CURATOR: Briefing updated in " elapsed " (fallback — spawn failed)."))))
      (catch Exception e
        (log/log-error! "CURATOR FAILED" e)
        (str "CURATOR: Failed — " (.getMessage e))))))
