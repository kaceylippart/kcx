(ns kcx.expand
  "Prompt expansion engine for KCX.

   Resolves DSL tokens (!verb, +modifier, @arg) against a layered
   expansion dictionary. Tokens are keys into the dictionary —
   they expand into rich prompt text that would otherwise require
   verbose natural language.

   Three-tier resolution: base (ships with KCX) < project < personal."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]))


;; ============================================================================
;; Template Rendering
;; ============================================================================

(defn- split-clauses
  "Split text into clause segments by comma or period boundaries.
   Each segment is trimmed. Periods are preserved with their clause."
  [text]
  ;; Split on comma-space or period-space boundaries, preserving delimiters
  ;; We split on ", " to get clauses within sentences
  (let [;; First split on period boundaries (sentence-level)
        sentences (str/split text #"(?<=\.)\s+")
        ;; Then split each sentence on comma boundaries (clause-level)
        clauses (mapcat (fn [sentence]
                          (let [parts (str/split sentence #",\s*")]
                            (if (= 1 (count parts))
                              [sentence]
                              ;; Recombine: first part stays, rest get trimmed
                              parts)))
                        sentences)]
    (vec (filter #(not (str/blank? %)) (map str/trim clauses)))))

(defn- rejoin-clauses
  "Rejoin clauses into a sentence, cleaning up punctuation."
  [clauses]
  (let [joined (str/join ", " clauses)]
    ;; Clean up: ", ." → "." and leading ", " etc
    (-> joined
        (str/replace #",\s*\." ".")
        (str/replace #"^,\s*" "")
        (str/replace #",\s*$" "")
        str/trim
        ;; Ensure ends with period if original had one
        (#(if (and (seq %) (not (str/ends-with? % ".")))
            (str % ".")
            %)))))

(defn render-template
  "Render a prompt template with positional args.

   Params define named slots with defaults:
     [{:name \"target\" :default \"the codebase\"}
      {:name \"scope\"  :default :omit}]

   Args are positional: [\"calc.clj\" \"divide-fn\"]

   When a param's default is :omit and no arg is provided,
   the clause containing {param} is dropped."
  [template params args]
  (if (or (nil? params) (empty? params))
    template
    (let [args (or args [])
          ;; Build substitution map: param-name → value or :omit
          substitutions
          (into {}
                (map-indexed
                  (fn [i {:keys [name default]}]
                    (let [arg (get (vec args) i)]
                      [name (if arg
                              arg
                              (if (= :omit default)
                                :omit
                                default))]))
                  params))
          ;; Find params that should be omitted
          omit-params (set (keep (fn [[k v]] (when (= :omit v) k)) substitutions))
          ;; If any params are omitted, split into clauses and drop those containing {param}
          result (if (seq omit-params)
                   (let [clauses (split-clauses template)
                         kept (remove
                                (fn [clause]
                                  (some #(str/includes? clause (str "{" % "}"))
                                        omit-params))
                                clauses)]
                     (rejoin-clauses kept))
                   template)]
      ;; Substitute remaining params
      (reduce
        (fn [text [param-name value]]
          (if (= :omit value)
            text
            (str/replace text (str "{" param-name "}") (str value))))
        result
        substitutions))))


;; ============================================================================
;; Dictionary Merging
;; ============================================================================

(defn merge-expansions
  "Merge expansion dictionaries: personal > project > base.
   Merge at key level — if personal defines 'review', it fully replaces base 'review'."
  [base project personal]
  (let [base    (or base {})
        project (or project {})
        personal (or personal {})]
    {:verbs    (merge (get base :verbs {})
                      (get project :verbs {})
                      (get personal :verbs {}))
     :modifiers (merge (get base :modifiers {})
                       (get project :modifiers {})
                       (get personal :modifiers {}))}))


;; ============================================================================
;; Suggest Similar (for "did you mean?" warnings)
;; ============================================================================

(defn- levenshtein
  "Compute Levenshtein edit distance between two strings."
  [a b]
  (let [a (vec a) b (vec b)
        la (count a) lb (count b)]
    (cond
      (zero? la) lb
      (zero? lb) la
      :else
      (let [prev-row (vec (range (inc lb)))]
        (loop [i 0 prev prev-row]
          (if (>= i la)
            (peek prev)
            (let [curr (loop [j 0 curr [(inc i)]]
                         (if (>= j lb)
                           curr
                           (let [cost (if (= (nth a i) (nth b j)) 0 1)
                                 val (min
                                       (inc (nth curr j))          ; delete
                                       (inc (nth prev (inc j)))    ; insert
                                       (+ (nth prev j) cost))]    ; substitute
                             (recur (inc j) (conj curr val)))))]
              (recur (inc i) curr))))))))

(defn suggest-similar
  "Find known names similar to the input (for typo suggestions).
   Returns matches with Levenshtein distance ≤ 2 or prefix matches."
  [input known-names]
  (let [input-lower (str/lower-case input)]
    (->> known-names
         (filter (fn [name]
                   (let [name-lower (str/lower-case name)]
                     (or
                       ;; Prefix match
                       (str/starts-with? name-lower input-lower)
                       ;; Close edit distance (within 2)
                       (<= (levenshtein input-lower name-lower) 2)))))
         (sort-by #(levenshtein (str/lower-case input) (str/lower-case %)))
         vec)))


;; ============================================================================
;; Core Expansion
;; ============================================================================

(defn- expand-verb
  "Expand a verb against the dictionary. Returns [expanded-text workflow warnings]."
  [verb-map expansions]
  (let [{:keys [name args]} verb-map
        verb-def (get-in expansions [:verbs name])]
    (if verb-def
      [(render-template (:prompt verb-def) (:params verb-def) args)
       (:workflow verb-def)
       []]
      ;; Unknown verb
      (let [known (keys (get expansions :verbs {}))
            suggestions (suggest-similar name known)
            hint (if (seq suggestions)
                   (str " Did you mean: " (str/join ", " (map #(str "!" %) suggestions)) "?")
                   "")]
        [nil nil [(str "!" name " not found in expansions." hint)]]))))

(defn- expand-modifier
  "Expand a single modifier against the dictionary.
   Returns [expanded-mod-map warnings]."
  [mod-map expansions]
  (let [{:keys [name args]} mod-map
        mod-def (get-in expansions [:modifiers name])]
    (if mod-def
      [{:key name
        :prompt (render-template (:prompt mod-def) (:params mod-def) args)
        :applies-to (or (:applies-to mod-def) :all)}
       []]
      ;; Unknown modifier
      (let [known (keys (get expansions :modifiers {}))
            suggestions (suggest-similar name known)
            hint (if (seq suggestions)
                   (str " Did you mean: " (str/join ", " (map #(str "+" %) suggestions)) "?")
                   "")]
        [nil [(str "+" name " not found in expansions." hint)]]))))

(defn expand
  "Expand a parsed command by resolving tokens against expansion dictionaries.

   Input cmd keys:
     :verb      — {:name \"review\" :args [\"calc.clj\"]} or nil for natural language
     :modifiers — [{:name \"thorough\" :args []}]
     :user-text — free text from the user
     :prompt    — natural language prompt (when no verb)

   Returns the cmd augmented with:
     :expanded-verb      — rendered verb prompt text
     :expanded-modifiers — [{:key :prompt :applies-to}]
     :workflow           — workflow type from verb def
     :warnings           — [\"!unknown not found...\"]
     :expanded?          — true if verb was successfully expanded"
  [cmd expansions]
  (if (nil? (:verb cmd))
    ;; Natural language passthrough — no expansion
    cmd
    (let [;; Expand verb
          [expanded-verb workflow verb-warnings] (expand-verb (:verb cmd) expansions)
          ;; Expand modifiers
          mod-results (map #(expand-modifier % expansions) (or (:modifiers cmd) []))
          expanded-mods (vec (keep first mod-results))
          mod-warnings (vec (mapcat second mod-results))
          ;; Combine warnings
          all-warnings (vec (concat verb-warnings mod-warnings))]
      (merge cmd
             {:expanded-verb expanded-verb
              :expanded-modifiers expanded-mods
              :workflow workflow
              :warnings all-warnings
              :expanded? (some? expanded-verb)}))))


;; ============================================================================
;; Filter Modifiers by Agent Role
;; ============================================================================

;; ============================================================================
;; Disk Loading
;; ============================================================================

(defn load-expansions-file
  "Load an expansion dictionary from an EDN file. Returns nil if file doesn't exist."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn load-base-expansions
  "Load the base expansion dictionary shipped with KCX.
   Searches relative to the project root (resources/base-expansions.edn)."
  []
  (let [;; Try relative path first (normal bb invocation from project root)
        candidates ["resources/base-expansions.edn"
                    ;; Also try relative to this source file's location
                    (str (System/getProperty "user.dir") "/resources/base-expansions.edn")]
        f (->> candidates
               (map io/file)
               (filter #(.exists %))
               first)]
    (when f
      (edn/read-string (slurp f)))))


;; ============================================================================
;; Filter Modifiers by Agent Role
;; ============================================================================

(defn filter-modifiers-for
  "Filter expanded modifiers for a specific agent role.
   Returns modifiers where :applies-to is :all or matches the role."
  [role modifiers]
  (vec (filter
         (fn [{:keys [applies-to]}]
           (or (= :all applies-to)
               (= role applies-to)))
         (or modifiers []))))
