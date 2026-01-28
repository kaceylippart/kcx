(ns kcx.claude-api
  "Direct Anthropic API integration for spawning agent workers.

   This is the clean, universal approach - works anywhere with just ANTHROPIC_API_KEY.
   No CLI spawning, no environment hacks, no platform-specific issues."
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [kcx.logging :as log]))

;; ============================================================================
;; Configuration
;; ============================================================================

(def api-base "https://api.anthropic.com/v1")

(def default-model
  (or (System/getenv "KCX_WORKER_MODEL") "claude-sonnet-4-20250514"))

(def max-turns
  "Maximum tool-use turns before stopping"
  (or (some-> (System/getenv "KCX_MAX_TURNS") parse-long) 10))

(defn get-api-key []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (throw (ex-info "ANTHROPIC_API_KEY not set" {:type :missing-api-key}))))

;; ============================================================================
;; Tool Definitions
;; ============================================================================

(def agent-tools
  "Tools available to spawned agents"
  [{:name "read_file"
    :description "Read the contents of a file"
    :input_schema {:type "object"
                   :properties {:path {:type "string" :description "File path to read"}}
                   :required ["path"]}}

   {:name "write_file"
    :description "Write content to a file (creates or overwrites)"
    :input_schema {:type "object"
                   :properties {:path {:type "string" :description "File path to write"}
                                :content {:type "string" :description "Content to write"}}
                   :required ["path" "content"]}}

   {:name "list_files"
    :description "List files in a directory"
    :input_schema {:type "object"
                   :properties {:path {:type "string" :description "Directory path"}
                                :pattern {:type "string" :description "Optional glob pattern"}}
                   :required ["path"]}}

   {:name "search_files"
    :description "Search for text pattern in files"
    :input_schema {:type "object"
                   :properties {:pattern {:type "string" :description "Search pattern (regex)"}
                                :path {:type "string" :description "Directory to search in"}}
                   :required ["pattern"]}}])

;; ============================================================================
;; Tool Execution
;; ============================================================================

(defn execute-tool
  "Execute a tool and return the result"
  [tool-name input working-dir]
  (log/log! :debug "TOOL EXECUTE" {:tool tool-name :input input})
  (try
    (case tool-name
      "read_file"
      (let [path (str working-dir "/" (:path input))]
        (if (.exists (java.io.File. path))
          {:success true :content (slurp path)}
          {:success false :error (str "File not found: " path)}))

      "write_file"
      (let [path (str working-dir "/" (:path input))
            parent (.getParentFile (java.io.File. path))]
        (when (and parent (not (.exists parent)))
          (.mkdirs parent))
        (spit path (:content input))
        {:success true :message (str "Wrote " (count (:content input)) " chars to " path)})

      "list_files"
      (let [dir (java.io.File. (str working-dir "/" (:path input)))]
        (if (.isDirectory dir)
          {:success true
           :files (->> (.listFiles dir)
                       (map #(.getName %))
                       (take 100)
                       vec)}
          {:success false :error "Not a directory"}))

      "search_files"
      (let [pattern (re-pattern (:pattern input))
            dir (java.io.File. (str working-dir "/" (or (:path input) ".")))
            matches (atom [])]
        (doseq [f (file-seq dir)
                :when (.isFile f)
                :let [content (try (slurp f) (catch Exception _ nil))]
                :when (and content (re-find pattern content))]
          (swap! matches conj (.getPath f)))
        {:success true :matches (take 20 @matches)})

      ;; Unknown tool
      {:success false :error (str "Unknown tool: " tool-name)})
    (catch Exception e
      {:success false :error (str "Tool error: " (.getMessage e))})))

;; ============================================================================
;; API Communication
;; ============================================================================

(defn send-message
  "Send a message to the Anthropic API"
  [{:keys [model system messages tools max-tokens]}]
  (let [api-key (get-api-key)
        body (cond-> {:model (or model default-model)
                      :max_tokens (or max-tokens 4096)
                      :messages messages}
               system (assoc :system system)
               tools (assoc :tools tools))]
    (log/log! :debug "API REQUEST" {:model (:model body) :message-count (count messages)})
    (let [response (http/post (str api-base "/messages")
                              {:headers {"x-api-key" api-key
                                         "anthropic-version" "2023-06-01"
                                         "content-type" "application/json"}
                               :body (json/generate-string body)
                               :throw false})
          status (:status response)
          body (try (json/parse-string (:body response) true) (catch Exception _ nil))]
      (log/log! :debug "API RESPONSE" {:status status :stop-reason (:stop_reason body)})
      (if (= 200 status)
        {:success true :response body}
        {:success false :error (or (:error body) {:status status :body (:body response)})}))))

;; ============================================================================
;; Agent Loop
;; ============================================================================

(defn extract-text-content
  "Extract text from response content blocks"
  [content]
  (->> content
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

(defn run-agent
  "Run an agent with the given prompt until completion or max turns.

   Returns {:success bool :output string :tool-calls [...] :turns int}"
  [prompt & {:keys [system-prompt working-dir model]
             :or {working-dir "."
                  model default-model}}]
  (log/log! :info "AGENT START" {:prompt-length (count prompt) :working-dir working-dir})

  (loop [messages [{:role "user" :content prompt}]
         turn 0
         tool-calls []]
    (if (>= turn max-turns)
      (do
        (log/log! :warn "AGENT MAX TURNS" {:turns turn})
        {:success false
         :output "Max turns reached"
         :tool-calls tool-calls
         :turns turn})

      (let [result (send-message {:model model
                                  :system system-prompt
                                  :messages messages
                                  :tools agent-tools})]
        (if-not (:success result)
          (do
            (log/log! :error "AGENT API ERROR" (:error result))
            {:success false
             :output (str "API error: " (:error result))
             :tool-calls tool-calls
             :turns turn})

          (let [response (:response result)
                content (:content response)
                stop-reason (:stop_reason response)]

            (cond
              ;; Done - no more tool use
              (= "end_turn" stop-reason)
              (do
                (log/log! :info "AGENT COMPLETE" {:turns (inc turn)})
                {:success true
                 :output (extract-text-content content)
                 :tool-calls tool-calls
                 :turns (inc turn)})

              ;; Tool use requested
              (= "tool_use" stop-reason)
              (let [tool-uses (filter #(= "tool_use" (:type %)) content)
                    tool-results (for [{:keys [id name input]} tool-uses]
                                   (let [result (execute-tool name input working-dir)]
                                     (log/log! :debug "TOOL RESULT" {:tool name :success (:success result)})
                                     {:type "tool_result"
                                      :tool_use_id id
                                      :content (json/generate-string result)}))]
                (recur
                  (-> messages
                      (conj {:role "assistant" :content content})
                      (conj {:role "user" :content (vec tool-results)}))
                  (inc turn)
                  (into tool-calls (map #(select-keys % [:name :input]) tool-uses))))

              ;; Other stop reason
              :else
              (do
                (log/log! :info "AGENT STOPPED" {:reason stop-reason})
                {:success true
                 :output (extract-text-content content)
                 :tool-calls tool-calls
                 :turns (inc turn)}))))))))

;; ============================================================================
;; High-Level Interface
;; ============================================================================

(defn spawn-worker
  "Spawn a worker agent to perform a task.

   This is the main entry point - replaces CLI-based spawning."
  [prompt & {:keys [working-dir]
             :or {working-dir "playground"}}]
  (run-agent prompt
             :system-prompt "You are WORKER, a focused coding agent. Complete the task efficiently.
When done, output EXACTLY on its own line:
WORKER_RESULT|STATUS|FILES|SUMMARY
Where STATUS is 'success' or 'failed', FILES is comma-separated list of modified files, SUMMARY is brief description.
Example: WORKER_RESULT|success|src/foo.clj|Added error handling"
             :working-dir working-dir))

(defn spawn-reviewer
  "Spawn a reviewer agent to check work."
  [files-changed summary & {:keys [working-dir]
                            :or {working-dir "playground"}}]
  (run-agent (str "Review these changes:\nFiles: " (str/join ", " files-changed)
                  "\nSummary: " summary
                  "\n\nRead the files and verify correctness.")
             :system-prompt "You are REVIEWER. Check code changes for correctness.
Output EXACTLY on its own line:
REVIEW_RESULT|VERDICT|FEEDBACK
Where VERDICT is 'approve', 'reject', or 'needs_revision'.
Example: REVIEW_RESULT|approve|Code looks good, proper error handling added"
             :working-dir working-dir))
