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
(require '[kcx.core :as core]
         '[kcx.dsl :as dsl]
         '[kcx.state :as state]
         '[kcx.orchestrator :as orchestrator]
         '[kcx.agents :as agents]
         '[kcx.utils :as utils])

;; --- CONFIG ---
(def state-file (str (System/getProperty "user.home") "/.kcx/state.edn"))

;; --- STATE MANAGEMENT (EDN) ---
(defn load-state []
  (if (.exists (io/file state-file))
    (try
      (edn/read-string (slurp state-file))
      (catch Exception _ {:meta {:project "New" :note "Recovered from parse error"}}))
    {:meta {:project "New" :created (str (java.time.Instant/now))}}))

(defn save-state [new-state-str]
  ;; Safety: Try to parse the string from the LLM before saving
  (try
    (let [data (edn/read-string new-state-str)]
      (io/make-parents state-file)
      (spit state-file (with-out-str (pprint/pprint data)))
      "State updated successfully.")
    (catch Exception e
      (str "Error: LLM generated invalid EDN. State NOT saved.\n" (.getMessage e)))))

;; --- MCP HANDLERS ---
(defn handle-request [req]
  (let [method (get req "method")
        id     (get req "id")
        params (get req "params")
        args   (get params "arguments")]

    (case method
      "initialize"
      {:protocolVersion "2024-11-05"
       :capabilities {:tools {}}
       :serverInfo {:name "kcx-bb" :version "1.0"}}

      "tools/list"
      {:tools [{:name "read_state"
                :description "Read the project state (EDN format). Use this to hydrate context."
                :inputSchema {:type "object" :properties {}}}

               {:name "update_state"
                :description "Overwrite the state file. Input MUST be valid EDN."
                :inputSchema {:type "object"
                              :properties {:edn {:type "string" :description "The full EDN map"}}
                              :required ["edn"]}}

               {:name "write_file"
                :description "Write code to a file."
                :inputSchema {:type "object"
                              :properties {:path {:type "string"} :content {:type "string"}}
                              :required ["path" "content"]}}

               {:name "kcx_command"
                :description "Execute a KC-X DSL command. Supports both traditional and Claude-safe syntax:

TRADITIONAL: ':gen @file.clj +async -unwrap'
CLAUDE-SAFE: 'kcx:gen file:main.clj with:async not:unwrap'
RAW MODE: 'raw: :gen @file.clj +async -unwrap'

Use this for any command that looks like KC-X DSL syntax."
                :inputSchema {:type "object"
                              :properties {:command {:type "string"}}
                              :required ["command"]}}

               {:name "kcx_help"
                :description "Get KC-X syntax help and Claude-safe alternatives for symbol conflicts."
                :inputSchema {:type "object"
                              :properties {:topic {:type "string"
                                                   :enum ["syntax" "symbols" "agents" "examples" "all"]
                                                   :description "Help topic to display"}}
                              :required []}}]}

      "tools/call"
      {:content
       [{:type "text"
         :text (case (get params "name")
                 "read_state"   (with-out-str (pprint/pprint (load-state)))
                 "update_state" (save-state (get args "edn"))
                 "write_file"   (do (io/make-parents (get args "path"))
                                    (spit (get args "path") (get args "content"))
                                    (str "Wrote to " (get args "path")))
                 "kcx_command"  (let [cmd (get args "command")]
                                 (try
                                   ;; Use the full KC-X implementation
                                   (let [conflict-level (dsl/detect-conflict-level cmd)
                                         normalized-input (dsl/normalize-for-parsing cmd)]

                                     ;; Handle help requests
                                     (if (contains? #{"help" ":help" "syntax"} (str/trim cmd))
                                       (dsl/get-syntax-help)

                                       ;; Parse and execute command
                                       (if-let [parsed-cmd (dsl/parse-command normalized-input)]
                                         (let [project-state (with-out-str (pprint/pprint (load-state)))
                                               result (orchestrator/execute-command parsed-cmd project-state)
                                               primary-agent (agents/route-command parsed-cmd)
                                               requires-workflow? (agents/requires-workflow? parsed-cmd)
                                               conflict-info (case conflict-level
                                                               :none "✅ No symbol conflicts detected"
                                                               :low "⚠️ Minor conflicts resolved"
                                                               :high "🔧 Major conflicts resolved")]
                                           (str "KC-X CLOJURE EXECUTION:\n\n"
                                                conflict-info "\n\n"
                                                "PARSED COMMAND:\n"
                                                "- Original: " cmd "\n"
                                                "- " (dsl/format-command-summary parsed-cmd) "\n\n"
                                                "ROUTING:\n"
                                                "- Primary Agent: " (name primary-agent) "\n"
                                                "- Multi-Agent Workflow: " requires-workflow? "\n\n"
                                                "RESULT:\n" result))

                                         ;; Parse failed
                                         "❌ Invalid DSL syntax. Use 'help' for syntax guide.")))
                                   (catch Exception e
                                     (str "❌ Error: " (.getMessage e)))))
                 "kcx_help"     (core/get-kcx-help (get args :topic "all"))
                 "Unknown tool")}]}

      ;; Default
      nil)))

;; --- JSON-RPC LOOP ---
(defn -main []
  (binding [*out* (java.io.OutputStreamWriter. System/out)]
    (binding [*err* System/err]
      (println "🔌 KC-X Clojure MCP Server Starting..."))

    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (when-not (str/blank? line)
        (try
          (let [req (json/parse-string line)
                res (handle-request req)]
            (when res
              (println (json/generate-string {:jsonrpc "2.0" :id (get req "id") :result res}))))
          (catch Exception e
            (binding [*out* *err*] (println "JSON-RPC Error:" (.getMessage e)))))))))

(-main)