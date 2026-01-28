#!/usr/bin/env bb

;; KCX Detailed Integration Tests
;; Simulates real user interactions and validates the full flow

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

(require '[clojure.string :as str]
         '[kcx.core :as core]
         '[kcx.dsl :as dsl]
         '[kcx.agents :as agents]
         '[kcx.orchestrator :as orchestrator]
         '[kcx.worker :as worker]
         '[kcx.state :as state]
         '[kcx.logging :as log])

(def ^:dynamic *verbose* false)
(def ^:dynamic *live-mode* false)
(def results (atom []))

(defn trace [& args] (when *verbose* (apply println "  │" args)))
(defn record! [name status] (swap! results conj {:test name :status status}))

(defn check [desc condition]
  (let [status (if condition :pass :fail)]
    (println (str "  " (if condition "✓" "✗") " " desc))
    (record! desc status)
    condition))

(defn section [title]
  (println)
  (println (str "┌─ " title " " (str/join "" (repeat (- 55 (count title)) "─")))))

;; ============================================================================
;; Test Scenarios
;; ============================================================================

(defn test-status-command []
  (section "Status Command Flow")
  (let [parsed (dsl/parse-command "kcx !status")
        agent (agents/route-command parsed)
        response (core/handle-request
                   {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                    "params" {"name" "kcx_command"
                              "arguments" {"command" "kcx !status"}}})]
    (trace "Parsed:" parsed)
    (trace "Agent:" agent)
    (check "DSL parses verb 'status'" (= "status" (:verb parsed)))
    (check "Routes to :controller" (= :controller agent))
    (check "MCP returns :content" (contains? response :content))))

(defn test-fix-command []
  (section "Fix Command Flow")
  (let [input "kcx !fix @calculator.clj +error-handling -println"
        parsed (dsl/parse-command input)
        agent (agents/route-command parsed)
        needs-wf (agents/requires-workflow? parsed)
        instructions (orchestrator/build-worker-instruction parsed "test-task-123")]
    (trace "Parsed:" parsed)
    (check "DSL parses verb 'fix'" (= "fix" (:verb parsed)))
    (check "DSL parses target" (= "calculator.clj" (:target parsed)))
    (check "DSL parses includes" (= ["error-handling"] (:includes parsed)))
    (check "DSL parses excludes" (= ["println"] (:excludes parsed)))
    (check "Routes to :worker" (= :worker agent))
    (check "Requires workflow" needs-wf)
    (check "Instructions contain target" (str/includes? (str instructions) "calculator.clj"))))

(defn test-worker-output-parsing []
  (section "Worker Output Parsing")
  ;; Success case
  (let [output "Done!\nWORKER_RESULT|success|src/a.clj,src/b.clj|Fixed errors"
        parsed (worker/parse-worker-result output)]
    (check "Parses success status" (= "success" (:status parsed)))
    (check "Parses multiple files" (= 2 (count (:files-changed parsed))))
    (check "Parses summary" (str/includes? (:summary parsed) "Fixed")))
  ;; Failure case
  (let [output "WORKER_RESULT|failed||Could not complete"
        parsed (worker/parse-worker-result output)]
    (check "Parses failed status" (= "failed" (:status parsed))))
  ;; Malformed
  (let [output "No marker here"
        parsed (worker/parse-worker-result output)]
    (check "Returns unknown for malformed" (= "unknown" (:status parsed)))))

(defn test-reviewer-output-parsing []
  (section "Reviewer Output Parsing")
  (let [approve "REVIEW_RESULT|approve|Looks good"
        reject "REVIEW_RESULT|reject|Needs work"
        p1 (worker/parse-review-result approve)
        p2 (worker/parse-review-result reject)]
    (check "Parses approve verdict" (= "approve" (:verdict p1)))
    (check "Parses reject verdict" (= "reject" (:verdict p2)))
    (check "Parses feedback" (seq (:feedback p1)))))

(defn test-state-mutations []
  (section "State Mutations")
  (let [s1 (state/load-state)
        c1 (:command-count s1)
        s2 (state/increment-command-count s1)
        s3 (state/add-memory-entry s2 {:action "test" :target "t.clj" :description "Test"})]
    (check "Increment command count" (= (inc c1) (:command-count s2)))
    (check "Add memory entry" (> (count (:memory s3)) (count (:memory s1))))))

(defn test-mcp-protocol []
  (section "MCP Protocol Compliance")
  ;; tools/list
  (let [r (core/handle-request {"jsonrpc" "2.0" "id" 1 "method" "tools/list"})]
    (check "tools/list returns :tools" (sequential? (:tools r)))
    (check "Tools have :name" (every? :name (:tools r))))
  ;; kcx_command
  (let [r (core/handle-request
            {"jsonrpc" "2.0" "id" 2 "method" "tools/call"
             "params" {"name" "kcx_command" "arguments" {"command" "kcx !status"}}})]
    (check "kcx_command returns :content" (contains? r :content)))
  ;; write_file
  (let [path "/tmp/kcx-int-test.txt"
        r (core/handle-request
            {"jsonrpc" "2.0" "id" 3 "method" "tools/call"
             "params" {"name" "write_file" "arguments" {"path" path "content" "test"}}})]
    (check "write_file succeeds" (= "test" (try (slurp path) (catch Exception _ nil))))
    (try (clojure.java.io/delete-file path) (catch Exception _))))

(defn test-live-workflow []
  (section "Live Workflow (WORKER → REVIEWER → CURATOR)")
  (if-not *live-mode*
    (println "  ⏭ Skipped (use --live)")
    (do
      (let [test-file "playground/src/int_test.clj"]
        (spit test-file "(ns int-test)\n(defn broken [x] (/ x 0))\n")
        (log/start-session!)
        (let [pre-count (:command-count (state/load-state))
              result (worker/execute-workflow
                       {:verb "fix" :target "int_test.clj" :includes ["error-handling"]})
              post-count (:command-count (state/load-state))
              new-content (slurp test-file)]
          (check "Workflow succeeds" (:success result))
          (check "Worker produced result" (some? (:worker result)))
          (check "Reviewer produced verdict" (some? (:reviewer result)))
          (check "Curator updated state" (> post-count pre-count))
          (check "File was modified" (not= new-content "(ns int-test)\n(defn broken [x] (/ x 0))\n")))
        (log/end-session!)
        (try (clojure.java.io/delete-file test-file) (catch Exception _))))))

;; ============================================================================
;; Report
;; ============================================================================

(defn report []
  (let [r @results
        total (count r)
        passed (count (filter #(= :pass (:status %)) r))
        failed (count (filter #(= :fail (:status %)) r))]
    (println)
    (println (str/join "" (repeat 60 "=")))
    (println "📊 INTEGRATION TEST REPORT")
    (println (str/join "" (repeat 60 "=")))
    (println (format "\n  Total: %d  Passed: %d (%.0f%%)  Failed: %d"
                     total passed (* 100.0 (/ passed (max 1 total))) failed))
    (when (seq (filter #(= :fail (:status %)) r))
      (println "\n  Failures:")
      (doseq [{:keys [test]} (filter #(= :fail (:status %)) r)]
        (println (str "    ✗ " test))))
    (println)))

;; ============================================================================
;; Main
;; ============================================================================

(let [args (set *command-line-args*)]
  (binding [*verbose* (contains? args "--verbose")
            *live-mode* (contains? args "--live")]
    (println "🔬 KCX Integration Tests")
    (println (str "   Mode: " (if *live-mode* "LIVE" "MOCK")
                  (when *verbose* " (verbose)")))
    (test-status-command)
    (test-fix-command)
    (test-worker-output-parsing)
    (test-reviewer-output-parsing)
    (test-state-mutations)
    (test-mcp-protocol)
    (test-live-workflow)
    (report)))
