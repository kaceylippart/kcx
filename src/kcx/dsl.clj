(ns kcx.dsl
  "DSL command parsing for KC-X.

   Five symbols:
     !verb      → action (expands to prompt text)
     @param     → positional parameter (file path, with Claude autocomplete)
     %param     → positional parameter (general value, no autocomplete)
     +modifier  → prompt modifier (text for agent prompts)
     >directive → pipeline directive (changes workflow shape)

   @ and % are interchangeable — both fill positional params in order.
   Use @\"multi word\" or %\"multi word\" for values with spaces.
   Everything else is natural language, passed as :instruction."
  (:require
    [clojure.string :as str]))


;; Input validation limits for security
(def max-input-length 10000)
(def max-component-length 1000)

;; Regex patterns for DSL parsing
(def verb-pattern #"!\s*([\w-]+)")
(def target-pattern #"@([\w./-]+)")
(def modifier-pattern #"\+([\w-]+)")
(def directive-pattern #">([\w-]+)")
;; Param patterns: @value, %value, @"quoted value", %"quoted value"
(def param-pattern #"[@%]\"([^\"]+)\"|[@%]([\w./-]+)")

;; Pattern for natural language prompts (quoted strings - entire input)
(def natural-prompt-pattern #"^[\"'](.+)[\"']$")

(defn validate-input
  "Validate input for security and size constraints"
  [input]
  (and (string? input)
       (<= (count input) max-input-length)
       ;; Block command injection
       (not (str/includes? input "$("))
       (not (str/includes? input "`"))
       ;; Block path traversal
       (not (str/includes? input "../"))))

(defn sanitize-component
  "Sanitize a component by removing dangerous characters"
  [s]
  (when s
    (let [s (str s)
          cleaned (-> s
                      (str/replace #"[`$\\]" "")
                      (str/replace #"\.\." ""))
          length (count cleaned)
          safe-length (min max-component-length length)]
      (when (> safe-length 0)
        (subs cleaned 0 safe-length)))))

(defn- extract-all-matches
  "Extract all regex matches for a pattern from input."
  [pattern input]
  (->> (re-seq pattern input)
       (map second)
       (map sanitize-component)
       (filter seq)
       (take 10)
       vec))

(defn- strip-tokens
  "Remove all recognized symbol tokens from input, leaving natural language."
  [input]
  (-> input
      (str/replace verb-pattern "")
      (str/replace param-pattern "")
      (str/replace modifier-pattern "")
      (str/replace directive-pattern "")
      str/trim))

(defn- extract-ordered-args
  "Extract all @value and %value tokens in order of appearance.
   Supports quoted forms: @\"multi word\" and %\"multi word\".
   Returns a vec of string values (quotes stripped)."
  [input]
  (->> (re-seq param-pattern input)
       (mapv (fn [[_ quoted unquoted]]
               (sanitize-component (or quoted unquoted))))
       (filterv seq)
       (take 10)
       vec))

(defn parse-natural-prompt
  "Detect and parse natural language prompts in quotes.
   Returns a command map with :verb 'prompt' if input is quoted string."
  [input]
  (when (and input (validate-input input))
    (let [trimmed (str/trim input)]
      (when-let [match (re-find natural-prompt-pattern trimmed)]
        (let [prompt (sanitize-component (second match))]
          (when (and prompt (seq prompt))
            {:verb "prompt"
             :prompt prompt
             :target "global_context"
             :modifiers []
             :directives []
             :instruction nil}))))))


(defn parse-dsl-command
  "Parse a DSL command string into a command map.

   Extracts symbol tokens (!verb @target +modifier >directive),
   then collects remaining text as natural language instruction."
  [input]
  (when (and input (validate-input input) (not (str/blank? input)))
    (let [input (str/trim input)]
      (try
        ;; 1. Parse verb (required)
        (when-let [verb-match (re-find verb-pattern input)]
          (let [verb (sanitize-component (second verb-match))]
            (when (and verb (seq verb))
              (let [;; 2. Parse positional args (@ and % tokens, in order)
                    args (extract-ordered-args input)
                    ;; First arg is the target (backward compat)
                    target (or (first args) "global_context")

                    ;; 3. Parse modifiers (+)
                    modifiers (extract-all-matches modifier-pattern input)

                    ;; 4. Parse directives (>)
                    directives (extract-all-matches directive-pattern input)

                    ;; 5. Remaining text = natural language instruction
                    remaining (strip-tokens input)
                    instruction (when (seq remaining) remaining)]

                {:verb verb
                 :target target
                 :args args
                 :modifiers modifiers
                 :directives directives
                 :instruction instruction}))))
        (catch Exception _e
          nil)))))


(defn parse-command
  "Main entry point for DSL parsing.
   Supports DSL commands (!verb @target) and natural language prompts (\"prompt\")."
  [input]
  (when (and input (validate-input input) (not (str/blank? input)))
    (let [remainder (str/trim input)]
      (when (seq remainder)
        (or (parse-natural-prompt remainder)
            (parse-dsl-command remainder))))))


(def syntax-help
  "KC-X SYNTAX:

Symbols (embed inline with natural language):
  !verb         - Action (fix, gen, edit, debug, review, test, etc.)
  @param        - Positional parameter (file path, has Claude autocomplete)
  %param        - Positional parameter (general value, no autocomplete)
  +modifier     - Prompt modifier (expands to agent instructions)
  >directive    - Pipeline directive (changes workflow shape)

@ and % are interchangeable. Use quotes for multi-word values.
Everything else is natural language.

Examples:
  /kcx !fix @calculator.clj and make sure the edge cases are covered
  /kcx !edit @calc.clj %\"add error handling\"
  /kcx !explain %workflows
  /kcx !review @audience-selector.cljs +thorough
  /kcx !fix @calc.clj +thorough >skip-tests
  /kcx !debug @calc.clj and let me know if there's anything else wrong
  /kcx !fix @calc.clj >fast just fix the typo
  /kcx !redo +step-by-step
  /kcx !status

Directives:
  >skip-tests   - Skip the testing stage
  >skip-review  - Skip the review stage
  >fast         - Worker + curator only
  >yolo         - Worker only, no validation")
