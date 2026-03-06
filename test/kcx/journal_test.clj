(ns kcx.journal-test
  (:require
    [clojure.test :refer [deftest testing is use-fixtures run-tests]]
    [clojure.java.io :as io]
    [kcx.journal :as journal]))


;; ============================================================================
;; Test Fixture — redirect journal to temp dir
;; ============================================================================

(def test-dir (str (System/getProperty "java.io.tmpdir") "/kcx-journal-test"))

(defn with-temp-journal [f]
  ;; Override journal paths to temp dir
  (with-redefs [journal/kcx-home     test-dir
                journal/journal-file  (str test-dir "/journal.edn")
                journal/counter-file  (str test-dir "/suggest-counter")]
    ;; Clean before
    (let [dir (io/file test-dir)]
      (when (.exists dir)
        (doseq [f (reverse (file-seq dir))]
          (.delete f))))
    (try
      (f)
      (finally
        ;; Clean after
        (let [dir (io/file test-dir)]
          (when (.exists dir)
            (doseq [f (reverse (file-seq dir))]
              (.delete f))))))))

(use-fixtures :each with-temp-journal)


;; ============================================================================
;; Journal Tests
;; ============================================================================

(deftest test-load-empty-journal
  (testing "Loading nonexistent journal returns template"
    (let [j (journal/load-journal)]
      (is (= [] (:entries j)))
      (is (= 0 (get-in j [:meta :total-entries]))))))

(deftest test-add-entry
  (testing "Adding entries persists to disk"
    (journal/add-entry! {:verb "fix" :target "calc.clj" :instruction "fix the bug"})
    (journal/add-entry! {:verb "edit" :target "api.clj" :instruction "add auth"})
    (let [j (journal/load-journal)]
      (is (= 2 (count (:entries j))))
      (is (= 2 (get-in j [:meta :total-entries])))
      ;; Entries have timestamps
      (is (every? :timestamp (:entries j)))
      ;; Data preserved
      (is (= "fix" (:verb (first (:entries j))))))))

(deftest test-entry-cap
  (testing "Journal caps at max-entries"
    (with-redefs [journal/max-entries 5]
      (dotimes [i 8]
        (journal/add-entry! {:verb "fix" :index i}))
      (let [j (journal/load-journal)]
        (is (= 5 (count (:entries j))))
        (is (= 5 (get-in j [:meta :total-entries])))
        ;; Oldest entries trimmed — first remaining should be index 3
        (is (= 3 (:index (first (:entries j)))))))))

(deftest test-get-recent-entries
  (testing "Returns last N entries"
    (dotimes [i 10]
      (journal/add-entry! {:verb "fix" :index i}))
    (let [recent (journal/get-recent-entries 3)]
      (is (= 3 (count recent)))
      (is (= 7 (:index (first recent))))
      (is (= 9 (:index (last recent)))))))

(deftest test-get-recent-entries-fewer-than-n
  (testing "Returns all entries when fewer than N"
    (journal/add-entry! {:verb "fix"})
    (let [recent (journal/get-recent-entries 50)]
      (is (= 1 (count recent))))))


;; ============================================================================
;; Counter Tests
;; ============================================================================

(deftest test-counter-initial
  (testing "Counter starts at 0"
    (is (= 0 (journal/load-counter)))))

(deftest test-counter-increment
  (testing "Counter increments and persists"
    (is (= 1 (journal/increment-counter!)))
    (is (= 2 (journal/increment-counter!)))
    (is (= 3 (journal/increment-counter!)))
    ;; Persisted to disk
    (is (= 3 (journal/load-counter)))))

(deftest test-counter-reset
  (testing "Counter resets to 0"
    (journal/increment-counter!)
    (journal/increment-counter!)
    (journal/reset-counter!)
    (is (= 0 (journal/load-counter)))))


;; ============================================================================
;; Run
;; ============================================================================

(when (= *file* (System/getProperty "babashka.file"))
  (run-tests))
