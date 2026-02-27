(ns kcx.workflow-test
  (:require
    [clojure.test :refer [deftest testing is run-tests]]
    [kcx.workflow :as wf]))


;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn success-handler
  "Returns a handler that always succeeds with the given data."
  [data]
  (fn [_cmd _artifacts]
    (merge {:success true} data)))

(defn fail-handler
  "Returns a handler that always fails with the given data."
  [data]
  (fn [_cmd _artifacts]
    (merge {:success false} data)))

(defn counting-handler
  "Returns a handler that tracks call count in an atom.
   Succeeds after n failures."
  [fail-count atom-ref data]
  (fn [_cmd _artifacts]
    (let [n (swap! atom-ref inc)]
      (if (<= n fail-count)
        (merge {:success false :attempt n} data)
        (merge {:success true :attempt n} data)))))

(defn artifact-capturing-handler
  "Returns a handler that records the artifacts it received."
  [captured-atom data]
  (fn [_cmd artifacts]
    (reset! captured-atom artifacts)
    (merge {:success true} data)))


;; ============================================================================
;; Executor: Happy Path
;; ============================================================================

(deftest test-standard-workflow-happy-path
  (testing "All handlers succeed → :done"
    (let [handlers {:worker   (success-handler {:files-changed ["a.clj"] :summary "did work"})
                    :tester   (success-handler {:verdict "pass" :feedback "tests pass"})
                    :reviewer (success-handler {:verdict "approve" :feedback "lgtm"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (:success result))
      (is (= :done (:final-state result)))
      (is (contains? (:artifacts result) :work))
      (is (contains? (:artifacts result) :test))
      (is (contains? (:artifacts result) :review))
      (is (contains? (:artifacts result) :curate)))))

(deftest test-tdd-workflow-happy-path
  (testing "TDD: write-tests → implement → validate → review → curate → done"
    (let [handlers {:tester   (success-handler {:files-changed ["test.clj"]})
                    :worker   (success-handler {:files-changed ["impl.clj"]})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/tdd-workflow {:verb "tdd"} handlers)]
      (is (:success result))
      (is (= :done (:final-state result)))
      ;; TDD has unique states
      (is (contains? (:artifacts result) :write-tests))
      (is (contains? (:artifacts result) :implement))
      (is (contains? (:artifacts result) :validate)))))

(deftest test-review-workflow-happy-path
  (testing "Review: reviewer → curate → done"
    (let [handlers {:reviewer (success-handler {:verdict "approve" :feedback "lgtm" :review-text "Full review here"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/review-workflow {:verb "review"} handlers)]
      (is (:success result))
      (is (= :done (:final-state result)))
      (is (contains? (:artifacts result) :review))
      (is (contains? (:artifacts result) :curate))
      (is (not (contains? (:artifacts result) :work))))))

(deftest test-explain-workflow-happy-path
  (testing "Explain: explainer → curate → done"
    (let [handlers {:explainer (success-handler {:explanation "This module does X" :summary "Explained the module"})
                    :curator   (success-handler {:updated true})}
          result   (wf/run wf/explain-workflow {:verb "explain"} handlers)]
      (is (:success result))
      (is (= :done (:final-state result)))
      (is (contains? (:artifacts result) :explain))
      (is (contains? (:artifacts result) :curate))
      (is (not (contains? (:artifacts result) :architect))))))

(deftest test-architect-workflow-happy-path
  (testing "Architect: architect → work → test → review → curate → done"
    (let [handlers {:architect (success-handler {:files-changed ["spec.md"]})
                    :worker    (success-handler {:files-changed ["impl.clj"]})
                    :tester    (success-handler {:verdict "pass"})
                    :reviewer  (success-handler {:verdict "approve"})
                    :curator   (success-handler {:updated true})}
          result   (wf/run wf/architect-workflow {:verb "plan"} handlers)]
      (is (:success result))
      (is (= :done (:final-state result)))
      (is (contains? (:artifacts result) :architect)))))


;; ============================================================================
;; Executor: Retry Behavior
;; ============================================================================

(deftest test-tester-failure-retries-to-worker
  (testing "Tester fails → retries back to worker, then succeeds"
    (let [tester-calls (atom 0)
          worker-calls (atom 0)
          handlers {:worker   (fn [_cmd _art]
                                (swap! worker-calls inc)
                                {:success true :files-changed ["a.clj"] :summary "work"})
                    :tester   (counting-handler 1 tester-calls {:feedback "tests fail"})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (:success result))
      ;; Worker called twice: once initially, once after tester retry
      (is (= 2 @worker-calls))
      ;; Tester called twice: first fail, then pass
      (is (= 2 @tester-calls)))))

(deftest test-reviewer-rejection-retries-to-worker
  (testing "Reviewer rejects → retries back to worker, then approves"
    (let [reviewer-calls (atom 0)
          worker-calls   (atom 0)
          handlers {:worker   (fn [_cmd _art]
                                (swap! worker-calls inc)
                                {:success true :files-changed ["a.clj"]})
                    :tester   (success-handler {:verdict "pass"})
                    :reviewer (counting-handler 1 reviewer-calls {:feedback "needs work"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (:success result))
      ;; Worker called twice: once initially, once after reviewer rejection
      (is (= 2 @worker-calls))
      ;; Reviewer called twice: first reject, then approve
      (is (= 2 @reviewer-calls)))))

(deftest test-retry-exhaustion-fails
  (testing "Tester fails more times than retries allow → :failed"
    (let [handlers {:worker  (success-handler {:files-changed ["a.clj"]})
                    :tester  (fail-handler {:feedback "always fails"})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (not (:success result)))
      (is (= :failed (:final-state result))))))


;; ============================================================================
;; Executor: Failure at First State
;; ============================================================================

(deftest test-worker-failure-immediate
  (testing "Worker fails immediately → :failed (no retries on :work)"
    (let [handlers {:worker   (fail-handler {:summary "spawn failed"})
                    :tester   (success-handler {})
                    :reviewer (success-handler {})
                    :curator  (success-handler {})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (not (:success result)))
      (is (= :failed (:final-state result))))))


;; ============================================================================
;; Artifact Accumulation
;; ============================================================================

(deftest test-artifacts-accumulate
  (testing "Each handler receives artifacts from prior states"
    (let [curator-saw (atom nil)
          handlers {:worker   (success-handler {:files-changed ["a.clj"] :summary "work done"})
                    :tester   (success-handler {:verdict "pass" :feedback "all good"})
                    :reviewer (success-handler {:verdict "approve" :feedback "lgtm"})
                    :curator  (artifact-capturing-handler curator-saw {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (:success result))
      ;; Curator should have seen all prior artifacts
      (is (contains? @curator-saw :work))
      (is (contains? @curator-saw :test))
      (is (contains? @curator-saw :review)))))

(deftest test-failed-artifacts-preserved
  (testing "Artifacts from failed states are preserved for retry handlers"
    (let [worker-saw (atom nil)
          tester-calls (atom 0)
          handlers {:worker   (artifact-capturing-handler worker-saw {:success true :files-changed ["a.clj"]})
                    :tester   (counting-handler 1 tester-calls {:feedback "first failure"})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers)]
      (is (:success result))
      ;; On the second worker call (after tester retry), worker should see the failed test artifact
      (is (contains? @worker-saw :test)))))


;; ============================================================================
;; Verb → Workflow Mapping
;; ============================================================================

(deftest test-get-workflow
  (testing "Workflow types resolve to correct definitions"
    (is (= :standard (:id (wf/get-workflow :standard))))
    (is (= :tdd (:id (wf/get-workflow :tdd))))
    (is (= :architect (:id (wf/get-workflow :architect))))
    (is (= :review (:id (wf/get-workflow :review))))
    (is (= :explain (:id (wf/get-workflow :explain))))
    ;; Unknown types fall back to standard
    (is (= :standard (:id (wf/get-workflow :nonexistent))))))


;; ============================================================================
;; Callbacks
;; ============================================================================

(deftest test-on-state-callback
  (testing "on-state callback fires for each state"
    (let [visited (atom [])
          handlers {:worker   (success-handler {:files-changed ["a.clj"]})
                    :tester   (success-handler {:verdict "pass"})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers
                           {:on-state (fn [state _def] (swap! visited conj state))})]
      (is (:success result))
      (is (= [:work :test :review :curate] @visited)))))

(deftest test-on-result-callback
  (testing "on-result callback fires after each handler"
    (let [results (atom [])
          handlers {:worker   (success-handler {:files-changed ["a.clj"]})
                    :tester   (success-handler {:verdict "pass"})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {:verb "fix"} handlers
                           {:on-result (fn [state result trans]
                                         (swap! results conj {:state state
                                                              :success (:success result)
                                                              :transition (:transition trans)}))})]
      (is (:success result))
      (is (= 4 (count @results)))
      (is (every? #(= :next (:transition %)) @results)))))


;; ============================================================================
;; Pipeline Directives
;; ============================================================================

(deftest test-apply-directives-skip-tests
  (testing ">skip-tests removes tester, rewires worker→reviewer"
    (let [{:keys [workflow warnings]} (wf/apply-directives wf/standard-workflow ["skip-tests"])]
      (is (empty? warnings))
      ;; Worker should go straight to review
      (is (= :review (get-in workflow [:states :work :next])))
      ;; No tester states remain
      (is (not (some (fn [[_ def]] (= :tester (:handler def))) (:states workflow)))))))

(deftest test-apply-directives-skip-review
  (testing ">skip-review removes reviewer, rewires tester→curator"
    (let [{:keys [workflow warnings]} (wf/apply-directives wf/standard-workflow ["skip-review"])]
      (is (empty? warnings))
      ;; Tester should go to curate
      (is (= :curate (get-in workflow [:states :test :next])))
      ;; No reviewer states remain
      (is (not (some (fn [[_ def]] (= :reviewer (:handler def))) (:states workflow)))))))

(deftest test-apply-directives-fast
  (testing ">fast removes tester and reviewer"
    (let [{:keys [workflow warnings]} (wf/apply-directives wf/standard-workflow ["fast"])]
      (is (empty? warnings))
      ;; Worker goes straight to curate
      (is (= :curate (get-in workflow [:states :work :next])))
      ;; Only worker + curator remain
      (is (= #{:worker :curator} (set (map :handler (vals (:states workflow)))))))))

(deftest test-apply-directives-yolo
  (testing ">yolo removes tester, reviewer, and curator"
    (let [{:keys [workflow warnings]} (wf/apply-directives wf/standard-workflow ["yolo"])]
      (is (empty? warnings))
      ;; Worker goes straight to done
      (is (= :done (get-in workflow [:states :work :next])))
      ;; Only worker remains
      (is (= #{:worker} (set (map :handler (vals (:states workflow)))))))))

(deftest test-apply-directives-composable
  (testing ">skip-tests >skip-review is equivalent to >fast"
    (let [composed (:workflow (wf/apply-directives wf/standard-workflow ["skip-tests" "skip-review"]))
          fast     (:workflow (wf/apply-directives wf/standard-workflow ["fast"]))]
      ;; Both should have worker→curate
      (is (= :curate (get-in composed [:states :work :next])))
      (is (= :curate (get-in fast [:states :work :next])))
      ;; Same handler set
      (is (= (set (map :handler (vals (:states composed))))
             (set (map :handler (vals (:states fast)))))))))

(deftest test-apply-directives-unknown
  (testing "Unknown directive produces warning"
    (let [{:keys [workflow warnings]} (wf/apply-directives wf/standard-workflow ["yeet"])]
      (is (= 1 (count warnings)))
      (is (re-find #"yeet" (first warnings)))
      ;; Workflow unchanged
      (is (= (:states wf/standard-workflow) (:states workflow))))))

(deftest test-apply-directives-empty
  (testing "No directives returns workflow unchanged"
    (let [{:keys [workflow warnings]} (wf/apply-directives wf/standard-workflow [])]
      (is (empty? warnings))
      (is (= (:states wf/standard-workflow) (:states workflow))))))

(deftest test-apply-directives-executes
  (testing "Modified workflow actually runs correctly"
    (let [{:keys [workflow]} (wf/apply-directives wf/standard-workflow ["fast"])
          handlers {:worker  (success-handler {:files-changed ["a.clj"] :summary "did work"})
                    :curator (success-handler {:updated true})}
          result (wf/run workflow {:verb "fix"} handlers)]
      (is (:success result))
      (is (= :done (:final-state result)))
      (is (contains? (:artifacts result) :work))
      (is (contains? (:artifacts result) :curate))
      ;; No test or review artifacts
      (is (not (contains? (:artifacts result) :test)))
      (is (not (contains? (:artifacts result) :review))))))


;; ============================================================================
;; Edge Cases
;; ============================================================================

(deftest test-missing-handler-throws
  (testing "Missing handler throws descriptive error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No handler for"
          (wf/run wf/standard-workflow {:verb "fix"} {})))))

(deftest test-empty-cmd
  (testing "Workflow runs with minimal cmd"
    (let [handlers {:worker   (success-handler {:files-changed []})
                    :tester   (success-handler {:verdict "pass"})
                    :reviewer (success-handler {:verdict "approve"})
                    :curator  (success-handler {:updated true})}
          result   (wf/run wf/standard-workflow {} handlers)]
      (is (:success result)))))


;; ============================================================================
;; Run
;; ============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
