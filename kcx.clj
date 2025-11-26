#!/usr/bin/env bb

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

(require '[kcx.core :as core])

(core/start-server)
