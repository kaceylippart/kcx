(ns kcx.core
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [kcx.dsl :as dsl]
    [kcx.logging :as log]
    [kcx.orchestrator :as orchestrator]
    [kcx.state :as state]
    [kcx.utils :refer [write-file]]))


(defn handle-tool-call
  [tool-name args]
  (log/log-tool-call! tool-name args)
  (let [result (case tool-name
                 "kcx_command"
                 (let [cmd (get args "command")
                       parsed (dsl/parse-command cmd)]
                   (log/log! :debug "DSL PARSE" {:input cmd :parsed parsed})
                   (orchestrator/execute-command parsed))

                 "read_state"
                 (let [current-state (state/load-state)]
                   (log/log! :debug "READ STATE" current-state)
                   (with-out-str (pprint/pprint current-state)))

                 "write_file"
                 (let [path (get args "path")
                       content (get args "content")]
                   (io/make-parents path)
                   (write-file path content)
                   (log/log! :debug "WRITE FILE" {:path path :size (count content)})
                   (str "Wrote to " path))

                 "Unknown tool")]
    (log/log-tool-result! tool-name result)
    result))

(defn handle-request
  [req]
  (log/log-request! req)
  (let [method (get req "method")
        params (get req "params")
        args (get params "arguments")
        result (case method
                 "initialize"
                 {:protocolVersion "2024-11-05"
                  :capabilities {:tools {}}
                  :serverInfo {:name "kcx" :version "1.0"}}

                 "tools/list"
                 {:tools [{:name "kcx_command"
                           :description "Execute a KCX DSL command for agent-driven workflows"
                           :inputSchema {:type "object"
                                         :properties {:command {:type "string"
                                                                :description "KCX DSL command (e.g., 'kcx:gen file:main.clj with:async')"}}
                                         :required ["command"]}}
                          {:name "read_state"
                           :description "Read the current KCX project state (memory bank)"
                           :inputSchema {:type "object" :properties {}}}
                          {:name "write_file"
                           :description "Write content to a file"
                           :inputSchema {:type "object"
                                         :properties {:path {:type "string" :description "File path to write"}
                                                      :content {:type "string" :description "Content to write"}}
                                         :required ["path" "content"]}}]}

                 "tools/call"
                 {:content [{:type "text"
                             :text (handle-tool-call (get params "name") args)}]}

                 nil)]
    (when result
      (log/log-response! result))
    result))


(defn start-server
  []
  (log/start-session!)
  (log/log! :info "SERVER" "KCX MCP Server starting...")
  (binding [*out* (java.io.OutputStreamWriter. System/out)]
    (try
      (doseq [line (line-seq (java.io.BufferedReader. *in*))]
        (when-let [req (try (json/parse-string line) (catch Exception e (log/log-error! "JSON parse error" e) nil))]
          (try
            (when-let [res (handle-request req)]
              (let [response {:jsonrpc "2.0" :id (get req "id") :result res}]
                (println (json/generate-string response))))
            (catch Exception e
              (log/log-error! "Request handling error" e)
              (println (json/generate-string {:jsonrpc "2.0" :id (get req "id") :error {:code -32603 :message (str e)}}))))))
      (finally
        (log/end-session!)))))
