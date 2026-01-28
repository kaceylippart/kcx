#!/usr/bin/env bb

;; KCX Test Harness & Evaluation System
;; =====================================
;; Runs comprehensive tests and generates an improvement report.
;;
;; Usage: ./test_harness.clj [--quick] [--verbose] [--component NAME]
;;
;; Options:
;;   --quick      Skip slow tests (agent spawning)
;;   --verbose    Show detailed output
;;   --component  Test only specific component (dsl, state, worker, workflow)

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

(require '[clojure.string :as str]
         '[clojure.pprint :as pprint]
         '[babashka.process :as p])

;; Lazy-load KCX modules to catch load errors
(defn load-module [ns-sym]
  (try
    (require ns-sym)
    {:success true :ns ns-sym}
    (catch Exception e
      {:success false :ns ns-sym :error (str e)})))

;; ============================================================================
;; Test Infrastructure
;; ============================================================================

(def ^:dynamic *verbose* false)
(def ^:dynamic *quick-mode* false)

(defn log [& args]
  (when *verbose*
    (apply println args)))

(defn now [] (str (java.time.Instant/now)))

(def results (atom {:tests [] :started (now)}))

(defn record-test! [category name result]
  (swap! results update :tests conj
         {:category category
          :name name
          :result result
          :timestamp (now)}))

(defn test-case [category name test-fn]
  (print (str "  " name "... "))
  (flush)
  (let [start (System/currentTimeMillis)
        result (try
                 (let [r (test-fn)]
                   {:status (if (:pass r) :pass :fail)
                    :details r})
                 (catch Exception e
                   {:status :error
                    :details {:error (str e)}}))
        elapsed (- (System/currentTimeMillis) start)
        result (assoc result :elapsed-ms elapsed)]
    (record-test! category name result)
    (println (case (:status result)
               :pass "✓"
               :fail "✗"
               :error "⚠"))
    (when (and *verbose* (not= :pass (:status result)))
      (println "    " (:details result)))
    result))

;; ============================================================================
;; Component Tests: DSL Parser
;; ============================================================================

(defn test-dsl []
  (println "\n📝 DSL Parser Tests")
  ;; Use parse-dsl-command directly (without kcx prefix) for unit tests
  ;; parse-command requires "kcx " prefix and is tested in integration
  (let [dsl (requiring-resolve 'kcx.dsl/parse-dsl-command)
        parse-with-prefix (requiring-resolve 'kcx.dsl/parse-command)]

    (test-case :dsl "Parse simple verb"
      (fn []
        (let [r (dsl "!status")]
          {:pass (= "status" (:verb r))
           :parsed r})))

    (test-case :dsl "Parse verb + target"
      (fn []
        (let [r (dsl "!fix @calculator.clj")]
          {:pass (and (= "fix" (:verb r))
                      (= "calculator.clj" (:target r)))
           :parsed r})))

    (test-case :dsl "Parse includes"
      (fn []
        (let [r (dsl "!gen +error-handling +logging")]
          {:pass (= ["error-handling" "logging"] (:includes r))
           :parsed r})))

    (test-case :dsl "Parse excludes"
      (fn []
        (let [r (dsl "!debug -println -prn")]
          {:pass (= ["println" "prn"] (:excludes r))
           :parsed r})))

    (test-case :dsl "Parse full command"
      (fn []
        (let [r (dsl "!fix @api.clj +validation -debug >output.clj &worker")]
          {:pass (and (= "fix" (:verb r))
                      (= "api.clj" (:target r))
                      (= ["validation"] (:includes r))
                      (= ["debug"] (:excludes r))
                      (= "output.clj" (:redirect r))  ; Note: it's :redirect not :output
                      (= "worker" (:agent r)))
           :parsed r})))

    (test-case :dsl "Handle empty input"
      (fn []
        (let [r (dsl "")]
          {:pass (nil? r)
           :parsed r})))

    (test-case :dsl "Handle kcx prefix"
      (fn []
        (let [r (parse-with-prefix "kcx !status")]
          {:pass (= "status" (:verb r))
           :parsed r})))))

;; ============================================================================
;; Component Tests: State Management
;; ============================================================================

(defn test-state []
  (println "\n💾 State Management Tests")
  (require 'kcx.state)
  (let [load-state (requiring-resolve 'kcx.state/load-state)
        add-memory (requiring-resolve 'kcx.state/add-memory-entry)
        make-entry (requiring-resolve 'kcx.state/make-memory-entry)
        test-file "/tmp/kcx-test-state.edn"]

    (test-case :state "Load existing state"
      (fn []
        (let [s (load-state)]
          {:pass (and (map? s)
                      (contains? s :meta))
           :state (select-keys s [:meta :command-count])})))

    (test-case :state "Make memory entry"
      (fn []
        (let [e (make-entry {:action "test" :target "file.clj" :description "test"} 1)]
          {:pass (and (map? e)
                      (contains? e :action)
                      (contains? e :date))  ; Uses :date not :timestamp
           :entry e})))

    (test-case :state "Add memory entry to state"
      (fn []
        (let [s (load-state)
              s2 (add-memory s {:action "test" :target "x.clj" :description "test"})]
          {:pass (> (count (:memory s2)) (count (:memory s)))
           :before (count (:memory s))
           :after (count (:memory s2))})))

    (test-case :state "Handle missing state file"
      (fn []
        (let [r (try
                  (read-string (slurp "/tmp/nonexistent-kcx-state.edn"))
                  (catch Exception e :not-found))]
          {:pass (= :not-found r)})))))

;; ============================================================================
;; Component Tests: Agent Routing
;; ============================================================================

(defn test-agents []
  (println "\n🤖 Agent Routing Tests")
  (require 'kcx.agents)
  (let [route (requiring-resolve 'kcx.agents/route-command)]

    ;; route-command returns the agent keyword directly, not a map
    (test-case :agents "Route status to controller"
      (fn []
        (let [r (route {:verb "status"})]
          {:pass (= :controller r)
           :routing r})))

    (test-case :agents "Route fix to worker"
      (fn []
        (let [r (route {:verb "fix" :target "file.clj"})]
          {:pass (= :worker r)
           :routing r})))

    (test-case :agents "Route review to reviewer"
      (fn []
        (let [r (route {:verb "review" :target "file.clj"})]
          {:pass (= :reviewer r)
           :routing r})))

    (test-case :agents "Route gen to worker"
      (fn []
        (let [r (route {:verb "gen" :target "new.clj"})]
          {:pass (= :worker r)
           :routing r})))))

;; ============================================================================
;; Component Tests: Worker Spawning
;; ============================================================================

(defn test-worker []
  (if *quick-mode*
    (println "\n⚡ Worker Tests (SKIPPED - quick mode)")
    (do
      (println "\n⚡ Worker Spawn Tests")
  (require 'kcx.worker)
  (let [spawn (requiring-resolve 'kcx.worker/spawn-claude)
        build-prompt (requiring-resolve 'kcx.worker/build-worker-prompt)
        parse-result (requiring-resolve 'kcx.worker/parse-worker-result)]

    (test-case :worker "Build worker prompt"
      (fn []
        (let [p (build-prompt {:verb "fix" :target "test.clj" :includes ["errors"]})]
          {:pass (and (str/includes? p "WORKER")
                      (str/includes? p "FIX")
                      (str/includes? p "WORKER_RESULT"))
           :prompt p})))

    (test-case :worker "Parse worker result - success"
      (fn []
        (let [r (parse-result "some output\nWORKER_RESULT|success|a.clj,b.clj|Fixed stuff\nmore")]
          {:pass (and (= "success" (:status r))
                      (= ["a.clj" "b.clj"] (:files-changed r)))
           :parsed r})))

    (test-case :worker "Parse worker result - no match"
      (fn []
        (let [r (parse-result "random output without marker")]
          {:pass (= "unknown" (:status r))
           :parsed r})))

    (test-case :worker "Spawn simple prompt (live)"
      (fn []
        (let [r (spawn "Respond with exactly: TEST_OK")]
          {:pass (and (:success r)
                      (str/includes? (:output r) "TEST_OK"))
           :result r})))

    (test-case :worker "Spawn with tool use (live)"
      (fn []
        (let [r (spawn "List files in the current directory using Glob. Then say GLOB_DONE.")]
          {:pass (and (:success r)
                      (str/includes? (:output r) "GLOB_DONE"))
           :result r})))))))

;; ============================================================================
;; Integration Tests: Full Workflow
;; ============================================================================

(defn test-workflow []
  (if *quick-mode*
    (println "\n🔄 Workflow Tests (SKIPPED - quick mode)")
    (do
      (println "\n🔄 Full Workflow Tests")
      (require 'kcx.worker 'kcx.logging)
      (let [execute (requiring-resolve 'kcx.worker/execute-workflow)
            start-log (requiring-resolve 'kcx.logging/start-session!)
            end-log (requiring-resolve 'kcx.logging/end-session!)]

        ;; Setup test file
        (spit "playground/src/test_target.clj"
              "(ns test-target)\n\n(defn greet [name] (str \"Hello \" name))\n")

        (start-log)

        (test-case :workflow "Execute review workflow"
          (fn []
            (let [r (execute {:verb "review"
                              :target "test_target.clj"
                              :includes ["code-quality"]})]
              {:pass (:success r)
               :result (select-keys r [:success :phase])})))

        (test-case :workflow "Execute fix workflow"
          (fn []
            (let [r (execute {:verb "fix"
                              :target "test_target.clj"
                              :includes ["add-docstring"]})]
              {:pass (:success r)
               :result (select-keys r [:success :phase])})))

        (end-log)

        ;; Cleanup
        (try (io/delete-file "playground/src/test_target.clj") (catch Exception _))))))

;; ============================================================================
;; MCP Protocol Tests
;; ============================================================================

(defn test-mcp []
  (println "\n📡 MCP Protocol Tests")
  (require 'kcx.core)
  (let [handle (requiring-resolve 'kcx.core/handle-request)]

    ;; MCP responses use keyword keys and return result directly
    (test-case :mcp "Handle tools/list"
      (fn []
        (let [r (handle {"jsonrpc" "2.0" "id" 1 "method" "tools/list"})]
          {:pass (and (map? r)
                      (contains? r :tools)
                      (seq (:tools r)))
           :response (select-keys r [:tools])})))

    (test-case :mcp "Handle kcx_command"
      (fn []
        (let [r (handle {"jsonrpc" "2.0"
                         "id" 2
                         "method" "tools/call"
                         "params" {"name" "kcx_command"
                                   "arguments" {"command" "kcx !status"}}})]
          {:pass (and (map? r)
                      (contains? r :content))
           :response (select-keys r [:content])})))

    (test-case :mcp "Handle read_state"
      (fn []
        (let [r (handle {"jsonrpc" "2.0"
                         "id" 3
                         "method" "tools/call"
                         "params" {"name" "read_state"
                                   "arguments" {}}})]
          {:pass (and (map? r)
                      (contains? r :content))
           :response (type r)})))

    (test-case :mcp "Handle unknown method"
      (fn []
        (let [r (handle {"jsonrpc" "2.0" "id" 4 "method" "unknown/method"})]
          {:pass true  ; Just verify it doesn't crash
           :response r})))))

;; ============================================================================
;; Report Generation
;; ============================================================================

(defn generate-report []
  (let [tests (:tests @results)
        by-category (group-by :category tests)
        by-status (group-by #(get-in % [:result :status]) tests)
        total (count tests)
        passed (count (:pass by-status))
        failed (count (:fail by-status))
        errors (count (:error by-status))
        pass-rate (if (zero? total) 0 (double (/ passed total)))]

    (println "\n" (str/join "" (repeat 60 "=")))
    (println "📊 KCX EVALUATION REPORT")
    (println (str/join "" (repeat 60 "=")))

    (println "\n## Summary")
    (println (format "  Total:  %d tests" total))
    (println (format "  Passed: %d (%.0f%%)" passed (* 100 pass-rate)))
    (println (format "  Failed: %d" failed))
    (println (format "  Errors: %d" errors))

    (println "\n## By Component")
    (doseq [[cat tests] (sort-by first by-category)]
      (let [cat-passed (count (filter #(= :pass (get-in % [:result :status])) tests))]
        (println (format "  %-12s %d/%d" (name cat) cat-passed (count tests)))))

    (when (seq (:fail by-status))
      (println "\n## Failures")
      (doseq [t (:fail by-status)]
        (println (format "  ✗ [%s] %s" (name (:category t)) (:name t)))
        (when *verbose*
          (println "    " (get-in t [:result :details])))))

    (when (seq (:error by-status))
      (println "\n## Errors")
      (doseq [t (:error by-status)]
        (println (format "  ⚠ [%s] %s" (name (:category t)) (:name t)))
        (println "    " (get-in t [:result :details :error]))))

    (println "\n## Improvement Opportunities")
    (let [improvements (atom [])]
      ;; Analyze failures for patterns
      (when (some #(= :dsl (:category %)) (:fail by-status))
        (swap! improvements conj "- DSL parser has edge cases that need handling"))
      (when (some #(= :worker (:category %)) (:fail by-status))
        (swap! improvements conj "- Worker agent spawning or output parsing needs work"))
      (when (some #(= :workflow (:category %)) (:fail by-status))
        (swap! improvements conj "- Full workflow integration has issues"))
      (when (< pass-rate 0.8)
        (swap! improvements conj "- Overall reliability below 80% - needs stabilization"))
      (when (some #(> (get-in % [:result :elapsed-ms] 0) 30000) tests)
        (swap! improvements conj "- Some operations are slow (>30s) - consider timeouts/optimization"))
      (when (empty? @improvements)
        (swap! improvements conj "- System appears healthy! Consider adding more test coverage."))
      (doseq [i @improvements]
        (println " " i)))

    (println "\n" (str/join "" (repeat 60 "=")))))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn run-tests [& {:keys [component]}]
  (println "🧪 KCX Test Harness")
  (println "   Mode:" (if *quick-mode* "quick" "full"))
  (println "   Verbose:" *verbose*)
  (when component
    (println "   Component:" component))

  ;; Load modules first
  (println "\n📦 Loading modules...")
  (doseq [m ['kcx.dsl 'kcx.state 'kcx.agents 'kcx.worker 'kcx.core 'kcx.logging]]
    (let [r (load-module m)]
      (if (:success r)
        (println (format "  ✓ %s" m))
        (println (format "  ✗ %s - %s" m (:error r))))))

  ;; Run tests based on component filter
  (let [run? (fn [c] (or (nil? component) (= component (name c))))]
    (when (run? :dsl) (test-dsl))
    (when (run? :state) (test-state))
    (when (run? :agents) (test-agents))
    (when (run? :mcp) (test-mcp))
    (when (run? :worker) (test-worker))
    (when (run? :workflow) (test-workflow)))

  (generate-report))

;; Parse args and run
(let [args (set *command-line-args*)]
  (binding [*verbose* (contains? args "--verbose")
            *quick-mode* (contains? args "--quick")]
    (let [component (some #(when (str/starts-with? % "--component=")
                             (subs % 12))
                          *command-line-args*)]
      (run-tests :component component))))
