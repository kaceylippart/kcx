(ns kcx.state
  "State management for KC-X using EDN format"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def default-state-file "kcx_state.edn")

;; Default EDN template (converted from KDL format)
(def state-template
  {:meta {:version "1.0"
          :author "KC-X"
          :created (str (java.time.Instant/now))}

   :stack {:language "Clojure"
           :framework "Babashka"}

   :active-context {:task "New Task"
                    :status "Ready to begin"}

   :memory []})

(defn create-template
  "Create a new EDN state template"
  []
  state-template)

(defn get-current-state-file
  "Get the current project state file path"
  []
  ;; Check if there's a current project set
  (if-let [current-project (try
                             (some-> ".kcx_current_project"
                                     slurp
                                     str/trim)
                             (catch Exception _ nil))]
    (if (and (not (str/blank? current-project))
             (not= current-project "global"))
      (let [project-file (str "kcx_state_" current-project ".edn")]
        (if (.exists (io/file project-file))
          project-file
          default-state-file))
      default-state-file)
    default-state-file))

(defn validate-edn
  "Validate EDN state structure"
  [edn-data]
  (try
    (and (map? edn-data)
         (contains? edn-data :meta)
         (contains? edn-data :active-context)
         (vector? (:memory edn-data)))
    (catch Exception _
      false)))

(defn load-state
  "Load state from EDN file with error recovery"
  ([]
   (load-state (get-current-state-file)))
  ([state-file]
   (if (.exists (io/file state-file))
     (try
       (let [content (slurp state-file)
             data (edn/read-string content)]
         (if (validate-edn data)
           data
           ;; Invalid structure, use template
           (create-template)))
       (catch Exception e
         ;; Parse error, use template with recovery note
         (assoc-in (create-template)
                   [:meta :note]
                   (str "Recovered from parse error: " (.getMessage e)))))
     ;; File doesn't exist, create new
     (assoc-in (create-template)
               [:meta :created]
               (str (java.time.Instant/now))))))

(defn save-state
  "Save state to EDN file with validation"
  ([state-data]
   (save-state state-data (get-current-state-file)))
  ([state-data state-file]
   (try
     ;; Validate before saving
     (if (validate-edn state-data)
       (do
         (io/make-parents state-file)
         (->> state-data
              (pprint/pprint)
              (with-out-str)
              (spit state-file))
         "State updated successfully.")
       "Error: Invalid EDN structure. State NOT saved.")
     (catch Exception e
       (str "Error: Failed to save state. " (.getMessage e))))))

(defn save-state-string
  "Save state from EDN string with validation"
  [edn-string]
  (try
    (let [data (edn/read-string edn-string)]
      (save-state data))
    (catch Exception e
      (str "Error: Invalid EDN format. State NOT saved.\n" (.getMessage e)))))

(defn add-memory-decision
  "Add a decision to the memory log"
  [state decision]
  (let [memory-entry {:decision decision
                      :date (str (java.time.LocalDate/now))}]
    (update state :memory (fnil conj []) memory-entry)))

(defn update-active-context
  "Update the active context (task and status)"
  [state task status]
  (assoc state :active-context {:task task :status status}))

(defn update-stack-info
  "Update stack information (language, framework, etc.)"
  [state & {:keys [language framework] :as updates}]
  (update state :stack merge updates))

;; Project management functions
(defn set-current-project
  "Set the current project"
  [project-name]
  (try
    (if (= project-name "global")
      ;; Remove current project file to default to global
      (do
        (.delete (io/file ".kcx_current_project"))
        :ok)
      ;; Set specific project
      (do
        (spit ".kcx_current_project" project-name)
        :ok))
    (catch Exception e
      {:error (.getMessage e)})))

(defn list-projects
  "List all available projects"
  []
  (let [current-project (try
                         (some-> ".kcx_current_project"
                                 slurp
                                 str/trim)
                         (catch Exception _ "global"))
        current-project (if (str/blank? current-project) "global" current-project)]

    (try
      (let [files (file-seq (io/file "."))
            projects (atom [])]

        ;; Add global project if it exists
        (when (.exists (io/file "kcx_state.edn"))
          (let [marker (if (= current-project "global") " 👈 current" "")]
            (swap! projects conj (str "global (kcx_state.edn)" marker))))

        ;; Find project-specific state files
        (doseq [file files]
          (let [name (.getName file)]
            (when (and (str/starts-with? name "kcx_state_")
                       (str/ends-with? name ".edn"))
              (let [project-name (-> name
                                     (str/replace-first "kcx_state_" "")
                                     (str/replace-first ".edn" ""))
                    marker (if (= project-name current-project) " 👈 current" "")]
                (swap! projects conj (str project-name " (" name ")" marker))))))

        (if (empty? @projects)
          "📋 No kcx projects found. Use 'proj:project_name' to create one."
          (str "📋 Available kcx projects:\n" (str/join "\n" @projects))))

      (catch Exception e
        (str "❌ Failed to list projects: " (.getMessage e))))))

(defn create-or-switch-project
  "Create a new project or switch to existing one"
  [project-name force-init?]
  ;; Validate project name
  (if-not (and (not (str/blank? project-name))
               (<= (count project-name) 50)
               (re-matches #"[a-zA-Z0-9_-]+" project-name)
               (not (str/starts-with? project-name "-"))
               (not (str/ends-with? project-name "-")))
    "❌ Invalid project name. Use alphanumeric characters, underscores, and hyphens only."

    (let [filename (str "kcx_state_" project-name ".edn")
          exists? (.exists (io/file filename))]

      (if (and exists? (not force-init?))
        ;; Switch to existing project
        (do
          (set-current-project project-name)
          (str "✅ Switched to existing project '" project-name "' at " filename
               ". Use proj:" project-name ":init to reinitialize."))

        ;; Create new project state file
        (let [template (create-template)
              project-template (-> template
                                   (assoc-in [:active-context :task] (str "Project: " project-name))
                                   (assoc-in [:active-context :status] "Project initialized and ready to begin"))]
          (try
            (save-state project-template filename)
            (set-current-project project-name)
            (if exists?
              (str "🔄 Reinitialized and switched to project '" project-name "' at " filename)
              (str "✨ Created and switched to project '" project-name "' at " filename))
            (catch Exception e
              (str "❌ Failed to create project file: " (.getMessage e)))))))))

;; KDL to EDN migration helper (for converting existing KDL files)
(defn migrate-kdl-to-edn
  "Convert KDL format to EDN format (basic conversion)"
  [kdl-content]
  (try
    ;; Basic KDL to EDN conversion - this is a simplified parser
    ;; For complex KDL files, you might want to use a proper KDL parser
    (let [lines (str/split-lines kdl-content)
          state (atom (create-template))]

      ;; Simple pattern matching for common KDL structures
      (doseq [line lines]
        (let [line (str/trim line)]
          (cond
            ;; meta { version "1.0" author "KC-X" }
            (re-find #"meta\s*\{" line)
            (let [version (second (re-find #"version\s+\"([^\"]+)\"" kdl-content))
                  author (second (re-find #"author\s+\"([^\"]+)\"" kdl-content))]
              (swap! state assoc-in [:meta :version] (or version "1.0"))
              (swap! state assoc-in [:meta :author] (or author "KC-X")))

            ;; stack { language "Rust" framework "Axum" }
            (re-find #"stack\s*\{" line)
            (let [language (second (re-find #"language\s+\"([^\"]+)\"" kdl-content))
                  framework (second (re-find #"framework\s+\"([^\"]+)\"" kdl-content))]
              (when language (swap! state assoc-in [:stack :language] language))
              (when framework (swap! state assoc-in [:stack :framework] framework)))

            ;; active_context { task "..." status "..." }
            (re-find #"active_context\s*\{" line)
            (let [task (second (re-find #"task\s+\"([^\"]+)\"" kdl-content))
                  status (second (re-find #"status\s+\"([^\"]+)\"" kdl-content))]
              (when task (swap! state assoc-in [:active-context :task] task))
              (when status (swap! state assoc-in [:active-context :status] status)))

            ;; decision "..." date="..."
            (re-find #"decision\s+\"" line)
            (let [decision (second (re-find #"decision\s+\"([^\"]+)\"" line))
                  date (second (re-find #"date=\"([^\"]+)\"" line))]
              (when (and decision date)
                (swap! state update :memory conj {:decision decision :date date}))))))

      @state)
    (catch Exception e
      ;; If conversion fails, return template with error note
      (assoc-in (create-template)
                [:meta :note]
                (str "KDL conversion failed: " (.getMessage e))))))