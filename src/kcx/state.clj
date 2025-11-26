(ns kcx.state
  "State management for KC-X using EDN format"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [kcx.utils :refer [read-file write-file]])) ; <--- SANITIZED IO

(def default-state-file "kcx_state.edn")
(def kcx-dir (str (System/getProperty "user.home") "/.kcx"))
(def registry-file (str kcx-dir "/registry.edn"))


(defn create-template
  []
  {:meta {:version "1.0" :author "KC-X" :created (str (java.time.Instant/now))}
   :stack {:language "Clojure" :framework "Babashka"}
   :active-context {:task "New Task" :status "Ready"}
   :memory []})


(defn load-registry
  []
  (if (.exists (io/file registry-file))
    (try (edn/read-string (read-file registry-file)) (catch Exception _ {}))
    {}))


(defn save-registry
  [registry]
  (try
    (io/make-parents registry-file)
    (write-file registry-file (with-out-str (pprint/pprint registry)))
    :ok
    (catch Exception e {:error (str e)})))


(defn set-current-project
  [project-name]
  (try
    (if (= project-name "global")
      (do (.delete (io/file ".kcx_current_project")) :ok)
      (do (write-file ".kcx_current_project" project-name) :ok))
    (catch Exception e {:error (.getMessage e)})))


(defn get-current-state-file
  []
  (if-let [current (try (some-> ".kcx_current_project" read-file str/trim) (catch Exception _ nil))]
    (if (and (not (str/blank? current)) (not= current "global"))
      (let [f (str "kcx_state_" current ".edn")]
        (if (.exists (io/file f)) f default-state-file))
      default-state-file)
    default-state-file))


(defn validate-edn
  [data]
  (and (map? data) (contains? data :meta) (contains? data :active-context)))


(defn load-state
  []
  (let [f (get-current-state-file)]
    (if (.exists (io/file f))
      (try
        (let [data (edn/read-string (read-file f))]
          (if (validate-edn data) data (create-template)))
        (catch Exception _ (create-template)))
      (create-template))))


(defn save-state
  [data]
  (let [f (get-current-state-file)]
    (if (validate-edn data)
      (do (io/make-parents f)
          (write-file f (with-out-str (pprint/pprint data)))
          "State updated successfully.")
      "Error: Invalid EDN structure.")))


(defn save-state-string
  [s]
  (try (save-state (edn/read-string s))
       (catch Exception e (str "Error parsing EDN: " (.getMessage e)))))


;; Upsert Pattern
(defn switch-project
  [name]
  (let [reg (load-registry)
        existing-path (get reg name)
        safe-name (str/replace name #"[^a-zA-Z0-9-_]" "_")
        final-path (or existing-path (str kcx-dir "/projects/" safe-name ".edn"))
        exists? (.exists (io/file final-path))]

    (when-not exists?
      (io/make-parents final-path)
      (let [tmpl (assoc-in (create-template) [:meta :project] name)]
        (write-file final-path (with-out-str (pprint/pprint tmpl)))))

    (when-not existing-path (save-registry (assoc reg name final-path)))
    (set-current-project name)

    (str "Active Project: " name "\nLocation: " final-path "\nStatus: " (if exists? "Loaded" "Created"))))


(defn list-projects
  []
  (let [reg (load-registry)
        current (try (str/trim (read-file ".kcx_current_project")) (catch Exception _ "global"))]
    (str "Current: " current "\nAvailable:\n" (with-out-str (pprint/pprint (keys reg))))))
