#!/usr/bin/env bash
# Reset playground to initial state

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Resetting playground..."

# Restore calculator.clj with the bug
cat > src/calculator.clj << 'EOF'
(ns calculator
  "A simple calculator with a bug for testing")

(defn add [a b]
  (+ a b))

(defn subtract [a b]
  (- a b))

(defn multiply [a b]
  (* a b))

;; BUG: Division doesn't handle divide-by-zero
(defn divide [a b]
  (/ a b))

(defn calculate [op a b]
  (case op
    :add (add a b)
    :subtract (subtract a b)
    :multiply (multiply a b)
    :divide (divide a b)
    (throw (ex-info "Unknown operation" {:op op}))))
EOF

# Restore test file
cat > test/calculator_test.clj << 'EOF'
(ns calculator-test
  (:require [clojure.test :refer [deftest testing is]]
            [calculator :refer :all]))

(deftest add-test
  (testing "addition"
    (is (= 5 (add 2 3)))
    (is (= 0 (add -1 1)))
    (is (= -5 (add -2 -3)))))

(deftest subtract-test
  (testing "subtraction"
    (is (= 1 (subtract 3 2)))
    (is (= -2 (subtract -1 1)))))

(deftest multiply-test
  (testing "multiplication"
    (is (= 6 (multiply 2 3)))
    (is (= 0 (multiply 0 5)))))

(deftest divide-test
  (testing "division"
    (is (= 2 (divide 6 3)))
    (is (= 0.5 (divide 1 2))))
  ;; TODO: This test will fail - divide-by-zero not handled
  #_(testing "divide by zero"
    (is (nil? (divide 1 0)))))

(deftest calculate-test
  (testing "calculate dispatcher"
    (is (= 10 (calculate :add 4 6)))
    (is (= 2 (calculate :subtract 5 3)))
    (is (= 12 (calculate :multiply 3 4)))
    (is (= 3 (calculate :divide 9 3)))))
EOF

echo "✅ Playground reset complete"
