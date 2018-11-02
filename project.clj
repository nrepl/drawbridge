(defproject nrepl/drawbridge "0.2.0-SNAPSHOT"
  :description "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  :url "http://github.com/nrepl/drawbridge"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [nrepl "0.4.5"]
                 [ring/ring-core "1.7.1"]
                 [cheshire "5.8.1"]

                 ;; client
                 [clj-http "3.9.1"]]

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :dev-dependencies [[ring "1.7.1"]]
  :profiles {:dev {:dependencies [[ring "1.7.1"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.5.7"]]}})
