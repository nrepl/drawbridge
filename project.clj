(defproject com.cemerick/drawbridge "0.0.3-SNAPSHOT"
  :description "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  :url "http://github.com/cemerick/drawbridge"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/tools.nrepl "0.2.0-beta5"]
                 [ring/ring-core "1.0.2"]
                 [cheshire "3.0.0"]

                 ;; client
                 [clj-http "0.3.6"]]
  :dev-dependencies [[ring "1.0.0"]]
  :profiles {:dev {:dependencies [[ring "1.0.0"]]
                   :plugins [[lein-clojars "0.8.0"]]}
             :1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0-beta5"]]}}
  :main ^{:skip-aot true} cemerick.drawbridge)
