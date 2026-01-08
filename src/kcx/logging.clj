(ns kcx.logging
  "Verbose logging for kcx MCP server"
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pprint]
    [clojure.string :as str]))


(def kcx-home (str (System/getProperty "user.home") "/kcx"))
(def log-dir (str kcx-home "/logs"))

(defonce current-session-id (atom nil))
(defonce current-log-file (atom nil))


(defn timestamp
  []
  (str (java.time.Instant/now)))


(defn date-str
  []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")))


(defn ensure-log-dir
  []
  (let [dir (io/file log-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))


(defn start-session!
  "Initialize a new logging session"
  []
  (ensure-log-dir)
  (let [session-id (str (java.util.UUID/randomUUID))
        log-file (str log-dir "/session_" (date-str) "_" (subs session-id 0 8) ".log")]
    (reset! current-session-id session-id)
    (reset! current-log-file log-file)
    (spit log-file (str "=== KCX Session Started ===\n"
                        "Session ID: " session-id "\n"
                        "Timestamp: " (timestamp) "\n"
                        "================================\n\n"))
    session-id))


(defn log!
  "Log a message with level and optional data"
  [level message & [data]]
  (when-let [log-file @current-log-file]
    (let [entry (str "[" (timestamp) "] [" (str/upper-case (name level)) "] " message
                     (when data
                       (str "\n  Data: " (pr-str data)))
                     "\n")]
      (spit log-file entry :append true))))


(defn log-request!
  "Log an incoming MCP request"
  [req]
  (log! :info ">>> INCOMING REQUEST"
        {:method (get req "method")
         :id (get req "id")
         :params (get req "params")}))


(defn log-response!
  "Log an outgoing MCP response"
  [res]
  (log! :info "<<< OUTGOING RESPONSE" res))


(defn log-tool-call!
  "Log a tool invocation"
  [tool-name args]
  (log! :info (str "TOOL CALL: " tool-name) args))


(defn log-tool-result!
  "Log tool result"
  [tool-name result]
  (log! :info (str "TOOL RESULT: " tool-name) {:result result}))


(defn log-error!
  "Log an error"
  [message & [exception]]
  (log! :error message
        (when exception
          ;; Just stringify the exception - Babashka restricts method calls on some exception types
          {:exception (str exception)})))


(defn log-state-change!
  "Log state changes"
  [description old-state new-state]
  (log! :info (str "STATE CHANGE: " description)
        {:before (when old-state (pr-str old-state))
         :after (when new-state (pr-str new-state))}))


(defn log-agent-routing!
  "Log agent routing decisions"
  [command agent-type]
  (log! :info "AGENT ROUTING"
        {:command command
         :routed-to agent-type}))


(defn end-session!
  "End the current logging session"
  []
  (when-let [log-file @current-log-file]
    (spit log-file (str "\n=== KCX Session Ended ===\n"
                        "Timestamp: " (timestamp) "\n"
                        "================================\n")
          :append true))
  (reset! current-session-id nil)
  (reset! current-log-file nil))
