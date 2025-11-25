(ns kcx.utils
  "Shared utilities for KC-X"
  (:require [clojure.string :as str]))

(defn format-timestamp
  "Format a timestamp for display"
  [instant-str]
  (try
    (-> (java.time.Instant/parse instant-str)
        (.atZone (java.time.ZoneId/systemDefault))
        (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))
    (catch Exception _
      instant-str)))

(defn truncate-string
  "Truncate a string to max length with ellipsis"
  [s max-length]
  (if (> (count s) max-length)
    (str (subs s 0 (- max-length 3)) "...")
    s))

(defn kebab-case->snake-case
  "Convert kebab-case to snake_case"
  [s]
  (str/replace s #"-" "_"))

(defn snake-case->kebab-case
  "Convert snake_case to kebab-case"
  [s]
  (str/replace s #"_" "-"))

(defn validate-file-path
  "Validate a file path is safe and reasonable"
  [path]
  (and (string? path)
       (not (str/blank? path))
       (not (str/starts-with? path "/"))  ; No absolute paths
       (not (str/includes? path ".."))    ; No parent directory traversal
       (<= (count path) 255)))            ; Reasonable length

(defn safe-parse-int
  "Safely parse an integer with default value"
  [s default-val]
  (try
    (Integer/parseInt (str s))
    (catch Exception _
      default-val)))

(defn format-duration
  "Format a duration between two instants"
  [start-str end-str]
  (try
    (let [start (java.time.Instant/parse start-str)
          end (java.time.Instant/parse end-str)
          duration (java.time.Duration/between start end)
          seconds (.getSeconds duration)]
      (cond
        (< seconds 60) (str seconds "s")
        (< seconds 3600) (str (quot seconds 60) "m " (rem seconds 60) "s")
        :else (str (quot seconds 3600) "h " (quot (rem seconds 3600) 60) "m")))
    (catch Exception _
      "unknown")))

(defn deep-merge
  "Recursively merge maps"
  [& maps]
  (apply merge-with (fn [& args]
                      (if (every? map? args)
                        (apply deep-merge args)
                        (last args)))
         maps))

(defn filter-map
  "Filter a map by predicate on key-value pairs"
  [pred m]
  (into {} (filter pred m)))

(defn map-values
  "Transform all values in a map with function f"
  [f m]
  (into {} (map (fn [[k v]] [k (f v)]) m)))

(defn ensure-vector
  "Ensure value is a vector"
  [v]
  (cond
    (vector? v) v
    (sequential? v) (vec v)
    (nil? v) []
    :else [v]))

(defn remove-nil-values
  "Remove keys with nil values from map"
  [m]
  (into {} (filter (fn [[_ v]] (not (nil? v))) m)))

(defn format-error
  "Format an error message consistently"
  [error-type message & {:keys [details]}]
  (str "❌ " (str/upper-case (name error-type)) ": " message
       (when details (str "\nDetails: " details))))

(defn format-success
  "Format a success message consistently"
  [message & {:keys [details]}]
  (str "✅ " message
       (when details (str "\nDetails: " details))))

(defn format-warning
  "Format a warning message consistently"
  [message & {:keys [details]}]
  (str "⚠️ " message
       (when details (str "\nDetails: " details))))

(defn format-info
  "Format an info message consistently"
  [message & {:keys [details]}]
  (str "ℹ️ " message
       (when details (str "\nDetails: " details))))