(ns kcx.workflow
  "Data-driven workflow engine for KCX.

   Workflows are declarative graphs of states and transitions.
   A single generic executor walks the graph, spawning handlers
   at each state and accumulating artifacts between stages.

   The executor controls SEQUENCE (what happens when).
   Handlers control CAPABILITY (how work gets done)."
  (:require
    [clojure.string :as str]))


;; ============================================================================
;; Workflow Definitions
;; ============================================================================
;; Each workflow is a map with:
;;   :id      - keyword identifier
;;   :initial - starting state
;;   :states  - map of state-keyword -> state-definition
;;
;; Each state definition has:
;;   :handler   - keyword selecting the handler function
;;   :next      - state to transition to on success
;;   :on-fail   - state to transition to on failure (default :failed)
;;   :on-reject - state to transition to on rejection (default :failed)
;;   :retries   - max times this state can be re-entered via on-fail/on-reject (default 0)

(def standard-workflow
  "WORKER → TESTER → REVIEWER → CURATOR → DONE
   Tester failure loops back to worker. Reviewer rejection loops back to worker."
  {:id      :standard
   :initial :work
   :states
   {:work   {:handler :worker   :next :test    :on-fail :failed}
    :test   {:handler :tester   :next :review  :on-fail :work   :retries 3}
    :review {:handler :reviewer :next :curate  :on-reject :work :retries 3}
    :curate {:handler :curator  :next :done}}})

(def tdd-workflow
  "TESTER (write tests) → WORKER (implement) → TESTER (validate) → REVIEWER → CURATOR → DONE
   Validation failure loops back to worker."
  {:id      :tdd
   :initial :write-tests
   :states
   {:write-tests {:handler :tester    :next :implement :on-fail :failed}
    :implement   {:handler :worker    :next :validate  :on-fail :failed}
    :validate    {:handler :tester    :next :review    :on-fail :implement :retries 3}
    :review      {:handler :reviewer  :next :curate    :on-reject :implement :retries 3}
    :curate      {:handler :curator   :next :done}}})

(def review-workflow
  "WORKER (review) → CURATOR → DONE
   Lightweight path for review/check/lint — no testing or secondary review."
  {:id      :review
   :initial :work
   :states
   {:work   {:handler :worker  :next :curate :on-fail :failed}
    :curate {:handler :curator :next :done}}})

(def explain-workflow
  "EXPLAINER → DONE
   Single read-only agent. No files written, no memory update."
  {:id      :explain
   :initial :explain
   :states
   {:explain {:handler :explainer :next :done :on-fail :failed}}})

(def architect-workflow
  "ARCHITECT → WORKER → TESTER → REVIEWER → CURATOR → DONE
   Architect creates specs first, then standard pipeline."
  {:id      :architect
   :initial :architect
   :states
   {:architect {:handler :architect :next :work    :on-fail :failed}
    :work      {:handler :worker    :next :test    :on-fail :failed}
    :test      {:handler :tester    :next :review  :on-fail :work   :retries 3}
    :review    {:handler :reviewer  :next :curate  :on-reject :work :retries 3}
    :curate    {:handler :curator   :next :done}}})


(defn verb->workflow
  "Map a DSL verb to its workflow definition."
  [verb]
  (case verb
    ("test" "tdd")                        tdd-workflow
    ("plan" "arch" "design" "analyze")    architect-workflow
    ("review" "check" "lint")             review-workflow
    ("explain" "why" "how")              explain-workflow
    standard-workflow))

(defn get-workflow
  "Get a workflow definition by keyword type (:standard, :tdd, :architect)."
  [workflow-type]
  (case workflow-type
    :tdd       tdd-workflow
    :architect architect-workflow
    :review    review-workflow
    :explain   explain-workflow
    standard-workflow))


;; ============================================================================
;; Pipeline Directives
;; ============================================================================
;; Directives modify the workflow graph at runtime by removing stages.
;; Each directive maps to a set of handler keywords to remove.

(def ^:private directive->handlers
  "Map of directive names to the handler keywords they remove."
  {"skip-tests"  #{:tester}
   "skip-review" #{:reviewer}
   "fast"        #{:tester :reviewer}
   "yolo"        #{:tester :reviewer :curator}})

(def known-directives (set (keys directive->handlers)))

(defn- remove-handler-from-workflow
  "Remove all states with a given handler keyword from a workflow.
   Rewires transitions: any state pointing to a removed state now
   points to that removed state's :next target instead."
  [workflow handler-key]
  (let [states (:states workflow)
        ;; Find states to remove (those with the target handler)
        removed-states (set (keep (fn [[state-key state-def]]
                                    (when (= handler-key (:handler state-def))
                                      state-key))
                                  states))
        ;; Build a redirect map: removed-state → its :next
        redirects (into {} (map (fn [s] [s (get-in states [s :next])]) removed-states))
        ;; Follow redirect chains (in case consecutive states are removed)
        resolve-redirect (fn [target]
                           (loop [t target seen #{}]
                             (if (or (nil? t) (contains? seen t) (not (contains? redirects t)))
                               t
                               (recur (get redirects t) (conj seen t)))))
        ;; Rewire remaining states
        new-states (into {}
                         (keep (fn [[state-key state-def]]
                                 (when-not (contains? removed-states state-key)
                                   [state-key
                                    (cond-> state-def
                                      (:next state-def)
                                      (update :next resolve-redirect)
                                      (:on-fail state-def)
                                      (update :on-fail resolve-redirect)
                                      (:on-reject state-def)
                                      (update :on-reject resolve-redirect))]))
                               states))
        ;; Fix initial state if it was removed
        new-initial (resolve-redirect (:initial workflow))]
    (assoc workflow
           :states new-states
           :initial new-initial)))

(defn apply-directives
  "Apply pipeline directives to a workflow, removing stages.
   Returns {:workflow modified-workflow :warnings [...]}.

   Directives are strings like \"skip-tests\", \"fast\", etc.
   Unknown directives produce warnings but don't fail."
  [workflow directives]
  (if (empty? directives)
    {:workflow workflow :warnings []}
    (let [warnings (vec (keep (fn [d]
                                (when-not (contains? known-directives d)
                                  (str ">" d " is not a known directive. Known: "
                                       (str/join ", " (map #(str ">" %) (sort known-directives))))))
                              directives))
          handlers-to-remove (reduce (fn [acc d]
                                       (into acc (get directive->handlers d #{})))
                                     #{}
                                     directives)
          modified (reduce remove-handler-from-workflow workflow handlers-to-remove)]
      {:workflow modified :warnings warnings})))


;; ============================================================================
;; Executor
;; ============================================================================

(defn- resolve-transition
  "Determine the next state after a handler result.
   On success: transition to :next.
   On failure: check retry budget, transition to :on-fail/:on-reject or :failed."
  [state-def result retry-count]
  (if (:success result)
    {:transition :next
     :next-state (:next state-def)}
    (let [fallback  (or (:on-fail state-def) (:on-reject state-def) :failed)
          max-r     (:retries state-def 0)]
      (if (< retry-count max-r)
        {:transition :retry
         :next-state fallback}
        {:transition :exhausted
         :next-state :failed}))))


(defn run
  "Execute a workflow to completion.

   Parameters:
     workflow  - workflow definition map (e.g., standard-workflow)
     cmd       - parsed DSL command
     handlers  - map of handler-keyword -> (fn [cmd artifacts] -> result)
     opts      - optional settings:
                 :on-state  (fn [state state-def]) called before each handler
                 :on-result (fn [state result transition]) called after each handler

   Returns:
     {:success bool
      :artifacts {state-keyword -> handler-result, ...}
      :final-state :done|:failed
      :retries {state-keyword -> retry-count, ...}}"
  ([workflow cmd handlers]
   (run workflow cmd handlers {}))
  ([workflow cmd handlers {:keys [on-state on-result] :as opts}]
   (loop [state     (:initial workflow)
          artifacts {}
          retries   {}]
     (cond
       (= state :done)
       {:success true :artifacts artifacts :final-state :done :retries retries}

       (= state :failed)
       {:success false :artifacts artifacts :final-state :failed :retries retries}

       :else
       (let [state-def  (get-in workflow [:states state])
             _          (when on-state (on-state state state-def))
             handler-fn (get handlers (:handler state-def))
             _          (when-not handler-fn
                          (throw (ex-info (str "No handler for " (:handler state-def))
                                         {:state state :handler (:handler state-def)})))
             result     (handler-fn cmd artifacts)
             transition (resolve-transition state-def result (get retries state 0))
             _          (when on-result (on-result state result transition))]
         (recur (:next-state transition)
                (assoc artifacts state result)
                (if (= :retry (:transition transition))
                  (update retries state (fnil inc 0))
                  retries)))))))
