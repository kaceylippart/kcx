#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; Add src to classpath
(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

;; Load KC-X modules for testing
(require '[kcx.templates :as templates]
         '[kcx.dsl :as dsl])

(println "🧠 Testing KC-X Agent Template System\n")

;; Test 1: Agent Routing
(println "=== Agent Routing Tests ===")
(let [test-verbs ["plan" "gen" "review" "remember" "help" "unknown"]]
  (doseq [verb test-verbs]
    (let [agent (templates/route-intent verb)]
      (println "'" verb "' → " (name agent) " agent"))))

(println "\n=== Template Compilation Tests ===")
;; Test 2: Template Compilation
(let [test-commands [":plan @auth.clj +jwt +secure"
                    ":gen @hello.clj +main"
                    ":review @api.clj"
                    ":remember 'Use PostgreSQL'"]]
  (doseq [cmd test-commands]
    (println "\nCommand: " cmd)
    (let [agent-key (-> cmd (str/split #"\s+") first (subs 1) templates/route-intent)
          template (templates/get-agent-template agent-key)]
      (println "Agent: " (name agent-key))
      (println "Template excerpt: " (subs template 0 (min 100 (count template))) "...")
      (println "Has behavioral constraints: " (str/includes? template "BEHAVIORAL CONSTRAINTS"))
      (println "Has output requirements: " (str/includes? template "OUTPUT REQUIREMENTS")))))

(println "\n=== Agent Template Validation ===")
;; Test 3: Agent Template Structure
(doseq [[agent-key template-data] templates/agent-templates]
  (println (str (name agent-key) ": ") (:role template-data))
  (let [template (:template template-data)]
    (println "  ✅ Has role definition: " (str/includes? template "ROLE:"))
    (println "  ✅ Has constraints: " (str/includes? template "BEHAVIORAL CONSTRAINTS"))
    (println "  ✅ Has output rules: " (str/includes? template "OUTPUT REQUIREMENTS"))))

(println "\n=== Workflow Sequence Tests ===")
;; Test 4: Workflow Sequences
(let [workflow-verbs ["gen" "refactor" "fix" "plan"]]
  (doseq [verb workflow-verbs]
    (let [sequence (templates/get-workflow-sequence verb)]
      (println verb " workflow: " (mapv name sequence)))))

(println "\n=== DSL Integration Tests ===")
;; Test 5: Integration with DSL Parser
(let [test-commands [":gen @main.clj +async -unwrap"
                    "kcx:plan file:auth.clj with:jwt not:plaintext"]]
  (doseq [cmd test-commands]
    (when-let [parsed (dsl/parse-command cmd)]
      (let [agent (templates/route-intent (:verb parsed))]
        (println "'" cmd "' → " (name agent) " (" (:verb parsed) " " (:target parsed) ")")))))

(println "\n🎉 Agent Template System Tests Completed!")

(println "\nTo test the full MCP server with agent templates:")
(println "1. Run: ./kcx.clj")
(println "2. Send test requests:")
(println (json/generate-string {:jsonrpc "2.0" :method "tools/call" :id 1
                               :params {:name "kcx" :arguments {:command ":plan @auth.clj +jwt"}}}))
(println (json/generate-string {:jsonrpc "2.0" :method "tools/call" :id 2
                               :params {:name "kcx" :arguments {:command ":gen @hello.clj +main"}}}))
(println (json/generate-string {:jsonrpc "2.0" :method "tools/call" :id 3
                               :params {:name "kcx_help" :arguments {:topic "agents"}}}))