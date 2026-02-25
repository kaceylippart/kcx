(ns calculator-test
  (:require
    [calculator :refer :all]
    [clojure.test :refer [deftest testing is]]))


(deftest add-test
  (testing "basic addition"
    (is (= 5 (add 2 3)))
    (is (= 0 (add -1 1)))
    (is (= -5 (add -2 -3))))

  (testing "addition input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add "2" 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add 2 "3")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add nil 3)))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (add Double/POSITIVE_INFINITY 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (add 2 Double/NEGATIVE_INFINITY)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (add Double/NaN 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (add 2 Double/NaN))))

  (testing "addition overflow detection"
    ;; Test with very large numbers that would cause overflow
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Addition resulted in overflow"
                          (add Double/MAX_VALUE Double/MAX_VALUE)))))


(deftest subtract-test
  (testing "basic subtraction"
    (is (= 1 (subtract 3 2)))
    (is (= -2 (subtract -1 1))))

  (testing "subtraction input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (subtract "5" 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (subtract 5 "3")))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (subtract Double/POSITIVE_INFINITY 3)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (subtract 5 Double/NaN))))

  (testing "subtraction overflow detection"
    ;; Test with values that would cause overflow
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Subtraction resulted in overflow"
                          (subtract Double/MAX_VALUE (- Double/MAX_VALUE))))))


(deftest multiply-test
  (testing "basic multiplication"
    (is (= 6 (multiply 2 3)))
    (is (= 0 (multiply 0 5))))

  (testing "multiplication input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (multiply "2" 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (multiply 2 [])))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (multiply Double/NEGATIVE_INFINITY 3)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (multiply Double/NaN 5))))

  (testing "multiplication overflow detection"
    ;; Test with very large numbers that would cause overflow
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Multiplication resulted in overflow"
                          (multiply Double/MAX_VALUE 2.0)))))


(deftest divide-test
  (testing "basic division"
    (is (= 2 (divide 6 3)))
    (is (= 1/2 (divide 1 2))))

  (testing "division input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (divide "6" 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (divide 6 nil)))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (divide Double/POSITIVE_INFINITY 3)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (divide 6 Double/NaN))))

  (testing "divide by zero"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Division by zero"
                          (divide 1 0))))

  (testing "division overflow detection"
    ;; Test division that results in overflow
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Division resulted in overflow"
                          (divide Double/MAX_VALUE 1e-308)))))


(deftest exponent-test
  (testing "basic exponentiation"
    (is (= 8.0 (exponent 2 3)))
    (is (= 25.0 (exponent 5 2)))
    (is (= 1.0 (exponent 10 0)))
    (is (= 7.0 (exponent 7 1)))
    (is (= 1.0 (exponent 1 100))))
  
  (testing "fractional exponents"
    (is (= 4.0 (exponent 16 0.5)))  ; square root
    (is (< (Math/abs (- 2.0 (exponent 8 (/ 1 3)))) 0.0001)))  ; cube root with tolerance
  
  (testing "negative exponents"
    (is (= 0.25 (exponent 2 -2)))
    (is (= 0.1 (exponent 10 -1))))
  
  (testing "exponent input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (exponent "2" 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (exponent 2 "3")))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (exponent Double/POSITIVE_INFINITY 3)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (exponent 2 Double/NaN))))

  (testing "error conditions"
    ;; Zero base with negative exponent
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Zero raised to negative power is undefined"
                          (exponent 0 -1)))

    ;; Negative base with non-integer exponent
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Negative base with non-integer exponent not supported"
                          (exponent -4 0.5)))))


(deftest calculate-test
  (testing "calculate dispatcher"
    (is (= 10 (calculate :add 4 6)))
    (is (= 2 (calculate :subtract 5 3)))
    (is (= 12 (calculate :multiply 3 4)))
    (is (= 3 (calculate :divide 9 3))))

  (testing "calculate input validation"
    ;; Non-keyword operation
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Operation must be a keyword"
                          (calculate "add" 4 6)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Operation must be a keyword"
                          (calculate 123 4 6)))

    ;; Unknown operation
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown operation"
                          (calculate :unknown 4 6)))))


(deftest calculate-exponent-test
  (testing "exponent through calculate dispatcher"
    (is (= 8.0 (calculate :exponent 2 3)))
    (is (= 0.25 (calculate :exponent 2 -2)))
    (is (= 1.0 (calculate :exponent 5 0)))))


(deftest add-modulo-test
  (testing "basic add-modulo operations"
    (is (= 1 (add-modulo 3 5 7)))    ; (3 + 5) mod 7 = 8 mod 7 = 1
    (is (= 0 (add-modulo 4 6 5)))    ; (4 + 6) mod 5 = 10 mod 5 = 0
    (is (= 0 (add-modulo 7 8 3)))    ; (7 + 8) mod 3 = 15 mod 3 = 0
    (is (= 3 (add-modulo 10 5 4))))  ; (10 + 5) mod 4 = 15 mod 4 = 3

  (testing "edge cases"
    (is (= 0 (add-modulo 0 0 5)))    ; (0 + 0) mod 5 = 0
    (is (= 1 (add-modulo 1 0 3)))    ; (1 + 0) mod 3 = 1
    (is (= 0 (add-modulo 5 5 1)))    ; (5 + 5) mod 1 = 0 (any number mod 1 = 0)
    (is (= 4 (add-modulo -3 7 5))))  ; (-3 + 7) mod 5 = 4

  (testing "negative numbers"
    (is (= 4 (add-modulo -3 7 5)))   ; (-3 + 7) mod 5 = 4
    (is (= 2 (add-modulo -5 -2 3)))) ; (-5 + -2) mod 3 = -7 mod 3 = 2 in Clojure

  (testing "add-modulo input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add-modulo "3" 5 7)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add-modulo 3 "5" 7)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add-modulo 3 5 "7")))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (add-modulo Double/POSITIVE_INFINITY 5 7)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (add-modulo 3 Double/NaN 7))))

  (testing "error conditions"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Modulo by zero is undefined"
                          (add-modulo 5 3 0)))))


(deftest calculate-add-modulo-test
  (testing "add-modulo through calculate dispatcher"
    (is (= 1 (calculate :add-modulo 3 5 7)))
    (is (= 0 (calculate :add-modulo 4 6 5)))
    (is (= 3 (calculate :add-modulo 10 5 4))))

  (testing "add-modulo error handling in dispatcher"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"add-modulo requires third argument"
                          (calculate :add-modulo 3 5)))))


(deftest add-sqrt-test
  (testing "basic add-sqrt operations"
    (is (= 3.0 (add-sqrt 4 5)))      ; sqrt(4 + 5) = sqrt(9) = 3
    (is (= 5.0 (add-sqrt 9 16)))     ; sqrt(9 + 16) = sqrt(25) = 5
    (is (= 4.0 (add-sqrt 7 9)))      ; sqrt(7 + 9) = sqrt(16) = 4
    (is (= 0.0 (add-sqrt 0 0))))     ; sqrt(0 + 0) = sqrt(0) = 0

  (testing "fractional results"
    (is (< (Math/abs (- 2.236067977499 (add-sqrt 1 4))) 0.0001))  ; sqrt(1 + 4) = sqrt(5) ≈ 2.236
    (is (< (Math/abs (- 3.16227766017 (add-sqrt 2 8))) 0.0001)))  ; sqrt(2 + 8) = sqrt(10) ≈ 3.162

  (testing "edge cases"
    (is (= 1.0 (add-sqrt 0 1)))      ; sqrt(0 + 1) = sqrt(1) = 1
    (is (= 1.0 (add-sqrt 1 0)))      ; sqrt(1 + 0) = sqrt(1) = 1
    (is (= 2.0 (add-sqrt -3 7)))     ; sqrt(-3 + 7) = sqrt(4) = 2
    (is (= 0.0 (add-sqrt -5 5))))    ; sqrt(-5 + 5) = sqrt(0) = 0

  (testing "add-sqrt input validation"
    ;; Non-number inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add-sqrt "4" 5)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter must be a number"
                          (add-sqrt 4 nil)))

    ;; Infinite inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be infinite"
                          (add-sqrt Double/NEGATIVE_INFINITY 16)))

    ;; NaN inputs
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Parameter cannot be NaN"
                          (add-sqrt Double/NaN 16))))

  (testing "error conditions"
    ;; Sum is negative
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot take square root of negative number"
                          (add-sqrt 2 -5)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot take square root of negative number"
                          (add-sqrt -10 -3)))))


(deftest calculate-add-sqrt-test
  (testing "add-sqrt through calculate dispatcher"
    (is (= 3.0 (calculate :add-sqrt 4 5)))
    (is (= 5.0 (calculate :add-sqrt 9 16)))
    (is (= 0.0 (calculate :add-sqrt 0 0))))

  (testing "add-sqrt error handling in dispatcher"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot take square root of negative number"
                          (calculate :add-sqrt 2 -5)))))


(deftest enhanced-error-handling-test
  (testing "enhanced error messages include suggestions"
    ;; Test that error data contains suggestions
    (try
      (add "not-a-number" 5)
      (catch clojure.lang.ExceptionInfo e
        (let [error-data (ex-data e)]
          (is (contains? error-data :suggestion))
          (is (= :invalid-type (:error-type error-data)))
          (is (string? (:suggestion error-data))))))

    (try
      (divide 10 0)
      (catch clojure.lang.ExceptionInfo e
        (let [error-data (ex-data e)]
          (is (contains? error-data :suggestion))
          (is (= :division-by-zero (:error-type error-data))))))

    (try
      (calculate "add" 1 2)
      (catch clojure.lang.ExceptionInfo e
        (let [error-data (ex-data e)]
          (is (contains? error-data :suggestion))
          (is (contains? error-data :type-name))
          (is (= :invalid-operation-type (:error-type error-data))))))

    (try
      (calculate :unknown-op 1 2)
      (catch clojure.lang.ExceptionInfo e
        (let [error-data (ex-data e)]
          (is (contains? error-data :available-operations))
          (is (vector? (:available-operations error-data)))
          (is (= :unknown-operation (:error-type error-data))))))))


(deftest safe-calculate-test
  (testing "safe-calculate with default values"
    ;; Normal operation
    (is (= 7 (safe-calculate :add 3 4)))

    ;; Error with default
    (is (= :error (safe-calculate :divide 1 0 {:default :error})))
    (is (= nil (safe-calculate :add "not-a-number" 5 {:default nil})))

    ;; Error without default should still throw
    (is (thrown? Exception (safe-calculate :divide 1 0))))

  (testing "safe-calculate with error callback"
    (let [error-info (atom nil)]
      (safe-calculate :divide 1 0
                      {:default :error
                       :on-error #(reset! error-info %)})
      (is (some? @error-info))
      (is (instance? Exception (:error @error-info)))
      (is (= :divide (:operation @error-info)))))

  (testing "safe-calculate with additional arguments"
    (is (= 1 (safe-calculate :add-modulo 3 5 {:args [7]})))
    (is (= :error (safe-calculate :add-modulo 3 5 {:args [0] :default :error})))))


(deftest enhanced-error-handling-test-v2
  (testing "BigInteger handling with precision warnings"
    ;; Test large BigInteger operations
    (let [big-int (bigint Long/MAX_VALUE)]
      (is (number? (add big-int 1)))
      (is (number? (multiply big-int 2)))))

  (testing "BigDecimal handling with precision loss detection"
    ;; Test BigDecimal operations that lose precision
    (let [big-dec (BigDecimal. "123.456789012345678901234567890")]
      (is (number? (add big-dec 1)))
      (is (number? (divide big-dec 2)))))

  (testing "Mixed numeric types"
    (is (= 4.5 (add 2 2.5)))
    (is (= 7/2 (add 3 1/2)))
    (is (number? (multiply (bigint 100) 3.14))))

  (testing "BigInteger within safe bounds"
    ;; Test with BigInteger that fits within Long bounds
    (let [safe-big-int (bigint 1000000)]
      (is (number? (add safe-big-int 1))))))


(deftest calculate-batch-test
  (testing "batch calculations - all successful"
    (let [operations [{:op :add :a 1 :b 2}
                      {:op :multiply :a 3 :b 4}
                      {:op :subtract :a 10 :b 3}]
          results (calculate-batch operations)]
      (is (= [3 12 7] results))))

  (testing "batch calculations - with error, no continue"
    (let [operations [{:op :add :a 1 :b 2}
                      {:op :divide :a 1 :b 0}  ; This will fail
                      {:op :multiply :a 3 :b 4}]]
      (is (thrown? Exception (calculate-batch operations)))))

  (testing "batch calculations - with error, continue enabled"
    (let [operations [{:op :add :a 1 :b 2}
                      {:op :divide :a 1 :b 0}  ; This will fail
                      {:op :multiply :a 3 :b 4}]
          results (calculate-batch operations {:continue-on-error true})]
      (is (= 3 (count results)))
      (is (= 3 (first results)))
      (is (= :calculator/error (second results)))
      (is (= 12 (nth results 2)))))

  (testing "batch calculations - with error collection"
    (let [operations [{:op :add :a 1 :b 2}
                      {:op :divide :a 1 :b 0}  ; This will fail
                      {:op :multiply :a 3 :b 4}]
          results (calculate-batch operations {:continue-on-error true :collect-errors true})]
      (is (= 3 (count results)))
      (is (:success (first results)))
      (is (= 3 (:result (first results))))
      (is (not (:success (second results))))
      (is (contains? (second results) :error))
      (is (:success (nth results 2)))
      (is (= 12 (:result (nth results 2))))))

  (testing "batch calculations - with arguments"
    (let [operations [{:op :add-modulo :a 7 :b 8 :args [3]}
                      {:op :add-sqrt :a 9 :b 16}]
          results (calculate-batch operations)]
      (is (= [0 5.0] results)))))