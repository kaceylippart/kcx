(ns kcx.dsl
  "DSL command parsing for KC-X.

   Four symbols:
     !verb     → action (expands to prompt text)
     @target   → file/namespace to work on
     +modifier → prompt modifier (text for agent prompts)
     >directive → pipeline directive (changes workflow shape)

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
      (str/replace target-pattern "")
      (str/replace modifier-pattern "")
      (str/replace directive-pattern "")
      str/trim))

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
              (let [;; 2. Parse target (optional)
                    target-match (re-find target-pattern input)
                    raw-target (second target-match)
                    target (if (and raw-target
                                    (not (str/includes? raw-target ".."))
                                    (not (str/starts-with? raw-target "/"))
                                    (not (str/includes? raw-target "/etc/"))
                                    (not (str/includes? raw-target "/bin/"))
                                    (not (str/includes? raw-target "/usr/"))
                                    (not (str/includes? raw-target "/var/")))
                             (sanitize-component raw-target)
                             "global_context")

                    ;; 3. Parse modifiers (+)
                    modifiers (extract-all-matches modifier-pattern input)

                    ;; 4. Parse directives (>)
                    directives (extract-all-matches directive-pattern input)

                    ;; 5. Remaining text = natural language instruction
                    remaining (strip-tokens input)
                    instruction (when (seq remaining) remaining)]

                {:verb verb
                 :target (or target "global_context")
                 :modifiers modifiers
                 :directives directives
                 :instruction instruction}))))
        (catch Exception _e
          nil)))))


(defn parse-command
  "Main entry point - requires 'kcx ' prefix.
   Supports DSL commands (kcx !verb @target) and natural language prompts (kcx \"prompt\")."
  [input]
  (when (and input (validate-input input) (not (str/blank? input)))
    (let [trimmed (str/trim input)]
      (when (str/starts-with? trimmed "kcx ")
        (let [remainder (-> trimmed (subs 4) str/trim)]
          (when (seq remainder)
            (or (parse-natural-prompt remainder)
                (parse-dsl-command remainder))))))))


(defn format-command-summary
  "Format a parsed command for display"
  [{:keys [verb target modifiers directives instruction] :as cmd}]
  (when cmd
    (str "!" (sanitize-component (str verb))
         (when (and target (not= target "global_context"))
           (str " @" (sanitize-component (str target))))
         (when (seq modifiers)
           (str " " (str/join " " (map #(str "+" (sanitize-component (str %))) modifiers))))
         (when (seq directives)
           (str " " (str/join " " (map #(str ">" (sanitize-component (str %))) directives))))
         (when instruction
           (str " " instruction)))))


(def syntax-help
  "KC-X SYNTAX:

Symbols (embed inline with natural language):
  !verb         - Action (fix, gen, edit, debug, review, test, etc.)
  @target       - File or namespace to work on
  +modifier     - Prompt modifier (expands to agent instructions)
  >directive    - Pipeline directive (changes workflow shape)

Everything else is natural language.

Examples:
  kcx !fix @calculator.clj and make sure the edge cases are covered
  kcx !review @audience-selector.cljs +thorough
  kcx !fix @calc.clj +thorough >skip-tests
  kcx !debug @calc.clj and let me know if there's anything else wrong
  kcx !gen +web-api build out a new campaign list endpoint
  kcx !fix @calc.clj >fast just fix the typo
  kcx !redo +explain
  kcx !status

Directives:
  >skip-tests   - Skip the testing stage
  >skip-review  - Skip the review stage
  >fast         - Worker + curator only
  >yolo         - Worker only, no validation")


(defn get-syntax-help
  []
  syntax-help)
