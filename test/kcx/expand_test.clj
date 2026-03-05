(ns kcx.expand-test
  (:require
    [clojure.test :refer [deftest testing is run-tests]]
    [kcx.expand :as expand]))


;; ============================================================================
;; Test Fixtures — mock expansion dictionaries
;; ============================================================================

(def test-base-expansions
  {:verbs
   {"review" {:prompt "Review {target}, focusing on {scope}."
              :params [{:name "target" :default "the codebase"}
                       {:name "scope"  :default "correctness and code quality"}]
              :workflow :standard}
    "fix"    {:prompt "Fix the issue in {target}."
              :params [{:name "target" :default "the codebase"}]
              :workflow :standard}
    "tdd"    {:prompt "Write tests first for {target}, then implement."
              :params [{:name "target" :default "the codebase"}]
              :workflow :tdd}}
   :modifiers
   {"thorough"    {:prompt "Compare against the broader codebase."
                   :applies-to :all}
    "no-hedge"    {:prompt "Be direct and confident."
                   :applies-to :reviewer}
    "minimal"     {:prompt "Make the smallest change possible."
                   :applies-to :worker}
    "style"       {:prompt "Follow the patterns in {ref}."
                   :params [{:name "ref" :default "the existing codebase"}]
                   :applies-to :worker}
    "step-by-step" {:prompt "Explain your reasoning at each step."
                    :applies-to :all}}})

(def test-project-expansions
  {:verbs
   {"deploy" {:prompt "Deploy {target} to staging."
              :params [{:name "target" :default "the application"}]
              :workflow :standard}}
   :modifiers
   {"our-style" {:prompt "Follow the team API conventions in routes.clj."
                 :applies-to :worker}}})



;; ============================================================================
;; Template Rendering
;; ============================================================================

(deftest test-render-template-all-params
  (testing "Template with all params provided"
    (is (= {:ok "Review calc.clj, focusing on divide-fn."}
           (expand/render-template
             "Review {target}, focusing on {scope}."
             [{:name "target" :default "the codebase"}
              {:name "scope"  :default "correctness and code quality"}]
             ["calc.clj" "divide-fn"])))))

(deftest test-render-template-defaults
  (testing "Missing params use string defaults"
    (is (= {:ok "Review the codebase, focusing on correctness and code quality."}
           (expand/render-template
             "Review {target}, focusing on {scope}."
             [{:name "target" :default "the codebase"}
              {:name "scope"  :default "correctness and code quality"}]
             [])))))

(deftest test-render-template-partial-defaults
  (testing "Some args provided, rest use defaults"
    (is (= {:ok "Review calc.clj, focusing on correctness and code quality."}
           (expand/render-template
             "Review {target}, focusing on {scope}."
             [{:name "target" :default "the codebase"}
              {:name "scope"  :default "correctness and code quality"}]
             ["calc.clj"])))))

(deftest test-render-template-no-params
  (testing "Template with no param slots passes through"
    (is (= {:ok "Be direct and confident."}
           (expand/render-template
             "Be direct and confident."
             nil
             [])))))

(deftest test-render-template-single-param
  (testing "Single param substitution"
    (is (= {:ok "Fix the issue in calc.clj."}
           (expand/render-template
             "Fix the issue in {target}."
             [{:name "target" :default "the codebase"}]
             ["calc.clj"])))))

(deftest test-render-template-required-param-provided
  (testing "Required param with arg succeeds"
    (is (= {:ok "Debug the following error: NPE in foo.clj."}
           (expand/render-template
             "Debug the following error: {target}."
             [{:name "target"}]
             ["NPE in foo.clj"])))))

(deftest test-render-template-required-param-missing
  (testing "Required param without arg returns error"
    (let [result (expand/render-template
                   "Debug the following error: {target}."
                   [{:name "target"}]
                   [])]
      (is (:error result))
      (is (re-find #"target" (:error result))))))

(deftest test-render-template-mixed-required-and-default
  (testing "Mix of required and defaulted params"
    (is (= {:ok "Deploy foo to staging."}
           (expand/render-template
             "Deploy {target} to {env}."
             [{:name "target"}
              {:name "env" :default "staging"}]
             ["foo"])))
    (let [result (expand/render-template
                   "Deploy {target} to {env}."
                   [{:name "target"}
                    {:name "env" :default "staging"}]
                   [])]
      (is (:error result))
      (is (re-find #"target" (:error result))))))


;; ============================================================================
;; Dictionary Merging
;; ============================================================================

(deftest test-merge-expansions-base-only
  (testing "Base expansions work alone"
    (let [merged (expand/merge-expansions test-base-expansions nil)]
      (is (= "Review {target}, focusing on {scope}."
             (get-in merged [:verbs "review" :prompt])))
      (is (= "Compare against the broader codebase."
             (get-in merged [:modifiers "thorough" :prompt]))))))

(deftest test-merge-expansions-project-adds
  (testing "Project expansions add new entries"
    (let [merged (expand/merge-expansions test-base-expansions test-project-expansions)]
      ;; Project adds deploy verb
      (is (some? (get-in merged [:verbs "deploy"])))
      ;; Project adds our-style modifier
      (is (some? (get-in merged [:modifiers "our-style"])))
      ;; Base entries still present
      (is (some? (get-in merged [:verbs "review"]))))))

(deftest test-merge-expansions-project-overrides-base
  (testing "Project overrides base at key level"
    (let [project-with-review (assoc-in test-project-expansions
                                        [:verbs "review"]
                                        {:prompt "Project review of {target}."
                                         :params [{:name "target" :default "the codebase"}]})
          merged (expand/merge-expansions test-base-expansions project-with-review)]
      ;; Project wins
      (is (= "Project review of {target}."
             (get-in merged [:verbs "review" :prompt]))))))


;; ============================================================================
;; Core Expansion
;; ============================================================================

(deftest test-expand-verb-with-args
  (testing "Verb expands with positional args"
    (let [cmd {:verb {:name "review" :args ["calc.clj" "divide-fn"]}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (:expanded? result))
      (is (= "Review calc.clj, focusing on divide-fn."
             (:expanded-verb result)))
      (is (= :standard (:workflow result))))))

(deftest test-expand-verb-no-args-uses-defaults
  (testing "Verb with no args uses defaults"
    (let [cmd {:verb {:name "review" :args []}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (:expanded? result))
      (is (= "Review the codebase, focusing on correctness and code quality."
             (:expanded-verb result))))))

(deftest test-expand-verb-partial-args
  (testing "Verb with some args, rest use default"
    (let [cmd {:verb {:name "review" :args ["calc.clj"]}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (= "Review calc.clj, focusing on correctness and code quality."
             (:expanded-verb result))))))

(deftest test-expand-modifiers
  (testing "Modifiers expand correctly"
    (let [cmd {:verb {:name "fix" :args ["calc.clj"]}
               :modifiers [{:name "thorough" :args []}
                           {:name "no-hedge" :args []}]
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (= 2 (count (:expanded-modifiers result))))
      (is (= "Compare against the broader codebase."
             (:prompt (first (:expanded-modifiers result)))))
      (is (= :all (:applies-to (first (:expanded-modifiers result)))))
      (is (= :reviewer (:applies-to (second (:expanded-modifiers result))))))))

(deftest test-expand-modifier-with-args
  (testing "Modifier with args substitutes params"
    (let [cmd {:verb {:name "fix" :args ["calc.clj"]}
               :modifiers [{:name "style" :args ["routes.clj"]}]
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (= "Follow the patterns in routes.clj."
             (:prompt (first (:expanded-modifiers result))))))))

(deftest test-expand-modifier-default-param
  (testing "Modifier with no args uses default"
    (let [cmd {:verb {:name "fix" :args ["calc.clj"]}
               :modifiers [{:name "style" :args []}]
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (= "Follow the patterns in the existing codebase."
             (:prompt (first (:expanded-modifiers result))))))))

(deftest test-expand-unknown-verb
  (testing "Unknown verb produces warning"
    (let [cmd {:verb {:name "yeet" :args ["calc.clj"]}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (not (:expanded? result)))
      (is (seq (:warnings result)))
      (is (re-find #"yeet" (first (:warnings result)))))))

(deftest test-expand-unknown-modifier
  (testing "Unknown modifier produces warning"
    (let [cmd {:verb {:name "fix" :args ["calc.clj"]}
               :modifiers [{:name "yolo" :args []}]
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      ;; Verb still expands
      (is (some? (:expanded-verb result)))
      ;; But warning for unknown modifier
      (is (seq (:warnings result)))
      (is (re-find #"yolo" (first (:warnings result)))))))

(deftest test-expand-preserves-user-text
  (testing "User text preserved through expansion"
    (let [cmd {:verb {:name "review" :args ["calc.clj"]}
               :modifiers []
               :user-text "the divide function has a bug"}
          result (expand/expand cmd test-base-expansions)]
      (is (= "the divide function has a bug" (:user-text result))))))

(deftest test-expand-natural-language-passthrough
  (testing "Pure natural language (no verb) passes through unexpanded"
    (let [cmd {:verb nil
               :prompt "add error handling to the calculator"
               :modifiers []
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (not (:expanded? result)))
      (is (= "add error handling to the calculator" (:prompt result))))))

(deftest test-expand-workflow-override
  (testing "Verb expansion can specify workflow type"
    (let [cmd {:verb {:name "tdd" :args ["calc.clj"]}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd test-base-expansions)]
      (is (= :tdd (:workflow result))))))

(deftest test-expand-required-param-missing
  (testing "Verb with missing required param produces warning, no expansion"
    (let [expansions {:verbs {"debug" {:prompt "Debug the following error: {target}."
                                        :params [{:name "target"}]
                                        :workflow :standard}}}
          cmd {:verb {:name "debug" :args []}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd expansions)]
      (is (not (:expanded? result)))
      (is (seq (:warnings result)))
      (is (re-find #"target" (first (:warnings result)))))))

(deftest test-expand-required-param-provided
  (testing "Verb with required param provided expands normally"
    (let [expansions {:verbs {"debug" {:prompt "Debug the following error: {target}."
                                        :params [{:name "target"}]
                                        :workflow :standard}}}
          cmd {:verb {:name "debug" :args ["NPE in foo.clj"]}
               :modifiers []
               :user-text nil}
          result (expand/expand cmd expansions)]
      (is (:expanded? result))
      (is (= "Debug the following error: NPE in foo.clj."
             (:expanded-verb result))))))


;; ============================================================================
;; Filter Modifiers by Agent Role
;; ============================================================================

(deftest test-filter-modifiers-for-worker
  (testing "Worker gets :worker and :all modifiers"
    (let [mods [{:key "thorough" :prompt "..." :applies-to :all}
                {:key "no-hedge" :prompt "..." :applies-to :reviewer}
                {:key "minimal"  :prompt "..." :applies-to :worker}]]
      (is (= 2 (count (expand/filter-modifiers-for :worker mods)))))))

(deftest test-filter-modifiers-for-reviewer
  (testing "Reviewer gets :reviewer and :all modifiers"
    (let [mods [{:key "thorough" :prompt "..." :applies-to :all}
                {:key "no-hedge" :prompt "..." :applies-to :reviewer}
                {:key "minimal"  :prompt "..." :applies-to :worker}]]
      (is (= 2 (count (expand/filter-modifiers-for :reviewer mods)))))))

(deftest test-filter-modifiers-empty
  (testing "No modifiers returns empty"
    (is (empty? (expand/filter-modifiers-for :worker [])))))


;; ============================================================================
;; Suggest Similar (for "did you mean?" warnings)
;; ============================================================================

(deftest test-suggest-similar-close-match
  (testing "Suggests close matches for typos"
    (let [known ["thorough" "minimal" "skip-tests" "no-hedge"]
          suggestions (expand/suggest-similar "throough" known)]
      (is (some #(= "thorough" %) suggestions)))))

(deftest test-suggest-similar-prefix-match
  (testing "Suggests prefix matches"
    (let [known ["thorough" "minimal" "skip-tests" "no-hedge"]
          suggestions (expand/suggest-similar "skip" known)]
      (is (some #(= "skip-tests" %) suggestions)))))

(deftest test-suggest-similar-no-match
  (testing "No suggestions for completely different input"
    (let [known ["thorough" "minimal" "skip-tests"]
          suggestions (expand/suggest-similar "xyzzy" known)]
      (is (empty? suggestions)))))


;; ============================================================================
;; Disk Loading
;; ============================================================================

(deftest test-load-base-expansions
  (testing "Loads base-expansions.edn from resources"
    (let [base (expand/load-base-expansions)]
      (is (map? base))
      (is (some? (get-in base [:verbs "fix"])))
      (is (some? (get-in base [:verbs "review"])))
      (is (some? (get-in base [:modifiers "thorough"])))
      ;; Verify structure
      (is (string? (get-in base [:verbs "fix" :prompt])))
      (is (keyword? (get-in base [:verbs "fix" :workflow]))))))

(deftest test-load-expansions-from-missing-file
  (testing "Loading from nonexistent file returns nil"
    (is (nil? (expand/load-expansions-file "/tmp/does-not-exist-kcx.edn")))))

(deftest test-load-and-merge-full-stack
  (testing "Load base + merge with in-memory project"
    (let [base (expand/load-base-expansions)
          merged (expand/merge-expansions base test-project-expansions)]
      ;; Project deploy added
      (is (some? (get-in merged [:verbs "deploy"])))
      ;; Base fix still there
      (is (some? (get-in merged [:verbs "fix"]))))))


;; ============================================================================
;; Run
;; ============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
