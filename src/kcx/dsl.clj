(ns kcx.dsl
  "DSL command parsing for KC-X"
  (:require
    [clojure.string :as str]))


;; Input validation limits for security
(def max-input-length 10000)
(def max-component-length 1000)

;; Regex patterns for DSL parsing with improved security
(def verb-pattern #"^[!:/\s]*(\w+)")
;; More restrictive target pattern - no path traversal or absolute paths
(def target-pattern #"@([a-zA-Z0-9_./-]+)")
(def include-pattern #"\+([\w-]+)")
;; Fixed exclude pattern to avoid semicolon parsing as exclude
(def exclude-pattern #"(?<![a-zA-Z0-9])-([\w-]+)")
(def redirect-pattern #">\s*@?([a-zA-Z0-9_./-]+)")
(def agent-pattern #"&(\w+)")

;; Pattern for natural language prompts (quoted strings - entire input)
(def natural-prompt-pattern #"^[\"'](.+)[\"']$")

;; Pattern for trailing quoted instruction in DSL commands
;; Matches "..." or '...' at the end of the input
;; Uses separate patterns for double and single quotes to handle apostrophes correctly
(def instruction-double-quote-pattern #"\"([^\"]+)\"\s*$")
(def instruction-single-quote-pattern #"'([^']+)'\s*$")

(defn validate-input
  "Validate input for security and size constraints"
  [input]
  (and (string? input)
       (<= (count input) max-input-length)
       ;; Check for suspicious patterns - command injection
       (not (str/includes? input "$("))
       (not (str/includes? input "`"))
       (not (str/includes? input "\n"))
       (not (str/includes? input "\r"))
       ;; Block semicolon command chaining
       (not (str/includes? input ";"))
       ;; Block path traversal attempts
       (not (str/includes? input "../"))
       ;; Block absolute system paths
       (not (str/includes? input "@/"))
       ;; Block pipe operations
       (not (str/includes? input "|"))
       ;; Block redirections
       (not (str/includes? input ">>"))
       ;; Ensure reasonable character set - more restrictive
       (re-matches #"[a-zA-Z0-9\s!@#$%^&*()_+\-=\[\]{}':\",./<>?~]*" input)))

(defn sanitize-component
  "Sanitize a component by removing dangerous characters"
  [s]
  (when s
    (let [s (str s)  ; Ensure it's a string
          ;; Remove dangerous characters
          cleaned (-> s
                      (str/replace #"[`$\\;|&><]" "")  ; Remove shell metacharacters
                      (str/replace #"[\r\n]" "")       ; Remove line breaks
                      (str/replace #"\.\." ""))        ; Remove path traversal
          ;; Safe truncation with bounds check
          length (count cleaned)
          safe-length (min max-component-length length)]
      (when (> safe-length 0)
        (subs cleaned 0 safe-length)))))

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
             :includes []
             :excludes []
             :redirect nil
             :agent nil}))))))


(defn extract-matches
  "Extract all regex matches for a pattern from input with validation"
  [pattern input]
  (when (and pattern input (validate-input input))
    (->> (re-seq pattern input)
         (map second)
         (map sanitize-component)
         (filter seq)
         (take 10)))) ; Limit number of matches to prevent DoS


(defn remove-match-from-input
  "Remove a matched portion from input string"
  [input match-result]
  (if-let [full-match (first match-result)]
    (str/replace input full-match "")
    input))


(defn parse-dsl-command
  "Parse a DSL command string into a command map.
   Supports trailing quoted instruction: kcx !verb @target +include \"instruction\""
  [input]
  (when (and input (validate-input input) (not (str/blank? input)))
    (let [input (str/trim input)]
      (try
        ;; 0. Extract trailing quoted instruction first (if present)
        ;; Try double quotes first, then single quotes
        (let [instruction-match (or (re-find instruction-double-quote-pattern input)
                                    (re-find instruction-single-quote-pattern input))
              instruction (sanitize-component (second instruction-match))
              ;; Remove instruction from input for further parsing
              input-without-instruction (if instruction-match
                                          (str/replace input (first instruction-match) "")
                                          input)]

          ;; 1. Parse verb (required)
          (when-let [verb-match (re-find verb-pattern input-without-instruction)]
            (let [verb (sanitize-component (second verb-match))]
              (when (and verb (seq verb))
                (let [;; 2. Parse target (optional - defaults to "global_context")
                      target-match (re-find target-pattern input-without-instruction)
                      raw-target (second target-match)
                      ;; Additional validation for target paths
                      target (if (and raw-target
                                      ;; Block path traversal
                                      (not (str/includes? raw-target ".."))
                                      ;; Block absolute paths
                                      (not (str/starts-with? raw-target "/"))
                                      ;; Block system paths
                                      (not (str/includes? raw-target "/etc/"))
                                      (not (str/includes? raw-target "/bin/"))
                                      (not (str/includes? raw-target "/usr/"))
                                      (not (str/includes? raw-target "/var/")))
                               (sanitize-component raw-target)
                               "global_context")

                      ;; Create remaining input without target for constraint parsing
                      remaining-input (if target-match
                                        (remove-match-from-input input-without-instruction target-match)
                                        input-without-instruction)

                      ;; 3. Parse constraints (+ and -)
                      includes (vec (extract-matches include-pattern remaining-input))
                      excludes (vec (extract-matches exclude-pattern remaining-input))

                      ;; 4. Parse redirect (>)
                      redirect-match (re-find redirect-pattern remaining-input)
                      redirect (sanitize-component (second redirect-match))

                      ;; 5. Parse agent (&)
                      agent-match (re-find agent-pattern remaining-input)
                      agent (sanitize-component (second agent-match))]

                  {:verb verb
                   :target (or target "global_context")
                   :includes includes
                   :excludes excludes
                   :redirect redirect
                   :agent agent
                   :instruction instruction})))))
        (catch Exception e
          ;; Log error but don't expose internal details
          nil)))))


(defn parse-command
  "Main entry point - requires 'kcx ' prefix.
   Supports both DSL commands (kcx !verb @target) and natural language prompts (kcx \"prompt\")."
  [input]
  (when (and input (validate-input input) (not (str/blank? input)))
    (let [trimmed (str/trim input)]
      (when (str/starts-with? trimmed "kcx ")
        (let [remainder (-> trimmed (subs 4) str/trim)]
          (when (seq remainder)
            ;; Try natural language prompt first (quoted strings)
            (or (parse-natural-prompt remainder)
                ;; Fall back to DSL parsing
                (parse-dsl-command remainder))))))))


(defn format-command-summary
  "Format a parsed command for display with safe output"
  [{:keys [verb target includes excludes redirect agent instruction] :as cmd}]
  (when cmd
    (let [safe-verb (sanitize-component (str verb))
          safe-target (sanitize-component (str target))
          safe-includes (map #(sanitize-component (str %)) includes)
          safe-excludes (map #(sanitize-component (str %)) excludes)
          safe-redirect (sanitize-component (str redirect))
          safe-agent (sanitize-component (str agent))
          safe-instruction (sanitize-component (str instruction))]
      (str "Verb: " safe-verb
           ", Target: " safe-target
           (when (seq safe-includes) (str ", Includes: " safe-includes))
           (when (seq safe-excludes) (str ", Excludes: " safe-excludes))
           (when safe-redirect (str ", Redirect: " safe-redirect))
           (when safe-agent (str ", Agent: " safe-agent))
           (when safe-instruction (str ", Instruction: \"" safe-instruction "\""))))))


(def syntax-help
  "KC-X SYNTAX:

1. NATURAL LANGUAGE:
   kcx \"your task description here\"

2. DSL COMMANDS:
   kcx !verb @target +include -exclude >output &agent \"instruction\"

3. REDO (modify and re-run last command):
   kcx !redo +add-constraint -remove-constraint \"new instruction\"

DSL Symbols:
  !verb         - Action (fix, gen, edit, debug, review, redo, etc.)
  @target       - File to work on
  +include      - Constraint to include
  -exclude      - Constraint to avoid
  >output       - Redirect output to file
  &agent        - Prefer specific agent
  \"instruction\" - Natural language context (optional, at end)

Examples:
  kcx \"add error handling to the calculator\"
  kcx !gen +web-api \"build out a new campaign list endpoint\"
  kcx !fix @calculator.clj +error-handling \"ensure division by zero is handled\"
  kcx !redo -docstrings
  kcx !redo \"don't modify foo.clj\"
  kcx !status")


(defn get-syntax-help
  []
  syntax-help)