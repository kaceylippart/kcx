(ns calculator
  "A simple calculator with comprehensive error handling"
  (:import (java.time Instant)
           (java.math BigDecimal BigInteger)))

;; Logging and utility functions
(defn log-error
  "Logs error information for debugging purposes.

  Parameters:
  - error-type (keyword): The type of error
  - message (string): Error message
  - context (map): Additional context information"
  [error-type message context]
  ;; In a production system, this would write to a proper logging system
  ;; For now, we'll use stderr to avoid interfering with normal output
  (binding [*out* *err*]
    (println (str "[ERROR " (Instant/now) "] "
                  (name error-type) ": " message
                  " | Context: " context))))

;; Helper functions for input validation and normalization
(defn normalize-number
  "Normalizes different numeric types for consistent handling.

  Parameters:
  - x: The value to normalize
  - param-name (string): Name of the parameter for error messages

  Returns:
  - number: Normalized numeric value

  Throws:
  - ex-info: When normalization fails or results in precision loss"
  [x param-name]
  (cond
    ;; BigDecimal - convert to double but warn about potential precision loss
    (instance? BigDecimal x)
    (let [double-val (.doubleValue ^BigDecimal x)]
      (when (and (not (Double/isInfinite double-val))
                 (not (Double/isNaN double-val))
                 (not= (.compareTo ^BigDecimal x (BigDecimal/valueOf double-val)) 0))
        (log-error :precision-warning "BigDecimal precision may be lost in conversion"
                   {:parameter param-name :original x :converted double-val}))
      double-val)

    ;; Very large BigInteger - warn about conversion
    (instance? BigInteger x)
    (let [double-val (.doubleValue ^BigInteger x)]
      (when (Double/isInfinite double-val)
        (log-error :conversion-overflow "BigInteger too large for double precision"
                   {:parameter param-name :original x :bit-length (.bitLength ^BigInteger x)}))
      double-val)

    ;; Regular numbers pass through
    (number? x) x

    ;; Non-numbers get handled by validate-number
    :else x))

;; Helper functions for input validation
(defn validate-number
  "Validates that the input is a number with enhanced error reporting.

  Parameters:
  - x: The value to validate
  - param-name (string): Name of the parameter for error messages

  Returns:
  - x: The input if valid

  Throws:
  - ex-info: When input is not a number"
  [x param-name]
  (if (number? x)
    x
    (let [type-name (if x (-> x type .getSimpleName) "nil")]
      (log-error :invalid-type "Non-numeric value provided as parameter"
                 {:parameter param-name :value x :type type-name})
      (throw (ex-info "Parameter must be a number"
                      {:parameter param-name :value x :type (type x) :type-name type-name
                       :error-type :invalid-type
                       :suggestion (cond
                                     (string? x) "Convert string to number using parse functions"
                                     (nil? x) "Ensure parameter is not nil"
                                     :else "Provide a numeric value (integer, double, ratio, etc.)")})))))

(defn validate-integer-range
  "Validates that an integer is within safe computational bounds.

  Parameters:
  - x (number): The number to validate
  - param-name (string): Name of the parameter for error messages

  Returns:
  - x: The input if valid

  Throws:
  - ex-info: When integer is outside safe range"
  [x param-name]
  (cond
    ;; BigInteger values - warn about potential performance impact
    (instance? BigInteger x)
    (do
      (when (> (.bitLength ^BigInteger x) 1000000)  ; ~300k digit numbers
        (log-error :large-number-warning "Very large BigInteger may impact performance"
                   {:parameter param-name :bit-length (.bitLength ^BigInteger x)}))
      x)

    ;; Regular integers - check Long bounds
    (integer? x)
    (let [max-safe-int Long/MAX_VALUE
          min-safe-int Long/MIN_VALUE]
      (if (or (> x max-safe-int) (< x min-safe-int))
        (do
          (log-error :integer-overflow "Integer outside safe computational range"
                     {:parameter param-name :value x :max-safe max-safe-int :min-safe min-safe-int})
          (throw (ex-info "Integer outside safe computational range"
                          {:parameter param-name :value x :max-safe max-safe-int :min-safe min-safe-int
                           :error-type :integer-overflow :suggestion "Use smaller integer values"})))
        x))

    ;; Non-integers pass through unchanged
    :else x))

(defn validate-finite
  "Validates that a number is finite (not infinite or NaN) with enhanced error reporting.

  Parameters:
  - x (number): The number to validate
  - param-name (string): Name of the parameter for error messages

  Returns:
  - x: The input if valid

  Throws:
  - ex-info: When number is infinite or NaN"
  [x param-name]
  (cond
    (and (double? x) (Double/isInfinite x))
    (do
      (log-error :infinite-value "Infinite value provided as parameter"
                 {:parameter param-name :value x})
      (throw (ex-info "Parameter cannot be infinite"
                      {:parameter param-name :value x :error-type :infinite-value
                       :suggestion "Use finite numeric values only"})))

    (and (double? x) (Double/isNaN x))
    (do
      (log-error :nan-value "NaN value provided as parameter"
                 {:parameter param-name :value x})
      (throw (ex-info "Parameter cannot be NaN"
                      {:parameter param-name :value x :error-type :nan-value
                       :suggestion "Ensure calculations don't result in NaN before using as input"})))

    :else (validate-integer-range x param-name)))

(defn check-precision-loss
  "Checks for potential precision loss in floating-point operations.

  Parameters:
  - result (number): The result to check
  - operation (string): Description of the operation
  - operands (map): Map of operands

  Returns:
  - result: The input (always returns, but may log warnings)"
  [result operation operands]
  (cond
    ;; Check for underflow in double precision
    (and (double? result)
         (not (Double/isInfinite result))
         (not (Double/isNaN result))
         (< (Math/abs result) Double/MIN_NORMAL)
         (not (zero? result)))
    (log-error :precision-warning "Result may have lost precision due to underflow"
               (merge operands {:result result :operation operation :min-normal Double/MIN_NORMAL}))

    ;; Check for potential precision loss when converting very large integers to double
    (and (double? result)
         (not (Double/isInfinite result))
         (not (Double/isNaN result))
         (some #(and (number? %)
                     (or (and (integer? %) (> (Math/abs (double %)) (Math/pow 2 53)))
                         (instance? BigInteger %))) (vals operands)))
    (log-error :precision-warning "Large integer may have lost precision in floating-point conversion"
               (merge operands {:result result :operation operation
                               :max-safe-integer (Math/pow 2 53)})))
  result)

(defn check-overflow
  "Checks if a result represents arithmetic overflow with enhanced error reporting.

  Parameters:
  - result (number): The result to check
  - operation (string): Description of the operation for error messages
  - operands (map): Map of operands for error context

  Returns:
  - result: The input if valid

  Throws:
  - ex-info: When result indicates overflow"
  [result operation operands]
  (cond
    (and (double? result) (Double/isInfinite result))
    (do
      (log-error :overflow (str operation " resulted in overflow")
                 (merge operands {:result result}))
      (throw (ex-info (str operation " resulted in overflow")
                      (merge operands {:result result :error-type :overflow
                                      :suggestion "Try using smaller input values or different approach"}))))

    (and (double? result) (Double/isNaN result))
    (do
      (log-error :invalid-result (str operation " resulted in NaN")
                 (merge operands {:result result}))
      (throw (ex-info (str operation " resulted in NaN")
                      (merge operands {:result result :error-type :invalid-result
                                      :suggestion "Check input values for mathematical validity"}))))

    :else (check-precision-loss result operation operands)))


(defn add
  "Adds two numbers together with input validation and overflow checking.

  Parameters:
  - a (number): The first number
  - b (number): The second number

  Returns:
  - number: The sum of a and b

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When result causes arithmetic overflow"
  [a b]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))
        result (+ validated-a validated-b)]
    (check-overflow result "Addition" {:addend1 validated-a :addend2 validated-b})))


(defn subtract
  "Subtracts the second number from the first with input validation and overflow checking.

  Parameters:
  - a (number): The number to subtract from
  - b (number): The number to subtract

  Returns:
  - number: The difference of a and b (a - b)

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When result causes arithmetic overflow"
  [a b]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))
        result (- validated-a validated-b)]
    (check-overflow result "Subtraction" {:minuend validated-a :subtrahend validated-b})))


(defn multiply
  "Multiplies two numbers together with input validation and overflow checking.

  Parameters:
  - a (number): The first number
  - b (number): The second number

  Returns:
  - number: The product of a and b

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When result causes arithmetic overflow"
  [a b]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))
        result (* validated-a validated-b)]
    (check-overflow result "Multiplication" {:multiplicand validated-a :multiplier validated-b})))


(defn divide
  "Divides the first number by the second with comprehensive input validation.

  Parameters:
  - a (number): The dividend (number to be divided)
  - b (number): The divisor (number to divide by)

  Returns:
  - number: The quotient of a and b (a / b)

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When attempting to divide by zero
  - ex-info: When result causes arithmetic overflow"
  [a b]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))]
    (if (zero? validated-b)
      (do
        (log-error :division-by-zero "Attempted division by zero"
                   {:dividend validated-a :divisor validated-b})
        (throw (ex-info "Division by zero"
                        {:dividend validated-a :divisor validated-b :error-type :division-by-zero
                         :suggestion "Ensure divisor is not zero before division"})))
      (let [result (/ validated-a validated-b)]
        (check-overflow result "Division" {:dividend validated-a :divisor validated-b})))))


(defn exponent
  "Calculates a raised to the power of b (a^b) with comprehensive input validation.

  Parameters:
  - a (number): The base number
  - b (number): The exponent

  Returns:
  - number: The result of a^b

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When base is zero and exponent is negative (division by zero)
  - ex-info: When base is negative and exponent is non-integer (complex result not supported)
  - ex-info: When result would cause arithmetic overflow"
  [a b]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))]
    (cond
      ;; Handle 0^negative = division by zero
      (and (zero? validated-a) (neg? validated-b))
      (do
        (log-error :zero-base-negative-exponent "Zero raised to negative power"
                   {:base validated-a :exponent validated-b})
        (throw (ex-info "Zero raised to negative power is undefined"
                        {:base validated-a :exponent validated-b :error-type :zero-base-negative-exponent
                         :suggestion "Use positive exponents with zero base, or non-zero base with negative exponents"})))

      ;; Handle negative base with non-integer exponent (would result in complex number)
      (and (neg? validated-a) (not (integer? validated-b)))
      (do
        (log-error :negative-base-non-integer "Negative base with non-integer exponent"
                   {:base validated-a :exponent validated-b})
        (throw (ex-info "Negative base with non-integer exponent not supported"
                        {:base validated-a :exponent validated-b :error-type :negative-base-non-integer
                         :suggestion "Use integer exponents with negative bases, or positive bases with any exponent"})))

      ;; Standard calculation
      :else
      (let [result (Math/pow validated-a validated-b)]
        ;; Check for overflow/underflow
        (cond
          (Double/isInfinite result)
          (throw (ex-info "Exponent operation resulted in overflow"
                          {:base validated-a :exponent validated-b :result result :error-type :overflow}))

          (Double/isNaN result)
          (throw (ex-info "Exponent operation resulted in NaN"
                          {:base validated-a :exponent validated-b :result result :error-type :invalid-result}))

          :else result)))))


(defn add-modulo
  "Adds two numbers and returns the result modulo a third number (a + b) mod c with input validation.

  Parameters:
  - a (number): First number to add
  - b (number): Second number to add
  - c (number): Modulo divisor

  Returns:
  - number: The result of (a + b) mod c

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When modulo divisor is zero (division by zero)
  - ex-info: When result causes arithmetic overflow"
  [a b c]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))
        validated-c (-> c (validate-number "c") (normalize-number "c") (validate-finite "c"))]
    (if (zero? validated-c)
      (do
        (log-error :modulo-by-zero "Attempted modulo operation with zero divisor"
                   {:addend1 validated-a :addend2 validated-b :modulo validated-c})
        (throw (ex-info "Modulo by zero is undefined"
                        {:addend1 validated-a :addend2 validated-b :modulo validated-c :error-type :modulo-by-zero
                         :suggestion "Use non-zero modulo divisor"})))
      (let [sum (+ validated-a validated-b)
            result (mod sum validated-c)]
        (check-overflow result "Add-modulo" {:addend1 validated-a :addend2 validated-b :modulo validated-c :sum sum})))))


(defn add-sqrt
  "Adds two numbers and returns the square root of the sum: sqrt(a + b) with input validation.

  Parameters:
  - a (number): First number to add
  - b (number): Second number to add

  Returns:
  - number: The square root of (a + b)

  Throws:
  - ex-info: When inputs are not numbers, infinite, or NaN
  - ex-info: When the sum is negative (complex result not supported)
  - ex-info: When result causes arithmetic overflow"
  [a b]
  (let [validated-a (-> a (validate-number "a") (normalize-number "a") (validate-finite "a"))
        validated-b (-> b (validate-number "b") (normalize-number "b") (validate-finite "b"))
        sum (+ validated-a validated-b)]
    (if (neg? sum)
      (do
        (log-error :negative-square-root "Attempted square root of negative number"
                   {:addend1 validated-a :addend2 validated-b :sum sum})
        (throw (ex-info "Cannot take square root of negative number"
                        {:addend1 validated-a :addend2 validated-b :sum sum :error-type :negative-square-root
                         :suggestion "Ensure the sum of addends is non-negative"})))
      (let [result (Math/sqrt sum)]
        (check-overflow result "Add-sqrt" {:addend1 validated-a :addend2 validated-b :sum sum})))))


(defn calculate
  "Dispatches calculator operations based on the provided operator with comprehensive error handling.

  Parameters:
  - op (keyword): The operation to perform (:add, :subtract, :multiply, :divide, :exponent, :add-modulo, :add-sqrt)
  - a (number): The first operand
  - b (number): The second operand
  - args (optional): Additional arguments for operations that require them (e.g., modulo for :add-modulo)

  Returns:
  - number: The result of the specified operation

  Throws:
  - ex-info: When operation parameter is not a keyword
  - ex-info: When an unknown operation is requested
  - ex-info: When required arguments are missing for specific operations
  - ex-info: Any errors from the underlying calculation functions"
  [op a b & args]
  (if (not (keyword? op))
    (let [type-name (-> op type .getSimpleName)]
      (log-error :invalid-operation-type "Non-keyword operation provided"
                 {:op op :type type-name})
      (throw (ex-info "Operation must be a keyword"
                      {:op op :type (type op) :type-name type-name :error-type :invalid-operation-type
                       :suggestion "Use keywords like :add, :subtract, :multiply, :divide, :exponent, :add-modulo, :add-sqrt"})))
    (case op
      :add (add a b)
      :subtract (subtract a b)
      :multiply (multiply a b)
      :divide (divide a b)
      :exponent (exponent a b)
      :add-modulo (if (seq args)
                    (add-modulo a b (first args))
                    (do
                      (log-error :missing-argument "Missing required argument for add-modulo operation"
                                 {:op op :provided-args (count args)})
                      (throw (ex-info "add-modulo requires third argument (modulo)"
                                      {:op op :error-type :missing-argument :provided-args (count args)
                                       :suggestion "Provide modulo value as third argument: (calculate :add-modulo a b modulo)"}))))
      :add-sqrt (add-sqrt a b)
      (do
        (log-error :unknown-operation "Unknown operation requested"
                   {:op op :available-ops [:add :subtract :multiply :divide :exponent :add-modulo :add-sqrt]})
        (throw (ex-info "Unknown operation"
                        {:op op :error-type :unknown-operation
                         :available-operations [:add :subtract :multiply :divide :exponent :add-modulo :add-sqrt]
                         :suggestion "Use one of the available operations"}))))))


(defn safe-calculate
  "Performs calculator operations with comprehensive error handling and optional default values.

  Parameters:
  - op (keyword): The operation to perform
  - a (number): The first operand
  - b (number): The second operand
  - options (map, optional): Configuration options
    - :default - Value to return on error (if provided, exceptions are caught)
    - :on-error - Function to call with error info when error occurs
    - :args - Additional arguments for operations that require them

  Returns:
  - number: The result of the operation, or default value if provided and error occurs

  Throws:
  - ex-info: Any errors from calculation (only if :default not provided)"
  [op a b & [options]]
  (let [{:keys [default on-error args]} options
        has-default? (contains? options :default)]
    (try
      (if args
        (apply calculate op a b args)
        (calculate op a b))
      (catch Exception e
        (when on-error
          (on-error {:error e :operation op :operands [a b] :args args}))
        (if has-default?
          default
          (throw e))))))


(defn calculate-batch
  "Performs multiple calculations in sequence, with error handling for individual operations.

  Parameters:
  - operations (vector): Vector of operation maps, each containing :op, :a, :b, and optional :args
  - options (map, optional): Configuration options
    - :continue-on-error - If true, continue processing after errors (default false)
    - :collect-errors - If true, collect error information in results (default false)

  Returns:
  - vector: Results of calculations, with error information if :collect-errors is true

  Example:
  (calculate-batch [{:op :add :a 1 :b 2}
                    {:op :divide :a 10 :b 2}
                    {:op :add-modulo :a 7 :b 8 :args [3]}])"
  [operations & [options]]
  (let [{:keys [continue-on-error collect-errors]} options]
    (reduce
      (fn [results operation]
        (try
          (let [{:keys [op a b args]} operation
                result (if args
                         (apply calculate op a b args)
                         (calculate op a b))]
            (conj results (if collect-errors
                            {:operation operation :result result :success true}
                            result)))
          (catch Exception e
            (let [error-info {:operation operation :error (.getMessage e) :success false}]
              (if continue-on-error
                (conj results (if collect-errors error-info :calculator/error))
                (throw e))))))
      []
      operations)))