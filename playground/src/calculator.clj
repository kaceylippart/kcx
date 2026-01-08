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


(defn calculate
  [op a b]
  (case op
    :add (add a b)
    :subtract (subtract a b)
    :multiply (multiply a b)
    :divide (divide a b)
    (throw (ex-info "Unknown operation" {:op op}))))
