(ns kcx.expand
  "Prompt expansion engine for KCX.

   Resolves DSL tokens (!verb, +modifier, @arg) against a layered
   expansion dictionary. Tokens are keys into the dictionary —
   they expand into rich prompt text that would otherwise require
   verbose natural language.

   Two-tier resolution: base (ships with KCX) < project (.kcx/expansions.edn)."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]))


;; ============================================================================
;; Template Rendering
;; ============================================================================

(defn render-template
  "Render a prompt template with positional args.

   Params define named slots with optional defaults:
     [{:name \"target\" :required true}                          ;; must be provided
      {:name \"scope\"  :default \"correctness and code quality\"}] ;; falls back

   Args are positional: [\"calc.clj\" \"divide-fn\"]

   Returns {:ok rendered-string} on success,
   or {:error \"missing required param: target\"} if a required param has no arg."
  [template params args]
  (if (or (nil? params) (empty? params))
    {:ok template}
    (let [args (vec (or args []))
          ;; Check for missing required params (no :default key = required)
          missing (->> params
                       (keep-indexed
                         (fn [i param]
                           (when (and (nil? (get args i))
                                      (not (contains? param :default)))
                             (:name param))))
                       vec)]
      (if (seq missing)
        {:error (str "missing required param: " (str/join ", " missing))}
        (let [substitutions
              (into {}
                    (map-indexed
                      (fn [i {:keys [name default]}]
                        [name (or (get args i) default)])
                      params))]
          {:ok (reduce
                 (fn [text [param-name value]]
                   (str/replace text (str "{" param-name "}") (str value)))
                 template
                 substitutions)})))))


;; ============================================================================
;; Dictionary Merging
;; ============================================================================

(defn merge-expansions
  "Merge expansion dictionaries: project > base.
   Merge at key level — if project defines 'review', it fully replaces base 'review'."
  [base project]
  (let [base    (or base {})
        project (or project {})]
    {:verbs    (merge (get base :verbs {})
                      (get project :verbs {}))
     :modifiers (merge (get base :modifiers {})
                       (get project :modifiers {}))}))


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
      (let [result (render-template (:prompt verb-def) (:params verb-def) args)]
        (if (:error result)
          [nil (:workflow verb-def) [(str "!" name " " (:error result))]]
          [(:ok result) (:workflow verb-def) []]))
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
      (let [result (render-template (:prompt mod-def) (:params mod-def) args)]
        (if (:error result)
          [nil [(str "+" name " " (:error result))]]
          [{:key name
            :prompt (:ok result)
            :applies-to (or (:applies-to mod-def) :all)}
           []]))

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
   Searches relative to the project root and KCX_HOME."
  []
  (let [kcx-home (or (System/getenv "KCX_HOME")
                     (str (System/getProperty "user.home") "/kcx"))
        candidates ["resources/base-expansions.edn"
                    (str kcx-home "/resources/base-expansions.edn")]
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
