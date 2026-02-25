(ns kcx.orchestrator-test
  (:require
    [clojure.test :refer [deftest testing is run-tests]]
    [kcx.orchestrator :as orchestrator]))


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
;; Run
;; ============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
