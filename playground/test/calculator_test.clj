(ns calculator-test
  (:require
    [calculator :refer :all]
    [clojure.test :refer [deftest testing is]]))


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
    (is (= 1/2 (divide 1 2))))
  (testing "divide by zero"
    (is (thrown? clojure.lang.ExceptionInfo (divide 1 0)))))


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
    (is (= 3 (calculate :divide 9 3)))))


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