(ns kcx.dsl
  "DSL command parsing for KC-X"
  (:require
    [clojure.string :as str]))


;; Regex patterns for DSL parsing
(def verb-pattern #"^[!:/\s]*(\w+)")
(def target-pattern #"@([\w_./-]+)")
(def include-pattern #"\+([\w-]+)")
(def exclude-pattern #"(?<!\w)-([\w-]+)")
(def redirect-pattern #">\s*@?([\w_./-]+)")
(def agent-pattern #"&(\w+)")


(defn extract-matches
  "Extract all regex matches for a pattern from input"
  [pattern input]
  (->> (re-seq pattern input)
       (map second)))


(defn remove-match-from-input
  "Remove a matched portion from input string"
  [input match-result]
  (if-let [full-match (first match-result)]
    (str/replace input full-match "")
    input))


(defn parse-dsl-command
  "Parse a DSL command string into a command map"
  [input]
  (when-not (str/blank? input)
    (let [input (str/trim input)]
      ;; 1. Parse verb (required)
      (when-let [verb-match (re-find verb-pattern input)]
        (let [verb (second verb-match)

              ;; 2. Parse target (optional - defaults to "global_context")
              target-match (re-find target-pattern input)
              target (if target-match
                       (second target-match)
                       "global_context")

              ;; Create remaining input without target for constraint parsing
              remaining-input (if target-match
                                (remove-match-from-input input target-match)
                                input)

              ;; 3. Parse constraints (+ and -)
              includes (extract-matches include-pattern remaining-input)
              excludes (extract-matches exclude-pattern remaining-input)

              ;; 4. Parse redirect (>)
              redirect-match (re-find redirect-pattern remaining-input)
              redirect (second redirect-match)

              ;; 5. Parse agent (&)
              agent-match (re-find agent-pattern remaining-input)
              agent (second agent-match)]

          {:verb verb
           :target target
           :includes (vec includes)
           :excludes (vec excludes)
           :redirect redirect
           :agent agent})))))


(defn parse-command
  "Main entry point - requires 'kcx ' prefix"
  [input]
  (when (and (not (str/blank? input))
             (str/starts-with? (str/trim input) "kcx "))
    (let [remainder (-> input str/trim (subs 4) str/trim)]
      (parse-dsl-command remainder))))


(defn format-command-summary
  "Format a parsed command for display"
  [{:keys [verb target includes excludes redirect agent] :as cmd}]
  (when cmd
    (str "Verb: " verb
         ", Target: " target
         (when (seq includes) (str ", Includes: " includes))
         (when (seq excludes) (str ", Excludes: " excludes))
         (when redirect (str ", Redirect: " redirect))
         (when agent (str ", Agent: " agent)))))


(def syntax-help
  "KC-X DSL SYNTAX:

kcx !verb @target +include -exclude >output &agent

Symbols:
  !verb    - Action (fix, gen, edit, debug, review, etc.)
  @target  - File to work on
  +include - Constraint to include
  -exclude - Constraint to avoid
  >output  - Redirect output to file
  &agent   - Prefer specific agent

Examples:
  kcx !status
  kcx !fix @calculator.clj +error-handling
  kcx !debug @api.clj +logging -println
  kcx !gen @utils.clj +async >utils_v2.clj")


(defn get-syntax-help
  []
  syntax-help)
