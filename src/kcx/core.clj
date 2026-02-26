(ns kcx.core
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [kcx.dsl :as dsl]
    [kcx.logging :as log]
    [kcx.orchestrator :as orchestrator]
    [kcx.worker :as worker]))


;; ============================================================================
;; MCP Progress Notifications
;; ============================================================================

(def ^:dynamic *stdout-lock* (Object.))

(defn- send-progress!
  "Send an MCP progress notification over stdout.
   Thread-safe — synchronizes on stdout lock."
  [progress-token message]
  (when progress-token
    (let [notification {:jsonrpc "2.0"
                        :method "notifications/progress"
                        :params {:progressToken progress-token
                                 :progress 0
                                 :message message}}]
      (locking *stdout-lock*
        (println (json/generate-string notification))
        (flush)))))


(defn handle-tool-call
  [tool-name args]
  (log/log-tool-call! tool-name args)
  (let [result (case tool-name
                 "kcx_command"
                 (let [cmd (get args "command")]
                   (log/log! :debug "COMMAND" {:input cmd})
                   (let [parsed (dsl/parse-command cmd)]
                     (log/log! :debug "DSL PARSE" {:parsed parsed})
                     (orchestrator/execute-command parsed)))

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
                 (let [kcx-root (or (System/getenv "KCX_HOME")
                                    (str (System/getProperty "user.home") "/kcx"))
                       version (try (str/trim (slurp (str kcx-root "/VERSION")))
                                    (catch Exception _ "dev"))]
                   {:protocolVersion "2024-11-05"
                    :capabilities {:tools {}}
                    :serverInfo {:name "kcx" :version version}})

                 ;; Notifications don't get responses
                 "notifications/initialized" nil
                 "notifications/cancelled" nil

                 "tools/list"
                 {:tools [{:name "kcx_command"
                           :description "Execute a KCX command for agent-driven workflows"
                           :inputSchema {:type "object"
                                         :properties {:command {:type "string"
                                                                :description "KCX command (e.g., '!fix @file.clj +error-handling')"}}
                                         :required ["command"]}}
]}

                 "tools/call"
                 (let [progress-token (get-in params ["_meta" "progressToken"])]
                   (binding [worker/*progress-callback*
                             (when progress-token
                               (fn [msg] (send-progress! progress-token msg)))]
                     {:content [{:type "text"
                                 :text (handle-tool-call (get params "name") args)}]}))

                 ;; Unknown method - log but don't respond
                 (do (log/log! :warn "UNKNOWN METHOD" method) nil))]
    (when result
      (log/log-response! result))
    result))


(defn start-server
  []
  (log/start-session!)
  (log/log! :info "SERVER" "KCX MCP Server starting...")
  (try
    (doseq [line (line-seq (java.io.BufferedReader. *in*))]
      (when-let [req (try (json/parse-string line) (catch Exception e (log/log-error! "JSON parse error" e) nil))]
        (try
          (when-let [res (handle-request req)]
            (let [response {:jsonrpc "2.0" :id (get req "id") :result res}]
              (locking *stdout-lock*
                (println (json/generate-string response))
                (flush))))
          (catch Exception e
            (log/log-error! "Request handling error" e)
            (locking *stdout-lock*
              (println (json/generate-string {:jsonrpc "2.0" :id (get req "id") :error {:code -32603 :message (str e)}}))
              (flush))))))
    (finally
      (log/end-session!))))
