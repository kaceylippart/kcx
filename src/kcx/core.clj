(ns kcx.core
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [kcx.dsl :as dsl]
    [kcx.orchestrator :as orchestrator]
    [kcx.state :as state]
    [kcx.utils :refer [write-file]]))


(defn handle-request
  [req]
  (let [method (get req "method")
        params (get req "params")
        args (get params "arguments")]
    (case method
      "initialize" {:capabilities {:tools {}} :serverInfo {:name "kcx" :version "1.0"}}
      "tools/list" {:tools [{:name "kcx_command" :inputSchema {:type "object" :properties {:command {:type "string"}} :required ["command"]}}
                            {:name "read_state" :inputSchema {:type "object" :properties {}}}
                            {:name "write_file" :inputSchema {:type "object" :properties {:path {:type "string"} :content {:type "string"}} :required ["path" "content"]}}]}
      "tools/call"
      {:content [{:type "text"
                  :text (case (get params "name")
                          "kcx_command" (orchestrator/execute-command (dsl/parse-command (get args "command")))
                          "read_state" (with-out-str (clojure.pprint/pprint (state/load-state)))
                          "write_file" (do (io/make-parents (get args "path"))
                                           (write-file (get args "path") (get args "content"))
                                           (str "Wrote to " (get args "path")))
                          "Unknown")}]}
      nil)))


(defn start-server
  []
  (binding [*out* (java.io.OutputStreamWriter. System/out)]
    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (when-let [req (try (json/parse-string line) (catch Exception _ nil))]
        (when-let [res (handle-request req)]
          (println (json/generate-string {:jsonrpc "2.0" :id (get req "id") :result res})))))))
