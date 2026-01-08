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
