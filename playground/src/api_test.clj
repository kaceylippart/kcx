(ns api-test)

(defn divide
  "Divides the first number by the second.

  Parameters:
  - a (number): The dividend (number to be divided)
  - b (number): The divisor (number to divide by)

  Returns:
  - number: The quotient of a and b (a / b)

  Note: This is a simple division function without error handling for zero divisor."
  [a b]
  (/ a b))
