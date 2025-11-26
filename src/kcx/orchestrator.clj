(ns kcx.orchestrator
  "Workflow logic"
  (:require
    [kcx.agents :as agents]
    [kcx.dsl :as dsl]
    [kcx.state :as state]))


(defonce state (atom {:active-tasks {}}))


(defn add-task
  [t]
  (swap! state assoc-in [:active-tasks (:id t)] t))


(defn update-task
  [id f]
  (swap! state update-in [:active-tasks id] f))


(defn handle-controller
  [cmd]
  (case (:verb cmd)
    "proj" (state/switch-project (:target cmd))
    "list" (state/list-projects)
    (str "Controller executing: " (:verb cmd))))


(defn execute-single
  [cmd type]
  (case type
    :controller (handle-controller cmd)
    :curator (str "Curator: " (:verb cmd))
    (str "Agent " type " executing " (:verb cmd))))


(defn create-plan
  [cmd]
  (str (agents/get-system-prompt :controller) "\n\nTASK: Create plan for " (:verb cmd)))


(defn create-impl
  [cmd plan]
  (str (agents/get-system-prompt :worker) "\n\nPLAN: " plan "\nTASK: Execute."))


(defn execute-workflow
  [id cmd]
  (let [plan (create-plan cmd)
        impl (create-impl cmd plan)]
    (update-task id #(agents/update-task-status % :in-progress))
    (str "🚀 WORKFLOW STARTED\nTASK: " id "\n=== PLAN ===\n" plan "\n=== INSTRUCTION ===\n" impl)))


(defn execute-command
  [cmd]
  (let [agent (agents/route-command cmd)
        workflow? (agents/requires-workflow? cmd)]
    (if-not workflow?
      (execute-single cmd agent)
      (let [task (agents/create-agent-task cmd agent)]
        (add-task task)
        (execute-workflow (:id task) cmd)))))
