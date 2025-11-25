(ns kcx.orchestrator
  "Multi-agent workflow orchestrator for KC-X"
  (:require [kcx.agents :as agents]
            [kcx.dsl :as dsl]
            [clojure.string :as str]))

;; Orchestrator state management
(defonce orchestrator-state
  (atom {:active-tasks {}
         :agent-contexts {}}))

(defn get-active-tasks []
  (:active-tasks @orchestrator-state))

(defn get-agent-contexts []
  (:agent-contexts @orchestrator-state))

(defn add-task [task]
  (swap! orchestrator-state assoc-in [:active-tasks (:id task)] task))

(defn update-task [task-id update-fn]
  (swap! orchestrator-state update-in [:active-tasks task-id] update-fn))

(defn remove-task [task-id]
  (swap! orchestrator-state update :active-tasks dissoc task-id))

(defn get-active-tasks-summary []
  (let [tasks (vals (:active-tasks @orchestrator-state))]
    (if (empty? tasks)
      "No active tasks"
      (->> tasks
           (map #(str "- " (:id %) " (" (:status %) "): " (get-in % [:original-command :verb])))
           (str/join "\n")))))

;; Project management command handler (defined first for forward reference)
(defn handle-proj-command [cmd]
  (case (:target cmd)
    "global_context"
    ;; No target specified - list available projects
    (do
      (require 'kcx.state)
      ((resolve 'kcx.state/list-projects)))

    "global"
    ;; Switch to global project
    (do
      (require 'kcx.state)
      (let [result ((resolve 'kcx.state/set-current-project) "global")]
        (if (= result :ok)
          "✅ Switched to global project (kcx_state.edn)"
          (str "❌ Failed to switch to global project: " (:error result)))))

    ;; Target specified - create/switch to project
    (let [should-init? (some #{"init"} (:includes cmd))]
      (require 'kcx.state)
      ((resolve 'kcx.state/create-or-switch-project) (:target cmd) should-init?))))

;; Single-agent command handlers
(defn handle-controller-command [cmd project-state]
  (case (:verb cmd)
    "proj" (handle-proj-command cmd)
    "status" (str "📊 KC-X STATUS\n\nProject State: Active\nCurrent Task: "
                  (get-in (read-string project-state) [:active-context :task] "None")
                  "\n\nActive Tasks:\n" (get-active-tasks-summary))
    "plan" (str "📋 PLANNING MODE\n\nAnalyzing command: " (dsl/format-command-summary cmd)
                "\n\nThis command will be routed to: " (agents/route-command cmd))
    (str "Controller handling: " (:verb cmd))))

(defn handle-memory-command [cmd project-state]
  (case (:verb cmd)
    "remember" (str "💭 MEMORY: Added to project context")
    "forget" (str "🗑️  MEMORY: Removed from project context")
    "context" (str "🧠 CONTEXT: Current project context displayed")
    "priority" (str "🎯 PRIORITY: Task priority updated")
    (str "MemoryManager handling: " (:verb cmd))))

(defn handle-review-command [cmd project-state]
  (case (:verb cmd)
    "review" (str "🔍 REVIEWER: Code review initiated")
    "check" (str "✅ REVIEWER: Quality check performed")
    "validate" (str "🎯 REVIEWER: Validation completed")
    "approve" (str "👍 REVIEWER: Changes approved")
    "lint" (str "🧹 REVIEWER: Code linting performed")
    (str "Reviewer handling: " (:verb cmd))))

(defn handle-proj-command [cmd]
  (case (:target cmd)
    "global_context"
    ;; No target specified - list available projects
    (require 'kcx.state)
    ((resolve 'kcx.state/list-projects))

    "global"
    ;; Switch to global project
    (do
      (require 'kcx.state)
      (let [result ((resolve 'kcx.state/set-current-project) "global")]
        (if (= result :ok)
          "✅ Switched to global project (kcx_state.edn)"
          (str "❌ Failed to switch to global project: " (:error result)))))

    ;; Target specified - create/switch to project
    (let [should-init? (some #{"init"} (:includes cmd))]
      (require 'kcx.state)
      ((resolve 'kcx.state/create-or-switch-project) (:target cmd) should-init?))))

;; Single-agent execution
(defn execute-single-agent [cmd agent-type project-state]
  (case agent-type
    :controller (handle-controller-command cmd project-state)
    :memory-manager (handle-memory-command cmd project-state)
    :reviewer (handle-review-command cmd project-state)
    :coder-builder
    ;; Even simple coder commands should go through review
    (str "AGENT_WORKFLOW_REQUIRED: Command '" (:verb cmd) "' requires CoderBuilder -> Reviewer workflow")))

;; Multi-agent workflow execution
(defn create-controller-plan [cmd project-state]
  (let [system-prompt (agents/get-system-prompt :controller)]
    (str system-prompt "\n\n"
         "CURRENT PROJECT STATE:\n" project-state "\n\n"
         "DSL COMMAND TO EXECUTE:\n"
         "- Verb: " (:verb cmd) "\n"
         "- Target: " (:target cmd) "\n"
         "- Includes: " (:includes cmd) "\n"
         "- Excludes: " (:excludes cmd) "\n"
         "- Redirect: " (:redirect cmd) "\n"
         "- Agent: " (:agent cmd) "\n\n"
         "Please create a detailed execution plan for this command, considering:\n"
         "1. What files need to be created/modified\n"
         "2. What constraints must be followed (+includes, -excludes)\n"
         "3. What the expected outcome should be\n"
         "4. Any dependencies or prerequisites")))

(defn create-implementation-request [cmd controller-plan project-state]
  (let [system-prompt (agents/get-system-prompt :coder-builder)]
    (str system-prompt "\n\n"
         "CONTROLLER PLAN:\n" controller-plan "\n\n"
         "CURRENT PROJECT STATE:\n" project-state "\n\n"
         "IMPLEMENTATION REQUIREMENTS:\n"
         "- Execute the plan created by the Controller Agent\n"
         "- Follow all constraints: +includes " (:includes cmd) ", -excludes " (:excludes cmd) "\n"
         "- Target: " (:target cmd) "\n"
         (when (:redirect cmd) (str "- Output to: " (:redirect cmd) "\n"))
         "\nImplement the changes according to the Controller's plan.")))

(defn execute-workflow [task-id cmd project-state]
  ;; Phase 1: Controller plans the work
  (let [controller-plan (create-controller-plan cmd project-state)

        ;; Phase 2: CoderBuilder implementation request
        implementation-request (create-implementation-request cmd controller-plan project-state)]

    ;; Update task status
    (update-task task-id #(agents/update-task-status % :needs-review))

    ;; Return structured workflow request
    (str "MULTI_AGENT_WORKFLOW_INITIATED:\n\n"
         "TASK_ID: " task-id "\n"
         "PRIMARY_AGENT: CoderBuilder\n"
         "STATUS: NeedsReview\n\n"
         "=== CONTROLLER PLAN ===\n"
         controller-plan "\n\n"
         "=== IMPLEMENTATION REQUEST ===\n"
         implementation-request "\n\n"
         "=== NEXT STEPS ===\n"
         "1. CoderBuilder will implement the changes\n"
         "2. Reviewer will validate the implementation\n"
         "3. MemoryManager will update project state\n"
         "4. User will be prompted for final approval\n\n"
         "Please execute this multi-agent workflow using your available tools.")))

;; Main execution entry point
(defn execute-command [cmd project-state]
  ;; Step 1: Route command to appropriate agent
  (let [primary-agent (agents/route-command cmd)
        requires-workflow? (agents/requires-workflow? cmd)]

    (if-not requires-workflow?
      ;; Simple command - handle directly by single agent
      (execute-single-agent cmd primary-agent project-state)

      ;; Step 2: Create task and initiate multi-agent workflow
      (let [task (agents/create-agent-task cmd primary-agent)
            task-id (:id task)]

        ;; Add task to orchestrator state
        (add-task task)

        ;; Step 3: Execute multi-agent workflow
        (execute-workflow task-id cmd project-state)))))

;; Task management functions
(defn complete-task [task-id result]
  (update-task task-id #(agents/update-task-status % :completed :result result)))

(defn fail-task [task-id error]
  (update-task task-id #(agents/update-task-status % :failed :result error)))

(defn approve-task [task-id]
  (update-task task-id #(agents/update-task-status % :approved)))

(defn reject-task [task-id reason]
  (update-task task-id #(agents/update-task-status % :rejected :result reason)))

;; Workflow stage functions for external coordination
(defn get-task-by-id [task-id]
  (get-in @orchestrator-state [:active-tasks task-id]))

(defn list-tasks-by-status [status]
  (->> (:active-tasks @orchestrator-state)
       vals
       (filter #(= (:status %) status))))

(defn clear-completed-tasks []
  (swap! orchestrator-state
         update :active-tasks
         #(into {} (remove (fn [[_ task]] (= (:status task) :completed)) %))))

;; Agent context management
(defn set-agent-context [agent-type context]
  (swap! orchestrator-state assoc-in [:agent-contexts agent-type] context))

(defn get-agent-context [agent-type]
  (get-in @orchestrator-state [:agent-contexts agent-type]))

(defn clear-agent-contexts []
  (swap! orchestrator-state assoc :agent-contexts {}))