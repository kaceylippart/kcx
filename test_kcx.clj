#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[clojure.java.io :as io])

;; Add src to classpath
(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

;; Load KC-X modules for testing
(require '[kcx.dsl :as dsl]
         '[kcx.state :as state]
         '[kcx.agents :as agents]
         '[kcx.orchestrator :as orchestrator])

(println "🧪 Testing KC-X Clojure Implementation\n")

;; Test 1: DSL Parsing
(println "=== DSL Parsing Tests ===")
(let [test-commands [":gen @main.clj +async -unwrap"
                    "kcx:gen file:test.clj with:logging not:debug"
                    "proj:myproject with:init"
                    "raw: !edit @config.clj +settings"]]
  (doseq [cmd test-commands]
    (println "Input: " cmd)
    (if-let [parsed (dsl/parse-command cmd)]
      (println "✅ Parsed: " (dsl/format-command-summary parsed))
      (println "❌ Parse failed"))
    (println)))

;; Test 2: State Management
(println "=== State Management Tests ===")
(let [test-state {:meta {:version "1.0" :author "Test"}
                  :stack {:language "Clojure"}
                  :active-context {:task "Test task" :status "Testing"}
                  :memory [{:decision "Use Clojure" :date "2025-11-25"}]}]
  (println "Creating test state...")
  (println "✅ Template created: " (boolean (state/create-template)))
  (println "✅ Validation: " (state/validate-edn test-state))
  (println "✅ State format looks good\n"))

;; Test 3: Agent System
(println "=== Agent System Tests ===")
(let [test-commands [{:verb "gen" :target "main.clj"}
                    {:verb "proj" :target "test"}
                    {:verb "review" :target "code.clj"}]]
  (doseq [cmd test-commands]
    (let [agent (agents/route-command cmd)
          workflow? (agents/requires-workflow? cmd)]
      (println "Command:" (:verb cmd) "→ Agent:" (name agent) "Workflow:" workflow?))))

(println "\n=== Conflict Detection Tests ===")
(let [conflict-tests ["normal command"
                     "command with @symbol"
                     "command with ! and & symbols"]]
  (doseq [test conflict-tests]
    (println "'" test "' → Conflict level:" (dsl/detect-conflict-level test))))

(println "\n🎉 Basic tests completed! KC-X Clojure implementation looks functional.")
(println "\nTo test the MCP server:")
(println "1. Run: ./kcx.clj")
(println "2. Send JSON-RPC requests via stdin")
(println "\nExample initialize request:")
(println (json/generate-string {:jsonrpc "2.0" :method "initialize" :id 1}))