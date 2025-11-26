#!/bin/bash
# Demonstration of KC-X Agent Template System

echo "🧠 KC-X Agent Template System Demo"
echo "========================================="
echo

echo "This demo shows how different agents get specialized prompts with"
echo "behavioral constraints that force them to use specific MCP tools."
echo

# Create a temporary script to send JSON-RPC messages
cat > /tmp/demo_requests.clj << 'EOF'
#!/usr/bin/env bb
(require '[cheshire.core :as json])

(defn send-request [id method & {:keys [params]}]
  (let [request {:jsonrpc "2.0"
                 :method method
                 :id id}
        request (if params (assoc request :params params) request)]
    (println (json/generate-string request))))

;; Demo 1: Architect Agent - Should use update_state tool
(println "=== ARCHITECT AGENT DEMO ===")
(send-request 1 "tools/call"
              :params {:name "kcx"
                      :arguments {:command ":plan @auth.clj +jwt +secure -plaintext"}})

(Thread/sleep 500)

;; Demo 2: Coder Agent - Should use write_file tool
(println "\n=== CODER AGENT DEMO ===")
(send-request 2 "tools/call"
              :params {:name "kcx"
                      :arguments {:command ":gen @hello.clj +main +test"}})

(Thread/sleep 500)

;; Demo 3: Reviewer Agent - Should output APPROVED or specific feedback
(println "\n=== REVIEWER AGENT DEMO ===")
(send-request 3 "tools/call"
              :params {:name "kcx"
                      :arguments {:command ":review @hello.clj"}})

(Thread/sleep 500)

;; Demo 4: Memory Manager Agent - Should use update_state tool
(println "\n=== MEMORY MANAGER AGENT DEMO ===")
(send-request 4 "tools/call"
              :params {:name "kcx"
                      :arguments {:command ":remember 'Use Clojure for backend'"}})

(Thread/sleep 500)

;; Demo 5: Agent Help System
(println "\n=== HELP SYSTEM DEMO ===")
(send-request 5 "tools/call"
              :params {:name "kcx_help"
                      :arguments {:topic "agents"}})
EOF

chmod +x /tmp/demo_requests.clj

echo "Starting KC-X Agent Template Server..."
echo "Expected: Each agent gets a specialized prompt with role constraints"
echo

# Run the demo (pipe test requests to the server)
timeout 10s bash -c '/tmp/demo_requests.clj | ./kcx.clj' || echo "Demo completed (timeout is expected)"

echo
echo "✅ Agent Template Demo Completed!"
echo
echo "🔍 KEY OBSERVATIONS:"
echo "1. Each agent receives a different template with specific behavioral constraints"
echo "2. Architect template says: 'You MUST use update_state tool'"
echo "3. Coder template says: 'You MUST use write_file tool'"
echo "4. Reviewer template says: 'Output ONLY APPROVED or specific line feedback'"
echo "5. Memory Manager template says: 'You MUST use update_state tool'"
echo
echo "This prevents 'role confusion' - agents can't just chat, they must use tools!"

# Cleanup
rm -f /tmp/demo_requests.clj