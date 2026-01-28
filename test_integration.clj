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
        needs-rev "REVIEW_RESULT|needs_revision|Consider edge cases"
        p1 (worker/parse-review-result approve)
        p2 (worker/parse-review-result reject)
        p3 (worker/parse-review-result needs-rev)]
    (check "Parses approve verdict" (= "approve" (:verdict p1)))
    (check "Parses reject verdict" (= "reject" (:verdict p2)))
    (check "Parses needs_revision verdict" (= "needs_revision" (:verdict p3)))
    (check "Parses feedback" (seq (:feedback p1)))))

(defn test-rejection-loop-prompt []
  (section "Rejection Loop - Prompt Building")
  (let [cmd {:verb "fix" :target "test.clj" :includes ["validation"]}
        ;; First attempt - no feedback
        prompt1 (worker/build-worker-prompt cmd)
        ;; Retry with feedback
        prompt2 (worker/build-worker-prompt cmd :reviewer-feedback "Missing null check" :iteration 2)]
    (check "Initial prompt has no retry context" (not (str/includes? prompt1 "REJECTED")))
    (check "Retry prompt includes feedback" (str/includes? prompt2 "Missing null check"))
    (check "Retry prompt shows iteration" (str/includes? prompt2 "iteration 2"))
    (check "Retry prompt has warning" (str/includes? prompt2 "PREVIOUS ATTEMPT REJECTED"))))

(defn test-tester-prompts []
  (section "Tester Agent Prompts")
  (let [cmd {:verb "test" :target "calculator.clj" :includes ["edge-cases"]}
        tdd-cmd {:verb "tdd" :target "api.clj" :excludes ["mocks"]}
        prompt1 (worker/build-tester-prompt cmd)
        prompt2 (worker/build-tester-prompt tdd-cmd)]
    (check "Test prompt contains TESTER" (str/includes? prompt1 "TESTER"))
    (check "Test prompt includes target" (str/includes? prompt1 "calculator.clj"))
    (check "Test prompt has includes" (str/includes? prompt1 "edge-cases"))
    (check "TDD prompt mentions TDD" (str/includes? prompt2 "TDD"))
    (check "TDD prompt has excludes" (str/includes? prompt2 "mocks"))
    (check "Tester prompt has result format" (str/includes? prompt1 "TESTER_RESULT"))))

(defn test-tester-output-parsing []
  (section "Tester Output Parsing")
  ;; Success case
  (let [output "Writing tests...\nTESTER_RESULT|success|test/calc_test.clj|Added 3 unit tests"
        parsed (worker/parse-tester-result output)]
    (check "Parses tester success status" (= "success" (:status parsed)))
    (check "Parses tester files" (= ["test/calc_test.clj"] (:files-changed parsed)))
    (check "Parses tester summary" (str/includes? (:summary parsed) "unit tests")))
  ;; Multiple files
  (let [output "TESTER_RESULT|success|test/a.clj,test/b.clj|Tests added"
        parsed (worker/parse-tester-result output)]
    (check "Parses multiple test files" (= 2 (count (:files-changed parsed)))))
  ;; Malformed
  (let [output "No marker here"
        parsed (worker/parse-tester-result output)]
    (check "Returns unknown for malformed tester output" (= "unknown" (:status parsed)))))

(defn test-tester-validation-parsing []
  (section "Tester Validation Parsing")
  ;; Pass case
  (let [output "Checking...\nTESTER_VALIDATION|pass|All tests pass, good coverage"
        parsed (worker/parse-tester-validation output)]
    (check "Parses tester validation pass" (= "pass" (:verdict parsed)))
    (check "Parses tester validation feedback" (str/includes? (:feedback parsed) "tests pass")))
  ;; Fail case
  (let [output "TESTER_VALIDATION|fail|Missing edge case handling"
        parsed (worker/parse-tester-validation output)]
    (check "Parses tester validation fail" (= "fail" (:verdict parsed))))
  ;; Fallback to TESTER_RESULT format
  (let [output "TESTER_RESULT|success|test/a.clj|Tests pass"
        parsed (worker/parse-tester-validation output)]
    (check "Falls back to TESTER_RESULT format" (= "pass" (:verdict parsed)))))

(defn test-tdd-routing []
  (section "TDD Command Routing")
  (let [test-cmd (dsl/parse-command "kcx !test @calculator.clj")
        tdd-cmd (dsl/parse-command "kcx !tdd @api.clj +coverage")
        test-agent (agents/route-command test-cmd)
        tdd-agent (agents/route-command tdd-cmd)
        test-wf (agents/requires-workflow? test-cmd)
        tdd-wf (agents/requires-workflow? tdd-cmd)]
    (check "Test routes to :tester" (= :tester test-agent))
    (check "TDD routes to :tester" (= :tester tdd-agent))
    (check "Test requires workflow" test-wf)
    (check "TDD requires workflow" tdd-wf)))

(defn test-architect-prompts []
  (section "Architect Agent Prompts")
  (let [plan-cmd {:verb "plan" :target "api.clj" :includes ["REST"]}
        design-cmd {:verb "design" :target "system" :excludes ["legacy"]}
        prompt1 (worker/build-architect-prompt plan-cmd)
        prompt2 (worker/build-architect-prompt design-cmd)]
    (check "Plan prompt contains ARCHITECT" (str/includes? prompt1 "ARCHITECT"))
    (check "Plan prompt has action" (str/includes? prompt1 "implementation plan"))
    (check "Plan prompt includes target" (str/includes? prompt1 "api.clj"))
    (check "Plan prompt has includes" (str/includes? prompt1 "REST"))
    (check "Design prompt has action" (str/includes? prompt2 "architecture"))
    (check "Design prompt has excludes" (str/includes? prompt2 "legacy"))
    (check "Architect prompt has result format" (str/includes? prompt1 "ARCHITECT_RESULT"))))

(defn test-architect-output-parsing []
  (section "Architect Output Parsing")
  ;; Success case
  (let [output "Planning...\nARCHITECT_RESULT|success|docs/spec.md|Created API specification"
        parsed (worker/parse-architect-result output)]
    (check "Parses architect success status" (= "success" (:status parsed)))
    (check "Parses architect files" (= ["docs/spec.md"] (:files-changed parsed)))
    (check "Parses architect summary" (str/includes? (:summary parsed) "specification")))
  ;; Multiple files
  (let [output "ARCHITECT_RESULT|success|docs/a.md,docs/b.md|Created specs"
        parsed (worker/parse-architect-result output)]
    (check "Parses multiple spec files" (= 2 (count (:files-changed parsed)))))
  ;; Malformed
  (let [output "No marker here"
        parsed (worker/parse-architect-result output)]
    (check "Returns unknown for malformed architect output" (= "unknown" (:status parsed)))))

(defn test-architect-routing []
  (section "Architect Command Routing")
  (let [plan-cmd (dsl/parse-command "kcx !plan @feature")
        design-cmd (dsl/parse-command "kcx !design @system")
        arch-cmd (dsl/parse-command "kcx !arch @api")
        analyze-cmd (dsl/parse-command "kcx !analyze @codebase")]
    (check "Plan routes to :architect" (= :architect (agents/route-command plan-cmd)))
    (check "Design routes to :architect" (= :architect (agents/route-command design-cmd)))
    (check "Arch routes to :architect" (= :architect (agents/route-command arch-cmd)))
    (check "Analyze routes to :architect" (= :architect (agents/route-command analyze-cmd)))))

(defn test-state-mutations []
  (section "State Mutations")
  (let [s1 (state/load-state)
        c1 (:command-count s1)
        s2 (state/increment-command-count s1)
        s3 (state/add-memory-entry s2 {:action "test" :target "t.clj" :description "Test"})]
    (check "Increment command count" (= (inc c1) (:command-count s2)))
    (check "Add memory entry" (> (count (:memory s3)) (count (:memory s1))))))

(defn test-memory-context []
  (section "Memory Context for Agent Decisions")
  ;; Create a test state with memory entries
  (let [test-state {:command-count 10
                    :memory [{:action "fix" :target "calc.clj" :description "Added error handling"
                              :priority :high :created-at 5 :date "2025-01-13"}
                             {:action "gen" :target "api.clj" :description "Generated REST endpoints"
                              :priority :critical :created-at 3 :date "2025-01-12"}
                             {:action "edit" :target "util.clj" :description "Minor change"
                              :priority :low :created-at 1 :date "2025-01-11"}]}]
    ;; Test get-entries-for-target
    (let [entries (state/get-entries-for-target test-state "calc.clj")]
      (check "get-entries-for-target finds matching entry" (= 1 (count entries)))
      (check "get-entries-for-target returns correct target" (= "calc.clj" (:target (first entries)))))
    ;; Test get-recent-entries
    (let [recent (state/get-recent-entries test-state :limit 2)]
      (check "get-recent-entries returns requested limit" (= 2 (count recent)))
      (check "get-recent-entries sorted by created-at" (> (:created-at (first recent)) (:created-at (second recent)))))
    ;; Test get-high-priority-entries
    (let [high-pri (state/get-high-priority-entries test-state)]
      (check "get-high-priority-entries filters low priority" (= 2 (count high-pri)))
      (check "get-high-priority-entries includes :critical" (some #(= :critical (:priority %)) high-pri))
      (check "get-high-priority-entries includes :high" (some #(= :high (:priority %)) high-pri)))
    ;; Test get-related-entries
    (let [related (state/get-related-entries test-state {:verb "fix" :target "calc.clj" :includes ["error"]})]
      (check "get-related-entries finds matching entries" (>= (count related) 1)))
    ;; Test format-memory-for-prompt
    (let [formatted (state/format-memory-for-prompt (:memory test-state) :max-entries 2 :header "TEST")]
      (check "format-memory-for-prompt returns string" (string? formatted))
      (check "format-memory-for-prompt includes header" (str/includes? formatted "TEST"))
      (check "format-memory-for-prompt includes priority" (str/includes? formatted "HIGH")))
    ;; Test build-memory-context with nil target (edge case)
    (let [ctx (state/build-memory-context {:verb "status" :target nil})]
      (check "build-memory-context handles nil target" (or (nil? ctx) (string? ctx))))))

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
    (test-rejection-loop-prompt)
    (test-tester-prompts)
    (test-tester-output-parsing)
    (test-tester-validation-parsing)
    (test-tdd-routing)
    (test-architect-prompts)
    (test-architect-output-parsing)
    (test-architect-routing)
    (test-state-mutations)
    (test-memory-context)
    (test-mcp-protocol)
    (test-live-workflow)
    (report)))
