(ns kcx.orchestrator-test
  (:require
    [clojure.test :refer [deftest testing is run-tests]]
    [kcx.orchestrator :as orchestrator]
    [kcx.expand :as expand]))


;; ============================================================================
;; Controller Routing
;; ============================================================================

(deftest test-controller-commands
  (testing "Controller verbs route correctly"
    (is (string? (orchestrator/execute-command {:verb "list"})))
    (is (string? (orchestrator/execute-command {:verb "status"})))
    (is (string? (orchestrator/execute-command {:verb "jobs"})))))

(deftest test-nil-command
  (testing "Nil command returns error"
    (let [result (orchestrator/execute-command nil)]
      (is (clojure.string/starts-with? result "ERROR:")))))

(deftest test-unknown-verb
  (testing "Unknown non-workflow verb routes to controller"
    (let [result (orchestrator/execute-command {:verb "unknown_verb"})]
      (is (string? result)))))


;; ============================================================================
;; Result Formatting
;; ============================================================================

(deftest test-format-workflow-result-success
  (testing "Successful result formats correctly"
    (let [result {:success true
                  :artifacts {:work {:files-changed ["a.clj"] :summary "did work"}
                              :test {:verdict "pass"}
                              :review {:verdict "approve" :feedback "lgtm"}
                              :curate {:updated true}}}
          formatted (orchestrator/format-workflow-result result {:verb "fix"})]
      (is (clojure.string/includes? formatted "COMPLETED"))
      (is (clojure.string/includes? formatted "a.clj"))
      (is (clojure.string/includes? formatted "did work"))
      (is (clojure.string/includes? formatted "lgtm")))))

(deftest test-format-workflow-result-failure
  (testing "Failed result formats correctly"
    (let [result {:success false
                  :artifacts {:work {:files-changed ["a.clj"] :summary "tried"}}
                  :retries {:test 3}}
          formatted (orchestrator/format-workflow-result result {:verb "fix"})]
      (is (clojure.string/includes? formatted "FAILED")))))


;; ============================================================================
;; Redo
;; ============================================================================

(deftest test-redo-without-previous
  (testing "Redo with no previous command returns error"
    ;; Reset last command state
    (reset! @(resolve 'kcx.worker/last-command-state) nil)
    (let [result (orchestrator/execute-redo {:verb "redo"})]
      (is (clojure.string/includes? result "ERROR")))))


;; ============================================================================
;; Expansion Integration
;; ============================================================================

(deftest test-worker-prompt-uses-expanded-text
  (testing "Worker prompt builder uses expanded verb when available"
    (let [cmd {:verb "fix" :target "calc.clj" :includes ["thorough"]
               :expanded? true
               :expanded-verb "Fix the issue in calc.clj."
               :expanded-modifiers [{:key "thorough" :prompt "Be thorough. Compare against the broader codebase." :applies-to :all}]}
          prompt ((resolve 'kcx.worker/build-worker-prompt) cmd)]
      ;; Should contain the expanded verb text
      (is (clojure.string/includes? prompt "Fix the issue in calc.clj."))
      ;; Should contain the expanded modifier as a directive
      (is (clojure.string/includes? prompt "DIRECTIVES"))
      (is (clojure.string/includes? prompt "Be thorough"))
      ;; Should NOT contain raw "FOCUS ON: thorough" (legacy constraints)
      (is (not (clojure.string/includes? prompt "FOCUS ON:"))))))

(deftest test-cmd->expandable-dsl
  (testing "DSL command adapts to expandable format"
    (let [cmd {:verb "fix" :target "calc.clj" :includes ["thorough" "minimal"] :instruction "fix the bug"}
          expandable (#'orchestrator/cmd->expandable cmd)]
      (is (= "fix" (get-in expandable [:verb :name])))
      (is (= ["calc.clj"] (get-in expandable [:verb :args])))
      (is (= 2 (count (:modifiers expandable))))
      (is (= "thorough" (get-in expandable [:modifiers 0 :name])))
      (is (= "fix the bug" (:user-text expandable))))))

(deftest test-cmd->expandable-natural-language
  (testing "Natural language command produces nil verb"
    (let [cmd {:verb "prompt" :prompt "add error handling"}
          expandable (#'orchestrator/cmd->expandable cmd)]
      (is (nil? (:verb expandable)))
      (is (= "add error handling" (:prompt expandable))))))

(deftest test-cmd->expandable-no-target
  (testing "Global context target produces empty args"
    (let [cmd {:verb "fix" :target "global_context" :includes []}
          expandable (#'orchestrator/cmd->expandable cmd)]
      (is (= [] (get-in expandable [:verb :args]))))))

(deftest test-expand-cmd-known-verb
  (testing "Known verb expands successfully"
    (let [cmd {:verb "fix" :target "calc.clj" :includes ["thorough"]}
          expanded (#'orchestrator/expand-cmd cmd)]
      (is (:expanded? expanded))
      (is (= "Fix the issue in calc.clj." (:expanded-verb expanded)))
      (is (= :standard (:workflow expanded)))
      (is (= 1 (count (:expanded-modifiers expanded))))
      ;; Original cmd keys preserved
      (is (= "fix" (:verb expanded)))
      (is (= "calc.clj" (:target expanded))))))

(deftest test-expand-cmd-unknown-verb
  (testing "Unknown verb produces warnings"
    (let [cmd {:verb "yeet" :target "calc.clj" :includes []}
          expanded (#'orchestrator/expand-cmd cmd)]
      (is (not (:expanded? expanded)))
      (is (seq (:warnings expanded))))))

(deftest test-expand-cmd-natural-language
  (testing "Natural language passes through without expansion"
    (let [cmd {:verb "prompt" :prompt "add error handling"}
          expanded (#'orchestrator/expand-cmd cmd)]
      (is (not (:expanded? expanded)))
      (is (= "add error handling" (:prompt expanded))))))


;; ============================================================================
;; Run
;; ============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
