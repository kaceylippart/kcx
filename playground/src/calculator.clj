(ns calculator
  "A simple calculator with error handling")


(defn add
  [a b]
  (+ a b))


(defn subtract
  [a b]
  (- a b))


(defn multiply
  [a b]
  (* a b))


(defn divide
  [a b]
  (if (zero? b)
    (throw (ex-info "Division by zero" {:dividend a :divisor b}))
    (/ a b)))


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


(defn add-modulo
  "Adds two numbers and returns the result modulo a third number (a + b) mod c.

  Parameters:
  - a (number): First number to add
  - b (number): Second number to add
  - c (number): Modulo divisor

  Returns:
  - number: The result of (a + b) mod c

  Throws:
  - ex-info: When modulo divisor is zero (division by zero)"
  [a b c]
  (if (zero? c)
    (throw (ex-info "Modulo by zero is undefined"
                    {:addend1 a :addend2 b :modulo c :error-type :modulo-by-zero}))
    (mod (+ a b) c)))


(defn add-sqrt
  "Adds two numbers and returns the square root of the sum: sqrt(a + b).

  Parameters:
  - a (number): First number to add
  - b (number): Second number to add

  Returns:
  - number: The square root of (a + b)

  Throws:
  - ex-info: When the sum is negative (complex result not supported)"
  [a b]
  (let [sum (+ a b)]
    (if (neg? sum)
      (throw (ex-info "Cannot take square root of negative number"
                      {:addend1 a :addend2 b :sum sum :error-type :negative-square-root}))
      (Math/sqrt sum))))


(defn calculate
  [op a b & args]
  (case op
    :add (add a b)
    :subtract (subtract a b)
    :multiply (multiply a b)
    :divide (divide a b)
    :exponent (exponent a b)
    :add-modulo (if (seq args)
                  (add-modulo a b (first args))
                  (throw (ex-info "add-modulo requires third argument (modulo)" {:op op})))
    :add-sqrt (add-sqrt a b)
    (throw (ex-info "Unknown operation" {:op op}))))