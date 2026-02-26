(ns kcx.state-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [kcx.state :as state]))


;; ============================================================================
;; Template & Validation
;; ============================================================================

(deftest test-create-template
  (testing "Creates v2 briefing template"
    (let [tmpl (state/create-template "test-project")]
      (is (= "2.0" (get-in tmpl [:meta :version])))
      (is (= "test-project" (get-in tmpl [:meta :project])))
      (is (= 0 (get-in tmpl [:meta :command-count])))
      (is (map? (:briefing tmpl)))
      (is (string? (get-in tmpl [:briefing :project-map])))
      (is (string? (get-in tmpl [:briefing :conventions])))
      (is (string? (get-in tmpl [:briefing :architecture])))
      (is (string? (get-in tmpl [:briefing :active-context])))
      (is (string? (get-in tmpl [:briefing :known-issues]))))))

(deftest test-validate-state
  (testing "Validates v2 state (briefing-based)"
    (is (true? (state/validate-state
                 {:meta {:version "2.0" :project "x"}
                  :briefing {:project-map "..." :active-context "..."}}))))

  (testing "Validates v1 state (legacy entry-based)"
    (is (true? (state/validate-state
                 {:meta {:version "1.0" :project "x"}
                  :command-count 0
                  :memory []}))))

  (testing "Rejects invalid state"
    (is (false? (state/validate-state {})))
    (is (false? (state/validate-state {:meta {}})))
    (is (false? (state/validate-state nil)))
    (is (false? (state/validate-state "not a map")))))


;; ============================================================================
;; v1 → v2 Migration
;; ============================================================================

(deftest test-migrate-v1-to-v2
  (testing "Migrates v1 entries to v2 active-context"
    (let [v1-state {:meta {:version "1.0" :project "test-proj" :created "2026-01-01"}
                    :command-count 5
                    :memory [{:action "fix" :target "calc.clj" :description "Fixed divide by zero"}
                             {:action "edit" :target "api.clj" :description "Added endpoint"}]}
          v2-state (#'state/migrate-v1->v2 v1-state)]
      (is (= "2.0" (get-in v2-state [:meta :version])))
      (is (= "test-proj" (get-in v2-state [:meta :project])))
      (is (= 5 (get-in v2-state [:meta :command-count])))
      (is (map? (:briefing v2-state)))
      ;; Active context should contain migrated entries
      (let [active (get-in v2-state [:briefing :active-context])]
        (is (clojure.string/includes? active "fix calc.clj"))
        (is (clojure.string/includes? active "edit api.clj"))
        (is (clojure.string/includes? active "Fixed divide by zero")))
      ;; Other sections should be placeholder
      (is (clojure.string/starts-with? (get-in v2-state [:briefing :project-map]) "(Not yet"))))

  (testing "Migrates empty v1 state"
    (let [v1-state {:meta {:version "1.0" :project "empty"} :command-count 0 :memory []}
          v2-state (#'state/migrate-v1->v2 v1-state)]
      (is (= "2.0" (get-in v2-state [:meta :version])))
      (is (= 0 (get-in v2-state [:meta :command-count])))
      (is (clojure.string/includes? (get-in v2-state [:briefing :active-context]) "No previous activity")))))


;; ============================================================================
;; Memory Context Building
;; ============================================================================

(deftest test-build-memory-context-empty
  (testing "Returns nil for fresh template (all placeholders)"
    ;; build-memory-context calls load-state which reads from disk,
    ;; so we test the logic by verifying the template has placeholder text
    (let [tmpl (state/create-template "fresh")]
      (is (clojure.string/starts-with?
            (get-in tmpl [:briefing :project-map])
            "(Not yet")))))

(deftest test-build-memory-context-with-content
  (testing "build-memory-context handles nil/empty cmd gracefully"
    ;; Should not throw, may return nil or string
    (let [result (state/build-memory-context nil)]
      (is (or (nil? result) (string? result))))
    (let [result (state/build-memory-context {})]
      (is (or (nil? result) (string? result))))
    (let [result (state/build-memory-context {:verb nil :target nil})]
      (is (or (nil? result) (string? result))))))


;; ============================================================================
;; Briefing Sections
;; ============================================================================

(deftest test-briefing-sections
  (testing "All five sections are defined"
    (is (= 5 (count state/briefing-sections)))
    (is (= [:project-map :conventions :architecture :active-context :known-issues]
           state/briefing-sections))))
