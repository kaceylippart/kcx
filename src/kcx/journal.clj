(ns kcx.journal
  "Global prompt journal for learning user patterns.

   Captures every workflow command and tracks a counter for
   auto-triggering the suggestor agent every N prompts."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [kcx.utils :refer [read-file write-file]]))


;; ============================================================================
;; Paths — global (not per-project)
;; ============================================================================

(def kcx-home (str (System/getProperty "user.home") "/.kcx"))
(def journal-file (str kcx-home "/journal.edn"))
(def counter-file (str kcx-home "/suggest-counter"))

(def ^:private max-entries 200)


;; ============================================================================
;; Journal I/O
;; ============================================================================

(defn- create-template
  []
  {:meta {:version "1.0" :total-entries 0}
   :entries []})

(defn load-journal
  "Load the global prompt journal. Returns template if file doesn't exist."
  []
  (if (.exists (io/file journal-file))
    (try
      (let [data (edn/read-string (read-file journal-file))]
        (if (and (map? data) (:entries data))
          data
          (create-template)))
      (catch Exception _ (create-template)))
    (create-template)))

(defn save-journal!
  "Persist the journal to disk."
  [journal]
  (io/make-parents journal-file)
  (write-file journal-file (with-out-str (pprint/pprint journal)))
  journal-file)

(defn add-entry!
  "Append a journal entry. Caps at max-entries (oldest trimmed)."
  [entry]
  (let [journal (load-journal)
        timestamped (assoc entry :timestamp (str (java.time.Instant/now)))
        entries (conj (:entries journal) timestamped)
        trimmed (if (> (count entries) max-entries)
                  (vec (drop (- (count entries) max-entries) entries))
                  (vec entries))]
    (save-journal! (-> journal
                       (assoc :entries trimmed)
                       (assoc-in [:meta :total-entries] (count trimmed))))))

(defn get-recent-entries
  "Return the last n journal entries."
  [n]
  (let [journal (load-journal)
        entries (:entries journal)]
    (vec (take-last n entries))))


;; ============================================================================
;; Suggestion Counter
;; ============================================================================

(defn load-counter
  "Load the suggestion counter. Returns 0 if file doesn't exist."
  []
  (if (.exists (io/file counter-file))
    (try
      (Integer/parseInt (clojure.string/trim (read-file counter-file)))
      (catch Exception _ 0))
    0))

(defn save-counter!
  "Persist counter value to disk."
  [n]
  (io/make-parents counter-file)
  (write-file counter-file (str n)))

(defn increment-counter!
  "Increment and persist counter. Returns new value."
  []
  (let [n (inc (load-counter))]
    (save-counter! n)
    n))

(defn reset-counter!
  "Reset counter to 0."
  []
  (save-counter! 0))
