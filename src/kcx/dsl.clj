(ns kcx.dsl
  "DSL command parsing for KC-X"
  (:require [clojure.string :as str]))

;; Regex patterns for DSL parsing (converted from Rust lazy_static)
(def verb-pattern #"^[!:/\s]*(\w+)")
(def target-pattern #"@([\w_./-]+)")
(def include-pattern #"\+(\w+)")
(def exclude-pattern #"-(\w+)")
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

;; Claude-safe syntax parsing (alternative to traditional @ + - syntax)
(def claude-safe-patterns
  {:kcx-prefix #"^kcx:(\w+)"
   :kx-prefix #"^kx:(\w+)"
   :file-target #"file:([\w_./-]+)"
   :with-include #"with:(\w+)"
   :without-exclude #"without:(\w+)|not:(\w+)"
   :to-redirect #"to:([\w_./-]+)|out:([\w_./-]+)"
   :as-agent #"as:(\w+)|agent:(\w+)"})

(defn parse-claude-safe-syntax
  "Parse Claude-safe DSL syntax (e.g., 'kcx:gen file:main.rs with:async not:unwrap')"
  [input]
  (when-not (str/blank? input)
    (let [input (str/trim input)]
      ;; Check for kcx: or kx: prefix
      (when-let [verb-match (or (re-find (:kcx-prefix claude-safe-patterns) input)
                                (re-find (:kx-prefix claude-safe-patterns) input))]
        (let [verb (second verb-match)

              ;; Parse target
              target-match (re-find (:file-target claude-safe-patterns) input)
              target (if target-match (second target-match) "global_context")

              ;; Parse includes (with:)
              includes (extract-matches (:with-include claude-safe-patterns) input)

              ;; Parse excludes (without: or not:)
              excludes (concat
                        (extract-matches #"without:(\w+)" input)
                        (extract-matches #"not:(\w+)" input))

              ;; Parse redirect (to: or out:)
              redirect-match (or (re-find #"to:([\w_./-]+)" input)
                                 (re-find #"out:([\w_./-]+)" input))
              redirect (second redirect-match)

              ;; Parse agent (as: or agent:)
              agent-match (or (re-find #"as:(\w+)" input)
                              (re-find #"agent:(\w+)" input))
              agent (second agent-match)]

          {:verb verb
           :target target
           :includes (vec includes)
           :excludes (vec excludes)
           :redirect redirect
           :agent agent})))))

(defn normalize-for-parsing
  "Normalize input for parsing, handling various syntax conflicts"
  [input]
  ;; Basic normalization - remove extra spaces
  (-> input
      str/trim
      (str/replace #"\s+" " ")))

(defn detect-conflict-level
  "Detect symbol conflict level in input"
  [input]
  (let [high-conflict-symbols #{"!" "@" "&" ">" "<"}
        low-conflict-symbols #{"-" "+"}

        has-high-conflicts? (some #(str/includes? input (str %)) high-conflict-symbols)
        has-low-conflicts? (some #(str/includes? input (str %)) low-conflict-symbols)]

    (cond
      has-high-conflicts? :high
      has-low-conflicts? :low
      :else :none)))

(defn recommend-syntax
  "Recommend Claude-safe syntax based on conflict level"
  [input conflict-level]
  (case conflict-level
    :high (str "Try Claude-safe syntax: 'kcx:gen file:example.rs with:constraint not:avoid to:output as:agent'")
    :low (str "Consider: 'kcx:gen file:example.rs with:async without:unwrap'")
    :none input))

(defn parse-command
  "Main entry point for parsing DSL commands with fallback strategies"
  [input]
  (when-not (str/blank? input)
    (let [normalized-input (normalize-for-parsing input)

          ;; Try raw mode first (starts with "raw:")
          raw-mode? (str/starts-with? normalized-input "raw:")
          actual-input (if raw-mode?
                        (subs normalized-input 4)
                        normalized-input)]

      ;; Try different parsing strategies
      (or
        ;; 1. Try Claude-safe syntax first
        (parse-claude-safe-syntax actual-input)

        ;; 2. Try traditional DSL syntax
        (parse-dsl-command actual-input)

        ;; 3. Return nil if parsing failed
        nil))))

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

;; Help and syntax information
(def syntax-help
  "KC-X DSL SYNTAX HELP:

=== TRADITIONAL SYNTAX ===
:gen @file.rs +async +serde -unwrap > output.txt &agent
Components:
  :verb    - Action to perform (gen, edit, refactor, etc.)
  @target  - File or context to work with
  +include - Constraints to include
  -exclude - Constraints to avoid
  >output  - Redirect result to file
  &agent   - Prefer specific agent

=== CLAUDE-SAFE SYNTAX ===
kcx:gen file:auth.rs with:async with:serde not:unwrap to:output as:agent
Components:
  kcx:verb      - Prefixed action
  file:target   - File specification
  with:include  - Include constraints
  not:exclude   - Exclude constraints
  to:output     - Output redirection
  as:agent      - Agent preference

=== RAW MODE (Bypass Claude interpretation) ===
raw: :gen @file.rs +async -unwrap

=== EXAMPLES ===
Traditional:  :gen @auth.rs +async -unwrap > tests.rs &reviewer
Claude-safe:  kcx:gen file:auth.rs with:async not:unwrap to:tests.rs as:reviewer
Mixed:        kx:gen file:main.rs +logging without:println to:main_v2.rs

=== VERBS ===
gen, create, edit, refactor, fix, build, test, run, review, proj, status, help")

(defn get-syntax-help []
  syntax-help)