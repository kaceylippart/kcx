#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.pprint :as pprint]
         '[clojure.string :as str])

;; Add src to classpath for our modules
(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

;; Load KC-X modules
(require '[kcx.state :as state]
         '[kcx.dsl :as dsl]
         '[kcx.templates :as templates])

;; --- 1. CONFIG & STATE ---
(def state-file (str (System/getProperty "user.home") "/.kcx/state.edn"))

(defn load-state []
  (if (.exists (io/file state-file))
    (try (edn/read-string (slurp state-file))
         (catch Exception _ {:meta {:project "New" :status "Recovered"}}))
    {:meta {:project "New" :created (str (java.time.Instant/now))}}))

(defn save-state [new-state-str]
  (try
    (let [data (edn/read-string new-state-str)]
      (io/make-parents state-file)
      (spit state-file (with-out-str (pprint/pprint data)))
      "State updated successfully.")
    (catch Exception e (str "Error saving state: " (str e)))))

;; --- 2. THE COMPILER (Enhanced with Agent Templates) ---
(def dsl-regex #"^:(\w+)(?:\s+@([\w\./-]+))?(?:\s+(.*))?")

(defn compile-prompt
  "Compile a rich contextual prompt using agent templates"
  [cmd-str]
  (let [[match verb target args] (re-matches dsl-regex cmd-str)]
    (if match
      (let [;; Parse full DSL command for better context
            parsed-cmd (dsl/parse-command cmd-str)
            agent-key (templates/route-intent verb)
            template (templates/get-agent-template agent-key)
            state (load-state)]

        ;; THE ENHANCED MASTER PROMPT
        (str "=== KC-X AGENT EXECUTION CONTEXT ===\n\n"

             "--- 1. PROJECT MEMORY (GLOBAL CONTEXT) ---\n"
             (with-out-str (pprint/pprint state)) "\n\n"

             "--- 2. ACTIVE AGENT: " (str/upper-case (name agent-key)) " ---\n"
             template "\n\n"

             "--- 3. CURRENT TASK SPECIFICATION ---\n"
             "COMMAND: " cmd-str "\n"
             "VERB: " verb "\n"
             "TARGET: " (or target "global_context") "\n"
             "PARSED CONSTRAINTS: " (when parsed-cmd (str (:includes parsed-cmd) " / " (:excludes parsed-cmd))) "\n"
             "ADDITIONAL ARGS: " (or args "Execute") "\n\n"

             "--- 4. EXECUTION MANDATE ---\n"
             "Execute this task according to your role constraints above.\n"
             "You MUST use the appropriate MCP tools as specified in your template.\n"
             "Do NOT provide generic responses - take concrete action.\n"
             "Remember: Your role has specific OUTPUT REQUIREMENTS that must be followed.\n\n"

             "=== BEGIN EXECUTION ==="))

      "Error: Invalid DSL syntax. Use :verb @target format")))

;; --- 3. HELP SYSTEM ---
(defn get-kcx-help [topic]
  (case topic
    "syntax" (str "KC-X DSL SYNTAX:\n\n"
                  ":verb @target +include -exclude\n\n"
                  "Examples:\n"
                  ":plan @auth.clj +jwt +secure    → Architect plans auth with JWT\n"
                  ":gen @hello.clj +main          → Coder generates hello world\n"
                  ":review @api.clj               → Reviewer checks API code\n"
                  ":remember 'Use PostgreSQL'     → Memory manager records decision")

    "agents" (str "KC-X AGENT SYSTEM:\n\n"
                  "🏗️  ARCHITECT    → :plan, :arch, :design, :analyze\n"
                  "⚡ CODER        → :gen, :create, :fix, :refactor, :edit\n"
                  "🔍 REVIEWER     → :review, :check, :validate, :test\n"
                  "🧠 MEMORY MGR   → :remember, :forget, :context, :clean\n"
                  "💬 ASSISTANT    → :help, :explain, :show\n\n"
                  "Each agent has strict behavioral constraints to prevent role confusion.")

    "templates" (str "AGENT TEMPLATES:\n\n"
                     "Each agent has:\n"
                     "- ROLE: What they are\n"
                     "- GOAL: What they accomplish\n"
                     "- BEHAVIORAL CONSTRAINTS: What they can/cannot do\n"
                     "- OUTPUT REQUIREMENTS: How they must respond\n\n"
                     "This prevents 'role confusion' and ensures agents use tools correctly.")

    "routing" (str "DSL ROUTING:\n\n" (templates/explain-routing))

    ;; Default: show all
    (str "KC-X Agent Template System\n\n"
         (get-kcx-help "agents") "\n\n"
         (get-kcx-help "syntax") "\n\n"
         "Key Innovation: Agent templates with behavioral constraints prevent 'role confusion'\n"
         "and ensure agents use MCP tools correctly instead of just chatting.\n\n"
         "Built with ❤️ in Clojure | Agent Templates | MCP Protocol")))

;; --- 4. MCP HANDLERS ---
(defn handle-request [req]
  (let [method (get req "method")
        params (get req "params")
        args (get params "arguments")]

    (case method
      "initialize"
      {:protocolVersion "2024-11-05"
       :capabilities {:tools {}}
       :serverInfo {:name "kcx-bb" :version "1.0"}}

      "tools/list"
      {:tools [
               ;; Core KC-X Engine with Agent Templates
               {:name "kcx"
                :description "The KC-X Agent Engine. Call this for ANY command starting with ':'.

Examples:
:plan @auth.clj         → Architect agent plans auth system
:gen @hello.clj +main   → Coder agent writes hello world
:review @auth.clj       → Reviewer agent checks code quality
:remember 'Use JWT'     → Memory manager records decision

Each agent has specific behavioral constraints and will use appropriate tools."
                :inputSchema {:type "object"
                              :properties {:command {:type "string"}}
                              :required ["command"]}}

               ;; Standard tools that agents use
               {:name "read_state"
                :description "Read the project state (EDN format). Use this to hydrate context."
                :inputSchema {:type "object" :properties {}}}

               {:name "update_state"
                :description "Update the project state. Input MUST be valid EDN."
                :inputSchema {:type "object"
                              :properties {:edn {:type "string" :description "The full EDN map"}}
                              :required ["edn"]}}

               {:name "write_file"
                :description "Write code to a file."
                :inputSchema {:type "object"
                              :properties {:path {:type "string"} :content {:type "string"}}
                              :required ["path" "content"]}}

               {:name "kcx_help"
                :description "Get KC-X help and agent information."
                :inputSchema {:type "object"
                              :properties {:topic {:type "string"
                                                   :enum ["syntax" "agents" "templates" "routing" "all"]
                                                   :description "Help topic to display"}}
                              :required []}}

               {:name "handoff"
                :description "Agent handoff system. Switches to a different specialized agent."
                :inputSchema {:type "object"
                              :properties {:target_role {:type "string"
                                                         :enum ["controller" "curator" "architect" "tester" "worker" "reviewer"]
                                                         :description "The target agent to hand off to"}}
                              :required ["target_role"]}}

               {:name "switch_project"
                :description "Switch context to a project. If the project does not exist, it will be created automatically."
                :inputSchema {:type "object"
                              :properties {:name {:type "string" :description "Project Name (e.g. 'Zodiac')"}}
                              :required ["name"]}}]}

      "tools/call"
      {:content
       [{:type "text"
         :text (case (get params "name")
                 "kcx"          (compile-prompt (get args "command"))
                 "read_state"   (with-out-str (pprint/pprint (load-state)))
                 "update_state" (save-state (get args "edn"))
                 "write_file"   (do (io/make-parents (get args "path"))
                                    (spit (get args "path") (get args "content"))
                                    (str "Wrote to " (get args "path")))
                 "kcx_help"     (get-kcx-help (get args "topic" "all"))
                 "handoff"      (templates/create-handoff-message (keyword (get args "target_role")))
                 "switch_project" (state/switch-project (get args "name"))
                 "Unknown tool")}]}

      ;; Default
      nil)))

;; --- 5. JSON-RPC LOOP ---
(defn -main []
  (binding [*out* (java.io.OutputStreamWriter. System/out)]
    (binding [*err* System/err]
      (println "🧠 KC-X Agent Template System Starting..."))

    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (when-not (str/blank? line)
        (try
          (let [req (json/parse-string line)
                res (handle-request req)]
            (when res
              (println (json/generate-string {:jsonrpc "2.0" :id (get req "id") :result res}))))
          (catch Exception e
            (binding [*out* *err*] (println "JSON-RPC Error:" (str e)))))))))

(-main)