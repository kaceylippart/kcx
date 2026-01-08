(ns kcx.state
  "State management for KC-X using EDN format"
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
;;       state.edn     <- project memory bank

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

(defn create-template
  "Create a new project state template"
  [project-name]
  {:meta {:version "1.0"
          :author "KC-X"
          :project project-name
          :created (str (java.time.Instant/now))}
   :command-count 0
   :memory []})


(defn validate-state
  "Validate state structure"
  [data]
  (and (map? data)
       (contains? data :meta)
       (contains? data :memory)
       (contains? data :command-count)))


;; =============================================================================
;; State I/O
;; =============================================================================

(defn load-state
  "Load state for the current project (creates if doesn't exist)"
  []
  (let [project (get-current-project)
        f (project-state-file project)]
    (if (.exists (io/file f))
      (try
        (let [data (edn/read-string (read-file f))]
          (if (validate-state data)
            data
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

    (str "→ Project: " safe-name "\n"
         "  Location: " f "\n"
         "  Status: " (if exists? "Loaded" "Created"))))


;; =============================================================================
;; Memory Priority System
;; =============================================================================

(def priority-levels
  {:critical 100  ; Never auto-delete (arch decisions, key patterns)
   :high     75   ; Long retention (recent fixes, active features)
   :normal   50   ; Standard retention (completed tasks)
   :low      25}) ; Can be pruned (minor changes, routine updates)


(def default-ttl
  "Default TTL in commands for each priority level"
  {:critical nil   ; Never expires
   :high     100   ; Expires after 100 commands
   :normal   30    ; Expires after 30 commands
   :low      10})  ; Expires after 10 commands


(defn make-memory-entry
  "Create a memory entry with priority and TTL"
  [{:keys [action target description priority]
    :or {priority :normal}}
   command-count]
  (let [ttl (get default-ttl priority)]
    (cond-> {:action action
             :target target
             :priority priority
             :created-at command-count
             :date (str (java.time.LocalDate/now))}
      description (assoc :description description)
      ttl (assoc :expires-at (+ command-count ttl)))))


(defn entry-expired?
  "Check if a memory entry has expired"
  [entry current-command-count]
  (when-let [expires-at (:expires-at entry)]
    (> current-command-count expires-at)))


(defn entry-priority-value
  "Get numeric priority value"
  [entry]
  (get priority-levels (:priority entry :normal) 50))


(defn prune-memory
  "Remove expired entries, keep under max limit"
  [memory command-count & {:keys [max-entries] :or {max-entries 50}}]
  (let [;; Remove expired (but never critical)
        alive (remove #(and (entry-expired? % command-count)
                            (not= :critical (:priority %)))
                      memory)]
    ;; If over limit, drop lowest priority
    (if (> (count alive) max-entries)
      (->> alive
           (sort-by entry-priority-value >)
           (take max-entries)
           vec)
      (vec alive))))


;; =============================================================================
;; State Operations (for use by orchestrator)
;; =============================================================================

(defn increment-command-count
  "Increment command counter"
  [state]
  (update state :command-count (fnil inc 0)))


(defn add-memory-entry
  "Add a memory entry with auto-pruning"
  [state entry-params]
  (let [cmd-count (:command-count state 0)
        entry (make-memory-entry entry-params cmd-count)
        new-memory (conj (:memory state []) entry)
        pruned (prune-memory new-memory cmd-count)]
    (assoc state :memory pruned)))


(defn promote-entry
  "Increase priority of an entry by target name"
  [state target]
  (let [levels [:low :normal :high :critical]
        promote (fn [e]
                  (if (= (:target e) target)
                    (let [idx (.indexOf levels (:priority e :normal))
                          new-p (get levels (min 3 (inc idx)))]
                      (assoc e :priority new-p :expires-at nil)) ; promoted = no expiry bump
                    e))]
    (update state :memory #(mapv promote %))))


(defn demote-entry
  "Decrease priority of an entry by target name"
  [state target]
  (let [levels [:low :normal :high :critical]
        cmd-count (:command-count state 0)
        demote (fn [e]
                 (if (= (:target e) target)
                   (let [idx (.indexOf levels (:priority e :normal))
                         new-p (get levels (max 0 (dec idx)))
                         new-ttl (get default-ttl new-p)]
                     (cond-> (assoc e :priority new-p)
                       new-ttl (assoc :expires-at (+ cmd-count new-ttl))))
                   e))]
    (update state :memory #(mapv demote %))))


(defn delete-entry
  "Delete a memory entry by target name"
  [state target]
  (update state :memory #(vec (remove (fn [e] (= (:target e) target)) %))))
