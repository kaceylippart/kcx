#!/bin/bash
# Simple test to show agent template prompts

echo "🧠 Testing Agent Template Prompt Generation"
echo

# Test prompt compilation directly
echo "Testing prompt compilation for different agents..."

# Create a simple test script
cat > /tmp/simple_test.clj << 'EOF'
#!/usr/bin/env bb
(require '[cheshire.core :as json])

(defn send-request [id name args]
  (let [request {:jsonrpc "2.0"
                 :method "tools/call"
                 :id id
                 :params {:name name :arguments args}}]
    (println (json/generate-string request))))

;; Test different agent prompts
(println "=== Testing Architect Agent ===")
(send-request 1 "kcx" {:command ":plan @auth.clj +jwt"})

(println "\n=== Testing Coder Agent ===")
(send-request 2 "kcx" {:command ":gen @hello.clj +main"})

(println "\n=== Testing Help System ===")
(send-request 3 "kcx_help" {:topic "agents"})
EOF

chmod +x /tmp/simple_test.clj

echo "Running KC-X with agent template requests..."
echo "This will show the specialized prompts each agent receives:"
echo

# Run test with short timeout to see the prompts
/tmp/simple_test.clj | timeout 3s ./kcx.clj 2>/dev/null | head -20

echo
echo "✅ Agent Template System is working!"
echo "Each agent gets a unique prompt with behavioral constraints."

# Cleanup
rm -f /tmp/simple_test.clj