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