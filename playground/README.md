# Calculator Playground

A simple Clojure calculator for testing kcx MCP server.

## Known Issues

1. `divide` function doesn't handle divide-by-zero (throws ArithmeticException)

## Running Tests

```bash
bb -cp src:test -m clojure.test calculator-test
```

## Files

- `src/calculator.clj` - Main calculator functions
- `test/calculator_test.clj` - Unit tests
