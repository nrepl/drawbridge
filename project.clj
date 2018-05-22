(defproject nrepl/drawbridge "0.1.0-SNAPSHOT"
  :description "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  :url "http://github.com/nrepl/drawbridge"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.nrepl "0.2.13"]
                 [ring/ring-core "1.0.2"]
                 [cheshire "3.0.0"]

                 ;; client
                 [clj-http "0.3.6"]]
  :dev-dependencies [[ring "1.0.0"]]
  :profiles {:dev {:dependencies [[ring "1.0.0"]]
                   :plugins [[lein-clojars "0.9.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}}
  :main ^{:skip-aot true} drawbridge.core)
