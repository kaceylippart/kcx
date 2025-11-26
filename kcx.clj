#!/usr/bin/env bb

(require '[babashka.classpath :refer [add-classpath]])
(require '[kcx.core :as core])

(add-classpath "src")
(core/start-server)
