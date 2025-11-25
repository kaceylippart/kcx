(ns kcx.core
  "Core KC-X MCP server implementation in Clojure"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kcx.dsl :as dsl]
            [kcx.state :as state]
            [kcx.orchestrator :as orchestrator]
            [kcx.agents :as agents]))

;; JSON-RPC request structure
(defn parse-jsonrpc-request [input]
  (try
    (json/parse-string input true)
    (catch Exception e
      (binding [*out* *err*]
        (println "❌ Failed to parse JSON:" (.getMessage e)))
      nil)))

(defn send-response [id result]
  (let [response {:jsonrpc "2.0"
                  :id id
                  :result result}]
    (println (json/generate-string response))))

;; MCP Server capabilities and tool definitions
(def server-capabilities
  {:protocolVersion "2024-11-05"
   :capabilities {:tools {}}
   :serverInfo {:name "kcx-bb" :version "1.0"}})

(def mcp-tools
  [{:name "kcx_command"
    :description "Execute a KC-X DSL command. Supports both traditional and Claude-safe syntax:

TRADITIONAL: ':gen @file.rs +async -unwrap'
CLAUDE-SAFE: 'kcx:gen file:main.rs with:async not:unwrap'
RAW MODE: 'raw: :gen @file.rs +async -unwrap'

Use this for any command that looks like KC-X DSL syntax."
    :inputSchema {:type "object"
                  :properties {:command {:type "string"}}
                  :required ["command"]}}

   {:name "read_state"
    :description "Read the EDN state file to understand project context."
    :inputSchema {:type "object"
                  :properties {}}}

   {:name "update_state"
    :description "Overwrite kcx_state.edn with new content. Use this to update tasks/memory."
    :inputSchema {:type "object"
                  :properties {:edn {:type "string"
                                     :description "The full valid EDN content"}}
                  :required ["edn"]}}

   {:name "kcx_help"
    :description "Get KC-X syntax help and Claude-safe alternatives for symbol conflicts."
    :inputSchema {:type "object"
                  :properties {:topic {:type "string"
                                       :enum ["syntax" "symbols" "agents" "examples" "all"]
                                       :description "Help topic to display"}}
                  :required []}}

   {:name "write_file"
    :description "Write content to a file."
    :inputSchema {:type "object"
                  :properties {:path {:type "string"}
                               :content {:type "string"}}
                  :required ["path" "content"]}}])

;; Tool execution handlers
(defn execute-kcx-command [command]
  (try
    (let [;; Step 1: Detect and resolve symbol conflicts
          conflict-level (dsl/detect-conflict-level command)
          normalized-input (dsl/normalize-for-parsing command)

          ;; Step 2: Handle help requests
          _ (when (contains? #{"help" ":help" "syntax"} (str/trim command))
              (throw (ex-info "help" {:help-requested true})))

          ;; Step 3: Parse the normalized command
          parsed-cmd (dsl/parse-command normalized-input)]

      (if parsed-cmd
        (do
          ;; Handle project management commands directly
          (if (= (:verb parsed-cmd) "proj")
            (orchestrator/handle-proj-command parsed-cmd)

            ;; Get current project state for context
            (let [project-state (with-out-str (clojure.pprint/pprint (state/load-state)))
                  result (orchestrator/execute-command parsed-cmd project-state)

                  ;; Get routing information for debugging
                  primary-agent (agents/route-command parsed-cmd)
                  requires-workflow? (agents/requires-workflow? parsed-cmd)

                  conflict-info (case conflict-level
                                  :none "✅ No symbol conflicts detected"
                                  :low (str "⚠️ Minor conflicts resolved. Safe alternative: "
                                           (dsl/recommend-syntax command :low))
                                  :high (str "🔧 Major conflicts resolved. Safe alternative: "
                                            (dsl/recommend-syntax command :high)))]

              (str "KC-X MULTI-AGENT EXECUTION:\n\n"
                   "SYMBOL CONFLICT ANALYSIS:\n" conflict-info "\n\n"
                   "PARSED COMMAND:\n"
                   "- Original: " command "\n"
                   "- Normalized: " normalized-input "\n"
                   "- " (dsl/format-command-summary parsed-cmd) "\n\n"
                   "ROUTING DECISION:\n"
                   "- Primary Agent: " (name primary-agent) "\n"
                   "- Requires Multi-Agent Workflow: " requires-workflow? "\n\n"
                   "EXECUTION RESULT:\n" result "\n\n"
                   "=== ACTIVE TASKS ===\n" (orchestrator/get-active-tasks-summary)))))

        ;; Parsing failed
        (let [help-text (case conflict-level
                          :high (str "❌ Invalid DSL syntax with symbol conflicts detected.\n\n"
                                    "Try Claude-safe syntax:\n"
                                    (dsl/recommend-syntax ":gen file:example.rs with:constraint not:avoid to:output as:agent" :high)
                                    "\n\nFor help: 'syntax' or 'help'")
                          _ (str "❌ Invalid DSL syntax. Examples:\n"
                                "  Traditional: ':gen @file.rs +constraint -avoid'\n"
                                "  Claude-safe: 'kcx:gen file:example.rs with:constraint not:avoid'\n"
                                "  Raw mode: 'raw: :gen @file.rs +constraint'\n\n"
                                "For help: 'syntax' or 'help'"))]
          help-text)))

    (catch Exception e
      (if (= (.getMessage e) "help")
        (dsl/get-syntax-help)
        (str "❌ Error executing command: " (.getMessage e))))))

(defn read-state-tool []
  (let [state-file (state/get-current-state-file)]
    (try
      (let [content (slurp state-file)]
        ;; Try to parse and validate
        (if-let [parsed (try (read-string content) (catch Exception _ nil))]
          (if (state/validate-edn parsed)
            content
            ;; Invalid EDN, return template
            (with-out-str (clojure.pprint/pprint (state/create-template))))
          ;; Parse failed, return template
          (with-out-str (clojure.pprint/pprint (state/create-template)))))
      (catch Exception _
        ;; File doesn't exist, create from template
        (let [template (state/create-template)]
          (state/save-state template state-file)
          (with-out-str (clojure.pprint/pprint template)))))))

(defn update-state-tool [edn-string]
  (state/save-state-string edn-string))

(defn get-kcx-help [topic]
  (case topic
    ("syntax" "symbols") (dsl/get-syntax-help)
    "agents" (str "KC-X MULTI-AGENT SYSTEM:\n\n"
                 "=== AGENT TYPES ===\n"
                 "🎯 Controller Agent\n"
                 "   - High-level coordination and project management\n"
                 "   - Routes commands to appropriate agents\n"
                 "   - Handles: proj, status, plan commands\n\n"
                 "🧠 Memory Manager Agent\n"
                 "   - Updates EDN state and project memory\n"
                 "   - Manages priorities and context shifts\n"
                 "   - Handles: remember, forget, context commands\n\n"
                 "⚡ Coder/Builder Agent\n"
                 "   - Code implementation and file operations\n"
                 "   - Generates, modifies, and refactors code\n"
                 "   - Handles: gen, create, edit, refactor, fix commands\n\n"
                 "🔍 Reviewer Agent\n"
                 "   - Quality assurance and requirement validation\n"
                 "   - Reviews all code changes before finalization\n"
                 "   - Handles: review, check, validate, approve commands\n\n"
                 "=== WORKFLOW ===\n"
                 "Complex tasks flow through: Controller → CoderBuilder → Reviewer → MemoryManager\n"
                 "Simple tasks go directly to the appropriate specialized agent.")
    "examples" (str "KC-X COMMAND EXAMPLES:\n\n"
                   "=== TRADITIONAL SYNTAX ===\n"
                   ":gen @auth.rs +async +serde -unwrap > @auth_tests.rs &reviewer\n"
                   ":edit @main.rs +logging -println\n"
                   ":refactor @utils.rs +clean > @utils_v2.rs\n"
                   ":proj @myproject +init\n"
                   ":status\n\n"
                   "=== CLAUDE-SAFE SYNTAX ===\n"
                   "kcx:gen file:auth.rs with:async with:serde not:unwrap to:auth_tests.rs as:reviewer\n"
                   "kcx:edit file:main.rs with:logging not:println\n"
                   "kcx:refactor file:utils.rs with:clean to:utils_v2.rs\n"
                   "proj:myproject with:init\n\n"
                   "=== RAW MODE (Bypass Claude interpretation) ===\n"
                   "raw: :gen @auth.rs +async -unwrap > @tests.rs\n"
                   "raw: !edit @main.rs +logging -println\n\n"
                   "=== MIXED SYNTAX ===\n"
                   "kcx:gen file:auth.rs +async not:unwrap to:tests.rs as:reviewer\n"
                   "kcx:edit file:main.rs with:logging -println to:main_v2.rs")
    ;; Default: show all help
    (str "KC-X HELP - Multi-Agent Development Assistant\n\n"
         "KC-X is a Context Operating System that uses specialized AI agents\n"
         "to handle complex software engineering workflows.\n\n"
         (get-kcx-help "agents") "\n\n"
         (get-kcx-help "examples") "\n\n"
         (dsl/get-syntax-help) "\n\n"
         "=== QUICK START ===\n"
         "1. Try: 'kcx:gen file:hello.clj with:main'\n"
         "2. Check status: 'status'\n"
         "3. Get project help: 'proj'\n"
         "4. View syntax help: Use kcx_help tool with topic 'syntax'\n\n"
         "=== CONFLICT-FREE USAGE ===\n"
         "When Claude interprets symbols like @ or ! in unexpected ways:\n"
         "- Use Claude-safe syntax: 'kcx:gen file:main.clj with:async'\n"
         "- Use raw mode: 'raw: :gen @main.clj +async'\n"
         "- Get syntax help: kcx_help tool\n\n"
         "Built with ❤️ in Clojure | Version 1.0 | Multi-Agent Architecture")))

(defn write-file-tool [path content]
  (try
    (when-let [parent (.getParent (io/file path))]
      (io/make-parents (io/file parent "dummy")))
    (spit path content)
    (str "Wrote to " path)
    (catch Exception e
      (str "❌ Failed to write file: " (.getMessage e)))))

;; Tool dispatcher
(defn execute-tool [tool-name args]
  (case tool-name
    "kcx_command" (execute-kcx-command (:command args))
    "read_state" (read-state-tool)
    "update_state" (update-state-tool (:edn args))
    "kcx_help" (get-kcx-help (get args :topic "all"))
    "write_file" (write-file-tool (:path args) (:content args))
    "Unknown tool"))

;; JSON-RPC request handler
(defn handle-jsonrpc-request [request]
  (let [{:keys [method params id]} request]
    (case method
      "initialize"
      (send-response id server-capabilities)

      "notifications/initialized"
      nil ; No response needed for notifications

      "tools/list"
      (send-response id {:tools mcp-tools})

      "tools/call"
      (let [tool-name (:name params)
            args (:arguments params)]
        (binding [*out* *err*]
          (println "🚀 Tool Call:" tool-name))
        (let [result (execute-tool tool-name args)]
          (send-response id
                        {:content [{:type "text" :text result}]
                         :isError false})))

      ;; Unknown method - ignore
      nil)))

;; Main server loop
(defn start-server []
  (binding [*out* *err*]
    (println "🔌 KC-X MCP Server Starting..."))

  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (when-not (str/blank? line)
      (when-let [request (parse-jsonrpc-request line)]
        (handle-jsonrpc-request request)))))