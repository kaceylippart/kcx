# Exponent Implementation Guide

## Worker Implementation Tasks

This guide provides concrete implementation steps for the WORKER agent to add exponent functionality to calculator.clj.

## Task Breakdown

### 1. Core Function Implementation

**File**: `playground/src/calculator.clj`

**Location**: Add after the `divide` function (around line 25)

```clojure
(defn exponent
  "Calculates a raised to the power of b (a^b).
  
  Parameters:
  - a (number): The base number
  - b (number): The exponent
  
  Returns:
  - number: The result of a^b
  
  Throws:
  - ex-info: When base is zero and exponent is negative (division by zero)
  - ex-info: When base is negative and exponent is non-integer (complex result not supported)
  - ex-info: When result would cause arithmetic overflow"
  [a b]
  (cond
    ;; Handle 0^negative = division by zero
    (and (zero? a) (neg? b))
    (throw (ex-info "Zero raised to negative power is undefined" 
                    {:base a :exponent b :error-type :zero-base-negative-exponent}))
    
    ;; Handle negative base with non-integer exponent (would result in complex number)
    (and (neg? a) (not (integer? b)))
    (throw (ex-info "Negative base with non-integer exponent not supported" 
                    {:base a :exponent b :error-type :negative-base-non-integer}))
    
    ;; Standard calculation
    :else
    (let [result (Math/pow a b)]
      ;; Check for overflow/underflow
      (cond
        (Double/isInfinite result)
        (throw (ex-info "Exponent operation resulted in overflow" 
                        {:base a :exponent b :result result :error-type :overflow}))
        
        (Double/isNaN result)
        (throw (ex-info "Exponent operation resulted in NaN" 
                        {:base a :exponent b :result result :error-type :invalid-result}))
        
        :else result))))
```

### 2. Dispatcher Integration

**File**: `playground/src/calculator.clj`

**Location**: Update the `calculate` function (around line 27)

**Change**: Add `:exponent` case to the existing case statement:

```clojure
(defn calculate
  [op a b]
  (case op
    :add (add a b)
    :subtract (subtract a b)
    :multiply (multiply a b)
    :divide (divide a b)
    :exponent (exponent a b)  ; <-- Add this line
    (throw (ex-info "Unknown operation" {:op op}))))
```

### 3. Test Implementation

**File**: `playground/test/calculator_test.clj`

**Location**: Add after existing test functions

```clojure
(deftest exponent-test
  (testing "basic exponentiation"
    (is (= 8.0 (exponent 2 3)))
    (is (= 25.0 (exponent 5 2)))
    (is (= 1.0 (exponent 10 0)))
    (is (= 7.0 (exponent 7 1)))
    (is (= 1.0 (exponent 1 100))))
  
  (testing "fractional exponents"
    (is (= 4.0 (exponent 16 0.5)))  ; square root
    (is (= 2.0 (exponent 8 (/ 1 3)))))  ; cube root
  
  (testing "negative exponents"
    (is (= 0.25 (exponent 2 -2)))
    (is (= 0.1 (exponent 10 -1))))
  
  (testing "error conditions"
    ;; Zero base with negative exponent
    (is (thrown? clojure.lang.ExceptionInfo (exponent 0 -1)))
    
    ;; Negative base with non-integer exponent
    (is (thrown? clojure.lang.ExceptionInfo (exponent -4 0.5)))))

(deftest calculate-exponent-test
  (testing "exponent through calculate dispatcher"
    (is (= 8.0 (calculate :exponent 2 3)))
    (is (= 0.25 (calculate :exponent 2 -2)))
    (is (= 1.0 (calculate :exponent 5 0)))))
```

### 4. Integration Verification

**Commands to test implementation**:

```bash
# Run tests
cd /Users/kacey.lippart/kcx/playground
bb -cp src:test -m clojure.test calculator-test

# Interactive testing
bb -cp src -e "(require '[calculator :refer :all]) (exponent 2 3)"
bb -cp src -e "(require '[calculator :refer :all]) (calculate :exponent 2 3)"
```

## Mathematical Test Cases

### Expected Results
- `(exponent 2 3)` → `8.0`
- `(exponent 5 2)` → `25.0`
- `(exponent 10 0)` → `1.0`
- `(exponent 7 1)` → `7.0`
- `(exponent 16 0.5)` → `4.0`
- `(exponent 2 -2)` → `0.25`

### Expected Exceptions
- `(exponent 0 -1)` → ExceptionInfo "Zero raised to negative power"
- `(exponent -4 0.5)` → ExceptionInfo "Negative base with non-integer exponent"

## Error Handling Verification

Each error condition should throw `clojure.lang.ExceptionInfo` with:
- Descriptive message
- Context map containing `{:base a :exponent b :error-type keyword}`
- Consistent with existing error handling patterns

## Code Quality Checklist

- [ ] Function follows existing naming conventions
- [ ] Docstring matches format of other functions
- [ ] Error handling uses `ex-info` with context maps
- [ ] All mathematical edge cases handled
- [ ] Tests cover both success and error cases
- [ ] Integration with `calculate` function works
- [ ] No breaking changes to existing functionality

## Performance Considerations

The implementation uses `Math/pow` which:
- Handles the full range of floating-point operations
- Provides consistent results across platforms
- Is the standard Java mathematical library function
- Has acceptable performance for calculator operations

## Future Enhancement Opportunities

While not required for this implementation:
- Integer-only fast path for integer exponents
- Arbitrary precision support for very large numbers
- Complex number support for negative base/fractional exponent cases
- Optimization for common cases (squares, cubes)

This guide provides everything needed for a WORKER agent to implement the exponent functionality correctly and safely.