(ns kcx.suggestor
  "Suggestor agent — analyzes prompt journal patterns and proposes
   new expansion dictionary entries (verbs and modifiers).

   Spawns an isolated sub-Claude (like curator) to find repeated
   phrases across the global prompt journal."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [kcx.journal :as journal]
    [kcx.logging :as log]
    [kcx.worker :as worker]))


;; ============================================================================
;; Prompt Building
;; ============================================================================

(defn- format-entry
  "Format a journal entry for the suggestor prompt."
  [i entry]
  (str (inc i) ". "
       (or (:raw-input entry) (str "!" (:verb entry) " @" (:target entry)))
       (when (seq (:instruction entry))
         (str "\n   Instruction: \"" (:instruction entry) "\""))
       (when (seq (:modifiers entry))
         (str "\n   Modifiers: " (str/join ", " (map #(str "+" %) (:modifiers entry)))))
       (when (seq (:directives entry))
         (str "\n   Directives: " (str/join ", " (map #(str ">" %) (:directives entry)))))
       "\n"))

(defn- format-expansions
  "Format current expansion dictionary for context."
  [expansions]
  (let [verbs (sort-by key (get expansions :verbs {}))
        mods (sort-by key (get expansions :modifiers {}))]
    (str "Verbs:\n"
         (str/join "\n" (map (fn [[k v]] (str "  !" k " → \"" (:prompt v) "\"")) verbs))
         "\n\nModifiers:\n"
         (str/join "\n" (map (fn [[k v]] (str "  +" k " → \"" (:prompt v) "\"")) mods)))))

(defn build-suggestor-prompt
  "Build the analysis prompt for the suggestor sub-Claude."
  [entries expansions]
  (str
    "You are SUGGESTOR. Analyze user prompt patterns to suggest new expansion dictionary entries.\n\n"
    "## CURRENT EXPANSION DICTIONARY\n\n"
    (format-expansions expansions)
    "\n\n## RECENT PROMPTS (" (count entries) " commands)\n\n"
    (str/join "\n" (map-indexed format-entry entries))
    "\n\n## YOUR TASK\n\n"
    "Analyze the prompts above and suggest NEW expansion entries. Do NOT suggest things that already exist.\n\n"
    "Look for:\n"
    "1. **Repeated phrases in instructions** → verb candidates (e.g., \"add error handling\" appears 5+ times → new !add-errors verb)\n"
    "2. **Recurring instruction suffixes** → modifier candidates (e.g., \"make sure tests pass\" → +test-check)\n"
    "3. **Verbs always used with >yolo** → should have :workflow :skip baked in\n"
    "4. **Common parameter patterns** → verbs with {param} template slots\n\n"
    "Output valid EDN ONLY (no explanation, no markdown fences):\n\n"
    "{:suggestions\n"
    " [{:type :verb           ;; :verb or :modifier\n"
    "   :confidence :high     ;; :high (8+) | :medium (4-7) | :low (2-3)\n"
    "   :pattern \"the exact phrase repeated\"\n"
    "   :frequency 8\n"
    "   :proposed-name \"kebab-case-name\"\n"
    "   :edn {:prompt \"Template with {param}.\"  ;; for verbs\n"
    "         :params [{:name \"param\"}]          ;; optional\n"
    "         :workflow :standard}}               ;; :standard, :skip, :review, etc.\n"
    "  {:type :modifier\n"
    "   :proposed-name \"name\"\n"
    "   :edn {:prompt \"Instruction text.\"\n"
    "         :applies-to :all}}]}\n\n"                ;; :all, :worker, :reviewer, etc.
    "Rules:\n"
    "- Only suggest if pattern appears 2+ times\n"
    "- proposed-name must be lowercase kebab-case\n"
    "- If no patterns found, return {:suggestions []}\n\n"
    "Output ONLY the EDN. Begin."))


;; ============================================================================
;; Suggestion Formatting
;; ============================================================================

(defn- format-suggestion
  "Format a single suggestion for display."
  [i suggestion]
  (let [{:keys [type confidence pattern frequency proposed-name edn]} suggestion
        type-label (name type)
        conf-label (when confidence (str ", " (name confidence) " confidence"))]
    (str (inc i) ". [" type-label "] \"" pattern "\" (" frequency " occurrences" conf-label ")\n"
         (case type
           :verb (str "   !" proposed-name
                      (when (:params edn)
                        (str " " (str/join " " (map #(str "@" (:name %)) (:params edn)))))
                      " → \"" (:prompt edn) "\""
                      " [" (name (or (:workflow edn) :standard)) "]")
           :modifier (str "   +" proposed-name " → \"" (:prompt edn) "\""
                          " (" (name (or (:applies-to edn) :all)) ")")
           (str "   " proposed-name " → " (:prompt edn))))))

(defn format-suggestions
  "Format suggestions for display. Returns nil if auto-mode and no suggestions."
  [suggestions manual?]
  (let [items (:suggestions suggestions)]
    (cond
      (and (empty? items) (not manual?))
      nil

      (empty? items)
      "No patterns found in the prompt journal. Keep using KCX and try again later."

      :else
      (let [entry-count (get suggestions :entry-count 0)]
        (str "═══ EXPANSION SUGGESTIONS ═══\n"
             "Analyzed " entry-count " prompts. Found " (count items) " pattern"
             (when (> (count items) 1) "s") ":\n\n"
             (str/join "\n\n" (map-indexed format-suggestion items))
             "\n\n"
             "To add, copy the EDN to .kcx/expansions.edn\n"
             "═══════════════════════════════\n"
             "Present the above suggestions to the user. Do NOT add them automatically.")))))


;; ============================================================================
;; Suggestor Handler
;; ============================================================================

(defn handle-suggestor
  "Spawn suggestor sub-Claude to analyze journal and suggest expansions.
   Returns formatted suggestions string, or nil if auto-mode and nothing found."
  [manual? expansions]
  (let [entries (journal/get-recent-entries 50)]
    (if (< (count entries) 2)
      (when manual?
        "Not enough prompts in the journal yet. Run a few more commands first.")
      (do
        (log/log! :info "SUGGESTOR" {:manual? manual? :entries (count entries)})
        (try
          (let [prompt (build-suggestor-prompt entries expansions)
                result (worker/spawn-claude prompt :timeout-ms 120000 :agent-name "SUGGESTOR")]
            (if (:success result)
              (let [output (str/trim (:output result))
                    parsed (try (edn/read-string output) (catch Exception _ nil))]
                (if (and parsed (map? parsed) (contains? parsed :suggestions))
                  (format-suggestions (assoc parsed :entry-count (count entries)) manual?)
                  (do
                    (log/log! :warn "SUGGESTOR INVALID OUTPUT" {:output (subs output 0 (min 200 (count output)))})
                    (when manual?
                      "Suggestor returned invalid output. Try again."))))
              (do
                (log/log! :warn "SUGGESTOR SPAWN FAILED" {:error (:error result)})
                (when manual?
                  "Suggestor failed to start. Check logs."))))
          (catch Exception e
            (log/log-error! "SUGGESTOR FAILED" e)
            (when manual?
              (str "Suggestor error: " (.getMessage e)))))))))
