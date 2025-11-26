(ns kcx.utils
  "Shared utilities for KC-X"
  (:require
    [clojure.string :as str]))


;; --- IO ALIASES ---
(def read-file slurp)
(def write-file spit)


;; --- FORMATTING ---
(defn format-timestamp
  [instant-str]
  (try
    (-> (java.time.Instant/parse instant-str)
        (.atZone (java.time.ZoneId/systemDefault))
        (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")))
    (catch Exception _ instant-str)))


(defn truncate-string
  [s max-length]
  (if (> (count s) max-length)
    (str (subs s 0 (- max-length 3)) "...")
    s))


(defn kebab-case->snake-case
  [s]
  (str/replace s #"-" "_"))


(defn snake-case->kebab-case
  [s]
  (str/replace s #"_" "-"))


(defn validate-file-path
  [path]
  (and (string? path)
       (not (str/blank? path))
       (not (str/starts-with? path "/"))
       (not (str/includes? path ".."))
       (<= (count path) 255)))


(defn deep-merge
  [& maps]
  (apply merge-with (fn [& args]
                      (if (every? map? args)
                        (apply deep-merge args)
                        (last args)))
         maps))
