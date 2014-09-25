(defproject racehub/drawbridge "0.1.0"
  :description "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  :url "http://github.com/racehub/drawbridge"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-alpha2"]
                 [org.clojure/tools.nrepl "0.2.5"]
                 [ring/ring-core "1.3.0"]
                 [cheshire "5.3.1"]

                 ;; client
                 [clj-http "1.0.0"]]
  :profiles {:dev {:dependencies [[ring "1.3.0"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}}
  :main ^{:skip-aot true} cemerick.drawbridge)
