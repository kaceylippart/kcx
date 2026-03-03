(ns kcx.orchestrator-test
  (:require
    [clojure.test :refer [deftest testing is run-tests]]
    [kcx.dsl :as dsl]
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
  (testing "Unknown verb returns error"
    (let [result (orchestrator/execute-command {:verb "unknown_verb" :target "test.clj" :modifiers []})]
      (is (clojure.string/includes? result "Unknown verb")))))


;; ============================================================================
;; Plan Generation
;; ============================================================================

(deftest test-workflow-plan-structure
  (testing "Workflow plan contains expected sections"
    (let [cmd {:verb "fix" :target "calc.clj" :args ["calc.clj"] :modifiers []}
          result (orchestrator/execute-command cmd)]
      (is (clojure.string/includes? result "KCX WORKFLOW"))
      (is (clojure.string/includes? result "!fix"))
      (is (clojure.string/includes? result "STEP 1"))
      (is (clojure.string/includes? result "WORKER"))
      (is (clojure.string/includes? result "WORKFLOW RULES")))))

(deftest test-workflow-plan-steps
  (testing "Standard workflow plan has worker, tester, reviewer, curator steps"
    (let [cmd {:verb "fix" :target "calc.clj" :args ["calc.clj"] :modifiers []}
          result (orchestrator/execute-command cmd)]
      (is (clojure.string/includes? result "WORKER"))
      (is (clojure.string/includes? result "TESTER"))
      (is (clojure.string/includes? result "REVIEWER"))
      (is (clojure.string/includes? result "CURATOR")))))

(deftest test-workflow-plan-with-directives
  (testing ">yolo skips workflow, returns prompt directly"
    (let [cmd {:verb "fix" :target "calc.clj" :args ["calc.clj"] :modifiers [] :directives ["yolo"]}
          result (orchestrator/execute-command cmd)]
      (is (clojure.string/includes? result "Fix the following issue"))
      (is (clojure.string/includes? result "Execute this directly"))
      (is (not (clojure.string/includes? result "KCX WORKFLOW")))
      (is (not (clojure.string/includes? result "STEP"))))))


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

(deftest test-plan-includes-expanded-modifiers
  (testing "Workflow plan includes expanded modifier text"
    (let [cmd {:verb "fix" :target "calc.clj" :args ["calc.clj"] :modifiers ["thorough"]}
          result (orchestrator/execute-command cmd)]
      ;; Should contain the expanded modifier text
      (is (clojure.string/includes? result "Be thorough")))))

(deftest test-cmd->expandable-dsl
  (testing "DSL command adapts to expandable format"
    (let [cmd {:verb "fix" :target "calc.clj" :args ["calc.clj"] :modifiers ["thorough" "minimal"] :instruction "fix the bug"}
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
    (let [cmd {:verb "fix" :target "global_context" :modifiers []}
          expandable (#'orchestrator/cmd->expandable cmd)]
      (is (= [] (get-in expandable [:verb :args]))))))

(deftest test-expand-cmd-known-verb
  (testing "Known verb expands successfully"
    (let [cmd {:verb "fix" :target "calc.clj" :args ["calc.clj"] :modifiers ["thorough"]}
          expanded (#'orchestrator/expand-cmd cmd)]
      (is (:expanded? expanded))
      (is (= "Fix the following issue: calc.clj." (:expanded-verb expanded)))
      (is (= :standard (:workflow expanded)))
      (is (= 1 (count (:expanded-modifiers expanded))))
      ;; Original cmd keys preserved
      (is (= "fix" (:verb expanded)))
      (is (= "calc.clj" (:target expanded))))))

(deftest test-expand-cmd-unknown-verb
  (testing "Unknown verb produces warnings"
    (let [cmd {:verb "yeet" :target "calc.clj" :modifiers []}
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
;; DSL Parser (4-symbol)
;; ============================================================================

(deftest test-dsl-basic-command
  (testing "Basic !verb @target parses correctly"
    (let [cmd (dsl/parse-command "!fix @calculator.clj")]
      (is (= "fix" (:verb cmd)))
      (is (= "calculator.clj" (:target cmd)))
      (is (= ["calculator.clj"] (:args cmd)))
      (is (= [] (:modifiers cmd)))
      (is (= [] (:directives cmd))))))

(deftest test-dsl-with-modifiers-and-directives
  (testing "Full command with +modifier and >directive"
    (let [cmd (dsl/parse-command "!fix @calc.clj +thorough >skip-tests")]
      (is (= "fix" (:verb cmd)))
      (is (= "calc.clj" (:target cmd)))
      (is (= ["thorough"] (:modifiers cmd)))
      (is (= ["skip-tests"] (:directives cmd))))))

(deftest test-dsl-multiple-directives
  (testing "Multiple >directives parsed"
    (let [cmd (dsl/parse-command "!fix @calc.clj >skip-tests >skip-review")]
      (is (= ["skip-tests" "skip-review"] (:directives cmd))))))

(deftest test-dsl-inline-natural-language
  (testing "Remaining text becomes instruction"
    (let [cmd (dsl/parse-command "!debug @calc.clj and let me know if there's anything else wrong")]
      (is (= "debug" (:verb cmd)))
      (is (= "calc.clj" (:target cmd)))
      (is (= "and let me know if there's anything else wrong" (:instruction cmd))))))

(deftest test-dsl-mixed-everything
  (testing "Verb, target, modifier, directive, and natural language"
    (let [cmd (dsl/parse-command "!fix @calc.clj +thorough >fast just fix the typo")]
      (is (= "fix" (:verb cmd)))
      (is (= "calc.clj" (:target cmd)))
      (is (= ["thorough"] (:modifiers cmd)))
      (is (= ["fast"] (:directives cmd)))
      (is (= "just fix the typo" (:instruction cmd))))))

(deftest test-dsl-no-old-symbols
  (testing "Old - and & symbols are not parsed as special"
    (let [cmd (dsl/parse-command "!fix @calc.clj -something &agent")]
      ;; -something and &agent should end up in instruction, not parsed as special tokens
      (is (= [] (:directives cmd)))
      (is (some? (:instruction cmd))))))

(deftest test-dsl-quoted-natural-language
  (testing "Quoted prompt still works"
    (let [cmd (dsl/parse-command "\"add error handling\"")]
      (is (= "prompt" (:verb cmd)))
      (is (= "add error handling" (:prompt cmd))))))

(deftest test-dsl-percent-alias
  (testing "% works as alias for @"
    (let [cmd (dsl/parse-command "!explain %workflows")]
      (is (= "explain" (:verb cmd)))
      (is (= "workflows" (:target cmd)))
      (is (= ["workflows"] (:args cmd))))))

(deftest test-dsl-multi-params
  (testing "Multiple @ and % fill positional args in order"
    (let [cmd (dsl/parse-command "!edit @calc.clj %error-handling")]
      (is (= "edit" (:verb cmd)))
      (is (= "calc.clj" (:target cmd)))
      (is (= ["calc.clj" "error-handling"] (:args cmd))))))

(deftest test-dsl-quoted-param
  (testing "Quoted param values have quotes stripped"
    (let [cmd (dsl/parse-command "!edit @calc.clj %\"add error handling\"")]
      (is (= "calc.clj" (:target cmd)))
      (is (= ["calc.clj" "add error handling"] (:args cmd))))))

(deftest test-dsl-quoted-at-param
  (testing "Quoted @ param also works"
    (let [cmd (dsl/parse-command "!explain @\"test-driven development\"")]
      (is (= "test-driven development" (:target cmd)))
      (is (= ["test-driven development"] (:args cmd))))))

(deftest test-dsl-mixed-params-with-modifiers
  (testing "Params and modifiers coexist"
    (let [cmd (dsl/parse-command "!edit @calc.clj %\"fix the bug\" +thorough >fast")]
      (is (= ["calc.clj" "fix the bug"] (:args cmd)))
      (is (= ["thorough"] (:modifiers cmd)))
      (is (= ["fast"] (:directives cmd))))))


;; ============================================================================
;; Run
;; ============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
