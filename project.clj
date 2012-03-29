(defproject com.cemerick/drawbridge "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories {"sonatype-oss-public" "https://oss.sonatype.org/content/groups/public/"}
  :dependencies [[org.clojure/clojure "1.2.0"]
                 [org.clojure/tools.nrepl "0.2.0-SNAPSHOT"]
                 [ring/ring-core "1.0.2"]
                 [cheshire "3.0.0"]
                 [clj-http "0.3.4" :optional true]]
  :dev-dependencies [[ring "1.0.0"]])
