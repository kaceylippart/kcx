#!/bin/bash
# Simple MCP server test script

echo "🧪 Testing KC-X Clojure MCP Server"
echo

# Create a temporary script to send JSON-RPC messages
cat > /tmp/test_mcp.clj << 'EOF'
#!/usr/bin/env bb
(require '[cheshire.core :as json])

(defn send-request [method & {:keys [params id]}]
  (let [request {:jsonrpc "2.0"
                 :method method
                 :id (or id 1)}
        request (if params (assoc request :params params) request)]
    (println (json/generate-string request))))

;; Test 1: Initialize
(send-request "initialize")

;; Test 2: Tools list
(send-request "tools/list" :id 2)

;; Test 3: Read state
(send-request "tools/call"
              :params {:name "read_state" :arguments {}}
              :id 3)

;; Test 4: KC-X command
(send-request "tools/call"
              :params {:name "kcx_command"
                      :arguments {:command "kcx:gen file:hello.clj with:main"}}
              :id 4)

;; Test 5: Help
(send-request "tools/call"
              :params {:name "kcx_help"
                      :arguments {:topic "syntax"}}
              :id 5)
EOF

chmod +x /tmp/test_mcp.clj

echo "Starting KC-X MCP server with test inputs..."
echo "Expected: JSON-RPC responses for initialize, tools/list, read_state, kcx_command, and kcx_help"
echo

# Run the test (pipe test requests to the server)
timeout 10s bash -c '/tmp/test_mcp.clj | ./kcx.clj' || echo "Test completed (timeout is expected)"

echo
echo "✅ MCP server test completed!"
echo "If you saw JSON responses above, the conversion was successful."

# Cleanup
rm -f /tmp/test_mcp.clj