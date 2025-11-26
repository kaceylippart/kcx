(ns kcx.orchestrator
  "Multi-agent workflow orchestrator for KC-X"
  (:require
    [clojure.string :as str]
    [kcx.agents :as agents]
    [kcx.dsl :as dsl]))


;; --- STATE ---
(defonce orchestrator-state
  (atom {:active-tasks {}
         :agent-contexts {}}))


(defn get-active-tasks-summary
  []
  (let [tasks (vals (:active-tasks @orchestrator-state))]
    (if (empty? tasks)
      "No active tasks"
      (->> tasks
           (map #(str "- " (:id %) " (" (:status %) "): " (get-in % [:original-command :verb])))
           (str/join "\n")))))


(defn add-task
  [task]
  (swap! orchestrator-state assoc-in [:active-tasks (:id task)] task))


(defn update-task
  [task-id update-fn]
  (swap! orchestrator-state update-in [:active-tasks task-id] update-fn))


;; --- HANDLERS ---

(defn handle-proj-command
  [cmd]
  (case (:target cmd)
    "global_context"
    (do (require 'kcx.state) ((resolve 'kcx.state/list-projects)))

    "global"
    (do (require 'kcx.state)
        (let [result ((resolve 'kcx.state/set-current-project) "global")]
          (if (= result :ok) "✅ Switched to global" (str "❌ Error: " (:error result)))))

    ;; Target specified
    (let [should-init? (some #{"init"} (:includes cmd))]
      (require 'kcx.state)
      ((resolve 'kcx.state/switch-project) (:target cmd)))))


(defn handle-controller-command
  [cmd project-state]
  (let [verb (:verb cmd)]
    (cond
      (= "proj" verb)
      (handle-proj-command cmd)

      (= "status" verb)
      (str "📊 STATUS\nTask: " (get-in (read-string project-state) [:active-context :task] "None")
           "\n\nTasks:\n" (get-active-tasks-summary))

      (= "plan" verb)
      "📋 PLANNING\nRouting to Architect..."

      :else
      (str "Controller executing: " (:verb cmd)))))


(defn handle-curator-command
  [cmd project-state]
  (case (:verb cmd)
    "save"  "💾 SAVING state..."
    "clean" "🧹 CLEANING memory..."
    (str "Curator executing: " (:verb cmd))))


(defn handle-review-command
  [cmd project-state]
  (str "🔍 REVIEWING: " (:verb cmd)))


;; --- EXECUTION ---

(defn execute-single-agent
  [cmd agent-type project-state]
  (case agent-type
    :controller (handle-controller-command cmd project-state)
    :curator    (handle-curator-command cmd project-state)
    :reviewer   (handle-review-command cmd project-state)
    :worker     (str "WORKFLOW REQUIRED: '" (:verb cmd) "' needs full Worker->Reviewer loop.")
    :architect  (str "WORKFLOW REQUIRED: '" (:verb cmd) "' needs Architect->Worker loop.")
    :tester     (str "WORKFLOW REQUIRED: '" (:verb cmd) "' needs TDD loop.")))


(defn create-controller-plan
  [cmd project-state]
  (let [prompt (agents/get-system-prompt :controller)]
    (str prompt "\n\n"
         "STATE: " project-state "\n"
         "COMMAND: " (dsl/format-command-summary cmd) "\n\n"
         "TASK: Create a detailed execution plan.")))


(defn create-implementation-request
  [cmd controller-plan project-state]
  (let [prompt (agents/get-system-prompt :worker)] ; FIXED: Was :coder-builder
    (str prompt "\n\n"
         "PLAN: " controller-plan "\n"
         "STATE: " project-state "\n\n"
         "TASK: Implement the plan. Use 'write_file' tools.")))


(defn execute-workflow
  [task-id cmd project-state]
  ;; 1. Controller Plans
  (let [controller-plan (create-controller-plan cmd project-state)
        ;; 2. Worker executes
        implementation-request (create-implementation-request cmd controller-plan project-state)]

    (update-task task-id #(agents/update-task-status % :needs-review))

    (str "🚀 MULTI-AGENT WORKFLOW STARTED\n"
         "TASK: " task-id "\n"
         "AGENT: Worker\n\n"
         "=== PLAN ===\n" controller-plan "\n\n"
         "=== INSTRUCTION ===\n" implementation-request)))


;; --- ENTRY POINT ---

(defn execute-command
  [cmd project-state]
  (let [primary-agent (agents/route-command cmd)
        requires-workflow? (agents/requires-workflow? cmd)]

    (if-not requires-workflow?
      (execute-single-agent cmd primary-agent project-state)
      (let [task (agents/create-agent-task cmd primary-agent)]
        (add-task task)
        (execute-workflow (:id task) cmd project-state)))))
