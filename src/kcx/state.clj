(ns kcx.state
  "State management for KC-X using EDN format.

   The memory bank is a structured briefing document (v2) that gives each
   sub-Claude comprehensive project context. The curator maintains it
   intelligently — no mechanical TTL/priority system needed."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [kcx.utils :refer [read-file write-file]]))


;; =============================================================================
;; Directory Structure
;; =============================================================================
;; ~/.kcx/
;;   current           <- contains current project name
;;   projects/
;;     <project>/
;;       state.edn     <- project memory bank (briefing document)

(def kcx-home (str (System/getProperty "user.home") "/.kcx"))
(def projects-dir (str kcx-home "/projects"))
(def current-file (str kcx-home "/current"))


(defn ensure-kcx-dirs!
  "Ensure KCX directories exist"
  []
  (io/make-parents (str projects-dir "/.keep")))


(defn get-default-project-name
  "Derive project name from current working directory"
  []
  (-> (System/getProperty "user.dir")
      (io/file)
      (.getName)
      (str/replace #"[^a-zA-Z0-9-_]" "_")))


(defn get-current-project
  "Get the current active project name"
  []
  (if (.exists (io/file current-file))
    (try
      (str/trim (read-file current-file))
      (catch Exception _ (get-default-project-name)))
    (get-default-project-name)))


(defn set-current-project!
  "Set the current active project"
  [project-name]
  (ensure-kcx-dirs!)
  (write-file current-file project-name)
  project-name)


(defn project-dir
  "Get the directory for a project"
  [project-name]
  (str projects-dir "/" project-name))


(defn project-state-file
  "Get the state file path for a project"
  [project-name]
  (str (project-dir project-name) "/state.edn"))


(defn get-current-state-file
  "Get the state file for the current project"
  []
  (project-state-file (get-current-project)))


;; =============================================================================
;; State Templates & Validation
;; =============================================================================

(def briefing-sections
  "The five sections of a project briefing."
  [:project-map :conventions :architecture :active-context :known-issues])

(defn create-template
  "Create a new project state template (v2 briefing format)"
  [project-name]
  {:meta {:version "2.0"
          :project project-name
          :command-count 0
          :updated (str (java.time.LocalDate/now))}
   :briefing
   {:project-map    "(Not yet populated — will be built by curator on first task.)"
    :conventions    "(Not yet populated — will be built by curator on first task.)"
    :architecture   "(Not yet populated — will be built by curator on first task.)"
    :active-context "(No activity yet.)"
    :known-issues   "(None known yet.)"}})


(defn validate-state
  "Validate state structure (supports both v1 and v2)"
  [data]
  (and (map? data)
       (contains? data :meta)
       (or
         ;; v2: briefing-based
         (and (contains? data :briefing)
              (map? (:briefing data)))
         ;; v1: legacy entry-based (will be migrated on load)
         (and (contains? data :memory)
              (contains? data :command-count)))))


;; =============================================================================
;; v1 → v2 Migration
;; =============================================================================

(defn- migrate-v1->v2
  "Migrate v1 state (flat action-log entries) to v2 (briefing sections)."
  [state]
  (let [entries (:memory state [])
        active-lines (mapv #(str "- " (:action %) " " (:target %)
                                 (when (:description %) (str ": " (:description %))))
                           entries)]
    {:meta {:version "2.0"
            :project (get-in state [:meta :project])
            :command-count (:command-count state 0)
            :updated (str (java.time.LocalDate/now))}
     :briefing
     {:project-map    "(Not yet populated — will be built by curator on next task.)"
      :conventions    "(Not yet populated — will be built by curator on next task.)"
      :architecture   "(Not yet populated — will be built by curator on next task.)"
      :active-context (if (seq active-lines)
                        (str "Recent activity (migrated from v1):\n" (str/join "\n" active-lines))
                        "(No previous activity.)")
      :known-issues   "(None known yet.)"}}))


;; =============================================================================
;; State I/O
;; =============================================================================

(defn load-state
  "Load state for the current project (creates if doesn't exist).
   Auto-migrates v1 states to v2 briefing format."
  []
  (let [project (get-current-project)
        f (project-state-file project)]
    (if (.exists (io/file f))
      (try
        (let [data (edn/read-string (read-file f))]
          (if (validate-state data)
            ;; Auto-migrate v1 → v2
            (if (and (contains? data :memory) (not (contains? data :briefing)))
              (migrate-v1->v2 data)
              data)
            (create-template project)))
        (catch Exception _ (create-template project)))
      (create-template project))))


(defn save-state!
  "Save state for the current project"
  [data]
  (let [project (get-current-project)
        f (project-state-file project)]
    (ensure-kcx-dirs!)
    (io/make-parents f)
    (write-file f (with-out-str (pprint/pprint data)))
    f))


;; =============================================================================
;; Project Management
;; =============================================================================

(defn list-projects
  "List all projects"
  []
  (ensure-kcx-dirs!)
  (let [current (get-current-project)
        dir (io/file projects-dir)
        projects (when (.exists dir)
                   (->> (.listFiles dir)
                        (filter #(.isDirectory %))
                        (map #(.getName %))
                        sort))]
    (str "Current: " current "\n"
         "Projects: " (if (seq projects)
                        (str/join ", " projects)
                        "(none)"))))


(defn switch-project
  "Switch to a project (creates if doesn't exist)"
  [project-name]
  (let [safe-name (str/replace project-name #"[^a-zA-Z0-9-_]" "_")
        f (project-state-file safe-name)
        exists? (.exists (io/file f))]

    ;; Create if doesn't exist
    (when-not exists?
      (ensure-kcx-dirs!)
      (io/make-parents f)
      (write-file f (with-out-str (pprint/pprint (create-template safe-name)))))

    ;; Set as current
    (set-current-project! safe-name)

    (str "-> Project: " safe-name "\n"
         "  Location: " f "\n"
         "  Status: " (if exists? "Loaded" "Created"))))


;; =============================================================================
;; Memory Context (for agent prompts)
;; =============================================================================

(defn build-memory-context
  "Build comprehensive briefing for agent prompts.
   Returns a formatted string to inject into agent prompts, or nil."
  [cmd]
  (let [state (load-state)
        briefing (:briefing state)]
    (when (and briefing (some #(let [v (get briefing %)]
                                 (and v (not (str/starts-with? v "(Not yet"))))
                               briefing-sections))
      (let [sections (for [k briefing-sections
                           :let [v (get briefing k)]
                           :when (and v (not (str/starts-with? v "(Not yet"))
                                        (not (str/starts-with? v "(No "))
                                        (not (str/starts-with? v "(None ")))]
                       v)]
        (when (seq sections)
          (str "=== PROJECT BRIEFING ===\n\n"
               (str/join "\n\n" sections)
               "\n\n========================\n"))))))

(defn format-memory-bank
  "Format the full memory bank for display, including metadata and all sections."
  []
  (let [state (load-state)
        meta (:meta state)
        briefing (:briefing state)]
    (if briefing
      (str "═══ MEMORY BANK ═══\n"
           "Project: " (:project meta "unknown") "\n"
           "Version: " (:version meta "?") "\n"
           "Commands: " (:command-count meta 0) "\n"
           "Updated: " (:updated meta "never") "\n"
           "\n"
           (str/join "\n\n"
                     (for [k briefing-sections
                           :let [v (get briefing k)]]
                       (str "── " (name k) " ──\n" (or v "(empty)"))))
           "\n\n═══════════════════\n"
           "Present the above memory bank to the user. Do NOT take further action.")
      "Memory bank is empty. Run a workflow to populate it.")))
